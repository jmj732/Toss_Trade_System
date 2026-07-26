package com.jmj.trade;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class PostgresIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = startPostgres();

    private static PostgreSQLContainer startPostgres() {
        var postgres = new PostgreSQLContainer("postgres:17-alpine");
        postgres.start();
        return postgres;
    }
}
