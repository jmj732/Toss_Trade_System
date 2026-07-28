package com.jmj.trade.security;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TradingBackendApplication.class)
@Import(OidcAuthSessionIntegrationTest.MockOidcConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OidcAuthSessionIntegrationTest extends PostgresIntegrationTest {

    private static final String ISSUER = "https://issuer.example";
    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 7, 28, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OidcIdentityService identities;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        jdbc.execute("TRUNCATE broker_connections, users CASCADE");
    }

    @Test
    void concurrentFirstLoginMapsOneExternalIdentityToOneInternalUuid() throws Exception {
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<UUID>> tasks = List.of(
                    () -> identities.resolve(ISSUER, "subject-1"),
                    () -> identities.resolve(ISSUER, "subject-1"),
                    () -> identities.resolve(ISSUER, "subject-1"),
                    () -> identities.resolve(ISSUER, "subject-1"));

            var results = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            assertThat(results).containsOnly(results.getFirst());
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM users WHERE oidc_issuer = ? AND oidc_subject = ?",
                    Integer.class,
                    ISSUER,
                    "subject-1")).isOne();
        }
    }

    @Test
    void oidcUserServiceReplacesExternalSubjectPrincipalWithInternalUuid() {
        var external = externalUser("subject-2");
        var service = new InternalOidcUserService(identities);

        var mapped = service.map(external);

        assertThat(UUID.fromString(mapped.getName())).isEqualTo(
                identities.resolve(ISSUER, "subject-2"));
        assertThat(mapped.getSubject()).isEqualTo("subject-2");
        assertThat(identities.resolve("https://other-issuer.example", "subject-2"))
                .isNotEqualTo(UUID.fromString(mapped.getName()));
    }

    @Test
    void mockOidcPrincipalUsesInternalUuidForOwnership() throws Exception {
        var owner = mappedUser("owner-subject");
        var other = mappedUser("other-subject");
        var connectionId = insertConnection(UUID.fromString(owner.getName()));

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(oidcLogin().oidcUser(owner)))
                .andExpect(status().isOk());

        mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(oidcLogin().oidcUser(other)))
                .andExpect(status().isNotFound());
    }

    @Test
    void csrfProtectedLogoutInvalidatesServerSessionAndClearsCookie() throws Exception {
        var owner = mappedUser("session-subject");
        var connectionId = insertConnection(UUID.fromString(owner.getName()));
        var authenticated = mockMvc.perform(get(
                                "/api/v1/broker-connections/{connectionId}/dashboard",
                                connectionId)
                        .with(oidcLogin().oidcUser(owner)))
                .andExpect(status().isOk())
                .andReturn();
        var session = authenticated.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(post("/logout").session((org.springframework.mock.web.MockHttpSession) session))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/logout")
                        .session((org.springframework.mock.web.MockHttpSession) session)
                        .with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString(
                        "JSESSIONID=;")));

        assertThat(((org.springframework.mock.web.MockHttpSession) session).isInvalid()).isTrue();
        mockMvc.perform(get(
                        "/api/v1/broker-connections/{connectionId}/dashboard",
                        connectionId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oidcAuthorizationEndpointIsEnabledWithoutPasswordLogin() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/mock"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        "Location",
                        org.hamcrest.Matchers.startsWith("https://issuer.example/authorize")));

        mockMvc.perform(get("/login"))
                .andExpect(status().isNotFound());
    }

    private OidcUser mappedUser(String subject) {
        return new InternalOidcUserService(identities).map(externalUser(subject));
    }

    private OidcUser externalUser(String subject) {
        var issuedAt = Instant.parse("2026-07-28T00:00:00Z");
        var token = new OidcIdToken(
                "token-" + subject,
                issuedAt,
                issuedAt.plusSeconds(300),
                Map.of("iss", ISSUER, "sub", subject));
        return new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                token);
    }

    private UUID insertConnection(UUID userId) {
        var connectionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO broker_connections (
                    id, user_id, broker_type, status,
                    credential_ciphertext, credential_nonce, credential_key_version,
                    credential_revision, created_at, updated_at, version
                ) VALUES (
                    ?, ?, 'TOSS_INVEST', 'ACTIVE', ?, ?, 1, 1, ?, ?, 0
                )
                """,
                connectionId,
                userId,
                new byte[17],
                new byte[12],
                NOW,
                NOW);
        return connectionId;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MockOidcConfiguration {

        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            var registration = ClientRegistration.withRegistrationId("mock")
                    .clientId("client")
                    .clientSecret("secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid")
                    .authorizationUri("https://issuer.example/authorize")
                    .tokenUri("https://issuer.example/token")
                    .jwkSetUri("https://issuer.example/jwks")
                    .userInfoUri("https://issuer.example/userinfo")
                    .userNameAttributeName("sub")
                    .clientName("Mock OIDC")
                    .build();
            return new InMemoryClientRegistrationRepository(registration);
        }
    }
}
