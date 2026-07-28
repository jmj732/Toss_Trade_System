package com.jmj.trade.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

@Service
final class OidcIdentityService {

    private final JdbcTemplate jdbcTemplate;

    OidcIdentityService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    UUID resolve(String issuer, String subject) {
        var validIssuer = required(issuer, 500);
        var validSubject = required(subject, 255);
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (id, oidc_issuer, oidc_subject)
                VALUES (?, ?, ?)
                ON CONFLICT (oidc_issuer, oidc_subject)
                DO UPDATE SET oidc_subject = EXCLUDED.oidc_subject
                RETURNING id
                """, UUID.class, UUID.randomUUID(), validIssuer, validSubject);
    }

    private static String required(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("OIDC identity claim is invalid");
        }
        return value;
    }
}
