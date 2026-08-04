package com.jmj.trade.security;

import com.jmj.trade.prediction.PredictionIngestionApiKeyAuthenticationFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<ClientRegistrationRepository> registrations,
            ObjectProvider<InternalOidcUserService> oidcUsers,
            ObjectProvider<PredictionIngestionApiKeyAuthenticationFilter> apiKeyFilter,
            @Value("${security.oidc.max-age:300}") String oidcMaxAge,
            DashboardRedirects dashboardRedirects
    ) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll());
        http.httpBasic(httpBasic -> httpBasic.disable());
        http.formLogin(formLogin -> formLogin.disable());
        http.csrf(csrf -> csrf.ignoringRequestMatchers(
                PredictionIngestionApiKeyAuthenticationFilter::isApiKeyBatchRequest));
        var filter = apiKeyFilter.getIfAvailable();
        if (filter != null) {
            http.addFilterBefore(filter, AnonymousAuthenticationFilter.class);
        }
        http.sessionManagement(session ->
                session.sessionFixation(fixation -> fixation.changeSessionId()));
        http.logout(logout -> logout
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(
                        HttpStatus.NO_CONTENT)));
        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        var registrationRepository = registrations.getIfAvailable();
        if (registrationRepository != null) {
            http.oauth2Login(oauth2 -> oauth2
                    .loginPage("/login")
                    .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(
                            new DashboardAuthorizationRequestResolver(
                                    registrationRepository,
                                    builder -> builder.additionalParameters(
                                            parameters -> parameters.put("max_age", oidcMaxAge)))))
                    .successHandler(dashboardSuccessHandler(dashboardRedirects))
                    .failureHandler(dashboardFailureHandler(dashboardRedirects))
                    .userInfoEndpoint(userInfo ->
                            userInfo.oidcUserService(oidcUsers.getObject())));
        }
        return http.build();
    }

    @Bean
    DashboardRedirects dashboardRedirects(
            @Value("${public.dashboard-url:http://localhost:3000}") String publicDashboardUrl
    ) {
        return new DashboardRedirects(publicDashboardUrl);
    }

    static AuthenticationSuccessHandler dashboardSuccessHandler(String publicDashboardUrl) {
        return dashboardSuccessHandler(new DashboardRedirects(publicDashboardUrl));
    }

    private static AuthenticationSuccessHandler dashboardSuccessHandler(DashboardRedirects redirects) {
        return (request, response, authentication) -> response.sendRedirect(
                redirects.dashboardUrl(DashboardAuthorizationRequestResolver.consumeReturnTo(request)));
    }

    static AuthenticationFailureHandler dashboardFailureHandler(String publicDashboardUrl) {
        return dashboardFailureHandler(new DashboardRedirects(publicDashboardUrl));
    }

    private static AuthenticationFailureHandler dashboardFailureHandler(DashboardRedirects redirects) {
        return (request, response, exception) -> response.sendRedirect(
                redirects.loginUrl(
                        DashboardRedirects.errorCode(exception),
                        DashboardAuthorizationRequestResolver.consumeReturnTo(request)));
    }
}
