package com.commercehub.events;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"commercehub.events.enabled", "commercehub.events.workers-enabled"},
    havingValue = "true")
public class OutboxPublisher {
  private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);
  private final OutboxStore outbox;
  private final KafkaTemplate<String, String> kafka;

  public OutboxPublisher(OutboxStore outbox, KafkaTemplate<String, String> kafka) {
    this.outbox = outbox;
    this.kafka = kafka;
  }

  @Scheduled(fixedDelayString = "${commercehub.events.poll-ms:500}")
  public void publishBatch() {
    for (int index = 0; index < 25; index++) {
      var next = outbox.claim(Instant.now());
      if (next.isEmpty()) return;
      send(next.get());
    }
  }

  public void send(OutboxStore.Claim claim) {
    try {
      kafka.send(OrderEvent.TOPIC, claim.aggregateId().toString(), claim.payload()).get(5, TimeUnit.SECONDS);
      outbox.confirmed(claim, Instant.now());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      outbox.failed(claim, Instant.now(), "PUBLISH_INTERRUPTED");
    } catch (Exception error) {
      // Timeout can mean the broker accepted the record. Redelivery is safe through each consumer inbox.
      outbox.failed(claim, Instant.now(), "BROKER_UNAVAILABLE");
      LOGGER.atWarn().addKeyValue("eventName", "event.publish.failed")
          .addKeyValue("eventId", claim.eventId()).addKeyValue("retryCount", claim.attempt())
          .addKeyValue("errorCode", "BROKER_UNAVAILABLE").log("Event retained for retry");
    }
  }
}
