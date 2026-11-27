package com.commercehub.events;

import com.commercehub.api.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "commercehub.events.enabled", havingValue = "true")
public class AdminOriginFilter extends OncePerRequestFilter {
  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(AdminOriginFilter.class);
  private final ObjectMapper json;
  private final String origin;

  public AdminOriginFilter(ObjectMapper json, @Value("${commercehub.events.operator-origin}") String origin) {
    this.json = json;
    this.origin = origin;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader("Origin");
    boolean foreign = supplied != null && (!origin.equals(supplied)
        || Collections.list(request.getHeaders("Origin")).size() != 1);
    if (request.getRequestURI().startsWith("/api/v1/admin/") && foreign) {
      String requestId = UUID.randomUUID().toString();
      response.setStatus(403);
      response.setContentType("application/json");
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("X-Content-Type-Options", "nosniff");
      json.writeValue(response.getOutputStream(), new ApiErrorResponse("ORIGIN_NOT_ALLOWED",
          "This request origin is not allowed", requestId));
      LOGGER.atWarn().addKeyValue("eventName", "api.request.failed").addKeyValue("requestId", requestId)
          .addKeyValue("routeTemplate", "/api/v1/admin/*").addKeyValue("httpStatus", 403)
          .addKeyValue("errorCode", "ORIGIN_NOT_ALLOWED").log("Local operator request denied");
      return;
    }
    chain.doFilter(request, response);
  }
}
