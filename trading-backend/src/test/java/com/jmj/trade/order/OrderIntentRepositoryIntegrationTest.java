package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TradingBackendApplication.class)
class OrderIntentRepositoryIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private OrderIntentRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanLedger() {
        jdbcTemplate.update("DELETE FROM execution_snapshots");
        jdbcTemplate.update("DELETE FROM broker_orders");
        repository.deleteAll();
        jdbcTemplate.update("DELETE FROM broker_accounts");
    }

    @Test
    void applicationRunsFlywayMigrationAgainstPostgres() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
    }

    @Test
    void repositoryPersistsAndLoadsOrderIntentMapping() {
        var accountId = UUID.randomUUID();
        var intentId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO broker_accounts (id) VALUES (?)", accountId);

        repository.saveAndFlush(
                OrderIntent.proposed(intentId, accountId, new BigDecimal("10")));
        entityManager.clear();

        var stored = repository.findById(intentId).orElseThrow();
        assertThat(stored.getBrokerAccountId()).isEqualTo(accountId);
        assertThat(stored.getQuantity()).isEqualByComparingTo("10");
        assertThat(stored.getStatus()).isEqualTo(OrderIntentStatus.PROPOSED);
        assertThat(stored.getVersion()).isZero();
    }
}
