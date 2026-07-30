package com.jmj.trade.security;

import com.jmj.trade.prediction.PredictionIngestionApiKeyAuthenticationFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<ClientRegistrationRepository> registrations,
            ObjectProvider<InternalOidcUserService> oidcUsers,
            ObjectProvider<PredictionIngestionApiKeyAuthenticationFilter> apiKeyFilter
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
        if (registrations.getIfAvailable() != null) {
            http.oauth2Login(oauth2 -> oauth2
                    .userInfoEndpoint(userInfo ->
                            userInfo.oidcUserService(oidcUsers.getObject())));
        }
        return http.build();
    }
}
