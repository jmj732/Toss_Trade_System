package com.jmj.trade.sheets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/investment-os/sheet-sync")
@ConditionalOnProperty(prefix = "investment-os.sheet", name = "enabled", havingValue = "true")
public final class InvestmentOsSheetController {
    private final InvestmentOsSheetSyncService service;

    public InvestmentOsSheetController(InvestmentOsSheetSyncService service) {
        this.service = service;
    }

    @PostMapping
    public InvestmentOsSheetSyncResult sync(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new org.springframework.security.authentication.BadCredentialsException("authentication required");
        }
        return service.sync(UUID.fromString(authentication.getName()), null);
    }
}
