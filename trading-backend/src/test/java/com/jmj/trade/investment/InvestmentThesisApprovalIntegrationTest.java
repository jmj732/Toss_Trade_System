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
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class InvestmentThesisApprovalIntegrationTest extends PostgresIntegrationTest {
    private static final UUID USER = UUID.fromString("11990000-0000-7000-8000-000000000099");
    private static final UUID OTHER_USER = UUID.fromString("11990000-0000-7000-8000-000000000098");
    private static final UUID SESSION_ID = UUID.fromString("22990000-0000-7000-8000-000000000001");
    private JdbcTemplate jdbc;
    private HikariDataSource dataSource;
    private InvestmentContextService service;

    @BeforeEach
    void setUp() {
        freshMigratedSchema();
        dataSource = pooledTestDataSource();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO users(id) VALUES(?)", USER);
        jdbc.update("INSERT INTO users(id) VALUES(?)", OTHER_USER);
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
    void confirmWithoutSourceAsOfThrowsInvalidInput() {
        var now = java.time.Instant.now();
        assertThatThrownBy(() -> service.putThesis(USER, "AAPL", input("CONFIRMED"), null, null, "user approved", SESSION_ID))
                .isInstanceOf(InvestmentException.class)
                .hasFieldOrPropertyWithValue("code", InvestmentException.Code.INVALID_INPUT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_revisions", Integer.class)).isZero();
    }

    @Test
    void confirmWithWrongExpectedUpdatedAtThrowsConflict() {
        var now = java.time.Instant.now();
        var pastTime = now.minusSeconds(60);
        service.putThesis(USER, "AAPL", input("AI_PROPOSED"), null, null, null, null);
        assertThatThrownBy(() -> service.putThesis(USER, "AAPL", input("CONFIRMED"), pastTime, now, "user approved", SESSION_ID))
                .isInstanceOf(InvestmentException.class)
                .hasFieldOrPropertyWithValue("code", InvestmentException.Code.CONFLICT);
        // Only 1 revision from the first call; the conflicting confirm doesn't create a revision
        assertThat(jdbc.queryForObject("SELECT count(*) FROM investment_thesis_revisions", Integer.class)).isEqualTo(1);
    }

    @Test
    void validConfirmCreatesRevisionWithCorrectMetadata() {
        var now = java.time.Instant.now();
        var proposed = service.putThesisProposal(USER, "AAPL", input("AI_PROPOSED"), null);
        var confirmed = service.putThesis(USER, "AAPL", input("CONFIRMED"), proposed.updatedAt(), now, "user approved", SESSION_ID);

        assertThat(confirmed.invalidationStatus()).isEqualTo("CONFIRMED");
        var revisions = service.thesisRevisions(USER, "AAPL", 200);
        assertThat(revisions).hasSize(2);

        var confirmRevision = revisions.get(0);
        assertThat(confirmRevision.revision()).isEqualTo(2);
        assertThat(confirmRevision.previousStatus()).isEqualTo("AI_PROPOSED");
        assertThat(confirmRevision.newStatus()).isEqualTo("CONFIRMED");
        assertThat(confirmRevision.actorType()).isEqualTo("USER_SESSION");
        assertThat(confirmRevision.reason()).isEqualTo("user approved");
        assertThat(confirmRevision.actorSessionId()).isEqualTo(SESSION_ID);
        assertThat(confirmRevision.sourceAsOf()).isNotNull();
    }

    @Test
    void triggerPriceChangeWhileConfirmedRequiresSourceAsOf() {
        var now = java.time.Instant.now();
        var initial = service.putThesis(USER, "AAPL", inputWithPrice("CONFIRMED", bd("100.00")), null, now, null, null);
        assertThatThrownBy(() -> service.putThesis(USER, "AAPL", inputWithPrice("CONFIRMED", bd("105.00")), initial.updatedAt(), null, null, null))
                .isInstanceOf(InvestmentException.class)
                .hasFieldOrPropertyWithValue("code", InvestmentException.Code.INVALID_INPUT);
    }

    @Test
    void unchangedReConfirmWithAllNullsSucceeds() {
        var now = java.time.Instant.now();
        var initial = service.putThesis(USER, "AAPL", inputWithPrice("CONFIRMED", bd("100.00")), null, now, null, null);
        var reconfirmed = service.putThesis(USER, "AAPL", inputWithPrice("CONFIRMED", bd("100.00")), null, null, null, null);
        assertThat(reconfirmed.invalidationStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void multipleProposalsTrackedInRevisions() {
        var proposed1 = service.putThesisProposal(USER, "AAPL", input("AI_PROPOSED"), null);
        var proposed2 = service.putThesisProposal(USER, "AAPL", input("UNVERIFIED"), proposed1.updatedAt());
        var proposed3 = service.putThesisProposal(USER, "AAPL", input("INVALIDATION_UNDEFINED"), proposed2.updatedAt());

        var revisions = service.thesisRevisions(USER, "AAPL", 200);
        assertThat(revisions).hasSize(3);

        for (var revision : revisions) {
            assertThat(revision.actorType()).isEqualTo("CONNECTOR_MCP");
        }

        assertThat(revisions.get(0).revision()).isEqualTo(3);
        assertThat(revisions.get(0).newStatus()).isEqualTo("INVALIDATION_UNDEFINED");
        assertThat(revisions.get(1).revision()).isEqualTo(2);
        assertThat(revisions.get(1).newStatus()).isEqualTo("UNVERIFIED");
        assertThat(revisions.get(2).revision()).isEqualTo(1);
        assertThat(revisions.get(2).newStatus()).isEqualTo("AI_PROPOSED");
    }

    @Test
    void thesisRevisionsOtherUserGetsEmptyList() {
        service.putThesisProposal(USER, "AAPL", input("AI_PROPOSED"), null);
        var revisions = service.thesisRevisions(OTHER_USER, "AAPL", 200);
        assertThat(revisions).isEmpty();
    }

    @Test
    void thesisRevisionsLimitZeroThrowsInvalidInput() {
        assertThatThrownBy(() -> service.thesisRevisions(USER, "AAPL", 0))
                .isInstanceOf(InvestmentException.class)
                .hasFieldOrPropertyWithValue("code", InvestmentException.Code.INVALID_INPUT);
    }

    @Test
    void thesisRevisionsLimitOver200ThrowsInvalidInput() {
        assertThatThrownBy(() -> service.thesisRevisions(USER, "AAPL", 201))
                .isInstanceOf(InvestmentException.class)
                .hasFieldOrPropertyWithValue("code", InvestmentException.Code.INVALID_INPUT);
    }

    private static InvestmentContextService.ThesisInput input(String status) {
        return new InvestmentContextService.ThesisInput("Caller supplied thesis", null, null,
                null, null, null, null, status, null, null, null);
    }

    private static InvestmentContextService.ThesisInput inputWithPrice(String status, BigDecimal price) {
        return new InvestmentContextService.ThesisInput("Caller supplied thesis", null, null,
                null, null, null, price, status, null, null, null);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
