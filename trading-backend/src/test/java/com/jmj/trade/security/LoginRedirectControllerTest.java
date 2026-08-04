package com.jmj.trade.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRedirectControllerTest {

    @Test
    void invalidReturnToFallsBackToDashboardLogin() throws Exception {
        var response = new MockHttpServletResponse();

        new LoginRedirectController(new DashboardRedirects("https://dashboard.example"))
                .login("login", "https://evil.example", response);

        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://dashboard.example/login?error=login");
    }
}
