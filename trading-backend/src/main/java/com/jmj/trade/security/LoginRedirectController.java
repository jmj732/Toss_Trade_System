package com.jmj.trade.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
final class LoginRedirectController {

    private final DashboardRedirects redirects;

    LoginRedirectController(DashboardRedirects redirects) {
        this.redirects = redirects;
    }

    @GetMapping("/login")
    void login(
            @RequestParam(name = "error", required = false) String error,
            @RequestParam(name = "returnTo", required = false) String returnTo,
            HttpServletResponse response
    ) throws IOException {
        response.sendRedirect(redirects.loginUrl(error, returnTo));
    }
}
