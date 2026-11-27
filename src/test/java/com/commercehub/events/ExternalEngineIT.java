package com.commercehub.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercehub.order.CommerceRequests;
import com.commercehub.order.CommerceService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** CI service-container contract. Run explicitly with -Pexternal-engine; never uses a hosted data environment. */
@SpringBootTest(properties = {"commercehub.events.enabled=true", "commercehub.events.workers-enabled=true",
    "spring.datasource.hikari.schema=be02_ci", "spring.flyway.schemas=be02_ci",
    "spring.flyway.default-schema=be02_ci"})
class ExternalEngineIT {
  @Autowired private CommerceService commerce;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void external(DynamicPropertyRegistry properties) {
    String url = System.getenv("BE02_TEST_DB_URL");
    String broker = System.getenv("BE02_TEST_KAFKA");
    if (url == null || !url.startsWith("jdbc:postgresql://127.0.0.1:17442/") ||
        !"127.0.0.1:17982".equals(broker)) throw new IllegalStateException("Isolated CI loopback services required");
    properties.add("spring.datasource.url", () -> url);
    properties.add("spring.datasource.username", () -> System.getenv("BE02_TEST_DB_USER"));
    properties.add("spring.datasource.password", () -> System.getenv("BE02_TEST_DB_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    properties.add("spring.kafka.bootstrap-servers", () -> broker);
    properties.add("spring.kafka.admin.auto-create", () -> "true");
  }

  @Test
  @DisplayName("[OUT-01] [CON-01] CI service engines retain and deliver the same transactional event")
  void pipeline() throws Exception {
    UUID product = jdbc.queryForObject("SELECT id FROM products WHERE slug='daybreak-coffee'", UUID.class);
    UUID order = commerce.createOrder(new CommerceRequests.Order(List.of(new CommerceRequests.Item(product, 1)), null),
        UUID.randomUUID().toString(), "c".repeat(43), null).order().orderId();
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (jdbc.queryForObject("""
        SELECT COUNT(*) FROM consumer_inbox i JOIN outbox_event o ON o.event_id=i.event_id WHERE o.aggregate_id=?
        """, Integer.class, order) != 2) {
      if (System.nanoTime() > deadline) throw new AssertionError("External-engine delivery deadline exceeded");
      Thread.sleep(100);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_projection WHERE order_id=?", Integer.class, order))
        .isEqualTo(1);
  }
}
