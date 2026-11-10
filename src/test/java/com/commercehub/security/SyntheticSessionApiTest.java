package com.commercehub.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SyntheticSessionApiTest {
  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void requiresAValidSessionAndReturnsGenericAuthenticationErrors() throws Exception {
    mockMvc.perform(get("/api/v1/sessions/current"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));

    mockMvc.perform(get("/api/v1/sessions/current").header("Authorization", "Basic invalid"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SESSION_INVALID"));

    mockMvc.perform(post("/api/v1/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\":\" \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void revokesSessionsAndPreventsTheirReuse() throws Exception {
    MvcResult created = mockMvc.perform(post("/api/v1/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\":\"CUSTOMER\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    JsonNode session = objectMapper.readTree(created.getResponse().getContentAsString()).path("session");
    String token = session.path("token").asText();

    mockMvc.perform(delete("/api/v1/sessions/current").header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent())
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    mockMvc.perform(get("/api/v1/sessions/current").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SESSION_INVALID"));
  }
}
