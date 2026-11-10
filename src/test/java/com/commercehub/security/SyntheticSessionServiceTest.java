package com.commercehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercehub.api.CommerceException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SyntheticSessionServiceTest {
  private static final UUID SESSION_ID = UUID.fromString("80a6da1d-80e9-4ad0-9b05-a090ff3c4c57");
  private static final String TOKEN = "A".repeat(43);
  private static final Instant EXPIRY = Instant.parse("2030-01-01T00:00:00Z");

  @Test
  void rejectsZeroAndNegativeSessionLifetimes() {
    SyntheticSessionRepository repository = mock(SyntheticSessionRepository.class);

    assertThatThrownBy(() -> new SyntheticSessionService(repository, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SyntheticSessionService(repository, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void treatsOptionalAndRequiredAuthorizationDifferently() {
    SyntheticSessionRepository repository = mock(SyntheticSessionRepository.class);
    SyntheticSessionService service = new SyntheticSessionService(repository, Duration.ofHours(1));

    assertThat(service.optionalPrincipal(null)).isNull();
    assertThatThrownBy(() -> service.requiredPrincipal(null))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> {
          assertThat(((CommerceException) error).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
          assertThat(((CommerceException) error).code()).isEqualTo("SESSION_REQUIRED");
        });
    assertThatThrownBy(() -> service.requiredPrincipal("  "))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("SESSION_REQUIRED"));
    assertThatThrownBy(() -> service.optionalPrincipal("Basic " + TOKEN))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("SESSION_INVALID"));
  }

  @Test
  void createsRandomSessionsAndStoresOnlyTheirHash() {
    SyntheticSessionRepository repository = mock(SyntheticSessionRepository.class);
    SyntheticSessionService service = new SyntheticSessionService(repository, Duration.ofHours(1));

    var session = service.create("CUSTOMER");

    assertThat(session.role()).isEqualTo("CUSTOMER");
    assertThat(session.token()).matches("[A-Za-z0-9_-]{43}");
    assertThat(session.expiresAt()).isAfter(Instant.now());
    org.mockito.ArgumentCaptor<String> hash = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(repository).insert(eq(session.id()), eq(SyntheticRole.CUSTOMER), hash.capture(), eq(session.expiresAt()));
    assertThat(hash.getValue()).matches("[a-f0-9]{64}").isNotEqualTo(session.token());
    assertThatThrownBy(() -> service.create("UNKNOWN"))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("INVALID_ROLE"));
    assertThatThrownBy(() -> SyntheticRole.parse(null))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("INVALID_ROLE"));
  }

  @Test
  void authenticatesCurrentSessionsAndRevokesTheirRepositoryRecords() {
    SyntheticSessionRepository repository = mock(SyntheticSessionRepository.class);
    SyntheticPrincipal principal = new SyntheticPrincipal(SESSION_ID, SyntheticRole.CUSTOMER);
    when(repository.findActive(anyString(), any())).thenReturn(Optional.of(principal));
    when(repository.findExpiry(SESSION_ID)).thenReturn(Optional.of(EXPIRY));
    SyntheticSessionService service = new SyntheticSessionService(repository, Duration.ofHours(1));

    assertThat(service.optionalPrincipal("Bearer " + TOKEN)).isEqualTo(principal);
    assertThat(service.current("Bearer " + TOKEN))
        .isEqualTo(new SyntheticSessionService.SessionStatus(SESSION_ID, "CUSTOMER", EXPIRY));
    service.revoke("Bearer " + TOKEN);

    verify(repository).revoke(eq(SESSION_ID), any());
  }

  @Test
  void rejectsUnknownOrExpiredSessionsWithoutRevealingTheirState() {
    SyntheticSessionRepository repository = mock(SyntheticSessionRepository.class);
    SyntheticPrincipal principal = new SyntheticPrincipal(SESSION_ID, SyntheticRole.CUSTOMER);
    when(repository.findActive(anyString(), any())).thenReturn(Optional.empty(), Optional.of(principal));
    when(repository.findExpiry(SESSION_ID)).thenReturn(Optional.empty());
    SyntheticSessionService service = new SyntheticSessionService(repository, Duration.ofHours(1));

    assertThatThrownBy(() -> service.requiredPrincipal("Bearer " + TOKEN))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("SESSION_INVALID"));
    assertThatThrownBy(() -> service.current("Bearer " + TOKEN))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("SESSION_INVALID"));
    verify(repository, never()).findActive(eq("invalid"), any());
  }
}
