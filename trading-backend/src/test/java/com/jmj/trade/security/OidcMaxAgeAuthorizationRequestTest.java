package com.jmj.trade.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import static org.assertj.core.api.Assertions.assertThat;

class OidcMaxAgeAuthorizationRequestTest {

    @Test
    void authorizationRequestCarriesMaxAgeForRealStepUpReauthentication() {
        var registration = ClientRegistration.withRegistrationId("oidc")
                .clientId("client")
                .clientSecret("secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid")
                .authorizationUri("https://issuer.example/authorize")
                .tokenUri("https://issuer.example/token")
                .userInfoUri("https://issuer.example/userinfo")
                .userNameAttributeName("sub")
                .build();
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(
                new InMemoryClientRegistrationRepository(registration), "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(builder ->
                builder.additionalParameters(parameters -> parameters.put("max_age", "300")));

        var request = new MockHttpServletRequest("GET", "/oauth2/authorization/oidc");
        var authorization = resolver.resolve(request);

        assertThat(authorization).isNotNull();
        assertThat(authorization.getAdditionalParameters()).containsEntry("max_age", "300");
    }

    @Test
    void authorizationRequestStoresValidatedReturnPathByState() {
        var registration = ClientRegistration.withRegistrationId("oidc")
                .clientId("client")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid")
                .authorizationUri("https://issuer.example/authorize")
                .tokenUri("https://issuer.example/token")
                .userInfoUri("https://issuer.example/userinfo")
                .userNameAttributeName("sub")
                .build();
        var resolver = new DashboardAuthorizationRequestResolver(
                new InMemoryClientRegistrationRepository(registration),
                builder -> builder.additionalParameters(parameters -> parameters.put("max_age", "300")));
        var request = new MockHttpServletRequest("GET", "/oauth2/authorization/oidc");
        request.addParameter("returnTo", "/portfolio");

        var authorization = resolver.resolve(request);
        request.addParameter("state", authorization.getState());

        assertThat(DashboardAuthorizationRequestResolver.consumeReturnTo(request))
                .isEqualTo("/portfolio");
        assertThat(DashboardAuthorizationRequestResolver.consumeReturnTo(request)).isNull();
    }
}
