package com.jmj.trade.broker;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrokerContractTest {

    private static final UUID CONNECTION_ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final BrokerConnectionRef CONNECTION = new BrokerConnectionRef(CONNECTION_ID, "toss");
    private static final BrokerAccountRef ACCOUNT = new BrokerAccountRef(
            CONNECTION_ID,
            "account-seq-1",
            "US_STOCK",
            "****1234");
    private static final Instant OBSERVED_AT = Instant.parse("2026-07-27T01:00:00Z");
    private static final BrokerCallMetadata METADATA = new BrokerCallMetadata(
            "request-1",
            OBSERVED_AT,
            Optional.empty());

    @Test
    void brokerAdapterExposesOnlyReadOnlyMethods() {
        var publicMethodNames = Arrays.stream(BrokerAdapter.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getName())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(publicMethodNames).isEqualTo(Set.of(
                "getAccounts",
                "getAccount",
                "getPositions",
                "getQuote",
                "getAccountCapacity"));
        assertThat(publicMethodNames)
                .noneMatch(name -> name.toLowerCase().contains("order"))
                .noneMatch(name -> name.toLowerCase().contains("place"))
                .noneMatch(name -> name.toLowerCase().contains("cancel"))
                .noneMatch(name -> name.toLowerCase().contains("modify"));
    }

    @Test
    void refsRejectMissingIdsAndUnsafeAccountDisplayNumbers() {
        assertThatThrownBy(() -> new BrokerConnectionRef(null, "toss"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BrokerConnectionRef(CONNECTION_ID, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BrokerAccountRef(CONNECTION_ID, "", "US_STOCK", "****1234"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BrokerAccountRef(CONNECTION_ID, "account-seq-1", "US_STOCK", "1234567890"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ACCOUNT.displayAccountNumber()).isEqualTo("****1234");
    }

    @Test
    void responseRequiresValueAndMetadataAndCarriesOptionalRateLimitMetadata() {
        var rateLimit = new RateLimitSnapshot(
                Optional.of(100),
                Optional.of(99),
                Optional.of(OBSERVED_AT.plusSeconds(60)),
                Optional.empty());
        var metadata = new BrokerCallMetadata("request-1", OBSERVED_AT, Optional.of(rateLimit));

        var response = new BrokerResponse<>(CONNECTION, metadata);

        assertThat(response.value()).isEqualTo(CONNECTION);
        assertThat(response.metadata().rateLimit()).contains(rateLimit);
        assertThatThrownBy(() -> new BrokerResponse<>(null, metadata))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BrokerResponse<>(CONNECTION, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void moneyByCurrencyHasNoCrossCurrencyTotalOperation() {
        var money = new MoneyByCurrency(Map.of(
                Currency.USD, new BigDecimal("12.34"),
                Currency.KRW, new BigDecimal("1000")));

        assertThat(money.amounts()).containsEntry(Currency.USD, new BigDecimal("12.34"));
        assertThat(Arrays.stream(MoneyByCurrency.class.getDeclaredMethods())
                .map(method -> method.getName().toLowerCase())
                .filter(name -> name.contains("sum") || name.contains("total"))
                .toList()).isEmpty();
    }

    @Test
    void accountSnapshotKeepsCashUnknownAndCapacitySeparate() {
        var snapshot = new AccountSnapshot(
                ACCOUNT,
                new MoneyByCurrency(Map.of(Currency.USD, new BigDecimal("10000"))),
                new MoneyByCurrency(Map.of(Currency.USD, new BigDecimal("8000"))),
                CashBalanceStatus.UNKNOWN,
                OBSERVED_AT);
        var capacity = new AccountCapacitySnapshot(
                ACCOUNT,
                Currency.USD,
                new BigDecimal("2500"),
                OBSERVED_AT);

        assertThat(snapshot.cashBalanceStatus()).isEqualTo(CashBalanceStatus.UNKNOWN);
        assertThat(capacity.cashBuyingPower()).isEqualByComparingTo("2500");
        assertThat(Arrays.stream(AccountSnapshot.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .filter(name -> name.contains("cash") && !name.equals("cashbalancestatus"))
                .toList()).isEmpty();
        assertThat(Arrays.stream(AccountCapacitySnapshot.class.getDeclaredMethods())
                .map(method -> method.getName().toLowerCase())
                .filter(name -> name.contains("total"))
                .toList()).isEmpty();
    }

    @Test
    void positionRequiresCommissionButAllowsMissingTax() {
        var position = new Position(
                ACCOUNT,
                "NVDA",
                "NVIDIA Corp",
                new BigDecimal("2"),
                Currency.USD,
                new BigDecimal("100"),
                new BigDecimal("120"),
                new BigDecimal("240"),
                new BigDecimal("40"),
                new BigDecimal("0.12"),
                new BigDecimal("1.25"),
                null,
                OBSERVED_AT);

        assertThat(position.commission()).isEqualByComparingTo("1.25");
        assertThat(position.tax()).isNull();
        assertThatThrownBy(() -> new Position(
                ACCOUNT,
                "NVDA",
                "NVIDIA Corp",
                new BigDecimal("2"),
                Currency.USD,
                new BigDecimal("100"),
                new BigDecimal("120"),
                new BigDecimal("240"),
                new BigDecimal("40"),
                new BigDecimal("0.12"),
                null,
                null,
                OBSERVED_AT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void quoteRequiresLocalObservationButBrokerTimestampMayBeMissing() {
        var quote = new Quote(
                CONNECTION,
                "AAPL",
                Currency.USD,
                new BigDecimal("200.12"),
                new BigDecimal("200.10"),
                new BigDecimal("200.15"),
                null,
                OBSERVED_AT);

        assertThat(quote.brokerTimestamp()).isNull();
        assertThat(quote.observedAt()).isEqualTo(OBSERVED_AT);
        assertThatThrownBy(() -> new Quote(
                CONNECTION,
                "AAPL",
                Currency.USD,
                new BigDecimal("200.12"),
                null,
                null,
                null,
                null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void brokerExceptionExposesOnlySafeErrorFields() {
        var exception = new BrokerException(
                BrokerErrorCategory.RATE_LIMITED,
                429,
                "TOO_MANY_REQUESTS",
                "request-1",
                Duration.ofSeconds(5),
                true,
                "broker call failed");

        assertThat(exception.category()).isEqualTo(BrokerErrorCategory.RATE_LIMITED);
        assertThat(exception.httpStatus()).contains(429);
        assertThat(exception.brokerErrorCode()).contains("TOO_MANY_REQUESTS");
        assertThat(exception.requestId()).contains("request-1");
        assertThat(exception.retryAfter()).contains(Duration.ofSeconds(5));
        assertThat(exception.isRetriable()).isTrue();
        assertThat(BrokerException.class.getDeclaredFields())
                .noneMatch(field -> field.getName().toLowerCase().contains("body"))
                .noneMatch(field -> field.getName().toLowerCase().contains("secret"));
    }

    @Test
    void optionalBrokerMetadataFieldsRejectBlankAndInvalidPresentValues() {
        assertThatThrownBy(() -> new BrokerCallMetadata(" ", OBSERVED_AT, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitSnapshot(
                Optional.of(-1),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BrokerException(
                BrokerErrorCategory.UNKNOWN,
                99,
                "SAFE_CODE",
                "request-1",
                null,
                false,
                "broker call failed"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BrokerException(
                BrokerErrorCategory.UNKNOWN,
                500,
                " ",
                "request-1",
                null,
                false,
                "broker call failed"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
