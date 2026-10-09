package com.jmj.trade.investment;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.account.PortfolioReadService;
import com.jmj.trade.marketdata.StockDataProviderRegistry;
import com.jmj.trade.monitoring.MonitoringWatchlistService;
import com.jmj.trade.risk.RiskPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class InvestmentPromptContractIntegrationTest extends PostgresIntegrationTest {
    private static final UUID USER = UUID.fromString("11990000-0000-7000-8000-000000000001");
    private JdbcTemplate jdbc;
    private HikariDataSource dataSource;
    private InvestmentContextService service;

    @BeforeEach
    void setUp() {
        freshMigratedSchema();
        dataSource = pooledTestDataSource();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO users(id) VALUES(?)", USER);
        service = new InvestmentContextService(jdbc, new ObjectMapper(), new DataSourceTransactionManager(dataSource),
                new StockDataProviderRegistry(List.of()), null, mock(PortfolioReadService.class),
                mock(MonitoringWatchlistService.class), mock(RiskPolicyService.class),
                Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(210), Duration.ofDays(10));
    }

    @AfterEach
    void closeTestDataSource() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void persistsPromptStatusesWithoutChangingTheirMeaningAndKeepsLegacyStatuses() {
        java.time.Instant lastUpdatedAt = null;
        for (var status : List.of("AI_PROPOSED", "UNVERIFIED", "INVALIDATION_UNDEFINED",
                "NOT_REVIEWED", "SUSPECTED", "CLEARED")) {
            var saved = service.putThesis(USER, "avt", input(status), null, null, null, null);
            assertThat(saved.invalidationStatus()).isEqualTo(status);
            lastUpdatedAt = saved.updatedAt();
            assertThat(jdbc.queryForObject("SELECT invalidation_status FROM investment_thesis_states WHERE user_id=? AND ticker='AVT'",
                    String.class, USER)).isEqualTo(status);
        }
        // CONFIRMED requires sourceAsOf when first set and expectedUpdatedAt matching prior updatedAt
        var now = java.time.Instant.now();
        var confirmed = service.putThesis(USER, "avt", input("CONFIRMED"), lastUpdatedAt, now, null, null);
        assertThat(confirmed.invalidationStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void rejectsUnknownStatusRatherThanCoercingItToConfirmed() {
        assertThatThrownBy(() -> service.putThesis(USER, "AVT", input("BUY"), null, null, null, null))
                .isInstanceOf(InvestmentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_states", Integer.class)).isZero();
    }

    @Test
    void proposalUpdatesRequireTheObservedVersionAndCannotOverwriteConfirmedState() {
        var created = service.putThesisProposal(USER, "AVT", input("AI_PROPOSED"), null);
        assertThatThrownBy(() -> service.putThesisProposal(USER, "AVT", input("UNVERIFIED"), null))
                .isInstanceOf(InvestmentException.class);
        var updated = service.putThesisProposal(USER, "AVT", input("UNVERIFIED"), created.updatedAt());
        assertThat(updated.invalidationStatus()).isEqualTo("UNVERIFIED");
        var now = java.time.Instant.now();
        var confirmed = service.putThesis(USER, "AVT", input("CONFIRMED"), updated.updatedAt(), now, null, null);
        assertThatThrownBy(() -> service.putThesisProposal(USER, "AVT", input("AI_PROPOSED"), confirmed.updatedAt()))
                .isInstanceOf(InvestmentException.class);
        assertThat(jdbc.queryForObject("SELECT invalidation_status FROM investment_thesis_states WHERE user_id=?",
                String.class, USER)).isEqualTo("CONFIRMED");
    }

    private static InvestmentContextService.ThesisInput input(String status) {
        return new InvestmentContextService.ThesisInput("Caller supplied thesis", null, null,
                null, null, null, null, status, null, null, null);
    }
}
