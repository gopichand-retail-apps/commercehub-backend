package com.commercehub.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class ApiExceptionHandlerTest {
  private final ApiExceptionHandler handler = new ApiExceptionHandler();

  @AfterEach
  void clearRequestContext() {
    MDC.clear();
  }

  @Test
  void mapsCommerceExceptionsToTheirSafeErrorEnvelope() {
    MDC.put("requestId", "request-123");

    var response = handler.handleCommerceException(
        new CommerceException(HttpStatus.CONFLICT, "STOCK_CHANGED", "Stock changed"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(new ApiErrorResponse("STOCK_CHANGED", "Stock changed", "request-123"));
    assertThat(MDC.get("errorCode")).isEqualTo("STOCK_CHANGED");
  }

  @Test
  void mapsInvalidRequestsAndMissingResourcesWithoutLeakingDetails() {
    MDC.put("requestId", "request-456");

    var invalidResponse = handler.handleInvalidRequest(new IllegalArgumentException("private detail"));
    var missingResponse = handler.handleNotFound(
        new NoResourceFoundException(HttpMethod.GET, "/private/customer"));

    assertThat(invalidResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(invalidResponse.getBody())
        .isEqualTo(new ApiErrorResponse("INVALID_REQUEST", "Review the request and try again", "request-456"));
    assertThat(missingResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(missingResponse.getBody())
        .isEqualTo(new ApiErrorResponse("NOT_FOUND", "Resource was not found", "request-456"));
  }

  @Test
  void mapsUnexpectedFailuresToAGenericServerError() {
    MDC.put("requestId", "request-789");

    var response = handler.handleUnexpected(new IllegalStateException("private detail"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody())
        .isEqualTo(new ApiErrorResponse(
            "INTERNAL_ERROR",
            "The request could not be completed",
            "request-789"));
    assertThat(response.getBody().message()).doesNotContain("private detail");
  }
}
