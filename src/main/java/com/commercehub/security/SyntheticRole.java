package com.commercehub.security;

import com.commercehub.api.CommerceException;
import org.springframework.http.HttpStatus;

public enum SyntheticRole {
  CUSTOMER,
  MERCHANT,
  SUPPORT;

  public static SyntheticRole parse(String code) {
    try {
      return valueOf(code);
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new CommerceException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "Choose an available demo role");
    }
  }
}
