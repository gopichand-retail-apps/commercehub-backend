package com.commercehub.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
  private final ExposedRequestIdFilter filter = new ExposedRequestIdFilter();

  @AfterEach
  void clearRequestContext() {
    MDC.clear();
  }

  @Test
  void rejectsUnsafeRequestIdsAndUsesValidTraceParentIds() throws Exception {
    MockHttpServletRequest request = request("/health");
    request.addHeader("X-Request-ID", "invalid request id");
    request.addHeader("traceparent", "00-ABCDEF0123456789ABCDEF0123456789-0123456789ABCDEF-01");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.filter(request, response, (incomingRequest, outgoingResponse) -> {});

    assertThat(response.getHeader("X-Request-ID")).matches("[a-f0-9-]{36}");
    assertThat(response.getHeader("X-Trace-ID")).isEqualTo("abcdef0123456789abcdef0123456789");
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
  }

  @Test
  void fallsBackForMalformedTraceParentsAndLogsServerFailuresSafely() throws Exception {
    MockHttpServletRequest request = request("/private/customer@example.invalid");
    request.addHeader("X-Request-ID", "safe-request-id");
    request.addHeader("traceparent", "malformed");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (incomingRequest, outgoingResponse) -> {
      MDC.put("errorCode", "INTERNAL_ERROR");
      ((HttpServletResponse) outgoingResponse).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    };

    filter.filter(request, response, chain);

    assertThat(response.getHeader("X-Request-ID")).isEqualTo("safe-request-id");
    assertThat(response.getHeader("X-Trace-ID")).matches("[a-f0-9]{32}");
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    assertThat(MDC.get("requestId")).isNull();
    assertThat(MDC.get("traceId")).isNull();
    assertThat(MDC.get("errorCode")).isNull();
  }

  private static MockHttpServletRequest request(String path) {
    return new MockHttpServletRequest("GET", path);
  }

  private static final class ExposedRequestIdFilter extends RequestIdFilter {
    ExposedRequestIdFilter() {
      super(buildProperties());
    }

    void filter(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {
      doFilterInternal(request, response, filterChain);
    }
  }

  private static BuildProperties buildProperties() {
    Properties properties = new Properties();
    properties.setProperty("version", "1.0.0");
    return new BuildProperties(properties);
  }
}
