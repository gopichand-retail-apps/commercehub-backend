package com.commercehub.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GuestRequestRateLimiter {
  private static final int MAX_TRACKED_CLIENTS = 4096;
  private static final String UNKNOWN_CLIENT = "unknown";

  private final int capacity;
  private final double refillPerNano;
  private final long windowNanos;
  private final LongSupplier nanoTime;
  private final LinkedHashMap<String, Bucket> buckets = new LinkedHashMap<>(16, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
      return size() > MAX_TRACKED_CLIENTS;
    }
  };

  @Autowired
  public GuestRequestRateLimiter(
      @Value("${commercehub.rate-limit.max-requests:30}") int maxRequests,
      @Value("${commercehub.rate-limit.window-seconds:60}") int windowSeconds) {
    this(maxRequests, windowSeconds, System::nanoTime);
  }

  GuestRequestRateLimiter(int maxRequests, int windowSeconds, LongSupplier nanoTime) {
    if (maxRequests < 1 || windowSeconds < 1) {
      throw new IllegalArgumentException("Rate limit values must be positive");
    }
    this.capacity = maxRequests;
    this.windowNanos = TimeUnit.SECONDS.toNanos(windowSeconds);
    this.refillPerNano = (double) maxRequests / windowNanos;
    this.nanoTime = Objects.requireNonNull(nanoTime);
  }

  public synchronized boolean allowRequest(String clientAddress) {
    long now = nanoTime.getAsLong();
    String clientKey = clientAddress == null || clientAddress.isBlank()
        ? UNKNOWN_CLIENT
        : clientAddress;
    Bucket bucket = buckets.get(clientKey);
    if (bucket == null) {
      bucket = new Bucket(capacity, now);
      buckets.put(clientKey, bucket);
    } else {
      bucket.refill(now, capacity, refillPerNano);
    }
    if (bucket.availableTokens < 1.0d) {
      return false;
    }
    bucket.availableTokens -= 1.0d;
    return true;
  }

  private static final class Bucket {
    private double availableTokens;
    private long lastRefillNanos;

    private Bucket(int capacity, long now) {
      this.availableTokens = capacity;
      this.lastRefillNanos = now;
    }

    private void refill(long now, int capacity, double refillPerNano) {
      long elapsedNanos = now - lastRefillNanos;
      if (elapsedNanos <= 0L) {
        return;
      }
      availableTokens = Math.min(capacity, availableTokens + elapsedNanos * refillPerNano);
      lastRefillNanos = now;
    }
  }
}
