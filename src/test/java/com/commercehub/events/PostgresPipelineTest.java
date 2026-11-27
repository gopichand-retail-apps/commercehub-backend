package com.commercehub.events;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DirtiesContext
class PostgresPipelineTest extends OutboxWorkflowTest {
  private static EmbeddedPostgres postgres;

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry properties) throws Exception {
    Path root = Path.of("target", "be02-postgres", UUID.randomUUID().toString()).toAbsolutePath();
    Files.createDirectories(root.resolve("native"));
    postgres = EmbeddedPostgres.builder().setPort(17432).setDataDirectory(root.resolve("data"))
        .setOverrideWorkingDirectory(root.resolve("native").toFile()).setCleanDataDirectory(false)
        .setServerConfig("listen_addresses", "127.0.0.1").start();
    properties.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:17432/postgres");
    properties.add("spring.datasource.username", () -> "postgres");
    properties.add("spring.datasource.password", () -> "");
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @AfterAll
  static void closePostgres() throws Exception { if (postgres != null) postgres.close(); }
}
