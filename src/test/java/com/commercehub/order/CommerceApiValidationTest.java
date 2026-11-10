package com.commercehub.order;

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

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:commercehub_coverage;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "commercehub.rate-limit.max-requests=1000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommerceApiValidationTest {
  private static final String ACCESS_KEY = "C".repeat(43);
  private static final String IDEMPOTENCY_KEY = "coverage-order-001";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void validatesSearchQuoteAndMergedQuantityInputs() throws Exception {
    mockMvc.perform(get("/api/v1/products").queryParam("q", " COFFEE "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.products.length()").value(1));
    mockMvc.perform(get("/api/v1/products").queryParam("q", "").queryParam("category", " "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.products.length()").value(8));

    String productId = productId();
    mockMvc.perform(post("/api/v1/orders/quote")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    mockMvc.perform(post("/api/v1/orders/quote")
            .contentType(MediaType.APPLICATION_JSON)
            .content(duplicateItemsBody(productId)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));
    mockMvc.perform(post("/api/v1/orders/quote")
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(UUID.randomUUID().toString(), 1)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
  }

  @Test
  void validatesGuestCheckoutHeadersAndRejectsChangedIdempotentRequests() throws Exception {
    String productId = productId();
    String firstBody = orderBody(productId, 1);
    mockMvc.perform(post("/api/v1/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(firstBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    mockMvc.perform(post("/api/v1/orders")
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(firstBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("ORDER_ACCESS_KEY_REQUIRED"));

    MvcResult created = mockMvc.perform(post("/api/v1/orders")
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .header("X-Order-Access-Key", ACCESS_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(firstBody))
        .andExpect(status().isCreated())
        .andReturn();
    JsonNode order = objectMapper.readTree(created.getResponse().getContentAsString()).path("order");
    String orderId = order.path("orderId").asText();

    mockMvc.perform(post("/api/v1/orders")
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .header("X-Order-Access-Key", ACCESS_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(productId, 2)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

    mockMvc.perform(get("/api/v1/orders/{orderId}", orderId)
            .header("X-Order-Access-Key", ACCESS_KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.orderId").value(orderId));

    MvcResult sessionResult = mockMvc.perform(post("/api/v1/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\":\"CUSTOMER\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    String sessionToken = objectMapper.readTree(sessionResult.getResponse().getContentAsString())
        .path("session").path("token").asText();
    MvcResult authenticatedOrder = mockMvc.perform(post("/api/v1/orders")
            .header("Idempotency-Key", "coverage-owner-001")
            .header("Authorization", "Bearer " + sessionToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(firstBody))
        .andExpect(status().isCreated())
        .andReturn();
    String authenticatedOrderId = objectMapper.readTree(authenticatedOrder.getResponse().getContentAsString())
        .path("order").path("orderId").asText();
    mockMvc.perform(get("/api/v1/orders/{orderId}", authenticatedOrderId)
            .header("X-Order-Access-Key", ACCESS_KEY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
  }

  private String productId() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/v1/products/dawn-stoneware-mug"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
  }

  private static String orderBody(String productId, int quantity) {
    return "{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + "}]}";
  }

  private static String duplicateItemsBody(String productId) {
    return "{\"items\":["
        + "{\"productId\":\"" + productId + "\",\"quantity\":11},"
        + "{\"productId\":\"" + productId + "\",\"quantity\":10}]}";
  }
}
