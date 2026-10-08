package com.jmj.trade;

import org.flywaydb.core.Flyway;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A JVM-wide singleton container, not one per subclass: the {@code POSTGRES} field is
 * declared on this class, so per the JLS a class is initialized at most once per
 * classloader — the static initializer runs on the first subclass touched, and every other
 * subclass in the same Surefire fork reuses that same running container.
 *
 * <p>The primary fix for CI's "FATAL: sorry, too many clients already" failure is the
 * {@code spring.datasource.hikari.maximum-pool-size} surefire system property in pom.xml,
 * which caps every {@code @SpringBootTest} context's connection pool ceiling instead of
 * leaving most of them on Hikari's unconstrained default (10 max, minIdle = max).
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

    private static boolean migratedFixtureReady;
    private static final String MIGRATED_FIXTURE = "/tmp/trade-test-migrated-fixture.dump";

    /** Reuse a snapshot produced by real migrations; restore schema AND seed data per case. */
    protected static synchronized Flyway freshMigratedSchema() {
        var flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false).load();
        flyway.clean();
        if (!migratedFixtureReady) {
            flyway.migrate();
            fixtureCommand("pg_dump", "--username=" + POSTGRES.getUsername(),
                    "--dbname=" + POSTGRES.getDatabaseName(), "--format=custom",
                    "--file=" + MIGRATED_FIXTURE);
            migratedFixtureReady = true;
        } else {
            fixtureCommand("pg_restore", "--exit-on-error", "--no-owner",
                    "--username=" + POSTGRES.getUsername(),
                    "--dbname=" + POSTGRES.getDatabaseName(), MIGRATED_FIXTURE);
        }
        return flyway;
    }

    protected static HikariDataSource pooledTestDataSource() {
        var source = new HikariDataSource();
        source.setJdbcUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        source.setMaximumPoolSize(2);
        return source;
    }

    private static void fixtureCommand(String... command) {
        try {
            if (POSTGRES.execInContainer(command).getExitCode() != 0)
                throw new IllegalStateException("Disposable database fixture command failed");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Disposable database fixture interrupted", interrupted);
        } catch (java.io.IOException unavailable) {
            throw new IllegalStateException("Disposable database fixture unavailable", unavailable);
        }
    }

    private static PostgreSQLContainer startPostgres() {
        var postgres = new PostgreSQLContainer("postgres:17-alpine")
                // withCommand replaces Testcontainers' default command, including fsync=off.
                // Restore that default for this disposable fixture; production Compose is unaffected.
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300");
        postgres.start();
        return postgres;
    }
}
