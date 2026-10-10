package com.jmj.trade.sheets;

import com.jmj.trade.account.AccountSyncService;
import com.jmj.trade.account.BrokerSurfaceService;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.connection.BrokerSurfaceResponse;
import com.jmj.trade.connector.ConnectorResponse;
import com.jmj.trade.connector.ConnectorService;
import com.jmj.trade.investment.InvestmentContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Weekend behavior of the sheet sync, driven only by the official Toss calendar (stubbed with production-shaped
 * KST-offset payloads): Friday's after-market ends 19:50 New York and Monday's day market opens Sunday 20:00
 * New York.
 */
class InvestmentOsSheetClosedMarketSyncTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONNECTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final BrokerAccountRef BROKER_ACCOUNT =
            new BrokerAccountRef(CONNECTION_ID, "01", "GENERAL", "****0001");
    /** Saturday 10:00 New York. */
    private static final Instant SATURDAY = Instant.parse("2026-10-03T14:00:00Z");
    /** Friday 19:55 New York, after the declared after-market end. */
    private static final Instant FRIDAY_AFTER_CLOSE = Instant.parse("2026-10-02T23:55:00Z");
    /** Friday 19:45 New York, inside the declared after-market. */
    private static final Instant FRIDAY_AFTER_MARKET = Instant.parse("2026-10-02T23:45:00Z");
    private static final Instant MONDAY_DAY_MARKET_OPEN = Instant.parse("2026-10-05T00:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private InvestmentOsSheetLease lease;
    private ConnectorService connector;
    private BrokerSurfaceService brokerSurface;
    private GoogleSheetsClient sheets;
    private JdbcTemplate jdbc;
    private AccountSyncService accountSync;

    @BeforeEach
    void setUp() {
        lease = mock(InvestmentOsSheetLease.class);
        when(lease.acquire(any())).thenReturn(true);
        connector = mock(ConnectorService.class);
        brokerSurface = mock(BrokerSurfaceService.class);
        sheets = mock(GoogleSheetsClient.class);
        jdbc = mock(JdbcTemplate.class);
        accountSync = mock(AccountSyncService.class);
        when(sheets.readValues(eq("sheet-1"), any())).thenReturn(new GoogleSheetsClient.SheetValues("range", List.of()));
        when(sheets.readValues(eq("sheet-1"), eq("'Account Registry'!A:Z"))).thenReturn(
                new GoogleSheetsClient.SheetValues("range", List.of(
                        List.of("Account", "Label", "Sync Mode", "Source", "Default Confidence", "Enabled",
                                "Last Sync", "Notes"),
                        List.of("ACCOUNT_1", "Toss", "AUTO", "TOSS_API", "HIGH", "TRUE", "old", "keep"),
                        List.of("ACCOUNT_2", "Manual", "MANUAL", "MANUAL", "MEDIUM", "TRUE", "manual-time",
                                "manual-note"))));
        when(sheets.readValues(eq("sheet-1"), eq("'Account State'!A:Z"))).thenReturn(
                new GoogleSheetsClient.SheetValues("account-state", List.of(
                        new java.util.ArrayList<>(InvestmentOsSheetModel.accountHeaders()),
                        new java.util.ArrayList<>(List.of("ACCOUNT_2", "MSFT", "HOLDING", "USD", "2", "90", "20",
                                "40", "", "USER_SCREENSHOT", "HIGH", SATURDAY.toString(), "TOSS_QUOTE_API",
                                SATURDAY.toString(), "HELD")),
                        new java.util.ArrayList<>(List.of("ACCOUNT_2", "CASH_USD", "CASH", "USD", "", "", "", "",
                                "50", "USER_SCREENSHOT", "HIGH", SATURDAY.toString(), "", "", "CASH")))));
        when(connector.brokerAccount(CONNECTION_ID)).thenReturn(BROKER_ACCOUNT);
        when(connector.orders(BROKER_ACCOUNT, "OPEN")).thenReturn(List.of());
        when(connector.orders(BROKER_ACCOUNT, "CLOSED")).thenReturn(List.of());
        when(brokerSurface.prices(USER_ID, CONNECTION_ID, "ABC,MSFT")).thenReturn(BrokerSurfaceResponse.available(List.of(
                new BrokerSurfaceResponse.PriceView("ABC", bd("15"), null, null, "USD", SATURDAY, SATURDAY),
                new BrokerSurfaceResponse.PriceView("MSFT", bd("20"), null, null, "USD", SATURDAY, SATURDAY))));
    }

    @Test
    void ageStaleSnapshotCapturedAfterTheLastIntervalIsAcceptedWithClosedMarketFacts() throws Exception {
        stubWeekendCalendars();
        when(connector.persistedPortfolio(USER_ID, CONNECTION_ID)).thenReturn(portfolio(FRIDAY_AFTER_CLOSE, true));
        var service = service();

        var first = service.sync();
        var second = service.sync();

        assertThat(first.error()).isNull();
        assertThat(second.error()).isNull();
        verify(accountSync, never()).syncForMonitoring(any(), any());
        // Captured in the current closed gap: the persisted snapshot is read without the broker read-through.
        verify(connector, never()).portfolio(any(), any());
        var payloads = acceptedPayloads(2);
        for (var payload : payloads) {
            assertThat(payload.path("account1AsOf").asText()).isEqualTo(FRIDAY_AFTER_CLOSE.toString());
            assertThat(payload.path("sessionReason").asText())
                    .isEqualTo(InvestmentContextService.PORTFOLIO_CAPTURED_OUTSIDE_DECLARED_INTERVALS);
            assertThat(payload.path("sessionReferenceAt").asText()).isEqualTo(FRIDAY_AFTER_CLOSE.toString());
            assertThat(payload.path("nextDeclaredIntervalStartsAt").asText())
                    .isEqualTo(MONDAY_DAY_MARKET_OPEN.toString());
        }
        // The official calendar is fetched once per New York date, not on every 5-minute sync.
        for (var date : List.of("2026-10-02", "2026-10-03", "2026-10-05")) {
            verify(brokerSurface, times(1)).marketCalendar(USER_ID, CONNECTION_ID, "US", LocalDate.parse(date));
        }
    }

    @Test
    void snapshotCapturedInsideTheAfterMarketTriggersOnePostCloseCaptureAndIsThenAccepted() throws Exception {
        stubWeekendCalendars();
        var captured = SATURDAY.minus(Duration.ofMinutes(1));
        when(connector.persistedPortfolio(USER_ID, CONNECTION_ID))
                .thenReturn(portfolio(FRIDAY_AFTER_MARKET, true), portfolio(captured, false));
        var service = service();

        var first = service.sync();
        var second = service.sync();

        assertThat(first.error()).isNull();
        assertThat(second.error()).isNull();
        verify(accountSync, times(1)).syncForMonitoring(USER_ID, CONNECTION_ID);
        verify(connector, never()).portfolio(any(), any());
        var payload = acceptedPayloads(2).getFirst();
        assertThat(payload.path("account1AsOf").asText()).isEqualTo(captured.toString());
        assertThat(payload.path("sessionReferenceAt").asText()).isEqualTo(captured.toString());
        assertThat(payload.path("nextDeclaredIntervalStartsAt").asText()).isEqualTo(MONDAY_DAY_MARKET_OPEN.toString());
    }

    @Test
    void failedPostCloseCaptureIsBoundedPerClosedGapAndTheSnapshotStaysNonAuthoritative() {
        stubWeekendCalendars();
        when(connector.persistedPortfolio(USER_ID, CONNECTION_ID)).thenReturn(portfolio(FRIDAY_AFTER_MARKET, true));
        when(accountSync.syncForMonitoring(USER_ID, CONNECTION_ID)).thenThrow(new RuntimeException("rate limited"));
        var service = service();

        for (var attempt = 0; attempt < 4; attempt++) {
            assertThat(service.sync().error()).isEqualTo("NON_AUTHORITATIVE_PORTFOLIO");
        }

        verify(accountSync, times(3)).syncForMonitoring(USER_ID, CONNECTION_ID);
        // Exhausted post-close attempts never fall back to the unbounded read-through.
        verify(connector, never()).portfolio(any(), any());
        verify(jdbc, times(4)).update(startsWith("INSERT INTO investment_os_portfolio_snapshots"), any(),
                eq(USER_ID), eq("FAILED"), any(), eq("PORTFOLIO_NOT_AUTHORITATIVE"), any(), any());
        verify(connector, never()).orders(any(), anyString());
    }

    @Test
    void withoutCalendarFactsAnAgeStaleSnapshotKeepsTheExistingBehavior() {
        when(connector.portfolio(USER_ID, CONNECTION_ID)).thenReturn(portfolio(FRIDAY_AFTER_CLOSE, true));

        var result = service().sync();

        assertThat(result.error()).isEqualTo("NON_AUTHORITATIVE_PORTFOLIO");
        verify(accountSync, never()).syncForMonitoring(any(), any());
        verify(jdbc).update(startsWith("INSERT INTO investment_os_portfolio_snapshots"), any(), eq(USER_ID),
                eq("FAILED"), any(), eq("PORTFOLIO_NOT_AUTHORITATIVE"), any(), any());
    }

    @Test
    void otherStaleReasonsAreNeverAcceptedEvenInsideAClosedGap() {
        stubWeekendCalendars();
        when(connector.persistedPortfolio(USER_ID, CONNECTION_ID)).thenReturn(new ConnectorResponse.Portfolio(
                FRIDAY_AFTER_CLOSE, true, "LATEST_SYNC_FAILED", false, List.of(), List.of(), null,
                List.of(position(FRIDAY_AFTER_CLOSE)), buyingPower(FRIDAY_AFTER_CLOSE)));

        var result = service().sync();

        assertThat(result.error()).isEqualTo("NON_AUTHORITATIVE_PORTFOLIO");
        // LATEST_SYNC_FAILED triggers one bounded read-only capture; the re-read snapshot is still rejected.
        verify(accountSync, times(1)).syncForMonitoring(USER_ID, CONNECTION_ID);
        verify(connector, never()).portfolio(any(), any());
    }

    @Test
    void aFreshSnapshotDuringADeclaredIntervalStoresNoClosedMarketFacts() throws Exception {
        stubWeekendCalendars();
        var fridayRegular = Instant.parse("2026-10-02T15:00:00Z");
        when(connector.portfolio(USER_ID, CONNECTION_ID)).thenReturn(portfolio(fridayRegular, false));
        var service = new InvestmentOsSheetSyncService(properties(), lease, connector, brokerSurface, sheets,
                () -> fridayRegular.plus(Duration.ofMinutes(2)), null, jdbc, new ObjectMapper());
        service.setAccountSync(accountSync);

        service.sync();

        verify(accountSync, never()).syncForMonitoring(any(), any());
        var payload = acceptedPayloads(1).getFirst();
        assertThat(payload.path("account1AsOf").asText()).isEqualTo(fridayRegular.toString());
        assertThat(payload.has("sessionReason")).isFalse();
        assertThat(payload.has("nextDeclaredIntervalStartsAt")).isFalse();
    }

    private InvestmentOsSheetSyncService service() {
        var service = new InvestmentOsSheetSyncService(properties(), lease, connector, brokerSurface, sheets,
                () -> SATURDAY, null, jdbc, new ObjectMapper());
        service.setAccountSync(accountSync);
        return service;
    }

    private static InvestmentOsSheetProperties properties() {
        return new InvestmentOsSheetProperties(true, "sheet-1", USER_ID, CONNECTION_ID,
                Duration.ofMinutes(5), Duration.ZERO, Duration.ofMinutes(2));
    }

    private List<tools.jackson.databind.JsonNode> acceptedPayloads(int count) throws Exception {
        var payloads = ArgumentCaptor.forClass(Object.class);
        verify(jdbc, times(count)).update(startsWith("INSERT INTO investment_os_portfolio_snapshots"), any(),
                eq(USER_ID), any(), any(), any(), payloads.capture(), any());
        var result = new java.util.ArrayList<tools.jackson.databind.JsonNode>();
        for (var payload : payloads.getAllValues()) result.add(mapper.readTree(String.valueOf(payload)));
        return result;
    }

    private void stubWeekendCalendars() {
        calendar("2026-10-02", """
                {"today":{"date":"2026-10-02",
                  "dayMarket":{"startTime":"2026-10-02T09:00:00.000+09:00","endTime":"2026-10-02T17:00:00.000+09:00"},
                  "preMarket":{"startTime":"2026-10-02T17:00:00.000+09:00","endTime":"2026-10-02T22:30:00.000+09:00"},
                  "regularMarket":{"startTime":"2026-10-02T22:30:00.000+09:00","endTime":"2026-10-03T05:00:00.000+09:00"},
                  "afterMarket":{"startTime":"2026-10-03T05:00:00.000+09:00","endTime":"2026-10-03T08:50:00.000+09:00"}},
                 "previousBusinessDay":{"date":"2026-10-01"},"nextBusinessDay":{"date":"2026-10-05"}}
                """);
        calendar("2026-10-03", """
                {"today":{"date":"2026-10-03","dayMarket":null,"preMarket":null,"regularMarket":null,"afterMarket":null},
                 "previousBusinessDay":{"date":"2026-10-02"},"nextBusinessDay":{"date":"2026-10-05"}}
                """);
        calendar("2026-10-05", """
                {"today":{"date":"2026-10-05",
                  "dayMarket":{"startTime":"2026-10-05T09:00:00.000+09:00","endTime":"2026-10-05T17:00:00.000+09:00"},
                  "preMarket":{"startTime":"2026-10-05T17:00:00.000+09:00","endTime":"2026-10-05T22:30:00.000+09:00"},
                  "regularMarket":{"startTime":"2026-10-05T22:30:00.000+09:00","endTime":"2026-10-06T05:00:00.000+09:00"},
                  "afterMarket":{"startTime":"2026-10-06T05:00:00.000+09:00","endTime":"2026-10-06T08:50:00.000+09:00"}},
                 "previousBusinessDay":{"date":"2026-10-02"},"nextBusinessDay":{"date":"2026-10-06"}}
                """);
    }

    private void calendar(String date, String json) {
        when(brokerSurface.marketCalendar(USER_ID, CONNECTION_ID, "US", LocalDate.parse(date)))
                .thenReturn(BrokerSurfaceResponse.available(
                        new BrokerSurfaceResponse.MarketCalendarView("US", mapper.readTree(json))));
    }

    private static ConnectorResponse.Portfolio portfolio(Instant completedAt, boolean tooOld) {
        return new ConnectorResponse.Portfolio(completedAt, tooOld, tooOld ? "SNAPSHOT_TOO_OLD" : null, false,
                List.of(), List.of(), null, List.of(position(completedAt)), buyingPower(completedAt));
    }

    private static ConnectorResponse.Position position(Instant observedAt) {
        return new ConnectorResponse.Position("ABC", "ABC", "US", bd("2"), "USD", bd("10"),
                bd("11"), bd("20"), bd("22"), bd("22"), bd("2"), bd("2"), bd("0.1"), bd("0.1"),
                bd("0"), bd("0"), bd("0"), bd("0"), bd("2"), observedAt);
    }

    private static Map<String, ConnectorResponse.BuyingPower> buyingPower(Instant observedAt) {
        return Map.of("USD", new ConnectorResponse.BuyingPower(bd("100"), observedAt),
                "KRW", new ConnectorResponse.BuyingPower(bd("0"), observedAt));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
