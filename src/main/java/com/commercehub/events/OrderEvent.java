package com.commercehub.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public record OrderEvent(UUID eventId, int schemaVersion, UUID aggregateId, long aggregateVersion,
    String eventType, Instant occurredAt, Payload payload) {
  public static final String TOPIC = "commerce.order-placed.v1";
  public record Payload(UUID orderId, long totalMinorUnits, String currency, int lineCount) {}

  public static OrderEvent read(ObjectMapper mapper, String key, String raw) {
    if (raw == null || raw.length() > 8192) throw new IllegalArgumentException("PAYLOAD_INVALID");
    final OrderEvent event;
    try {
      event = mapper.readerFor(OrderEvent.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(raw);
    } catch (JsonProcessingException error) { throw new IllegalArgumentException("PAYLOAD_INVALID"); }
    if (event == null || event.eventId == null || event.aggregateId == null || event.occurredAt == null ||
        event.schemaVersion != 1 || event.aggregateVersion < 1 || !"ORDER_PLACED".equals(event.eventType) ||
        !event.aggregateId.toString().equals(key) || event.payload == null ||
        !event.aggregateId.equals(event.payload.orderId) || event.payload.totalMinorUnits < 0 ||
        !"USD".equals(event.payload.currency) || event.payload.lineCount < 1 || event.payload.lineCount > 30) {
      throw new IllegalArgumentException("EVENT_INVALID");
    }
    return event;
  }

  public static String hash(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
  }
}
