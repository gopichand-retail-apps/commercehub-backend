package com.commercehub.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommerceApiTest {
  private static final String ACCESS_KEY = "A".repeat(43);
  private static final String MUG_SLUG = "dawn-stoneware-mug";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  void listsSyntheticProductsAndFiltersByCategory() throws Exception {
    mockMvc.perform(get("/api/v1/products"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.products.length()").value(8));

    mockMvc.perform(get("/api/v1/products").queryParam("category", "Home"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.products.length()").value(2));

    mockMvc.perform(get("/api/v1/products/everyday-daypack"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Everyday Daypack"));

    mockMvc.perform(get("/api/v1/products/market-canvas-tote"))
        .andExpect(status().isNotFound());
  }

    @Test
    void allowsPreflightFromBothExplicitLocalStorefrontOrigins() throws Exception {
        for (String origin : List.of("http://127.0.0.1:5301", "http://localhost:5301")) {
            mockMvc.perform(options("/api/v1/orders")
                            .header("Origin", origin)
                            .header("Access-Control-Request-Method", "POST")
                            .header(
                                "Access-Control-Request-Headers",
                                "authorization,content-type,idempotency-key,x-order-access-key,x-request-id"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void returnsNotFoundAndLogsOnlyAnUnmatchedRouteTemplate(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/private/customer@example.invalid/orders"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(output.getOut())
                .contains("\"routeTemplate\":\"/unmatched\"")
                .contains("\"errorCode\":\"NOT_FOUND\"")
                .doesNotContain("customer@example.invalid");
    }

  @Test
  void quotesOnTheServerAndRejectsUnknownPromoCodes() throws Exception {
    JsonNode mug = product(MUG_SLUG);
    String requestBody = orderBody(mug.path("id").asText(), 2, "HELLO10");

    mockMvc.perform(post("/api/v1/orders/quote")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quote.subtotalMinor").value(5_600))
        .andExpect(jsonPath("$.quote.discountMinor").value(560))
        .andExpect(jsonPath("$.quote.shippingMinor").value(495))
        .andExpect(jsonPath("$.quote.totalMinor").value(5_535));

    mockMvc.perform(post("/api/v1/orders/quote")
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderBody(mug.path("id").asText(), 1, "NOT-A-REAL-CODE")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("PROMO_CODE_INVALID"));
  }

  @Test
  void checkoutIsIdempotentAndOrderLookupRequiresItsAccessKey() throws Exception {
    JsonNode mugBefore = product(MUG_SLUG);
    int stockBefore = mugBefore.path("stockQuantity").asInt();
    String productId = mugBefore.path("id").asText();
    String idempotencyKey = UUID.randomUUID().toString();
    String requestBody = orderBody(productId, 1, null);

    MvcResult createdResult = mockMvc.perform(post("/api/v1/orders")
            .header("Idempotency-Key", idempotencyKey)
            .header("X-Order-Access-Key", ACCESS_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.duplicate").value(false))
        .andExpect(jsonPath("$.order.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.order.totalMinor").value(3_295))
        .andReturn();

    String orderId = objectMapper.readTree(createdResult.getResponse().getContentAsString())
        .path("order").path("orderId").asText();

    mockMvc.perform(post("/api/v1/orders")
            .header("Idempotency-Key", idempotencyKey)
            .header("X-Order-Access-Key", ACCESS_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(true));

    mockMvc.perform(get("/api/v1/orders/{id}", orderId))
        .andExpect(status().isNotFound());

    mockMvc.perform(get("/api/v1/orders/{id}", orderId)
            .header("X-Order-Access-Key", "B".repeat(43)))
        .andExpect(status().isNotFound());

    mockMvc.perform(get("/api/v1/orders/{id}", orderId)
            .header("X-Order-Access-Key", ACCESS_KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.orderId").value(orderId));

    mockMvc.perform(get("/api/v1/products/{slug}", MUG_SLUG))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stockQuantity").value(stockBefore - 1));
  }

  @Test
  void listsAndIssuesOnlyLocalSyntheticSessions() throws Exception {
    mockMvc.perform(get("/api/v1/sessions/roles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roles").isArray())
        .andExpect(jsonPath("$.roles.length()").value(3))
        .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
        .andExpect(jsonPath("$.roles[1]").value("MERCHANT"))
        .andExpect(jsonPath("$.roles[2]").value("SUPPORT"));

    MvcResult created = mockMvc.perform(post("/api/v1/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\":\"CUSTOMER\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(jsonPath("$.session.role").value("CUSTOMER"))
        .andExpect(jsonPath("$.session.token").isString())
        .andReturn();

    JsonNode session = objectMapper.readTree(created.getResponse().getContentAsString()).path("session");
    String sessionId = session.path("id").asText();
    String token = session.path("token").asText();
    String storedTokenHash = jdbc.queryForObject(
        "SELECT token_hash FROM synthetic_sessions WHERE id = ?", String.class, UUID.fromString(sessionId));

    assertThat(token).hasSize(43);
    assertThat(storedTokenHash).hasSize(64).isNotEqualTo(token);

    mockMvc.perform(get("/api/v1/sessions/current").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.session.id").value(sessionId))
        .andExpect(jsonPath("$.session.role").value("CUSTOMER"))
        .andExpect(jsonPath("$.session.token").doesNotExist());

    mockMvc.perform(post("/api/v1/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\":\"ADMIN\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_ROLE"));
  }

  @Test
  void bindsAuthenticatedOrdersToOwnerAndPreservesIdempotency() throws Exception {
    String ownerToken = createSessionToken("CUSTOMER");
    String otherToken = createSessionToken("CUSTOMER");
    JsonNode mugBefore = product(MUG_SLUG);
    String productId = mugBefore.path("id").asText();
    int stockBefore = mugBefore.path("stockQuantity").asInt();
    String idempotencyKey = UUID.randomUUID().toString();
    String requestBody = orderBody(productId, 1, null);

    MvcResult created = mockMvc.perform(post("/api/v1/orders")
            .header("Idempotency-Key", idempotencyKey)
            .header("Authorization", "Bearer " + ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.duplicate").value(false))
        .andReturn();
    String orderId = objectMapper.readTree(created.getResponse().getContentAsString())
        .path("order").path("orderId").asText();

    mockMvc.perform(post("/api/v1/orders")
            .header("Idempotency-Key", idempotencyKey)
            .header("Authorization", "Bearer " + ownerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicate").value(true));

    mockMvc.perform(get("/api/v1/orders/{id}", orderId)
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

    mockMvc.perform(post("/api/v1/orders")
            .header("Idempotency-Key", idempotencyKey)
            .header("Authorization", "Bearer " + otherToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

    mockMvc.perform(get("/api/v1/orders/{id}", orderId)
            .header("Authorization", "Bearer " + ownerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.orderId").value(orderId));

    mockMvc.perform(get("/api/v1/products/{slug}", MUG_SLUG))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stockQuantity").value(stockBefore - 1));
  }

  @Test
  void restrictsCustomerOrderWorkflowBySyntheticRole() throws Exception {
    String customerToken = createSessionToken("CUSTOMER");
    String merchantToken = createSessionToken("MERCHANT");
    String supportToken = createSessionToken("SUPPORT");
    JsonNode mug = product(MUG_SLUG);
    String requestBody = orderBody(mug.path("id").asText(), 1, null);
    MvcResult created = mockMvc.perform(post("/api/v1/orders")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Authorization", "Bearer " + customerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
        .andExpect(status().isCreated())
        .andReturn();
    String orderId = objectMapper.readTree(created.getResponse().getContentAsString())
        .path("order").path("orderId").asText();

    mockMvc.perform(post("/api/v1/orders")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Authorization", "Bearer " + merchantToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"));

    mockMvc.perform(get("/api/v1/orders/{id}", orderId)
            .header("Authorization", "Bearer " + supportToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ROLE_FORBIDDEN"));
  }

  private String createSessionToken(String role) throws Exception {
    MvcResult result = mockMvc.perform(post("/api/v1/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\":\"" + role + "\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).path("session").path("token").asText();
  }

  private JsonNode product(String slug) throws Exception {
    MvcResult result = mockMvc.perform(get("/api/v1/products/{slug}", slug))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private static String orderBody(String productId, int quantity, String promoCode) {
    String promo = promoCode == null ? "" : ",\"promoCode\":\"" + promoCode + "\"";
    return "{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":"
        + quantity + "}]" + promo + "}";
  }
}
