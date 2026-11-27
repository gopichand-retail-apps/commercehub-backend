package com.commercehub.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.commercehub.order.CommerceResponses.OrderReceipt;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PublisherTest {
  @Test
  @DisplayName("[PUB-01] failed and interrupted sends cannot mark delivery confirmed")
  @SuppressWarnings("unchecked") // Mockito's generic KafkaTemplate class token has no reified parameters.
  void sendOutcomes() throws Exception {
    OutboxStore store = mock(OutboxStore.class);
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    var publisher = new OutboxPublisher(store, kafka);
    var claim = new OutboxStore.Claim(UUID.randomUUID(), UUID.randomUUID(), "safe", UUID.randomUUID(), 1);
    when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    publisher.send(claim);
    verify(store).confirmed(eq(claim), any());
    reset(store);
    when(kafka.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("synthetic broker outage")));
    publisher.send(claim);
    verify(store).failed(eq(claim), any(), eq("BROKER_UNAVAILABLE"));
    verify(store, never()).confirmed(any(), any());
    CompletableFuture<SendResult<String, String>> blocked = mock(CompletableFuture.class);
    when(blocked.get(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
    when(kafka.send(anyString(), anyString(), anyString())).thenReturn(blocked);
    try {
      publisher.send(claim);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      verify(store).failed(eq(claim), any(), eq("PUBLISH_INTERRUPTED"));
    } finally { Thread.interrupted(); }
    when(store.claim(any())).thenReturn(Optional.of(claim), Optional.empty());
    when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    publisher.publishBatch();
    when(store.claim(any())).thenReturn(Optional.of(claim));
    publisher.publishBatch();
    verify(store, times(26)).confirmed(eq(claim), any());
  }

  @Test
  @DisplayName("[OUT-03] disabled writer does nothing; enabled writer refuses a separate transaction")
  void writerBoundary() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var mapper = mock(ObjectMapper.class);
    new OrderEventWriter(jdbc, mapper, false).append(null);
    verifyNoInteractions(jdbc, mapper);
    var writer = new OrderEventWriter(jdbc, mapper, true);
    assertThatThrownBy(() -> writer.append(null)).hasMessageContaining("share the order transaction");
    OrderReceipt receipt = mock(OrderReceipt.class);
    when(receipt.orderId()).thenReturn(UUID.randomUUID());
    when(receipt.createdAt()).thenReturn(Instant.now());
    when(receipt.items()).thenReturn(java.util.List.of());
    when(mapper.writeValueAsString(any())).thenThrow(new JsonMappingException(null, "synthetic encoder error"));
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try { assertThatThrownBy(() -> writer.append(receipt)).hasMessage("Order event could not be encoded"); }
    finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    KafkaConfiguration config = new KafkaConfiguration();
    assertThat(config.orderTopic().name()).isEqualTo(OrderEvent.TOPIC);
    assertThat(config.durableConsumerErrors()).isNotNull();
  }

  @Test
  @DisplayName("[CON-01] listener acknowledges only after the database service returned successfully")
  void acknowledgeAfterCommit() {
    EventConsumer consumer = mock(EventConsumer.class);
    var acknowledgment = mock(org.springframework.kafka.support.Acknowledgment.class);
    var record = new org.apache.kafka.clients.consumer.ConsumerRecord<>(OrderEvent.TOPIC, 0, 0L, "key", "value");
    var workers = new KafkaWorkers(consumer);
    workers.reporting(record, acknowledgment);
    var sequence = inOrder(consumer, acknowledgment);
    sequence.verify(consumer).accept("reporting", record);
    sequence.verify(acknowledgment).acknowledge();
    workers.notification(record, acknowledgment);
    verify(consumer).accept("notification", record);
    reset(acknowledgment);
    doThrow(new IllegalStateException("synthetic database outage")).when(consumer).accept("reporting", record);
    assertThatThrownBy(() -> workers.reporting(record, acknowledgment)).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(acknowledgment);
  }
}
