package com.commercehub.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercehub.security.GuestOrderRateLimitInterceptor;
import com.commercehub.security.GuestRequestRateLimiter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

class WebConfigTest {
  @Test
  void trimsAllowedOriginsAndDropsEmptyEntries() {
    GuestRequestRateLimiter limiter = new GuestRequestRateLimiter(2, 60);
    GuestOrderRateLimitInterceptor interceptor = new GuestOrderRateLimitInterceptor(limiter);
    WebConfig config = new WebConfig(" http://store.example.test, ,http://localhost:5301, ", interceptor);
    TestCorsRegistry registry = new TestCorsRegistry();

    config.addCorsMappings(registry);

    var cors = registry.configurations().get("/api/**");
    assertThat(cors.getAllowedOrigins())
        .containsExactly("http://store.example.test", "http://localhost:5301");
    assertThat(cors.getAllowedMethods()).containsExactly("GET", "POST", "DELETE", "OPTIONS");
    assertThat(cors.getMaxAge()).isEqualTo(600L);
  }

  private static final class TestCorsRegistry extends CorsRegistry {
    Map<String, CorsConfiguration> configurations() {
      return getCorsConfigurations();
    }
  }
}
