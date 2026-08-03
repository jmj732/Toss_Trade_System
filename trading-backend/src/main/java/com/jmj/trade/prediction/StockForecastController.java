package com.jmj.trade.prediction;

import com.jmj.trade.analysis.StockAnalysisException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stock-forecasts")
public class StockForecastController {

    private final StockForecastService forecasts;

    StockForecastController(StockForecastService forecasts) {
        this.forecasts = forecasts;
    }

    @PostMapping("/{symbol}")
    StockForecastService.StockForecastView execute(
            Principal principal,
            @PathVariable String symbol,
            @RequestBody GenerateRequest request
    ) {
        return forecasts.execute(
                userId(principal),
                symbol,
                request == null
                        ? null
                        : new StockForecastService.GenerateCommand(
                                request.connectionId(), request.modelVersion(), request.contractVersion()));
    }

    @GetMapping("/{symbol}")
    StockForecastService.StockForecastView latest(
            Principal principal,
            @PathVariable String symbol,
            @RequestParam(required = false) UUID runId
    ) {
        return forecasts.latest(userId(principal), symbol, runId);
    }

    @ExceptionHandler(StockForecastException.class)
    ResponseEntity<PublicError> forecastError(StockForecastException exception) {
        return switch (exception.code()) {
            case INVALID_INPUT -> error(HttpStatus.BAD_REQUEST, "STOCK_FORECAST_INPUT_INVALID");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "STOCK_FORECAST_NOT_FOUND");
            case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, "STOCK_FORECAST_TIMEOUT");
            case CONTRACT_ERROR -> error(HttpStatus.BAD_GATEWAY, "STOCK_FORECAST_CONTRACT_ERROR");
            case UPSTREAM_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "STOCK_FORECAST_SERVICE_UNAVAILABLE");
        };
    }

    @ExceptionHandler(StockAnalysisException.class)
    ResponseEntity<PublicError> analysisError(StockAnalysisException exception) {
        return switch (exception.code()) {
            case INVALID_USER -> error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
            case INVALID_SYMBOL -> error(HttpStatus.BAD_REQUEST, "STOCK_SYMBOL_INVALID");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "STOCK_ANALYSIS_OWNER_NOT_FOUND");
            case RESULT_NOT_FOUND -> error(HttpStatus.NOT_FOUND, "STOCK_ANALYSIS_RESULT_NOT_FOUND");
            case ALREADY_RUNNING -> error(HttpStatus.CONFLICT, "STOCK_ANALYSIS_ALREADY_RUNNING");
            case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, "STOCK_ANALYSIS_TIMEOUT");
            case CONTRACT_ERROR -> error(HttpStatus.BAD_GATEWAY, "STOCK_ANALYSIS_CONTRACT_ERROR");
            case UPSTREAM_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "STOCK_ANALYSIS_SERVICE_UNAVAILABLE");
        };
    }

    @ExceptionHandler(AnalysisPredictionException.class)
    ResponseEntity<PublicError> predictionError(AnalysisPredictionException exception) {
        return switch (exception.code()) {
            case INVALID_INPUT -> error(HttpStatus.BAD_REQUEST, "STOCK_FORECAST_INPUT_INVALID");
            case MODEL_VERSION_NOT_ACTIVE ->
                    error(HttpStatus.CONFLICT, "ANALYSIS_PREDICTION_MODEL_VERSION_NOT_ACTIVE");
            case QUOTE_CURRENCY_MISMATCH ->
                    error(HttpStatus.CONFLICT, "ANALYSIS_PREDICTION_QUOTE_CURRENCY_MISMATCH");
            case QUOTE_UNAVAILABLE ->
                    error(HttpStatus.CONFLICT, "ANALYSIS_PREDICTION_QUOTE_UNAVAILABLE");
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

    record GenerateRequest(UUID connectionId, String modelVersion, String contractVersion) {
    }

    record PublicError(String code) {
    }
}
