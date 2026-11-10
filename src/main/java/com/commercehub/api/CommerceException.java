package com.commercehub.api;

import org.springframework.http.HttpStatus;

public class CommerceException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public CommerceException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }
}
