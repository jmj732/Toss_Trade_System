package com.jmj.trade.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Consumer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

final class DashboardAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final String RETURN_TO_ATTRIBUTE =
            DashboardAuthorizationRequestResolver.class.getName() + ".returnTo";

    private final OAuth2AuthorizationRequestResolver delegate;

    DashboardAuthorizationRequestResolver(
            ClientRegistrationRepository registrations,
            Consumer<OAuth2AuthorizationRequest.Builder> customizer
    ) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(
                registrations, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(customizer);
        delegate = resolver;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return remember(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(
            HttpServletRequest request,
            String clientRegistrationId
    ) {
        return remember(request, delegate.resolve(request, clientRegistrationId));
    }

    static void rememberReturnTo(HttpServletRequest request, String state, String returnTo) {
        if (state == null || state.isBlank()) {
            return;
        }
        request.setAttribute(RETURN_TO_ATTRIBUTE, DashboardRedirects.safeReturnTo(returnTo));
    }

    static String consumeReturnTo(HttpServletRequest request) {
        var requestAuthorization = CookieAuthorizationRequestRepository.request(request);
        if (requestAuthorization != null) {
            var value = requestAuthorization.getAttribute(RETURN_TO_ATTRIBUTE);
            request.removeAttribute(CookieAuthorizationRequestRepository.class.getName());
            return value instanceof String string ? string : null;
        }
        var value = request.getAttribute(RETURN_TO_ATTRIBUTE);
        request.removeAttribute(RETURN_TO_ATTRIBUTE);
        return value instanceof String string ? string : null;
    }

    private OAuth2AuthorizationRequest remember(
            HttpServletRequest request,
            OAuth2AuthorizationRequest authorizationRequest
    ) {
        if (authorizationRequest != null && request.getParameter("returnTo") != null) {
            var returnTo = DashboardRedirects.safeReturnTo(request.getParameter("returnTo"));
            request.setAttribute(RETURN_TO_ATTRIBUTE, returnTo);
            return OAuth2AuthorizationRequest.from(authorizationRequest)
                    .attributes(attributes -> attributes.put(
                            RETURN_TO_ATTRIBUTE,
                            returnTo))
                    .build();
        }
        return authorizationRequest;
    }
}
