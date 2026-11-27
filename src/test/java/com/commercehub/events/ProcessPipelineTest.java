package com.commercehub.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ProcessPipelineTest {
  private final Path root = Path.of("target", "be02-process", UUID.randomUUID().toString()).toAbsolutePath();
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final List<Long> pids = new ArrayList<>();
  private Process app;
  private Process broker;
  private JdbcTemplate jdbc;
  private int launches;
  private String brokerFailure = "not sampled";

  void interactive() throws Exception {
    Files.createDirectories(root.resolve("native"));
    try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().setPort(17432)
        .setDataDirectory(root.resolve("pg-data")).setCleanDataDirectory(false)
        .setOverrideWorkingDirectory(root.resolve("native").toFile())
        .setServerConfig("listen_addresses", "127.0.0.1").start()) {
      jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
      try {
        prepareBroker();
        broker = start("kafka.Kafka", List.of(root.resolve("broker.properties").toString()));
        await(this::brokerReady, 45);
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", "127.0.0.1:17992"))) {
          admin.createTopics(List.of(new NewTopic(OrderEvent.TOPIC, 3, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
        startApp(true);
        Files.writeString(root.resolve("pids.json"), json.writeValueAsString(pids));
        System.out.println("Synthetic event lab ready at http://127.0.0.1:17302; evidence: " + root);
        System.out.println("Press Enter to stop only this lab. Data remains in its unique target directory.");
        System.in.read();
      } finally { stop(app); stop(broker); }
    }
  }

  @Test
  @DisplayName("[REC-01] [REC-02] [CON-01] real Kafka and PostgreSQL recover from process crashes and broker outage")
  void recoverRealProcesses() throws Exception {
    Files.createDirectories(root.resolve("native"));
    try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().setPort(17432)
        .setDataDirectory(root.resolve("pg-data")).setCleanDataDirectory(false)
        .setOverrideWorkingDirectory(root.resolve("native").toFile())
        .setServerConfig("listen_addresses", "127.0.0.1").start()) {
      jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
      try {
        prepareBroker();
        broker = start("kafka.Kafka", List.of(root.resolve("broker.properties").toString()));
        try { await(this::brokerReady, 45); }
        catch (AssertionError error) { throw new AssertionError(brokerFailure, error); }
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", "127.0.0.1:17992"))) {
          admin.createTopics(List.of(new NewTopic(OrderEvent.TOPIC, 3, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
        startApp(false);
        jdbc.update("UPDATE products SET stock_quantity=10000");
        createOrder();
        assertThat(number("SELECT COUNT(*) FROM outbox_event WHERE published_at_utc IS NULL")).isEqualTo(1);
        kill(app);
        startApp(true);
        await(this::consistent, 45);
        verifyDuplicate();
        verifyPoisonAndReplay();
        kill(broker);
        for (int index = 0; index < 5; index++) createOrder();
        await(() -> number("SELECT SUM(attempt_count) FROM outbox_event WHERE published_at_utc IS NULL") > 0, 15);
        assertThat(number("SELECT COUNT(*) FROM outbox_event WHERE published_at_utc IS NULL")).isEqualTo(5);
        long recoveryStart = System.nanoTime();
        broker = start("kafka.Kafka", List.of(root.resolve("broker.properties").toString()));
        await(this::consistent, 60);
        double catchupMs = (System.nanoTime() - recoveryStart) / 1_000_000.0;
        // Seeded random fault locations, bounded to three app/broker crashes. Persisted Kafka log is reused.
        var random = new java.util.Random(20261118);
        int cycles = Integer.getInteger("be02.soak.cycles", 3);
        assertThat(cycles).isBetween(3, 30);
        for (int cycle = 0; cycle < cycles; cycle++) {
          createOrder();
          if (random.nextBoolean()) { kill(app); startApp(true); }
          else {
            kill(broker);
            createOrder();
            broker = start("kafka.Kafka", List.of(root.resolve("broker.properties").toString()));
          }
          await(this::consistent, 60);
        }
        var samples = new ArrayList<Map<String, Object>>();
        for (int warmup = 0; warmup < 3; warmup++) createOrder();
        await(this::consistent, 30);
        for (int run = 0; run < 3; run++) {
          for (int index = 0; index < 12; index++) createOrder();
          await(this::consistent, 30);
          samples.add(latencies(run, 12));
        }
        var holdout = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
          var jobs = new ArrayList<java.util.concurrent.Callable<Boolean>>();
          for (int index = 0; index < 20; index++) jobs.add(() -> { createOrder(); return true; });
          for (var result : holdout.invokeAll(jobs)) assertThat(result.get()).isTrue();
        } finally { holdout.shutdownNow(); }
        await(this::consistent, 30);
        samples.add(latencies(3, 20));
        String support = session("SUPPORT");
        JsonNode health = request("GET", "/api/v1/admin/event-pipeline/health", support, null, null);
        assertThat(health.path("broker").path("brokerReady").asBoolean()).isTrue();
        await(() -> new BrokerProbe("127.0.0.1:17992").sample().consumerLag().values().stream()
            .allMatch(lag -> lag == 0), 20);
        Files.writeString(root.resolve("results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(
            Map.of("pids", pids, "catchupMs", catchupMs, "seed", 20261118, "crashCycles", cycles,
                "load", "3 warmup, 3x12 sequential orders, then 20 orders on 4 clients; fixed 500ms poll/25 batch",
                "latencySamples", samples, "orders", number("SELECT COUNT(*) FROM customer_orders"),
                "brokerHealth", health, "stockInvariant", stockInvariant())));
        assertThat(stockInvariant()).isTrue();
      } finally {
        stop(app);
        stop(broker);
        Files.writeString(root.resolve("pids.json"), json.writeValueAsString(pids));
      }
    }
  }

  private void prepareBroker() throws Exception {
    String logs = root.resolve("kafka-data").toString().replace('\\', '/');
    Files.writeString(root.resolve("broker.properties"), """
        process.roles=broker,controller
        node.id=1
        controller.quorum.voters=1@127.0.0.1:17993
        listeners=PLAINTEXT://127.0.0.1:17992,CONTROLLER://127.0.0.1:17993
        advertised.listeners=PLAINTEXT://127.0.0.1:17992
        controller.listener.names=CONTROLLER
        listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
        inter.broker.listener.name=PLAINTEXT
        offsets.topic.replication.factor=1
        transaction.state.log.replication.factor=1
        transaction.state.log.min.isr=1
        group.initial.rebalance.delay.ms=0
        auto.create.topics.enable=false
        message.max.bytes=65536
        log.retention.hours=168
        log.segment.bytes=1073741824
        num.network.threads=2
        num.io.threads=2
        """ + "log.dirs=" + logs + "\n");
    Process format = start("kafka.tools.StorageTool", List.of("format", "-t",
        org.apache.kafka.common.Uuid.randomUuid().toString(), "-c", root.resolve("broker.properties").toString()));
    assertThat(format.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(format.exitValue()).isZero();
  }

  private Process start(String main, List<String> arguments) throws Exception {
    String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Path args = root.resolve("args-" + (++launches) + ".txt");
    var lines = new ArrayList<>(List.of("-Xmx384m", "-cp", quote(classpath), main));
    arguments.forEach(argument -> lines.add(quote(argument)));
    Files.write(args, lines);
    String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
        "@" + args).redirectErrorStream(true).redirectOutput(root.resolve("process-" + launches + ".log").toFile());
    builder.environment().remove("DEBUG");
    Process process = builder.start();
    pids.add(process.pid());
    return process;
  }

  private static String quote(String value) { return "\"" + value.replace('\\', '/').replace("\"", "\\\"") + "\""; }

  private void startApp(boolean workers) throws Exception {
    app = start("com.commercehub.CommerceHubApplication", List.of("--server.address=127.0.0.1", "--server.port=17302",
        "--spring.datasource.url=jdbc:postgresql://127.0.0.1:17432/postgres", "--spring.datasource.username=postgres",
        "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.postgresql.Driver",
        "--commercehub.events.enabled=true", "--commercehub.events.workers-enabled=" + workers,
        "--commercehub.rate-limit.max-requests=10000", "--spring.main.banner-mode=off"));
    await(() -> {
      if (!app.isAlive()) throw new IllegalStateException("Owned application exited; inspect " + root);
      try {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:17302/health"))
            .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
      } catch (Exception error) { return false; }
    }, 40);
  }

  private boolean brokerReady() {
    try (Admin client = Admin.create(Map.of("bootstrap.servers", "127.0.0.1:17992",
        "default.api.timeout.ms", 3000, "request.timeout.ms", 1000))) {
      return !client.describeCluster().nodes().get(3, TimeUnit.SECONDS).isEmpty();
    } catch (Exception error) { brokerFailure = error.toString(); return false; }
  }

  private void createOrder() throws Exception {
    UUID product = jdbc.queryForObject("SELECT id FROM products WHERE slug='daybreak-coffee'", UUID.class);
    request("POST", "/api/v1/orders", null, UUID.randomUUID().toString(),
        json.writeValueAsString(Map.of("items", List.of(Map.of("productId", product, "quantity", 1)))));
  }

  private String session(String role) throws Exception {
    return request("POST", "/api/v1/sessions", null, null, "{\"role\":\"" + role + "\"}")
        .path("session").path("token").asText();
  }

  private JsonNode request(String method, String path, String token, String key, String body) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:17302" + path)).timeout(Duration.ofSeconds(12));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (key != null) builder.header("Idempotency-Key", key).header("X-Order-Access-Key", "x".repeat(43));
    builder.header("Content-Type", "application/json");
    builder.method(method,
        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).withFailMessage("HTTP %s at %s", response.statusCode(), path).isBetween(200, 299);
    return json.readTree(response.body());
  }

  private void verifyDuplicate() throws Exception {
    var row = jdbc.queryForMap("SELECT aggregate_id, payload_json FROM outbox_event LIMIT 1");
    Properties properties = new Properties();
    properties.putAll(Map.of("bootstrap.servers", "127.0.0.1:17992", "acks", "all",
        "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
        "value.serializer", "org.apache.kafka.common.serialization.StringSerializer"));
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
      producer.send(new ProducerRecord<>(OrderEvent.TOPIC, row.get("aggregate_id").toString(),
          row.get("payload_json").toString())).get(5, TimeUnit.SECONDS);
    }
    await(() -> number("SELECT MIN(duplicate_count) FROM consumer_state") >= 1, 20);
    assertThat(consistent()).isTrue();
  }

  private void verifyPoisonAndReplay() throws Exception {
    var row = jdbc.queryForMap("SELECT event_id, aggregate_id, payload_json FROM outbox_event LIMIT 1");
    Properties properties = new Properties();
    properties.putAll(Map.of("bootstrap.servers", "127.0.0.1:17992", "acks", "all",
        "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
        "value.serializer", "org.apache.kafka.common.serialization.StringSerializer"));
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
      producer.send(new ProducerRecord<>(OrderEvent.TOPIC, row.get("aggregate_id").toString(),
          row.get("payload_json").toString().replace("\"schemaVersion\":1", "\"schemaVersion\":2")))
          .get(5, TimeUnit.SECONDS);
    }
    await(() -> number("SELECT COUNT(*) FROM event_quarantine WHERE resolved_at_utc IS NULL") == 2, 20);
    String operator = session("SUPPORT");
    request("GET", "/api/v1/admin/outbox?state=published&size=1", operator, null, null);
    String path = "/api/v1/admin/event-pipeline/retry/" + row.get("event_id");
    String key = UUID.randomUUID().toString();
    for (int repeat = 0; repeat < 2; repeat++) {
      var retry = HttpRequest.newBuilder(URI.create("http://127.0.0.1:17302" + path))
          .header("Authorization", "Bearer " + operator).header("Origin", "http://127.0.0.1:17302")
          .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.noBody()).build();
      var response = http.send(retry, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(json.readTree(response.body()).path("duplicate").asBoolean()).isEqualTo(repeat == 1);
    }
    await(() -> number("SELECT COUNT(*) FROM event_quarantine WHERE resolved_at_utc IS NULL") == 0, 20);
    assertThat(consistent()).isTrue();
    for (String role : List.of("CUSTOMER", "MERCHANT")) {
      var denied = HttpRequest.newBuilder(URI.create("http://127.0.0.1:17302/api/v1/admin/outbox"))
          .header("Authorization", "Bearer " + session(role)).GET().build();
      assertThat(http.send(denied, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
    }
  }

  private boolean consistent() {
    long orders = number("SELECT COUNT(*) FROM customer_orders");
    return orders > 0 && number("SELECT COUNT(*) FROM outbox_event WHERE published_at_utc IS NOT NULL") == orders
        && number("SELECT COUNT(*) FROM order_projection") == orders
        && number("SELECT COUNT(*) FROM notification_log") == orders
        && number("SELECT COUNT(*) FROM consumer_inbox") == orders * 2;
  }

  private boolean stockInvariant() {
    return number("SELECT stock_quantity FROM products WHERE slug='daybreak-coffee'")
        + number("SELECT COUNT(*) FROM customer_orders") == 10000;
  }

  private Map<String, Object> latencies(int run, int count) {
    var values = jdbc.query("""
        SELECT EXTRACT(EPOCH FROM (published_at_utc-occurred_at_utc))*1000 FROM outbox_event
        ORDER BY occurred_at_utc DESC LIMIT ?
        """, (result, row) -> result.getDouble(1), count).stream().sorted().toList();
    return Map.of("run", run + 1, "count", values.size(), "p50ms", values.get((count - 1) / 2),
        "p95ms", values.get((int) Math.ceil(count * 0.95) - 1));
  }

  private long number(String sql) {
    Long result = jdbc.queryForObject(sql, Long.class);
    return result == null ? 0 : result;
  }

  private static void await(BooleanSupplier ready, int seconds) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (!ready.getAsBoolean()) {
      if (System.nanoTime() >= deadline) throw new AssertionError("Readiness/invariant deadline exceeded");
      Thread.sleep(100);
    }
  }

  private static void kill(Process process) throws Exception {
    process.destroyForcibly();
    assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
  }
  private static void stop(Process process) throws Exception {
    if (process != null && process.isAlive()) {
      process.destroy();
      if (!process.waitFor(5, TimeUnit.SECONDS)) kill(process);
    }
  }
}
