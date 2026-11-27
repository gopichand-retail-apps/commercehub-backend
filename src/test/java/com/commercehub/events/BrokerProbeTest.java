package com.commercehub.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BrokerProbeTest {
  @Test
  @DisplayName("[OBS-01] lag samples reflect committed offsets and outages never fabricate zero lag")
  @SuppressWarnings("unchecked") // KafkaFuture generic class tokens are erased in Mockito.
  void samples() throws Exception {
    Admin admin = mock(Admin.class);
    var topics = mock(DescribeTopicsResult.class);
    var offsets = mock(ListOffsetsResult.class);
    var groups = mock(ListConsumerGroupOffsetsResult.class);
    TopicPartition partition = new TopicPartition(OrderEvent.TOPIC, 0);
    Node node = new Node(1, "127.0.0.1", 17992);
    var description = new TopicDescription(OrderEvent.TOPIC, false,
        List.of(new TopicPartitionInfo(0, node, List.of(node), List.of(node))));
    when(admin.describeTopics(List.of(OrderEvent.TOPIC))).thenReturn(topics);
    when(topics.allTopicNames()).thenReturn(KafkaFuture.completedFuture(Map.of(OrderEvent.TOPIC, description)));
    when(admin.listOffsets(anyMap())).thenReturn(offsets);
    when(offsets.all()).thenReturn(KafkaFuture.completedFuture(Map.of(partition,
        new ListOffsetsResult.ListOffsetsResultInfo(10, 0, Optional.empty()))));
    when(admin.listConsumerGroupOffsets(anyString())).thenReturn(groups);
    when(groups.partitionsToOffsetAndMetadata())
        .thenReturn(KafkaFuture.completedFuture(Map.of(partition, new OffsetAndMetadata(2))))
        .thenReturn(KafkaFuture.completedFuture(Map.of()));
    try (var factory = mockStatic(Admin.class)) {
      factory.when(() -> Admin.create(anyMap())).thenReturn(admin);
      var probe = new BrokerProbe("127.0.0.1:17992");
      assertThat(probe.sample().consumerLag()).containsEntry("reporting", 8L).containsEntry("notification", 10L);
      KafkaFuture<Map<String, TopicDescription>> interrupted = mock(KafkaFuture.class);
      when(topics.allTopicNames()).thenReturn(interrupted);
      when(interrupted.get(1500, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException());
      try {
        assertThat(probe.sample().errorCode()).isEqualTo("PROBE_INTERRUPTED");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
      } finally { Thread.interrupted(); }
      factory.when(() -> Admin.create(anyMap())).thenThrow(new IllegalStateException("private-infrastructure"));
      var unavailable = probe.sample();
      assertThat(unavailable.brokerReady()).isFalse();
      assertThat(unavailable.consumerLag()).isEmpty();
      assertThat(unavailable.errorCode()).isEqualTo("BROKER_STATUS_UNAVAILABLE");
    }
  }
}
