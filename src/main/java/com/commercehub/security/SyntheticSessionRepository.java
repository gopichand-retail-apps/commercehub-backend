package com.commercehub.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SyntheticSessionRepository {
  private final JdbcTemplate jdbc;

  public SyntheticSessionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(UUID id, SyntheticRole role, String tokenHash, Instant expiresAt) {
    jdbc.update("""
        INSERT INTO synthetic_sessions (id, role, token_hash, expires_at)
        VALUES (?, ?, ?, ?)
        """, id, role.name(), tokenHash, Timestamp.from(expiresAt));
  }

  public Optional<SyntheticPrincipal> findActive(String tokenHash, Instant now) {
    return jdbc.query("""
        SELECT id, role
        FROM synthetic_sessions
        WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?
        """, (result, row) -> new SyntheticPrincipal(
        result.getObject("id", UUID.class),
        SyntheticRole.valueOf(result.getString("role"))), tokenHash, Timestamp.from(now))
        .stream()
        .findFirst();
  }

  public Optional<Instant> findExpiry(UUID id) {
    return jdbc.query("""
        SELECT expires_at
        FROM synthetic_sessions
        WHERE id = ? AND revoked_at IS NULL
        """, (result, row) -> result.getTimestamp("expires_at").toInstant(), id)
        .stream()
        .findFirst();
  }

  public void revoke(UUID id, Instant now) {
    jdbc.update("""
        UPDATE synthetic_sessions
        SET revoked_at = ?
        WHERE id = ? AND revoked_at IS NULL
        """, Timestamp.from(now), id);
  }
}
