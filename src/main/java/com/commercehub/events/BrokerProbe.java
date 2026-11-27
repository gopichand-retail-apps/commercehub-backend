package com.commercehub.events;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BrokerProbe {
  private final Map<String, Object> configuration;

  public BrokerProbe(@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
    configuration = Map.of("bootstrap.servers", bootstrap, "request.timeout.ms", 1000,
        "default.api.timeout.ms", 1500);
  }

  public record Sample(boolean brokerReady, Map<String, Long> consumerLag, String errorCode, Instant observedAt) {}

  public Sample sample() {
    try (Admin client = Admin.create(configuration)) {
      var topic = client.describeTopics(java.util.List.of(OrderEvent.TOPIC)).allTopicNames()
          .get(1500, TimeUnit.MILLISECONDS).get(OrderEvent.TOPIC);
      Map<TopicPartition, OffsetSpec> partitions = new HashMap<>();
      topic.partitions().forEach(partition ->
          partitions.put(new TopicPartition(OrderEvent.TOPIC, partition.partition()), OffsetSpec.latest()));
      var ends = client.listOffsets(partitions).all().get(1500, TimeUnit.MILLISECONDS);
      Map<String, Long> lag = new HashMap<>();
      for (String group : java.util.List.of("reporting", "notification")) {
        var offsets = client.listConsumerGroupOffsets("commercehub-" + group + "-v1")
            .partitionsToOffsetAndMetadata().get(1500, TimeUnit.MILLISECONDS);
        long behind = ends.entrySet().stream().mapToLong(entry -> {
          var offset = offsets.get(entry.getKey());
          return Math.max(0, entry.getValue().offset() - (offset == null ? 0 : offset.offset()));
        }).sum();
        lag.put(group, behind);
      }
      return new Sample(true, lag, null, Instant.now());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return new Sample(false, Map.of(), "PROBE_INTERRUPTED", Instant.now());
    } catch (Exception error) {
      return new Sample(false, Map.of(), "BROKER_STATUS_UNAVAILABLE", Instant.now());
    }
  }
}
