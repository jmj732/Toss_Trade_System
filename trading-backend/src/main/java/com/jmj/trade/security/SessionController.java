package com.jmj.trade.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/session")
final class SessionController {

    @GetMapping
    SessionView read(Principal principal, HttpServletRequest request) {
        var userId = UUID.fromString(principal.getName());
        var csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return new SessionView(userId, csrf.getHeaderName(), csrf.getToken());
    }

    record SessionView(UUID userId, String csrfHeaderName, String csrfToken) {
    }
}
