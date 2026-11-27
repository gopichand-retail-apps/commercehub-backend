package com.commercehub.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EventConsumer {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper json;

  public EventConsumer(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.json = json;
  }

  public void accept(String consumer, ConsumerRecord<String, String> record) {
    if (!java.util.Set.of("reporting", "notification").contains(consumer)) {
      throw new IllegalArgumentException("Unknown consumer");
    }
    transactions.executeWithoutResult(status -> process(consumer, record));
  }

  private void process(String consumer, ConsumerRecord<String, String> record) {
    // One local worker per group. This row also serializes replay and additional app instances.
    jdbc.queryForObject("SELECT duplicate_count FROM consumer_state WHERE consumer_name=? FOR UPDATE",
        Long.class, consumer);
    final OrderEvent event;
    try { event = OrderEvent.read(json, record.key(), record.value()); }
    catch (IllegalArgumentException error) {
      quarantine(consumer, record, safeId(record.value()), error.getMessage());
      return;
    }
    String hash = OrderEvent.hash(record.value());
    var seen = jdbc.query("SELECT payload_hash FROM consumer_inbox WHERE consumer_name=? AND event_id=?",
        (result, row) -> result.getString(1), consumer, event.eventId());
    if (!seen.isEmpty()) {
      if (!hash.equals(seen.get(0))) { quarantine(consumer, record, event.eventId(), "EVENT_ID_COLLISION"); return; }
      jdbc.update("UPDATE consumer_state SET duplicate_count=duplicate_count+1 WHERE consumer_name=?", consumer);
      resolve(consumer, event.eventId());
      return;
    }
    if ("reporting".equals(consumer) && !project(event)) {
      quarantine(consumer, record, event.eventId(), "VERSION_GAP_OR_STALE");
      return;
    }
    if ("notification".equals(consumer)) {
      jdbc.update("""
          INSERT INTO notification_log(event_id, order_id, created_at_utc, status) VALUES (?, ?, ?, 'SIMULATED')
          """, event.eventId(), event.aggregateId(), Timestamp.from(Instant.now()));
    }
    jdbc.update("""
        INSERT INTO consumer_inbox(consumer_name, event_id, payload_hash, processed_at_utc) VALUES (?, ?, ?, ?)
        """, consumer, event.eventId(), hash, Timestamp.from(Instant.now()));
    jdbc.update("UPDATE consumer_state SET last_success=? WHERE consumer_name=?",
        Timestamp.from(Instant.now()), consumer);
    resolve(consumer, event.eventId());
  }

  private boolean project(OrderEvent event) {
    var versions = jdbc.query("SELECT version FROM order_projection WHERE order_id=?",
        (result, row) -> result.getLong(1), event.aggregateId());
    long previous = versions.isEmpty() ? 0 : versions.get(0);
    if (event.aggregateVersion() != previous + 1) return false;
    var payload = event.payload();
    if (previous == 0) {
      jdbc.update("""
          INSERT INTO order_projection(order_id, status, total_minor_units, currency, version,
            last_event_id, last_updated_at_utc) VALUES (?, 'CONFIRMED', ?, ?, ?, ?, ?)
          """, event.aggregateId(), payload.totalMinorUnits(), payload.currency(), event.aggregateVersion(),
          event.eventId(), Timestamp.from(event.occurredAt()));
    } else {
      jdbc.update("""
          UPDATE order_projection SET total_minor_units=?, currency=?, version=?, last_event_id=?,
            last_updated_at_utc=? WHERE order_id=?
          """, payload.totalMinorUnits(), payload.currency(), event.aggregateVersion(), event.eventId(),
          Timestamp.from(event.occurredAt()), event.aggregateId());
    }
    return true;
  }

  private void quarantine(String consumer, ConsumerRecord<String, String> record, UUID id, String reason) {
    String location = consumer + ":" + record.topic() + ":" + record.partition() + ":" + record.offset();
    // Keep no raw poison payload. The local topic/broker byte policy also bounds malformed records.
    jdbc.update("""
        INSERT INTO event_quarantine(record_id, consumer_name, event_id, payload_hash, reason,
          topic_name, partition_id, record_offset, created_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT DO NOTHING
        """, OrderEvent.hash(location), consumer, id, OrderEvent.hash(String.valueOf(record.value())), reason,
        OrderEvent.TOPIC, record.partition(), record.offset(), Timestamp.from(Instant.now()));
  }

  private void resolve(String consumer, UUID id) {
    jdbc.update("UPDATE event_quarantine SET resolved_at_utc=? WHERE consumer_name=? AND event_id=?",
        Timestamp.from(Instant.now()), consumer, id);
  }

  private UUID safeId(String raw) {
    if (raw == null || raw.length() > 8192) return null;
    try { return UUID.fromString(json.readTree(raw).path("eventId").asText()); }
    catch (java.io.IOException | IllegalArgumentException error) { return null; }
  }
}
