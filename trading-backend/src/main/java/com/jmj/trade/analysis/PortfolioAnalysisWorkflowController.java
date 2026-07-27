package com.jmj.trade.analysis;

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

@RestController
@RequestMapping("/api/v1/broker-connections/{connectionId}/portfolio-analyses")
public class PortfolioAnalysisWorkflowController {

    private final PortfolioAnalysisWorkflowService service;

    PortfolioAnalysisWorkflowController(PortfolioAnalysisWorkflowService service) {
        this.service = service;
    }

    @PostMapping
    PortfolioAnalysisWorkflowService.AnalysisView execute(
            Principal principal,
            @PathVariable UUID connectionId
    ) {
        return service.execute(userId(principal), connectionId);
    }

    @GetMapping("/latest")
    PortfolioAnalysisWorkflowService.AnalysisView latest(
            Principal principal,
            @PathVariable UUID connectionId
    ) {
        return service.latest(userId(principal), connectionId);
    }

    @ExceptionHandler(PortfolioAnalysisException.class)
    ResponseEntity<PublicError> analysis(PortfolioAnalysisException exception) {
        return switch (exception.code()) {
            case INVALID_USER -> error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "BROKER_CONNECTION_NOT_FOUND");
            case SNAPSHOT_NOT_FOUND -> error(HttpStatus.NOT_FOUND, "PORTFOLIO_SNAPSHOT_NOT_FOUND");
            case RESULT_NOT_FOUND -> error(HttpStatus.NOT_FOUND, "ANALYSIS_RESULT_NOT_FOUND");
            case ALREADY_RUNNING -> error(HttpStatus.CONFLICT, "ANALYSIS_ALREADY_RUNNING");
            case TIMEOUT -> error(HttpStatus.GATEWAY_TIMEOUT, "ANALYSIS_TIMEOUT");
            case CONTRACT_ERROR -> error(HttpStatus.BAD_GATEWAY, "ANALYSIS_CONTRACT_ERROR");
            case UPSTREAM_UNAVAILABLE -> error(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ANALYSIS_SERVICE_UNAVAILABLE");
        };
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new PortfolioAnalysisException(PortfolioAnalysisException.Code.INVALID_USER);
        }
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    record PublicError(String code) {
    }
}
