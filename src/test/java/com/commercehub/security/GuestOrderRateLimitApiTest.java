package com.commercehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
    "commercehub.rate-limit.max-requests=2",
    "commercehub.rate-limit.window-seconds=60"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuestOrderRateLimitApiTest {
  private static final String PEER_ADDRESS = "192.0.2.20";
  private static final String ACCESS_KEY = "A".repeat(43);

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void limitsGuestOrderRoutesBySocketPeerAndLeavesHealthChecksAvailable() throws Exception {
    String productId = productId();
    mockMvc.perform(quoteRequest(productId, PEER_ADDRESS, "198.51.100.10"))
        .andExpect(status().isOk());
    mockMvc.perform(quoteRequest(productId, PEER_ADDRESS, "198.51.100.11"))
        .andExpect(status().isOk());

    MvcResult limited = mockMvc.perform(quoteRequest(productId, PEER_ADDRESS, "203.0.113.12"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(jsonPath("$.message").value("Too many requests. Please try again later."))
        .andExpect(jsonPath("$.details").doesNotExist())
        .andReturn();
    String responseRequestId = limited.getResponse().getHeader("X-Request-ID");
    JsonNode error = objectMapper.readTree(limited.getResponse().getContentAsString());
    assertThat(responseRequestId).isNotBlank();
    assertThat(error.path("requestId").asText()).isEqualTo(responseRequestId);

    mockMvc.perform(quoteRequest(productId, "192.0.2.21", null))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/v1/orders")
            .with(fromPeer(PEER_ADDRESS))
            .header("Idempotency-Key", "rate-limit-test-001")
            .header("X-Order-Access-Key", ACCESS_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(productId)))
        .andExpect(status().isTooManyRequests());
    mockMvc.perform(get("/api/v1/orders/{orderId}", UUID.randomUUID())
            .with(fromPeer(PEER_ADDRESS))
            .header("X-Order-Access-Key", ACCESS_KEY))
        .andExpect(status().isTooManyRequests());

    for (int attempt = 0; attempt < 3; attempt++) {
      mockMvc.perform(get("/health").with(fromPeer(PEER_ADDRESS)))
          .andExpect(status().isOk());
    }
  }

  private String productId() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/v1/products/dawn-stoneware-mug"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
  }

  private static MockHttpServletRequestBuilder quoteRequest(
      String productId,
      String peerAddress,
      String forwardedAddress) {
    MockHttpServletRequestBuilder request = post("/api/v1/orders/quote")
        .with(fromPeer(peerAddress))
        .contentType(MediaType.APPLICATION_JSON)
        .content(orderBody(productId));
    if (forwardedAddress != null) {
      request.header("X-Forwarded-For", forwardedAddress);
      request.header("Forwarded", "for=" + forwardedAddress);
    }
    return request;
  }

  private static RequestPostProcessor fromPeer(String peerAddress) {
    return request -> {
      request.setRemoteAddr(peerAddress);
      return request;
    };
  }

  private static String orderBody(String productId) {
    return "{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":1}]}";
  }
}
