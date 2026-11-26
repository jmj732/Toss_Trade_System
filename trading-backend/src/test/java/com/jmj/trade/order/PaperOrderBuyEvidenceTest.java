package com.jmj.trade.order;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Cited-source parsing for the approval preview. The stored sources JSON is shown as provided, not verified. */
class PaperOrderBuyEvidenceTest {

    @Test
    void citedSourcesKeepUrlTypeAndAsOfAsStoredWithoutVerifyingContent() {
        var json = """
                [{"url":"https://www.sec.gov/Archives/edgar/data/320193/000032019326000100/filing.htm",
                  "asOf":"2026-11-05T00:00:00Z","type":"FUNDAMENTAL"},
                 {"url":"https://www.nasdaq.com/market-activity/stocks/lunr/earnings",
                  "asOf":"2026-11-10T15:00:00Z","type":"EXPECTATIONS"}]
                """;

        var sources = PaperOrderWorkflowService.citedSources(json);

        assertThat(sources).hasSize(2);
        assertThat(sources.getFirst().type()).isEqualTo("FUNDAMENTAL");
        assertThat(sources.getFirst().url()).startsWith("https://www.sec.gov/");
        assertThat(sources.getFirst().asOf().toString()).isEqualTo("2026-11-05T00:00:00Z");
    }

    @Test
    void missingOrMalformedSourcesYieldNoEvidenceRowsInsteadOfFailingApproval() {
        assertThat(PaperOrderWorkflowService.citedSources(null)).isEmpty();
        assertThat(PaperOrderWorkflowService.citedSources("{}")).isEmpty();
        assertThat(PaperOrderWorkflowService.citedSources("[{\"type\":\"RISK\",\"asOf\":\"not-a-time\"}]"))
                .singleElement().satisfies(source -> assertThat(source.asOf()).isNull());
    }
}
