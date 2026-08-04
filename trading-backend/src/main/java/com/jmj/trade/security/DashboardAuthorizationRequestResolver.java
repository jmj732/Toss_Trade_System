package com.jmj.trade.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

final class DashboardAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final String TARGETS_SESSION_ATTRIBUTE =
            DashboardAuthorizationRequestResolver.class.getName() + ".targets";
    private static final int MAX_TARGETS = 8;

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
        var session = request.getSession(true);
        var targets = targets(session);
        targets.put(state, DashboardRedirects.safeReturnTo(returnTo));
        while (targets.size() > MAX_TARGETS) {
            Iterator<String> iterator = targets.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
        session.setAttribute(TARGETS_SESSION_ATTRIBUTE, targets);
    }

    static String consumeReturnTo(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) {
            return null;
        }
        var state = request.getParameter("state");
        if (state == null) {
            return null;
        }
        var value = targets(session).remove(state);
        if (targets(session).isEmpty()) {
            session.removeAttribute(TARGETS_SESSION_ATTRIBUTE);
        }
        return value;
    }

    private OAuth2AuthorizationRequest remember(
            HttpServletRequest request,
            OAuth2AuthorizationRequest authorizationRequest
    ) {
        if (authorizationRequest != null && request.getParameter("returnTo") != null) {
            rememberReturnTo(request, authorizationRequest.getState(), request.getParameter("returnTo"));
        }
        return authorizationRequest;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> targets(jakarta.servlet.http.HttpSession session) {
        var current = session.getAttribute(TARGETS_SESSION_ATTRIBUTE);
        if (current instanceof Map<?, ?> map) {
            return (Map<String, String>) map;
        }
        return new LinkedHashMap<>();
    }
}
