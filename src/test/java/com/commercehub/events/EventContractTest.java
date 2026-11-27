package com.commercehub.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventContractTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @Test
  @DisplayName("[SEC-01] event contract contains only safe versioned order facts and validates every required field")
  void contract() throws Exception {
    UUID id = UUID.randomUUID();
    var event = new OrderEvent(UUID.randomUUID(), 1, id, 1, "ORDER_PLACED", Instant.now(),
        new OrderEvent.Payload(id, 1234, "USD", 1));
    String raw = json.writeValueAsString(event);
    assertThat(OrderEvent.read(json, id.toString(), raw)).isEqualTo(event);
    assertThat(raw).doesNotContain("customer", "token", "password", "email", "accessKey", "card");
    for (String field : java.util.List.of("eventId", "schemaVersion", "aggregateId", "aggregateVersion",
        "eventType", "occurredAt", "payload")) {
      var tree = json.valueToTree(event);
      ((com.fasterxml.jackson.databind.node.ObjectNode) tree).set(field, json.nullNode());
      assertThatThrownBy(() -> OrderEvent.read(json, id.toString(), tree.toString()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (var replacement : java.util.List.of(
        new OrderEvent(event.eventId(), 1, id, 0, "ORDER_PLACED", event.occurredAt(), event.payload()),
        new OrderEvent(event.eventId(), 1, id, 1, "OTHER", event.occurredAt(), event.payload()),
        new OrderEvent(event.eventId(), 1, id, 1, "ORDER_PLACED", event.occurredAt(),
            new OrderEvent.Payload(UUID.randomUUID(), 1, "USD", 1)),
        new OrderEvent(event.eventId(), 1, id, 1, "ORDER_PLACED", event.occurredAt(),
            new OrderEvent.Payload(id, -1, "USD", 1)),
        new OrderEvent(event.eventId(), 1, id, 1, "ORDER_PLACED", event.occurredAt(),
            new OrderEvent.Payload(id, 1, "EUR", 1)),
        new OrderEvent(event.eventId(), 1, id, 1, "ORDER_PLACED", event.occurredAt(),
            new OrderEvent.Payload(id, 1, "USD", 0)),
        new OrderEvent(event.eventId(), 1, id, 1, "ORDER_PLACED", event.occurredAt(),
            new OrderEvent.Payload(id, 1, "USD", 31)))) {
      assertThatThrownBy(() -> OrderEvent.read(json, id.toString(), json.writeValueAsString(replacement)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> OrderEvent.read(json, id.toString(), raw.replace("\"schemaVersion\":1",
        "\"schemaVersion\":1,\"schemaVersion\":1"))).isInstanceOf(IllegalArgumentException.class);
    try (var digest = org.mockito.Mockito.mockStatic(java.security.MessageDigest.class)) {
      digest.when(() -> java.security.MessageDigest.getInstance("SHA-256"))
          .thenThrow(new java.security.NoSuchAlgorithmException());
      assertThatThrownBy(() -> OrderEvent.hash("synthetic")).hasMessage("SHA-256 unavailable");
    }
  }
}
