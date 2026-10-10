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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Weekend sheet sync with the production data shape seen on Saturday 2026-10-10 (US market closed): the legacy
 * Account State layout, ACCOUNT_2 manual rows whose {@code Synced At} is free text such as
 * {@code 2026-10-10 19:40 KST (user-confirmed)}, ISO {@code Price Synced At} written by the Toss quote fetch, and
 * KST-offset Toss calendars for Friday 2026-10-09, Saturday 2026-10-10 and Monday 2026-10-12.
 */
class InvestmentOsSheetWeekendProductionShapeTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONNECTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final BrokerAccountRef BROKER_ACCOUNT =
            new BrokerAccountRef(CONNECTION_ID, "01", "GENERAL", "****0001");
    private static final List<String> LEGACY_HEADERS = List.of(
            "asOf", "Account", "Asset", "Quantity", "Avg Cost", "Currency", "State", "Source", "Confidence",
            "Synced At", "Notes", "Current Price", "Market Value", "Price Source", "Price Synced At");
    /** Saturday 2026-10-10 06:47:59 New York, the accepted production attempt. */
    private static final Instant SATURDAY_SYNC = Instant.parse("2026-10-10T10:47:59Z");
    /** The account snapshot the read-through captured five seconds into that attempt. */
    private static final Instant SATURDAY_CAPTURE = Instant.parse("2026-10-10T10:48:04.665522Z");
    /** The previous accepted attempt's quote time, still on rows from the 10:42 sync. */
    private static final Instant PREVIOUS_QUOTE = Instant.parse("2026-10-10T10:42:50Z");
    /** Monday 2026-10-12 day market opens 09:00 KST, Sunday 20:00 New York. */
    private static final Instant MONDAY_DAY_MARKET_OPEN = Instant.parse("2026-10-12T00:00:00Z");

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
                        new ArrayList<>(LEGACY_HEADERS),
                        new ArrayList<>(List.of("2026-10-10", "ACCOUNT_1", "AVT", "2", "10", "USD", "HELD",
                                "TOSS_API", "HIGH", "2026-10-10T10:42:48.120000Z", "", "11", "22",
                                "TOSS_QUOTE_API", PREVIOUS_QUOTE.toString())),
                        new ArrayList<>(List.of("2026-10-10", "ACCOUNT_2", "LUNR", "3", "8", "USD", "HELD",
                                "USER_SCREENSHOT", "HIGH", "2026-10-10 19:40 KST (user-confirmed)", "", "9", "27",
                                "TOSS_QUOTE_API", PREVIOUS_QUOTE.toString())),
                        new ArrayList<>(List.of("2026-10-10", "ACCOUNT_2", "CASH_USD", "50", "", "USD", "CASH",
                                "USER_SCREENSHOT", "HIGH", "2026-10-10 19:40 KST (user-confirmed)", "", "", "",
                                "", "")))));
        when(connector.brokerAccount(CONNECTION_ID)).thenReturn(BROKER_ACCOUNT);
        when(connector.orders(BROKER_ACCOUNT, "OPEN")).thenReturn(List.of());
        when(connector.orders(BROKER_ACCOUNT, "CLOSED")).thenReturn(List.of());
        var quoted = SATURDAY_CAPTURE.plusSeconds(2);
        when(brokerSurface.prices(USER_ID, CONNECTION_ID, "AVT,LUNR")).thenReturn(BrokerSurfaceResponse.available(List.of(
                new BrokerSurfaceResponse.PriceView("AVT", bd("11"), null, null, "USD", quoted, quoted),
                new BrokerSurfaceResponse.PriceView("LUNR", bd("9"), null, null, "USD", quoted, quoted))));
    }

    @Test
    void saturdayCaptureWithLegacyManualRowsRecordsClosedMarketFacts() throws Exception {
        stubWeekendCalendars();
        when(connector.portfolio(USER_ID, CONNECTION_ID)).thenReturn(portfolio(SATURDAY_CAPTURE, false, null));

        var result = service(SATURDAY_SYNC).sync();

        assertThat(result.error()).isNull();
        var payload = acceptedPayloads(1).getFirst();
        assertThat(payload.path("manualStatus").asText()).isEqualTo("OK");
        assertThat(payload.path("account1AsOf").asText()).isEqualTo(SATURDAY_CAPTURE.toString());
        assertThat(payload.path("sessionReason").asText())
                .isEqualTo(InvestmentContextService.PORTFOLIO_CAPTURED_OUTSIDE_DECLARED_INTERVALS);
        assertThat(payload.path("sessionReferenceAt").asText()).isEqualTo(SATURDAY_CAPTURE.toString());
        assertThat(payload.path("nextDeclaredIntervalStartsAt").asText())
                .isEqualTo(MONDAY_DAY_MARKET_OPEN.toString());
    }

    /**
     * The attempt starts at {@code startedAt}; every later clock reading is 30 seconds on, after the read-through
     * account sync and quote fetch have completed, as in production.
     */
    private InvestmentOsSheetSyncService service(Instant startedAt) {
        var readings = new java.util.concurrent.atomic.AtomicInteger();
        var service = new InvestmentOsSheetSyncService(properties(), lease, connector, brokerSurface, sheets,
                () -> readings.getAndIncrement() == 0 ? startedAt : startedAt.plusSeconds(30), null, jdbc,
                new ObjectMapper());
        service.setAccountSync(accountSync);
        return service;
    }

    private static InvestmentOsSheetProperties properties() {
        return new InvestmentOsSheetProperties(true, "sheet-1", USER_ID, CONNECTION_ID,
                Duration.ofMinutes(5), Duration.ZERO, Duration.ofMinutes(2));
    }

    private List<JsonNode> acceptedPayloads(int count) throws Exception {
        var payloads = ArgumentCaptor.forClass(Object.class);
        verify(jdbc, times(count)).update(startsWith("INSERT INTO investment_os_portfolio_snapshots"), any(),
                eq(USER_ID), any(), any(), any(), payloads.capture(), any());
        var result = new ArrayList<JsonNode>();
        for (var payload : payloads.getAllValues()) result.add(mapper.readTree(String.valueOf(payload)));
        return result;
    }

    private void stubWeekendCalendars() {
        calendar("2026-10-09", """
                {"today":{"date":"2026-10-09",
                  "dayMarket":{"startTime":"2026-10-09T09:00:00.000+09:00","endTime":"2026-10-09T17:00:00.000+09:00"},
                  "preMarket":{"startTime":"2026-10-09T17:00:00.000+09:00","endTime":"2026-10-09T22:30:00.000+09:00"},
                  "regularMarket":{"startTime":"2026-10-09T22:30:00.000+09:00","endTime":"2026-10-10T05:00:00.000+09:00"},
                  "afterMarket":{"startTime":"2026-10-10T05:00:00.000+09:00","endTime":"2026-10-10T08:50:00.000+09:00"}},
                 "previousBusinessDay":{"date":"2026-10-08"},"nextBusinessDay":{"date":"2026-10-12"}}
                """);
        calendar("2026-10-10", """
                {"today":{"date":"2026-10-10","dayMarket":null,"preMarket":null,"regularMarket":null,"afterMarket":null},
                 "previousBusinessDay":{"date":"2026-10-09"},"nextBusinessDay":{"date":"2026-10-12"}}
                """);
        calendar("2026-10-12", """
                {"today":{"date":"2026-10-12",
                  "dayMarket":{"startTime":"2026-10-12T09:00:00.000+09:00","endTime":"2026-10-12T17:00:00.000+09:00"},
                  "preMarket":{"startTime":"2026-10-12T17:00:00.000+09:00","endTime":"2026-10-12T22:30:00.000+09:00"},
                  "regularMarket":{"startTime":"2026-10-12T22:30:00.000+09:00","endTime":"2026-10-13T05:00:00.000+09:00"},
                  "afterMarket":{"startTime":"2026-10-13T05:00:00.000+09:00","endTime":"2026-10-13T08:50:00.000+09:00"}},
                 "previousBusinessDay":{"date":"2026-10-09"},"nextBusinessDay":{"date":"2026-10-13"}}
                """);
    }

    private void calendar(String date, String json) {
        when(brokerSurface.marketCalendar(USER_ID, CONNECTION_ID, "US", LocalDate.parse(date)))
                .thenReturn(BrokerSurfaceResponse.available(
                        new BrokerSurfaceResponse.MarketCalendarView("US", mapper.readTree(json))));
    }

    private static ConnectorResponse.Portfolio portfolio(Instant completedAt, boolean stale, String staleReason) {
        return new ConnectorResponse.Portfolio(completedAt, stale, staleReason, false, List.of(), List.of(), null,
                List.of(position(completedAt)), buyingPower(completedAt));
    }

    private static ConnectorResponse.Position position(Instant observedAt) {
        return new ConnectorResponse.Position("AVT", "AVT", "US", bd("2"), "USD", bd("10"),
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
