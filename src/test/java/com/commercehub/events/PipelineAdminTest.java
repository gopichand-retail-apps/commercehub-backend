package com.commercehub.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.commercehub.security.SyntheticSessionService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"commercehub.events.enabled=true", "commercehub.events.workers-enabled=false",
    "spring.datasource.url=jdbc:h2:mem:be02_admin;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PipelineAdminTest {
  private static final String HOST = "127.0.0.1:17302";
  @Autowired private MockMvc api;
  @Autowired private SyntheticSessionService sessions;
  @MockitoBean private BrokerProbe broker;

  @Test
  @DisplayName("[ADM-01] only loopback Support may inspect pipeline state")
  void roles() throws Exception {
    String path = "/api/v1/admin/outbox";
    api.perform(get(path).header("Host", HOST)).andExpect(status().isUnauthorized());
    for (String role : java.util.List.of("CUSTOMER", "MERCHANT")) {
      String token = sessions.create(role).token();
      api.perform(get(path).header("Host", HOST).header("Authorization", "Bearer " + token))
          .andExpect(status().isForbidden());
    }
    String token = sessions.create("SUPPORT").token();
    api.perform(get(path).header("Host", HOST).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    api.perform(get(path).header("Host", "evil.test").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    api.perform(get(path).header("Host", HOST).header("Authorization", "Bearer " + token)
        .with(request -> { request.setRemoteAddr("192.0.2.1"); return request; })).andExpect(status().isForbidden());
    when(broker.sample()).thenReturn(
        new BrokerProbe.Sample(false, Map.of(), "BROKER_STATUS_UNAVAILABLE", Instant.now()));
    String body = api.perform(get("/api/v1/admin/event-pipeline/health").header("Host", HOST)
        .header("Authorization", "Bearer " + token)).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertThat(body).contains("BROKER_STATUS_UNAVAILABLE").doesNotContain("password", "payload_json", "Bearer");
  }

  @Test
  @DisplayName("[ADM-02] replay validates origin, UUID, body, request key and abuse budget")
  void validation() throws Exception {
    String token = sessions.create("SUPPORT").token();
    String path = "/api/v1/admin/event-pipeline/retry/";
    api.perform(post(path + UUID.randomUUID()).header("Host", HOST).header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    api.perform(post(path + "bad").header("Host", HOST).header("Authorization", "Bearer " + token)
        .header("Origin", "http://" + HOST)).andExpect(status().isBadRequest());
    api.perform(post(path + UUID.randomUUID()).header("Host", HOST).header("Authorization", "Bearer " + token)
        .header("Origin", "http://" + HOST).content("{}"))
        .andExpect(status().isBadRequest());
    for (int index = 0; index < 6; index++) {
      api.perform(post(path + UUID.randomUUID()).header("Host", HOST).header("Authorization", "Bearer " + token)
          .header("Origin", "http://" + HOST).header("Idempotency-Key", "synthetic-replay-key"))
          .andExpect(index == 5 ? status().isTooManyRequests() : status().isNotFound());
    }
    api.perform(get("/api/v1/admin/outbox?state=bad").header("Host", HOST).header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
    api.perform(get("/api/v1/admin/outbox?page=bad").header("Host", HOST).header("Authorization", "Bearer " + token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void budgetsAreBoundedAndExpiredWithoutEvictingActiveAccounts() {
    ReplayLimiter limiter = new ReplayLimiter();
    UUID actor = UUID.randomUUID();
    for (int index = 0; index < 5; index++) assertThat(limiter.allow(actor, 0)).isTrue();
    assertThat(limiter.allow(actor, 1)).isFalse();
    for (int index = 1; index < 4096; index++) assertThat(limiter.allow(UUID.randomUUID(), 1)).isTrue();
    assertThat(limiter.allow(UUID.randomUUID(), 1)).isFalse();
    assertThat(limiter.allow(actor, 60_001)).isTrue();
    var admin = mock(PipelineAdmin.class);
    var sessions = mock(SyntheticSessionService.class);
    for (String invalid : java.util.List.of("https://127.0.0.1:17302", "http://external.test", "http://localhost/")) {
      assertThatThrownBy(() -> new PipelineController(admin, sessions, limiter, invalid, broker))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @DisplayName("[ADM-03] Origin denial before MVC still follows the JSON error contract")
  void originDenial() throws Exception {
    api.perform(post("/api/v1/admin/event-pipeline/retry/" + UUID.randomUUID())
        .header("Host", HOST).header("Origin", "https://foreign.test"))
        .andExpect(status().isForbidden())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
            .value("ORIGIN_NOT_ALLOWED"));
    api.perform(get("/api/v1/admin/outbox").header("Host", HOST)
        .header("Origin", "http://" + HOST, "https://foreign.test"))
        .andExpect(status().isForbidden());
    api.perform(get("/health").header("Origin", "https://foreign.test")).andExpect(status().isOk());
  }

  @Test
  void controllerPassesOnlyServerVerifiedActorToIdempotentReplay() {
    var admin = mock(PipelineAdmin.class);
    var identity = mock(SyntheticSessionService.class);
    UUID actor = UUID.randomUUID();
    UUID event = UUID.randomUUID();
    when(identity.requiredPrincipal("Bearer fixture")).thenReturn(
        new com.commercehub.security.SyntheticPrincipal(actor, com.commercehub.security.SyntheticRole.SUPPORT));
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.setRemoteAddr("::1");
    request.addHeader("Host", HOST);
    request.addHeader("Origin", "http://" + HOST);
    request.addHeader("Authorization", "Bearer fixture");
    request.addHeader("Idempotency-Key", "synthetic-request");
    var controller = new PipelineController(admin, identity, new ReplayLimiter(), "http://" + HOST, broker);
    assertThat(controller.retry(request, event.toString()).getStatusCode().value()).isEqualTo(200);
    org.mockito.Mockito.verify(admin).retry(event, "synthetic-request", actor);
    assertThatThrownBy(() -> controller.retry(request, event.toString().toUpperCase()))
        .isInstanceOf(com.commercehub.api.CommerceException.class);
    request.addHeader("Transfer-Encoding", "chunked");
    assertThatThrownBy(() -> controller.retry(request, event.toString()))
        .isInstanceOf(com.commercehub.api.CommerceException.class);
  }
}
