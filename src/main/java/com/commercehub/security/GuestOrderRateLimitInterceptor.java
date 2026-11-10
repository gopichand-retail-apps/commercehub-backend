package com.commercehub.security;

import com.commercehub.api.CommerceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class GuestOrderRateLimitInterceptor implements HandlerInterceptor {
  private final GuestRequestRateLimiter rateLimiter;

  public GuestOrderRateLimitInterceptor(GuestRequestRateLimiter rateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request,
      HttpServletResponse response,
      Object handler) {
    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
      return true;
    }
    if (rateLimiter.allowRequest(request.getRemoteAddr())) {
      return true;
    }
    throw new CommerceException(
        HttpStatus.TOO_MANY_REQUESTS,
        "RATE_LIMITED",
        "Too many requests. Please try again later.");
  }
}
