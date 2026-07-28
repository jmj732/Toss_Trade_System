package com.jmj.trade.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
final class InternalOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcIdentityService identities;
    private final OidcUserService delegate = new OidcUserService();

    InternalOidcUserService(OidcIdentityService identities) {
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        return map(delegate.loadUser(request));
    }

    OidcUser map(OidcUser externalUser) {
        try {
            var issuer = Objects.requireNonNull(externalUser.getIssuer(), "issuer").toString();
            var internalUserId = identities.resolve(issuer, externalUser.getSubject());
            return new InternalOidcUser(internalUserId, externalUser);
        } catch (RuntimeException exception) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("oidc_identity_mapping_failed"),
                    exception);
        }
    }

    private record InternalOidcUser(UUID internalUserId, OidcUser delegate)
            implements OidcUser {

        private InternalOidcUser {
            Objects.requireNonNull(internalUserId, "internalUserId");
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String getName() {
            return internalUserId.toString();
        }

        @Override
        public Map<String, Object> getClaims() {
            return delegate.getClaims();
        }

        @Override
        public OidcUserInfo getUserInfo() {
            return delegate.getUserInfo();
        }

        @Override
        public OidcIdToken getIdToken() {
            return delegate.getIdToken();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return delegate.getAttributes();
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return delegate.getAuthorities();
        }
    }
}
