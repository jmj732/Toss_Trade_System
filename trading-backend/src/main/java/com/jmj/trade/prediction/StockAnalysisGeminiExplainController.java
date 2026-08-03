package com.jmj.trade.prediction;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/stock-analysis-explanations")
public class StockAnalysisGeminiExplainController {

    private final StockAnalysisGeminiExplainService explanations;

    StockAnalysisGeminiExplainController(StockAnalysisGeminiExplainService explanations) {
        this.explanations = explanations;
    }

    @PostMapping("/{symbol}")
    StockAnalysisGeminiExplainService.StockAnalysisGeminiExplainView execute(
            Principal principal, @PathVariable String symbol) {
        return explanations.execute(userId(principal), symbol);
    }

    @GetMapping("/{symbol}")
    StockAnalysisGeminiExplainService.StockAnalysisGeminiExplainView latest(
            Principal principal, @PathVariable String symbol,
            @RequestParam(required = false) UUID runId) {
        return explanations.latest(userId(principal), symbol, runId);
    }

    @ExceptionHandler(StockForecastException.class)
    ResponseEntity<PublicError> explainError(StockForecastException exception) {
        return switch (exception.code()) {
            case INVALID_INPUT -> error(HttpStatus.BAD_REQUEST, "STOCK_ANALYSIS_EXPLAIN_INPUT_INVALID");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "STOCK_ANALYSIS_EXPLAIN_NOT_FOUND");
            case CONTRACT_ERROR -> error(HttpStatus.BAD_GATEWAY, "STOCK_ANALYSIS_EXPLAIN_CONTRACT_ERROR");
            case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, "STOCK_ANALYSIS_EXPLAIN_TIMEOUT");
            case UPSTREAM_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "STOCK_ANALYSIS_EXPLAIN_SERVICE_UNAVAILABLE");
        };
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new StockForecastException(StockForecastException.Code.INVALID_INPUT);
        }
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    record PublicError(String code) {
    }
}
