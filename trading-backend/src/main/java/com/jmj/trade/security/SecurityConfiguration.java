package com.jmj.trade.security;

import com.jmj.trade.connector.ConnectorApiKeyAuthenticationFilter;
import com.jmj.trade.prediction.PredictionIngestionApiKeyAuthenticationFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.context.NullSecurityContextRepository;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<ClientRegistrationRepository> registrations,
            ObjectProvider<InternalOidcUserService> oidcUsers,
            ObjectProvider<PredictionIngestionApiKeyAuthenticationFilter> apiKeyFilter,
            ObjectProvider<ConnectorApiKeyAuthenticationFilter> connectorApiKeyFilter,
            ObjectProvider<AccessTokenService> accessTokens,
            ObjectProvider<RefreshTokenService> refreshTokens,
            ObjectProvider<CookieAuthorizationRequestRepository> authorizationRequests,
            ObjectProvider<com.jmj.trade.connector.ConnectorMcpOAuthService> connectorMcpOAuth,
            @Value("${security.oidc.max-age:300}") String oidcMaxAge,
            DashboardRedirects dashboardRedirects
    ) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                .requestMatchers("/.well-known/**", "/api/v1/connector/oauth/**").permitAll()
                .requestMatchers("/api/v1/connector/**").hasAuthority("SCOPE_CONNECTOR_READ")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll());
        http.httpBasic(httpBasic -> httpBasic.disable());
        http.formLogin(formLogin -> formLogin.disable());
        http.csrf(AbstractHttpConfigurer::disable);
        var filter = apiKeyFilter.getIfAvailable();
        if (filter != null) {
            http.addFilterBefore(filter, AnonymousAuthenticationFilter.class);
        }
        var connectorFilter = connectorApiKeyFilter.getIfAvailable();
        if (connectorFilter != null) {
            http.addFilterBefore(connectorFilter, AnonymousAuthenticationFilter.class);
        }
        var accessTokenService = accessTokens.getIfAvailable();
        if (accessTokenService != null) {
            http.addFilterBefore(
                    new AccessTokenAuthenticationFilter(accessTokenService),
                    AnonymousAuthenticationFilter.class);
        }
        http.securityContext(context -> context
                .securityContextRepository(new NullSecurityContextRepository()));
        http.requestCache(requestCache -> requestCache.disable());
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        var mcpOAuth = connectorMcpOAuth.getIfAvailable();
        http.logout(logout -> logout.disable());
        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, ignored) -> {
                    if (mcpOAuth != null
                            && request.getRequestURI().startsWith("/api/v1/connector/mcp/")) {
                        response.setHeader("WWW-Authenticate",
                                "Bearer resource_metadata=\""
                                        + mcpOAuth.publicUrl("/.well-known/oauth-protected-resource"
                                        + com.jmj.trade.connector.ConnectorMcpOAuthService.RESOURCE_PATH)
                                        + "\", scope=\""
                                        + com.jmj.trade.connector.ConnectorMcpOAuthService.READ_SCOPE + "\"");
                    }
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                }));
        var registrationRepository = registrations.getIfAvailable();
        if (registrationRepository != null) {
            http.oauth2Login(oauth2 -> oauth2
                    .loginPage("/login")
                    .authorizationEndpoint(endpoint -> endpoint
                            .authorizationRequestRepository(authorizationRequests.getObject())
                            .authorizationRequestResolver(new DashboardAuthorizationRequestResolver(
                                    registrationRepository,
                                    oidcAuthorizationCustomizer(oidcMaxAge))))
                    .successHandler(dashboardSuccessHandler(
                            dashboardRedirects, accessTokens.getObject(), refreshTokens.getObject(), mcpOAuth))
                    .failureHandler(dashboardFailureHandler(dashboardRedirects, mcpOAuth))
                    .userInfoEndpoint(userInfo ->
                            userInfo.oidcUserService(oidcUsers.getObject())));
        }
        return http.build();
    }

    static Consumer<org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest.Builder>
    oidcAuthorizationCustomizer(String oidcMaxAge) {
        return builder -> builder.additionalParameters(parameters -> {
            parameters.put("max_age", oidcMaxAge);
            parameters.put("prompt", "login");
        });
    }

    @Bean
    DashboardRedirects dashboardRedirects(
            @Value("${public.dashboard-url:http://localhost:3000}") String publicDashboardUrl
    ) {
        return new DashboardRedirects(publicDashboardUrl);
    }

    @Bean
    OriginPolicy originPolicy(
            @Value("${public.dashboard-url:http://localhost:3000}") String publicDashboardUrl
    ) {
        return new OriginPolicy(publicDashboardUrl);
    }

    static AuthenticationSuccessHandler dashboardSuccessHandler(String publicDashboardUrl) {
        return dashboardSuccessHandler(new DashboardRedirects(publicDashboardUrl));
    }

    private static AuthenticationSuccessHandler dashboardSuccessHandler(DashboardRedirects redirects) {
        return (request, response, authentication) -> response.sendRedirect(
                redirects.dashboardUrl(DashboardAuthorizationRequestResolver.consumeReturnTo(request)));
    }

    private static AuthenticationSuccessHandler dashboardSuccessHandler(
            DashboardRedirects redirects,
            AccessTokenService accessTokens,
            RefreshTokenService refreshTokens,
            com.jmj.trade.connector.ConnectorMcpOAuthService mcpOAuth
    ) {
        return (request, response, authentication) -> {
            var returnTo = DashboardAuthorizationRequestResolver.consumeReturnTo(request);
            if (mcpOAuth != null && mcpOAuth.isContinuation(returnTo)) {
                try {
                    response.sendRedirect(mcpOAuth.completeAfterLogin(returnTo, authentication));
                } catch (com.jmj.trade.connector.ConnectorMcpOAuthService.OAuthException exception) {
                    response.sendRedirect(mcpOAuth.loginFailureRedirect(returnTo, exception.code()));
                }
                return;
            }
            var userId = UUID.fromString(authentication.getName());
            var authTime = authenticatedAt(authentication);
            if (authTime == null && DashboardAuthorizationRequestResolver.consumeForcedReauthentication(request)) {
                authTime = Instant.now();
            }
            var refresh = refreshTokens.issue(userId, authTime);
            var access = accessTokens.issue(userId, refresh.sessionId(), authTime);
            AuthCookieSupport.setRefreshCookie(response, refresh.refreshToken(),
                    Duration.between(Instant.now(), refresh.expiresAt()));
            response.sendRedirect(redirects.dashboardUrl(returnTo, access.value(), access.expiresAt()));
        };
    }

    private static Instant authenticatedAt(org.springframework.security.core.Authentication authentication) {
        if (authentication.getPrincipal()
                instanceof org.springframework.security.oauth2.core.oidc.user.OidcUser oidcUser
                && oidcUser.getIdToken() != null) {
            return oidcUser.getIdToken().getAuthenticatedAt();
        }
        return null;
    }

    static AuthenticationFailureHandler dashboardFailureHandler(String publicDashboardUrl) {
        return dashboardFailureHandler(new DashboardRedirects(publicDashboardUrl), null);
    }

    private static AuthenticationFailureHandler dashboardFailureHandler(
            DashboardRedirects redirects,
            com.jmj.trade.connector.ConnectorMcpOAuthService mcpOAuth
    ) {
        return (request, response, exception) -> response.sendRedirect(
                failureRedirect(redirects, mcpOAuth, request, exception));
    }

    private static String failureRedirect(
            DashboardRedirects redirects,
            com.jmj.trade.connector.ConnectorMcpOAuthService mcpOAuth,
            jakarta.servlet.http.HttpServletRequest request,
            org.springframework.security.core.AuthenticationException exception
    ) {
        var returnTo = DashboardAuthorizationRequestResolver.consumeReturnTo(request);
        if (mcpOAuth != null && mcpOAuth.isContinuation(returnTo)) {
            return mcpOAuth.loginFailureRedirect(returnTo, DashboardRedirects.errorCode(exception));
        }
        return redirects.loginUrl(DashboardRedirects.errorCode(exception), returnTo);
    }
}
