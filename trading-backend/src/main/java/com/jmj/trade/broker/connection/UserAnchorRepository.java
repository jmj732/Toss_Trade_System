package com.jmj.trade.broker.connection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.UUID;

@Repository
class UserAnchorRepository {

    private final JdbcTemplate jdbcTemplate;

    UserAnchorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    void anchor(UUID userId) {
        jdbcTemplate.update("INSERT INTO users (id) VALUES (?) ON CONFLICT DO NOTHING",
                Objects.requireNonNull(userId, "userId"));
    }
}
