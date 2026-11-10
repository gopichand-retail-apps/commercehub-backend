package com.commercehub.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerTest {
  @Autowired
  private MockMvc mockMvc;

  @Test
  void reportsApplicationHealth() throws Exception {
    mockMvc.perform(get("/health"))
        .andExpect(status().isOk())
      .andExpect(header().exists("X-Request-ID"))
      .andExpect(header().exists("X-Trace-ID"))
        .andExpect(jsonPath("$.status").value("ok"))
        .andExpect(jsonPath("$.service").value("commercehub-api"));
  }

        @Test
        void reportsTheMavenApplicationVersion() throws Exception {
          mockMvc.perform(get("/api/v1/version"))
          .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.service").value("commercehub-api"))
          .andExpect(jsonPath("$.version").value("1.0.0"));
        }

  @Test
  void preservesAValidRequestId() throws Exception {
    mockMvc.perform(get("/health").header("X-Request-ID", "commercehub-test-01"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Request-ID", "commercehub-test-01"))
        .andExpect(header().exists("X-Trace-ID"));
  }
}
