package com.jmj.trade.security;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TradingBackendApplication.class)
class RefreshTokenServiceTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RefreshTokenService transactionalService;

    private RefreshTokenService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE auth_refresh_sessions, users CASCADE");
        userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?)", userId);
        service = new RefreshTokenService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void rotatesWithoutPersistingPlaintextAndDetectsReuseByRevokingFamily() {
        var first = service.issue(userId, NOW.minusSeconds(20));
        var second = service.rotate(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auth_refresh_sessions WHERE token_hash = ?",
                Integer.class,
                first.refreshToken())).isZero();

        assertThatThrownBy(() -> service.rotate(first.refreshToken()))
                .isInstanceOf(RefreshTokenService.RefreshTokenReuseException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auth_refresh_sessions WHERE family_id = ? AND revoked_at IS NOT NULL",
                Integer.class,
                first.familyId())).isEqualTo(2);
    }

    @Test
    void revokesOneSessionOrEverySession() {
        var first = service.issue(userId, NOW);
        var second = service.issue(userId, NOW);

        service.revoke(first.refreshToken());
        assertThatThrownBy(() -> service.rotate(first.refreshToken()))
                .isInstanceOf(RefreshTokenService.RefreshTokenReuseException.class);

        service.revokeAll(userId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auth_refresh_sessions WHERE user_id = ? AND revoked_at IS NOT NULL",
                Integer.class,
                userId)).isEqualTo(2);
        assertThatThrownBy(() -> service.rotate(second.refreshToken()))
                .isInstanceOf(RefreshTokenService.RefreshTokenReuseException.class);
    }

    @Test
    void concurrentRotationAllowsOneWinnerAndRevokesFamilyOnReuse() throws Exception {
        var first = transactionalService.issue(userId, Instant.now());
        var start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var results = List.of(
                    executor.submit(() -> rotateOnce(first.refreshToken(), start)),
                    executor.submit(() -> rotateOnce(first.refreshToken(), start)));
            start.countDown();

            assertThat(results.stream().map(future -> get(future)).toList())
                    .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM auth_refresh_sessions WHERE family_id = ? AND revoked_at IS NOT NULL",
                    Integer.class,
                    first.familyId())).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredRotationCommitsSessionRevocation() {
        var first = transactionalService.issue(userId, Instant.now());
        var now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.update("UPDATE auth_refresh_sessions SET issued_at = ?, expires_at = ? WHERE id = ?",
                now.minusSeconds(10), now.minusSeconds(1),
                first.sessionId());

        assertThatThrownBy(() -> transactionalService.rotate(first.refreshToken()))
                .isInstanceOf(RefreshTokenService.RefreshTokenExpiredException.class);
        assertThat(jdbc.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM auth_refresh_sessions WHERE id = ?",
                Boolean.class,
                first.sessionId())).isTrue();
    }

    private boolean rotateOnce(String rawToken, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            transactionalService.rotate(rawToken);
            return true;
        } catch (RefreshTokenService.RefreshTokenReuseException exception) {
            return false;
        }
    }

    private static boolean get(java.util.concurrent.Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
