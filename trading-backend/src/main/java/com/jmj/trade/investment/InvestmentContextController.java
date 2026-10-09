package com.jmj.trade.investment;

import com.jmj.trade.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/investment")
final class InvestmentContextController {

    private final InvestmentContextService service;

    InvestmentContextController(InvestmentContextService service) {
        this.service = service;
    }

    @GetMapping("/context")
    InvestmentContextService.ContextView context(Principal principal) {
        return service.context(userId(principal));
    }

    @PutMapping("/securities/{ticker}/thesis")
    InvestmentContextService.ThesisView thesis(
            Principal principal,
            @PathVariable String ticker,
            @RequestBody InvestmentContextService.ThesisInput input,
            @RequestParam(required = false) Instant expectedUpdatedAt,
            @RequestParam(required = false) Instant sourceAsOf,
            @RequestParam(required = false) String reason
    ) {
        var sessionId = extractSessionId(principal);
        return service.putThesis(userId(principal), ticker, input, expectedUpdatedAt, sourceAsOf, reason, sessionId);
    }

    @GetMapping("/securities/{ticker}/thesis/revisions")
    List<InvestmentContextService.ThesisRevisionView> thesisRevisions(
            Principal principal,
            @PathVariable String ticker,
            @RequestParam(defaultValue = "200") int limit
    ) {
        return service.thesisRevisions(userId(principal), ticker, limit);
    }

    @GetMapping("/decisions")
    List<InvestmentContextService.DecisionView> decisions(
            Principal principal,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return service.decisionLedger(userId(principal), limit);
    }

    @PostMapping("/decisions")
    InvestmentContextService.DecisionView decision(
            Principal principal,
            @RequestBody InvestmentContextService.DecisionInput input
    ) {
        return service.recordDecision(userId(principal), input);
    }

    @GetMapping("/tactical-overlay/inputs")
    com.jmj.trade.investment.tactical.TacticalOverlayService.TacticalInputsView tacticalInputs(
            Principal principal
    ) {
        return service.tacticalOverlayInputs(userId(principal));
    }

    @PutMapping("/securities/{ticker}/avwap-anchors")
    UUID avwapAnchor(
            Principal principal,
            @PathVariable String ticker,
            @RequestBody com.jmj.trade.investment.tactical.TacticalOverlayService.AnchorInput input
    ) {
        return service.putTacticalAnchor(userId(principal), ticker, input);
    }

    @DeleteMapping("/securities/{ticker}/avwap-anchors/{anchorId}")
    UUID deleteAvwapAnchor(Principal principal, @PathVariable String ticker, @PathVariable String anchorId,
                           @RequestParam Instant sourceAsOf) {
        return service.deleteTacticalAnchor(userId(principal), ticker, anchorId, sourceAsOf);
    }

    @PutMapping("/tactical-overlay/themes")
    UUID tacticalTheme(Principal principal,
                       @RequestBody com.jmj.trade.investment.tactical.TacticalOverlayService.ThemeInput input) {
        return service.putTacticalTheme(userId(principal), input);
    }

    @DeleteMapping("/tactical-overlay/themes/{themeId}")
    UUID deleteTacticalTheme(Principal principal, @PathVariable String themeId,
                             @RequestParam Instant sourceAsOf) {
        return service.deleteTacticalTheme(userId(principal), themeId, sourceAsOf);
    }

    @PutMapping("/tactical-overlay/themes/{themeId}/members/{ticker}")
    UUID tacticalThemeMember(
            Principal principal,
            @PathVariable String themeId,
            @PathVariable String ticker,
            @RequestBody com.jmj.trade.investment.tactical.TacticalOverlayService.ThemeMappingInput input
    ) {
        var pathBoundInput = new com.jmj.trade.investment.tactical.TacticalOverlayService.ThemeMappingInput(
                themeId, ticker, input.effectiveDate(), input.source(), input.sourceAsOf());
        return service.putTacticalThemeMapping(userId(principal), pathBoundInput);
    }

    @DeleteMapping("/tactical-overlay/themes/{themeId}/members/{ticker}")
    UUID deleteTacticalThemeMember(Principal principal, @PathVariable String themeId, @PathVariable String ticker,
                                   @RequestParam LocalDate effectiveDate, @RequestParam Instant sourceAsOf) {
        return service.deleteTacticalThemeMapping(userId(principal), themeId, ticker, effectiveDate, sourceAsOf);
    }

    @PutMapping("/securities/{ticker}/performance-entries")
    UUID tacticalPerformanceEntry(
            Principal principal,
            @PathVariable String ticker,
            @RequestBody com.jmj.trade.investment.tactical.TacticalOverlayService.PerformanceInput input
    ) {
        return service.putTacticalPerformanceEntry(userId(principal), ticker, input);
    }

    @DeleteMapping("/securities/{ticker}/performance-entries/{key}")
    UUID deleteTacticalPerformanceEntry(Principal principal, @PathVariable String ticker, @PathVariable String key,
                                        @RequestParam Instant sourceAsOf) {
        return service.deleteTacticalPerformanceEntry(userId(principal), ticker, key, sourceAsOf);
    }

    @ExceptionHandler(InvestmentException.class)
    ResponseEntity<PublicError> investment(InvestmentException exception) {
        return switch (exception.code()) {
            case INVALID_USER -> error(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_INVALID");
            case INVALID_INPUT -> error(HttpStatus.BAD_REQUEST, "INVESTMENT_INPUT_INVALID");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "INVESTMENT_STATE_NOT_FOUND");
            case CONFLICT -> error(HttpStatus.CONFLICT, "INVESTMENT_DECISION_CONFLICT");
        };
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<PublicError> malformedJson() {
        return error(HttpStatus.BAD_REQUEST, "INVESTMENT_INPUT_INVALID");
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw new InvestmentException(InvestmentException.Code.INVALID_USER);
        }
    }

    private static UUID extractSessionId(Principal principal) {
        if (principal instanceof AuthenticatedUser au) {
            return au.sessionId();
        }
        if (principal instanceof Authentication auth) {
            var authPrincipal = auth.getPrincipal();
            if (authPrincipal instanceof AuthenticatedUser au) {
                return au.sessionId();
            }
        }
        return null;
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    record PublicError(String code) {
    }
}
