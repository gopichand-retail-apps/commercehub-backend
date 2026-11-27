package com.commercehub.events;

import com.commercehub.api.CommerceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PipelineAdmin {
  private static final Logger LOGGER = LoggerFactory.getLogger(PipelineAdmin.class);
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper json;

  public PipelineAdmin(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.json = json;
  }

  public record Summary(UUID eventId, UUID aggregateId, int attemptCount, Instant occurredAt,
      Instant publishedAt, Instant nextAttemptAt, String lastErrorCode) {}

  public List<Summary> list(String state, int page, int size) {
    String predicate = switch (state) {
      case "pending" -> "published_at_utc IS NULL AND attempt_count < 10";
      case "failed" -> "published_at_utc IS NULL AND attempt_count >= 10";
      case "published" -> "published_at_utc IS NOT NULL";
      default -> throw invalid();
    };
    if (page < 0 || page > 1000 || size < 1 || size > 100) throw invalid();
    // Only the server-owned predicate enters SQL; all request values are bound.
    return jdbc.query("SELECT * FROM outbox_event WHERE " + predicate
        + " ORDER BY occurred_at_utc, event_id LIMIT ? OFFSET ?", (result, row) -> {
          Timestamp published = result.getTimestamp("published_at_utc");
          return new Summary(result.getObject("event_id", UUID.class), result.getObject("aggregate_id", UUID.class),
              result.getInt("attempt_count"), result.getTimestamp("occurred_at_utc").toInstant(),
              published == null ? null : published.toInstant(), result.getTimestamp("next_attempt_at_utc").toInstant(),
              result.getString("last_error_code"));
        }, size, page * size);
  }

  public Map<String, Object> health() {
    Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE published_at_utc IS NULL", Long.class);
    Long failed = jdbc.queryForObject("""
        SELECT COUNT(*) FROM outbox_event WHERE published_at_utc IS NULL AND attempt_count >= 10
        """, Long.class);
    Long quarantined = jdbc.queryForObject("SELECT COUNT(*) FROM event_quarantine WHERE resolved_at_utc IS NULL",
        Long.class);
    var counts = jdbc.queryForList("""
        SELECT consumer_name, last_success, duplicate_count FROM consumer_state ORDER BY consumer_name
        """);
    var position = jdbc.queryForList("SELECT MAX(published_at_utc) AS last_confirmation FROM outbox_event");
    var age = jdbc.queryForList("""
        SELECT MIN(occurred_at_utc) AS oldest_pending FROM outbox_event WHERE published_at_utc IS NULL
        """);
    var poison = jdbc.queryForList("""
        SELECT event_id, consumer_name, reason, partition_id, record_offset, created_at_utc
        FROM event_quarantine WHERE resolved_at_utc IS NULL ORDER BY created_at_utc DESC, record_id LIMIT 25
        """);
    return Map.of("observedAt", Instant.now(), "backlog", pending, "exhausted", failed, "quarantined", quarantined,
        "consumers", counts, "publisher", position.get(0), "pendingAge", age.get(0), "quarantinedRecords", poison);
  }

  public boolean retry(UUID eventId, String key, UUID actor) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) throw invalid();
    return Boolean.TRUE.equals(transactions.execute(status -> retryTransaction(eventId, key, actor)));
  }

  private boolean retryTransaction(UUID id, String key, UUID actor) {
    var rows = jdbc.query("""
        SELECT aggregate_id, payload_json, claim_until FROM outbox_event WHERE event_id=? FOR UPDATE
        """,
        (result, row) -> {
          Timestamp lease = result.getTimestamp("claim_until");
          return new ReplayRow(result.getObject("aggregate_id", UUID.class), result.getString("payload_json"),
              lease == null ? null : lease.toInstant());
        }, id);
    if (rows.isEmpty()) throw new CommerceException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "Event was not found");
    var existing = jdbc.query("SELECT event_id FROM event_replay_audit WHERE request_key=?",
        (result, row) -> result.getObject(1, UUID.class), key);
    if (!existing.isEmpty()) {
      if (!id.equals(existing.get(0))) throw conflict();
      return true;
    }
    ReplayRow row = rows.get(0);
    if (row.lease != null && row.lease.isAfter(Instant.now())) throw conflict();
    try {
      OrderEvent event = OrderEvent.read(json, row.orderId.toString(), row.payload);
      if (!event.eventId().equals(id)) throw conflict();
    } catch (IllegalArgumentException error) { throw conflict(); }
    int inserted = jdbc.update("""
        INSERT INTO event_replay_audit(request_key, event_id, actor_session_id, created_at_utc) VALUES (?, ?, ?, ?)
        ON CONFLICT DO NOTHING
        """, key, id, actor, Timestamp.from(Instant.now()));
    if (inserted == 0) throw conflict();
    jdbc.update("""
        UPDATE outbox_event SET published_at_utc=NULL, attempt_count=0, next_attempt_at_utc=?, last_error_code=NULL,
          claim_id=NULL, claim_until=NULL WHERE event_id=?
        """, Timestamp.from(Instant.now()), id);
    LOGGER.atInfo().addKeyValue("eventName", "event.replay.requested").addKeyValue("eventId", id)
        .addKeyValue("actorHash", OrderEvent.hash(actor.toString())).log("Local event replay requested");
    return false;
  }

  private record ReplayRow(UUID orderId, String payload, Instant lease) {}
  private static CommerceException invalid() {
    return new CommerceException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Review the request and try again");
  }
  private static CommerceException conflict() {
    return new CommerceException(HttpStatus.CONFLICT, "EVENT_RETRY_CONFLICT", "Event cannot be retried in this state");
  }
}
