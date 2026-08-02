package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.Currency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = TradingBackendApplication.class,
        properties = {
                "broker.credentials.enabled=true",
                "broker.credentials.active-key-version=1",
                "broker.credentials.keys.1=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "spring.datasource.hikari.maximum-pool-size=4"
        })
class LiveOrderSafetyLedgerIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-02T03:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderIntentRepository intents;

    private LiveOrderSafetyLedger ledger;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE real_order_daily_reservations, real_order_account_allowlist, order_intents, broker_accounts, broker_connections, users CASCADE");
        ledger = new LiveOrderSafetyLedger(jdbc, ZoneId.of("Asia/Seoul"));
    }

    @Test
    void registeredSmallAllowlistMapsTheExactAccountAndReservesDailyAmountAtomically() {
        var owner = owner();
        var account = UUID.randomUUID();
        jdbc.update("INSERT INTO broker_accounts (id) VALUES (?)", account);
        jdbc.update("""
                INSERT INTO real_order_account_allowlist (
                    id, user_id, broker_connection_id, broker_account_id, toss_account_seq,
                    display_account_number, enabled, daily_limit_krw, daily_limit_usd, created_at
                ) VALUES (?, ?, ?, ?, '01', '******0001', TRUE, 1000, 200, ?)
                """, UUID.randomUUID(), owner.userId(), owner.connectionId(), account, at(NOW));

        assertThat(ledger.resolve(owner.userId(), owner.connectionId(), account).brokerAccountId()).isEqualTo("01");
        assertThat(ledger.resolve(owner.userId(), owner.connectionId(), account).displayAccountNumber())
                .isEqualTo("******0001");

        var firstIntent = liveIntent(owner.userId(), owner.connectionId(), account);
        var first = ledger.reserve(owner.userId(), owner.connectionId(), account, firstIntent,
                Currency.USD, new BigDecimal("150"), NOW);
        assertThat(first.amount()).isEqualByComparingTo("150");
        assertThat(first.usageDate()).isEqualTo(NOW.atZone(ZoneId.of("Asia/Seoul")).toLocalDate());

        var secondIntent = liveIntent(owner.userId(), owner.connectionId(), account);
        assertThatThrownBy(() -> ledger.reserve(owner.userId(), owner.connectionId(), account, secondIntent,
                Currency.USD, new BigDecimal("51"), NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("daily order limit");
    }

    @Test
    void unregisteredOrDifferentInternalAccountFailsClosed() {
        var owner = owner();
        var account = UUID.randomUUID();

        assertThatThrownBy(() -> ledger.resolve(owner.userId(), owner.connectionId(), account))
                .isInstanceOf(IllegalStateException.class);
    }

    private UUID liveIntent(UUID userId, UUID connectionId, UUID accountId) {
        var id = UUID.randomUUID();
        intents.saveAndFlush(OrderIntent.proposedLive(id, accountId, userId, connectionId,
                OrderSide.BUY, OrderType.LIMIT, "AAPL", BigDecimal.ONE, new BigDecimal("180"), Currency.USD));
        return id;
    }

    private Owner owner() {
        var userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?)", userId);
        var connectionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status, credential_ciphertext, credential_nonce,
                    credential_key_version, credential_revision, created_at, updated_at, version
                ) VALUES (?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0)
                """, connectionId, userId, new byte[17], new byte[12], at(NOW), at(NOW));
        return new Owner(userId, connectionId);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record Owner(UUID userId, UUID connectionId) {
    }
}
