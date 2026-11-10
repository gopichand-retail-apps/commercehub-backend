package com.commercehub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
  private static final Logger LOGGER = LoggerFactory.getLogger(RequestIdFilter.class);
  private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,80}");
  private static final Pattern TRACEPARENT = Pattern.compile(
      "00-([a-fA-F0-9]{32})-[a-fA-F0-9]{16}-[a-fA-F0-9]{2}");
  private final BuildProperties buildProperties;

  public RequestIdFilter(BuildProperties buildProperties) {
    this.buildProperties = buildProperties;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {
    String requestId = resolveRequestId(request.getHeader("X-Request-ID"));
    String traceId = resolveTraceId(request.getHeader("traceparent"));
    long startedAt = System.nanoTime();
    MDC.put("requestId", requestId);
    MDC.put("traceId", traceId);
    response.setHeader("X-Request-ID", requestId);
    response.setHeader("X-Trace-ID", traceId);
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Referrer-Policy", "no-referrer");

    try {
      filterChain.doFilter(request, response);
    } finally {
      logCompletion(request, response, startedAt);
      MDC.remove("requestId");
      MDC.remove("traceId");
      MDC.remove("errorCode");
    }
  }

  private static String resolveRequestId(String provided) {
    if (provided != null && SAFE_REQUEST_ID.matcher(provided).matches()) {
      return provided;
    }
    return UUID.randomUUID().toString();
  }

  private static String resolveTraceId(String traceparent) {
    if (traceparent != null) {
      Matcher matcher = TRACEPARENT.matcher(traceparent);
      if (matcher.matches()) {
        return matcher.group(1).toLowerCase(Locale.ROOT);
      }
    }
    return UUID.randomUUID().toString().replace("-", "");
  }

  private void logCompletion(
      HttpServletRequest request,
      HttpServletResponse response,
      long startedAt) {
    int status = response.getStatus();
    Level level = status >= 500 ? Level.ERROR : status >= 400 ? Level.WARN : Level.INFO;
    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    var event = LOGGER.atLevel(level)
        .addKeyValue("eventName", status >= 400 ? "api.request.failed" : "api.request.completed")
      .addKeyValue("version", buildProperties.getVersion())
        .addKeyValue("routeTemplate", routeTemplate(request.getRequestURI()))
        .addKeyValue("httpMethod", request.getMethod())
        .addKeyValue("httpStatus", status)
        .addKeyValue("durationMs", durationMs)
        .addKeyValue("outcome", status >= 400 ? "failure" : "success");
    String errorCode = MDC.get("errorCode");
    if (errorCode != null) {
      event.addKeyValue("errorCode", errorCode);
    }
    event.log("HTTP request completed");
  }

  private static String routeTemplate(String path) {
    if (path.equals("/health")) {
      return path;
    }
    if (path.equals("/api/v1/version")) {
      return path;
    }
    if (path.equals("/api/v1/products") || path.startsWith("/api/v1/products/")) {
      return path.equals("/api/v1/products") ? path : "/api/v1/products/{slug}";
    }
    if (path.equals("/api/v1/orders/quote")) {
      return path;
    }
    if (path.equals("/api/v1/sessions/roles")) {
      return path;
    }
    if (path.equals("/api/v1/sessions")) {
      return path;
    }
    if (path.equals("/api/v1/sessions/current")) {
      return "/api/v1/sessions/current";
    }
    if (path.startsWith("/api/v1/orders/")) {
      return "/api/v1/orders/{orderId}";
    }
    return "/unmatched";
  }
}
