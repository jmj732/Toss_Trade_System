package com.jmj.trade.analysis;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stock-analyses")
public class StockAnalysisWorkflowController {

    private final StockAnalysisWorkflowService service;

    StockAnalysisWorkflowController(StockAnalysisWorkflowService service) {
        this.service = service;
    }

    @PostMapping("/{symbol}")
    StockAnalysisWorkflowService.StockAnalysisView execute(
            Principal principal,
            @PathVariable String symbol,
            @RequestBody(required = false) CreateRequest request
    ) {
        return service.execute(userId(principal), symbol, request == null ? Map.of() : request.identifiers());
    }

    @ExceptionHandler(StockAnalysisException.class)
    ResponseEntity<PublicError> analysis(StockAnalysisException exception) {
        return switch (exception.code()) {
            case INVALID_USER -> error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
            case INVALID_SYMBOL -> error(HttpStatus.BAD_REQUEST, "STOCK_SYMBOL_INVALID");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "STOCK_ANALYSIS_OWNER_NOT_FOUND");
            case ALREADY_RUNNING -> error(HttpStatus.CONFLICT, "STOCK_ANALYSIS_ALREADY_RUNNING");
            case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, "STOCK_ANALYSIS_TIMEOUT");
            case CONTRACT_ERROR -> error(HttpStatus.BAD_GATEWAY, "STOCK_ANALYSIS_CONTRACT_ERROR");
            case UPSTREAM_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "STOCK_ANALYSIS_SERVICE_UNAVAILABLE");
        };
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<PublicError> invalidInput() {
        return error(HttpStatus.BAD_REQUEST, "STOCK_ANALYSIS_INPUT_INVALID");
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new StockAnalysisException(StockAnalysisException.Code.INVALID_USER);
        }
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    record CreateRequest(Map<String, String> identifiers) {
    }

    record PublicError(String code) {
    }
}
