package com.jmj.trade.monitoring;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/monitoring")
class MonitoringController {

    private final MonitoringWatchlistService service;

    MonitoringController(MonitoringWatchlistService service) {
        this.service = service;
    }

    @GetMapping("/watchlist")
    List<MonitoringWatchlistService.WatchlistEntry> watchlist(Principal principal) {
        return service.list(userId(principal));
    }

    @PutMapping("/watchlist/{symbol}")
    MonitoringWatchlistService.WatchlistEntry putWatchlist(
            Principal principal,
            @PathVariable String symbol,
            @RequestBody WatchlistRequest request
    ) {
        return service.put(userId(principal), symbol, request.levels());
    }

    @DeleteMapping("/watchlist/{symbol}")
    ResponseEntity<Void> removeWatchlist(Principal principal, @PathVariable String symbol) {
        service.remove(userId(principal), symbol);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/positions/{symbol}/context")
    MonitoringWatchlistService.PositionContext positionContext(
            Principal principal,
            @PathVariable String symbol
    ) {
        return service.positionContext(userId(principal), symbol).orElseThrow(() ->
                new MonitoringException(MonitoringException.Code.NOT_FOUND));
    }

    @PutMapping("/positions/{symbol}/context")
    MonitoringWatchlistService.PositionContext putPositionContext(
            Principal principal,
            @PathVariable String symbol,
            @RequestBody MonitoringWatchlistService.PositionContextInput request
    ) {
        return service.putPositionContext(userId(principal), symbol, request);
    }

    @ExceptionHandler(MonitoringException.class)
    ResponseEntity<PublicError> monitoring(MonitoringException exception) {
        return switch (exception.code()) {
            case INVALID_USER -> error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
            case INVALID_INPUT -> error(HttpStatus.BAD_REQUEST, "MONITORING_INPUT_INVALID");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "MONITORING_STATE_NOT_FOUND");
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<PublicError> malformedJson() {
        return error(HttpStatus.BAD_REQUEST, "MONITORING_INPUT_INVALID");
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new MonitoringException(MonitoringException.Code.INVALID_USER);
        }
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    record WatchlistRequest(MonitoringWatchlistService.WatchlistLevels levels) {
    }

    record PublicError(String code) {
    }
}
