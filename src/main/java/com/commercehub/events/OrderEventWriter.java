package com.commercehub.events;

import com.commercehub.order.CommerceResponses.OrderReceipt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class OrderEventWriter {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final boolean enabled;

  public OrderEventWriter(JdbcTemplate jdbc, ObjectMapper json,
      @Value("${commercehub.events.enabled:false}") boolean enabled) {
    this.jdbc = jdbc;
    this.json = json;
    this.enabled = enabled;
  }

  public void append(OrderReceipt order) {
    if (!enabled) return;
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Order event must share the order transaction");
    }
    UUID eventId = UUID.randomUUID();
    var event = new OrderEvent(eventId, 1, order.orderId(), 1, "ORDER_PLACED", order.createdAt(),
        new OrderEvent.Payload(order.orderId(), order.totalMinor(), order.currency(), order.items().size()));
    try {
      jdbc.update("""
          INSERT INTO outbox_event(event_id, aggregate_type, aggregate_id, aggregate_version, event_type,
            schema_version, payload_json, occurred_at_utc, next_attempt_at_utc)
          VALUES (?, 'ORDER', ?, 1, 'ORDER_PLACED', 1, ?, ?, ?)
          """, eventId, order.orderId(), json.writeValueAsString(event), Timestamp.from(order.createdAt()),
          Timestamp.from(order.createdAt()));
    } catch (JsonProcessingException error) { throw new IllegalStateException("Order event could not be encoded"); }
  }
}
