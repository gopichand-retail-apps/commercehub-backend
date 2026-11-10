package com.commercehub.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(CommerceException.class)
  public ResponseEntity<ApiErrorResponse> handleCommerceException(CommerceException exception) {
    MDC.put("errorCode", exception.code());
    return response(exception.status(), exception.code(), exception.getMessage());
  }

  @ExceptionHandler({
      MethodArgumentNotValidException.class,
      HandlerMethodValidationException.class,
      ConstraintViolationException.class,
      MissingRequestHeaderException.class
  })
  public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {
    MDC.put("errorCode", "INVALID_REQUEST");
    return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Review the request and try again");
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleNotFound(NoResourceFoundException exception) {
    MDC.put("errorCode", "NOT_FOUND");
    return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource was not found");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
    MDC.put("errorCode", "INTERNAL_ERROR");
    LOGGER.atError()
        .addKeyValue("eventName", "api.request.failed")
        .addKeyValue("errorCode", "INTERNAL_ERROR")
        .setCause(exception)
        .log("Unhandled request failure");
    return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not be completed");
  }

  private static ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .body(new ApiErrorResponse(code, message, MDC.get("requestId")));
  }
}
