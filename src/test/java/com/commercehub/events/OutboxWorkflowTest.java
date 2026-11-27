package com.commercehub.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercehub.order.CommerceRequests;
import com.commercehub.order.CommerceService;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"commercehub.events.enabled=true", "commercehub.events.workers-enabled=false",
    "spring.datasource.url=jdbc:h2:mem:be02_outbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class OutboxWorkflowTest {
  @Autowired private CommerceService commerce;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;
  @Autowired private OutboxStore outbox;
  @Autowired private EventConsumer consumer;
  @Autowired private PipelineAdmin admin;
  private static final java.util.concurrent.atomic.AtomicLong OFFSET = new java.util.concurrent.atomic.AtomicLong();

  @Test
  @DisplayName("[OUT-01] committed order and idempotent replay share one durable event")
  void commitsOneEvent() {
    UUID product = jdbc.queryForObject("SELECT id FROM products WHERE slug='daybreak-coffee'", UUID.class);
    var request = new CommerceRequests.Order(List.of(new CommerceRequests.Item(product, 1)), null);
    String key = UUID.randomUUID().toString();
    UUID order = commerce.createOrder(request, key, "a".repeat(43), null).order().orderId();
    assertThat(commerce.createOrder(request, key, "a".repeat(43), null).duplicate()).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?", Integer.class, order))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("[OUT-02] outer rollback leaves no order or event and restores inventory")
  void rollback() {
    long before = count("outbox_event");
    long orders = count("customer_orders");
    int stock = jdbc.queryForObject("SELECT stock_quantity FROM products WHERE slug='daybreak-coffee'", Integer.class);
    assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
      create();
      throw new IllegalStateException("synthetic failure after durable writes");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(count("outbox_event")).isEqualTo(before);
    assertThat(count("customer_orders")).isEqualTo(orders);
    assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE slug='daybreak-coffee'", Integer.class))
        .isEqualTo(stock);
  }

  @Test
  @DisplayName("[PUB-01] broker confirmation alone marks a row published")
  void confirms() {
    isolate();
    create();
    var claim = outbox.claim(Instant.now()).orElseThrow();
    assertThat(jdbc.queryForObject("SELECT published_at_utc FROM outbox_event WHERE event_id=?",
        Timestamp.class, claim.eventId())).isNull();
    outbox.confirmed(claim, Instant.now());
    assertThat(outbox.claim(Instant.now())).isEmpty();
    assertThat(admin.list("published", 0, 100)).isNotEmpty();
  }

  @Test
  @DisplayName("[PUB-02] expired claim can recover and stale owner cannot acknowledge it")
  void leases() {
    isolate();
    create();
    var first = outbox.claim(Instant.now()).orElseThrow();
    assertThat(outbox.claim(Instant.now())).isEmpty();
    var second = outbox.claim(Instant.now().plusSeconds(31)).orElseThrow();
    assertThat(second.lease()).isNotEqualTo(first.lease());
    outbox.confirmed(first, Instant.now());
    outbox.failed(first, Instant.now(), "BROKER_UNAVAILABLE");
    assertThat(jdbc.queryForObject("SELECT claim_id FROM outbox_event WHERE event_id=?", UUID.class, first.eventId()))
        .isEqualTo(second.lease());
    outbox.confirmed(second, Instant.now());
  }

  @Test
  @DisplayName("[PUB-03] failed delivery backs off, caps retries and allows explicit operator recovery")
  void retries() {
    isolate();
    create();
    Instant now = Instant.now();
    OutboxStore.Claim last = null;
    for (int index = 1; index <= 10; index++) {
      last = outbox.claim(now).orElseThrow();
      assertThat(last.attempt()).isEqualTo(index);
      outbox.failed(last, now, "BROKER_UNAVAILABLE");
      assertThat(outbox.claim(now)).isEmpty();
      now = now.plusSeconds(61);
    }
    assertThat(outbox.claim(now)).isEmpty();
    assertThat(admin.list("failed", 0, 100)).extracting(PipelineAdmin.Summary::eventId).contains(last.eventId());
    assertThat(admin.retry(last.eventId(), UUID.randomUUID().toString(), UUID.randomUUID())).isFalse();
    assertThat(outbox.claim(Instant.now())).isPresent();
    assertThat(OutboxStore.retryDelayMillis(-1, -1)).isEqualTo(1000);
    assertThat(OutboxStore.retryDelayMillis(100, 2)).isEqualTo(60_000);
  }

  @Test
  @DisplayName("[CON-01] repeated delivery has one effect per consumer")
  void dedupe() throws Exception {
    OrderEvent event = event(UUID.randomUUID(), 1);
    var record = record(event);
    for (String group : List.of("reporting", "notification")) {
      consumer.accept(group, record);
      consumer.accept(group, record);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumer_inbox WHERE consumer_name=? AND event_id=?",
          Integer.class, group, event.eventId())).isEqualTo(1);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_log WHERE event_id=?",
        Integer.class, event.eventId())).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT version FROM order_projection WHERE order_id=?",
        Long.class, event.aggregateId())).isEqualTo(1);
  }

  @Test
  @DisplayName("[CON-02] effect failure rolls back inbox and redelivery remains possible")
  void effectRollback() throws Exception {
    OrderEvent event = event(UUID.randomUUID(), 1);
    jdbc.execute("ALTER TABLE consumer_inbox ADD CONSTRAINT synthetic_inbox_failure CHECK (event_id <> '"
        + event.eventId() + "')");
    try {
      assertThatThrownBy(() -> consumer.accept("reporting", record(event))).isInstanceOf(Exception.class);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_projection WHERE order_id=?", Integer.class,
          event.aggregateId())).isZero();
    } finally { jdbc.execute("ALTER TABLE consumer_inbox DROP CONSTRAINT synthetic_inbox_failure"); }
    consumer.accept("reporting", record(event));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumer_inbox WHERE event_id=?", Integer.class,
        event.eventId())).isEqualTo(1);
  }

  @Test
  @DisplayName("[ORD-01] gaps quarantine and later contiguous replay never regresses reporting")
  void ordering() throws Exception {
    UUID id = UUID.randomUUID();
    OrderEvent future = event(id, 2);
    consumer.accept("reporting", record(future));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_quarantine WHERE event_id=?", Integer.class,
        future.eventId())).isEqualTo(1);
    consumer.accept("reporting", record(event(id, 1)));
    consumer.accept("reporting", record(future));
    consumer.accept("reporting", record(event(id, 1)));
    assertThat(jdbc.queryForObject("SELECT version FROM order_projection WHERE order_id=?", Long.class, id))
        .isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT resolved_at_utc FROM event_quarantine WHERE event_id=?", Timestamp.class,
        future.eventId())).isNotNull();
  }

  @Test
  @DisplayName("[POI-01] poison never writes inbox/effects or retains the raw body")
  void poison() throws Exception {
    String valid = json.writeValueAsString(event(UUID.randomUUID(), 1));
    for (String raw : java.util.Arrays.asList(null, "null", "{}", "private secret", "x".repeat(8193),
        valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
        valid.replace("\"lineCount\":1", "\"lineCount\":0"),
        valid.replace("\"currency\":\"USD\"", "\"currency\":\"EUR\""),
        valid.replace("\"totalMinorUnits\":2295", "\"totalMinorUnits\":-1"),
        valid.replace("\"payload\":{", "\"payload\":{\"password\":\"private-secret\","),
        valid + " {}")) {
      long before = count("event_quarantine");
      consumer.accept("reporting", new ConsumerRecord<>(OrderEvent.TOPIC, 0, OFFSET.incrementAndGet(), "bad", raw));
      assertThat(count("event_quarantine")).isEqualTo(before + 1);
    }
    OrderEvent event = event(UUID.randomUUID(), 1);
    consumer.accept("reporting", record(event));
    var collision = new OrderEvent(event.eventId(), 1, event.aggregateId(), 1, event.eventType(),
        event.occurredAt(), new OrderEvent.Payload(event.aggregateId(), 2000, "USD", 1));
    consumer.accept("reporting", record(collision));
    assertThat(jdbc.queryForObject("SELECT reason FROM event_quarantine WHERE event_id=?", String.class,
        event.eventId())).isEqualTo("EVENT_ID_COLLISION");
    assertThatThrownBy(() -> consumer.accept("unknown", record(event))).hasMessage("Unknown consumer");
  }

  @Test
  @DisplayName("[POI-02] retries are audited, idempotent, reject unknown or corrupted sources and active leases")
  void replay() {
    isolate();
    create();
    var claim = outbox.claim(Instant.now()).orElseThrow();
    String key = UUID.randomUUID().toString();
    UUID actor = UUID.randomUUID();
    assertThatThrownBy(() -> admin.retry(claim.eventId(), key, actor)).hasMessageContaining("cannot be retried");
    outbox.confirmed(claim, Instant.now());
    assertThat(admin.retry(claim.eventId(), key, actor)).isFalse();
    assertThat(admin.retry(claim.eventId(), key, actor)).isTrue();
    assertThatThrownBy(() -> admin.retry(UUID.randomUUID(), key, actor)).hasMessage("Event was not found");
    assertThatThrownBy(() -> admin.retry(claim.eventId(), "bad", actor)).hasMessageContaining("Review");
    assertThatThrownBy(() -> admin.retry(claim.eventId(), null, actor)).hasMessageContaining("Review");
    create();
    UUID another = jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE event_id<>? LIMIT 1",
        UUID.class, claim.eventId());
    assertThatThrownBy(() -> admin.retry(another, key, actor)).hasMessageContaining("cannot be retried");
    jdbc.update("UPDATE outbox_event SET claim_until=? WHERE event_id=?",
        Timestamp.from(Instant.now().minusSeconds(1)), claim.eventId());
    assertThat(admin.retry(claim.eventId(), UUID.randomUUID().toString(), actor)).isFalse();
    String original = jdbc.queryForObject("SELECT payload_json FROM outbox_event WHERE event_id=?",
        String.class, claim.eventId());
    jdbc.update("UPDATE outbox_event SET payload_json=? WHERE event_id=?",
        original.replace(claim.eventId().toString(), UUID.randomUUID().toString()), claim.eventId());
    assertThatThrownBy(() -> admin.retry(claim.eventId(), UUID.randomUUID().toString(), actor))
        .hasMessageContaining("cannot be retried");
    jdbc.update("UPDATE outbox_event SET payload_json='{}' WHERE event_id=?", claim.eventId());
    assertThatThrownBy(() -> admin.retry(claim.eventId(), UUID.randomUUID().toString(), actor))
        .hasMessageContaining("cannot be retried");
    assertThat(count("event_replay_audit")).isPositive();
  }

  @Test
  @DisplayName("[OBS-01] database status reports backlog and quarantine without claiming broker health")
  void status() {
    assertThat(admin.health()).containsKeys("backlog", "pendingAge", "quarantined", "consumers", "publisher");
    assertThatThrownBy(() -> admin.list("injected SQL", 0, 1)).hasMessageContaining("Review");
    for (int[] bounds : List.of(new int[]{-1, 1}, new int[]{1001, 1}, new int[]{0, 0}, new int[]{0, 101})) {
      assertThatThrownBy(() -> admin.list("pending", bounds[0], bounds[1])).hasMessageContaining("Review");
    }
  }

  private void isolate() {
    jdbc.update("UPDATE outbox_event SET published_at_utc=?", Timestamp.from(Instant.now()));
  }

  @Test
  @DisplayName("[PUB-02] [CON-01] concurrent claims and duplicate consumers serialize their durable ownership")
  void concurrentOwnership() throws Exception {
    isolate();
    create();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var barrier = new java.util.concurrent.CyclicBarrier(2);
      java.util.concurrent.Callable<java.util.Optional<OutboxStore.Claim>> task = () -> {
        barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
        return outbox.claim(Instant.now());
      };
      var claims = pool.invokeAll(List.of(task, task));
      assertThat(claims.stream().filter(result -> {
        try { return result.get().isPresent(); }
        catch (Exception error) { throw new IllegalStateException(error); }
      }).count()).isEqualTo(1);
      OrderEvent event = event(UUID.randomUUID(), 1);
      var record = record(event);
      java.util.concurrent.Callable<Boolean> deliver = () -> { consumer.accept("reporting", record); return true; };
      for (var result : pool.invokeAll(List.of(deliver, deliver))) assertThat(result.get()).isTrue();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumer_inbox WHERE event_id=?",
          Integer.class, event.eventId())).isEqualTo(1);
    } finally { pool.shutdownNow(); }
  }
  private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
  private void create() {
    UUID id = jdbc.queryForObject("SELECT id FROM products WHERE slug='daybreak-coffee'", UUID.class);
    commerce.createOrder(new CommerceRequests.Order(List.of(new CommerceRequests.Item(id, 1)), null),
        UUID.randomUUID().toString(), "a".repeat(43), null);
  }
  private OrderEvent event(UUID order, long version) {
    return new OrderEvent(UUID.randomUUID(), 1, order, version, "ORDER_PLACED", Instant.now(),
        new OrderEvent.Payload(order, 2295, "USD", 1));
  }
  private ConsumerRecord<String, String> record(OrderEvent event) throws Exception {
    return new ConsumerRecord<>(OrderEvent.TOPIC, 0, OFFSET.incrementAndGet(), event.aggregateId().toString(),
        json.writeValueAsString(event));
  }
}
