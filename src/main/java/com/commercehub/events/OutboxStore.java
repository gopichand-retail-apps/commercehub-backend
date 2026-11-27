package com.commercehub.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class OutboxStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public OutboxStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.transactions = transactions;
  }

  public record Claim(UUID eventId, UUID aggregateId, String payload, UUID lease, int attempt) {}

  public Optional<Claim> claim(Instant now) {
    return transactions.execute(status -> {
      var rows = jdbc.query("""
          SELECT event_id, aggregate_id, payload_json, attempt_count FROM outbox_event o
          WHERE published_at_utc IS NULL AND attempt_count < 10 AND next_attempt_at_utc <= ?
            AND (claim_until IS NULL OR claim_until <= ?)
            AND NOT EXISTS (SELECT 1 FROM outbox_event older WHERE older.aggregate_id = o.aggregate_id
              AND older.aggregate_version < o.aggregate_version AND older.published_at_utc IS NULL)
          ORDER BY occurred_at_utc, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
          """, (result, row) -> new Claim(result.getObject("event_id", UUID.class),
          result.getObject("aggregate_id", UUID.class), result.getString("payload_json"), UUID.randomUUID(),
          result.getInt("attempt_count") + 1), Timestamp.from(now), Timestamp.from(now));
      if (rows.isEmpty()) return Optional.empty();
      Claim claim = rows.get(0);
      jdbc.update("""
          UPDATE outbox_event SET claim_id=?, claim_until=?, attempt_count=? WHERE event_id=?
          """, claim.lease(), Timestamp.from(now.plusSeconds(30)), claim.attempt(), claim.eventId());
      return Optional.of(claim);
    });
  }

  public void confirmed(Claim claim, Instant now) {
    jdbc.update("""
        UPDATE outbox_event SET published_at_utc=?, last_error_code=NULL, claim_id=NULL, claim_until=NULL
        WHERE event_id=? AND claim_id=?
        """, Timestamp.from(now), claim.eventId(), claim.lease());
  }

  public void failed(Claim claim, Instant now, String code) {
    long delay = retryDelayMillis(claim.attempt(), Math.random());
    jdbc.update("""
        UPDATE outbox_event SET next_attempt_at_utc=?, last_error_code=?, claim_id=NULL, claim_until=NULL
        WHERE event_id=? AND claim_id=?
        """, Timestamp.from(now.plusMillis(delay)), code, claim.eventId(), claim.lease());
  }

  public static long retryDelayMillis(int attempt, double jitter) {
    return Math.min(60_000, (1L << Math.min(16, Math.max(0, attempt - 1))) * 1000
        + (long) (Math.max(0, Math.min(1, jitter)) * 1000));
  }
}
