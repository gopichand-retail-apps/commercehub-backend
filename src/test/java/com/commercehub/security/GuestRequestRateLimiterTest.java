package com.commercehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GuestRequestRateLimiterTest {
  @Test
  void enforcesBurstLimitAndRefillsAtTheConfiguredRate() {
    AtomicLong now = new AtomicLong();
    GuestRequestRateLimiter limiter = new GuestRequestRateLimiter(2, 10, now::get);

    assertThat(limiter.allowRequest("192.0.2.10")).isTrue();
    assertThat(limiter.allowRequest("192.0.2.10")).isTrue();
    assertThat(limiter.allowRequest("192.0.2.10")).isFalse();

    now.addAndGet(TimeUnit.SECONDS.toNanos(5));

    assertThat(limiter.allowRequest("192.0.2.10")).isTrue();
    assertThat(limiter.allowRequest("192.0.2.10")).isFalse();
  }

  @Test
  void tracksDifferentNetworkPeersIndependently() {
    GuestRequestRateLimiter limiter = new GuestRequestRateLimiter(1, 60, () -> 0L);

    assertThat(limiter.allowRequest("192.0.2.10")).isTrue();
    assertThat(limiter.allowRequest("192.0.2.10")).isFalse();
    assertThat(limiter.allowRequest("192.0.2.11")).isTrue();
  }

  @Test
  void rejectsNonpositiveConfigurationAndUsesOneBucketForUnknownPeers() {
    assertThatThrownBy(() -> new GuestRequestRateLimiter(0, 60, () -> 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GuestRequestRateLimiter(1, 0, () -> 0L))
        .isInstanceOf(IllegalArgumentException.class);

    AtomicLong now = new AtomicLong(100L);
    GuestRequestRateLimiter limiter = new GuestRequestRateLimiter(1, 1, now::get);
    assertThat(limiter.allowRequest(null)).isTrue();
    assertThat(limiter.allowRequest("  ")).isFalse();
    now.set(50L);
    assertThat(limiter.allowRequest(null)).isFalse();
    now.set(1_000_000_100L);
    assertThat(limiter.allowRequest(null)).isTrue();
  }

  @Test
  void evictsTheLeastRecentlyUsedPeerWhenTheBoundedTableIsFull() {
    GuestRequestRateLimiter limiter = new GuestRequestRateLimiter(1, 60, () -> 0L);

    for (int client = 0; client <= 4_096; client++) {
      assertThat(limiter.allowRequest("192.0.2." + client)).isTrue();
    }

    assertThat(limiter.allowRequest("192.0.2.4096")).isFalse();
    assertThat(limiter.allowRequest("192.0.2.0")).isTrue();
  }
}
