package com.jmj.trade;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A JVM-wide singleton container, not one per subclass: the {@code POSTGRES} field is
 * declared on this class, so per the JLS a class is initialized at most once per
 * classloader — the static initializer runs on the first subclass touched, and every other
 * subclass in the same Surefire fork reuses that same running container.
 *
 * <p>The primary fix for CI's "FATAL: sorry, too many clients already" failure is the
 * {@code spring.datasource.hikari.maximum-pool-size}/{@code minimum-idle} surefire system
 * properties in pom.xml, which cap every {@code @SpringBootTest} context's connection pool
 * instead of leaving most of them on Hikari's unconstrained default (10 max, minIdle = max).
 * Measured peak before that fix: ~150 simultaneous connections from ~18 distinct cached
 * contexts, comfortably past Postgres's own default {@code max_connections=100}; with the
 * pool cap, baseline peak drops to roughly 18 × 2 ≈ 36. The higher {@code max_connections}
 * here is headroom on top of that baseline for whatever else is transiently open (e.g.
 * {@code PostgresContainerCapacityTest} deliberately opens ~120 raw connections at once) —
 * not something the baseline itself needs to stay under 100.
 */
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = startPostgres();

    private static PostgreSQLContainer startPostgres() {
        var postgres = new PostgreSQLContainer("postgres:17-alpine")
                .withCommand("postgres", "-c", "max_connections=300");
        postgres.start();
        return postgres;
    }
}
