package com.jmj.trade.broker.connection;

import com.jmj.trade.account.PortfolioReadException;
import com.jmj.trade.account.AccountSyncException;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class BrokerConnectionErrorHandler {

    @ExceptionHandler(AuthenticatedUserInvalidException.class)
    ResponseEntity<PublicError> authenticatedUserInvalid() {
        return error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
    }

    @ExceptionHandler(BrokerConnectionException.class)
    ResponseEntity<PublicError> brokerConnection(BrokerConnectionException exception) {
        return switch (exception.code()) {
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, exception.code().publicCode());
            case ALREADY_EXISTS -> error(HttpStatus.CONFLICT, exception.code().publicCode());
            case CONFLICT -> error(HttpStatus.CONFLICT, exception.code().publicCode());
            case VALIDATION_FAILED -> error(HttpStatus.UNPROCESSABLE_ENTITY, exception.code().publicCode());
        };
    }

    @ExceptionHandler(CredentialUnavailableException.class)
    ResponseEntity<PublicError> credentialUnavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "BROKER_CREDENTIAL_UNAVAILABLE");
    }

    @ExceptionHandler(PortfolioReadException.class)
    ResponseEntity<PublicError> portfolioSnapshotNotFound() {
        return error(HttpStatus.NOT_FOUND, "PORTFOLIO_SNAPSHOT_NOT_FOUND");
    }

    @ExceptionHandler(AccountSyncException.class)
    ResponseEntity<PublicError> accountSync(AccountSyncException exception) {
        return switch (exception.code()) {
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "BROKER_CONNECTION_NOT_FOUND");
            case SYNC_ALREADY_RUNNING -> error(HttpStatus.CONFLICT, "PORTFOLIO_SYNC_ALREADY_RUNNING");
            case CREDENTIAL_REVISION_CHANGED ->
                    error(HttpStatus.CONFLICT, "BROKER_CREDENTIAL_REVISION_CHANGED");
            case ACCOUNT_COUNT_UNSUPPORTED ->
                    error(HttpStatus.UNPROCESSABLE_ENTITY, "BROKER_ACCOUNT_COUNT_UNSUPPORTED");
            case BROKER_CONTRACT_MISMATCH ->
                    error(HttpStatus.BAD_GATEWAY, "BROKER_CONTRACT_MISMATCH");
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<PublicError> malformedJson() {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "BROKER_CONNECTION_VALIDATION_FAILED");
    }

    @ExceptionHandler(BrokerException.class)
    ResponseEntity<PublicError> broker(BrokerException exception) {
        var status = exception.httpStatus()
                .map(HttpStatus::resolve)
                .orElse(defaultStatus(exception.category()));
        if (status == null) {
            status = defaultStatus(exception.category());
        }
        return error(status, publicBrokerCode(exception.category()));
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    private static HttpStatus defaultStatus(BrokerErrorCategory category) {
        return switch (category) {
            case AUTHENTICATION, AUTHORIZATION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case INVALID_REQUEST, VALIDATION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BROKER_UNAVAILABLE, NETWORK, TEMPORARY, CONTRACT, UNKNOWN -> HttpStatus.SERVICE_UNAVAILABLE;
            case STALE_DATA -> HttpStatus.CONFLICT;
        };
    }

    private static String publicBrokerCode(BrokerErrorCategory category) {
        return "BROKER_" + category.name();
    }

    public record PublicError(String code) {
    }
}
