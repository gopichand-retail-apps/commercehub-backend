package com.commercehub.events;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"commercehub.events.enabled", "commercehub.events.workers-enabled"},
    havingValue = "true")
public class KafkaWorkers {
  private final EventConsumer consumer;

  public KafkaWorkers(EventConsumer consumer) { this.consumer = consumer; }

  @KafkaListener(topics = OrderEvent.TOPIC, groupId = "commercehub-reporting-v1")
  public void reporting(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    consumer.accept("reporting", record);
    acknowledgment.acknowledge();
  }

  @KafkaListener(topics = OrderEvent.TOPIC, groupId = "commercehub-notification-v1")
  public void notification(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    consumer.accept("notification", record);
    acknowledgment.acknowledge();
  }
}
