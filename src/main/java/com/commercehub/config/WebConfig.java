package com.commercehub.config;

import com.commercehub.security.GuestOrderRateLimitInterceptor;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final String[] frontendOrigins;
  private final GuestOrderRateLimitInterceptor guestOrderRateLimitInterceptor;

  public WebConfig(
      @Value("${commercehub.frontend-origins:http://127.0.0.1:5301,http://localhost:5301}") String origins,
      GuestOrderRateLimitInterceptor guestOrderRateLimitInterceptor) {
    this.frontendOrigins = Arrays.stream(origins.split(","))
        .map(String::trim)
        .filter(origin -> !origin.isEmpty())
        .toArray(String[]::new);
    this.guestOrderRateLimitInterceptor = guestOrderRateLimitInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(guestOrderRateLimitInterceptor)
        .addPathPatterns(
            "/api/v1/orders",
            "/api/v1/orders/quote",
            "/api/v1/orders/*",
            "/api/v1/sessions",
            "/api/v1/sessions/current");
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**")
      .allowedOrigins(frontendOrigins)
        .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
        .allowedHeaders(
            "Authorization",
            "Content-Type",
            "Idempotency-Key",
            "X-Order-Access-Key",
            "X-Request-ID",
            "traceparent")
        .exposedHeaders("X-Request-ID", "X-Trace-ID")
        .maxAge(600);
  }
}
