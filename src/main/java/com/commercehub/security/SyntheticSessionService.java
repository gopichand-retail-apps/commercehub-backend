package com.commercehub.security;

import com.commercehub.api.CommerceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SyntheticSessionService {
  private static final Logger LOGGER = LoggerFactory.getLogger(SyntheticSessionService.class);
  private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)^Bearer ([A-Za-z0-9_-]{43})$");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SyntheticSessionRepository sessions;
  private final Duration sessionTtl;

  public SyntheticSessionService(
      SyntheticSessionRepository sessions,
      @Value("${commercehub.synthetic-session-ttl:PT8H}") Duration sessionTtl) {
    if (sessionTtl.isZero() || sessionTtl.isNegative()) {
      throw new IllegalArgumentException("Synthetic session lifetime must be positive");
    }
    this.sessions = sessions;
    this.sessionTtl = sessionTtl;
  }

  public IssuedSession create(String roleCode) {
    SyntheticRole role = SyntheticRole.parse(roleCode);
    UUID id = UUID.randomUUID();
    Instant expiresAt = Instant.now().plus(sessionTtl);
    String token = createToken();
    sessions.insert(id, role, hash(token), expiresAt);
    logSessionEvent("synthetic.session.created", id, role);
    return new IssuedSession(id, role.name(), token, expiresAt);
  }

  public SyntheticPrincipal optionalPrincipal(String authorization) {
    return authorization == null ? null : authenticate(authorization);
  }

  public SyntheticPrincipal requiredPrincipal(String authorization) {
    if (authorization == null || authorization.isBlank()) {
      throw new CommerceException(
          HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "A local synthetic session is required");
    }
    return authenticate(authorization);
  }

  public SessionStatus current(String authorization) {
    SyntheticPrincipal principal = requiredPrincipal(authorization);
    Instant expiresAt = sessions.findExpiry(principal.id()).orElseThrow(this::sessionInvalid);
    return new SessionStatus(principal.id(), principal.role().name(), expiresAt);
  }

  public void revoke(String authorization) {
    SyntheticPrincipal principal = requiredPrincipal(authorization);
    sessions.revoke(principal.id(), Instant.now());
    logSessionEvent("synthetic.session.revoked", principal.id(), principal.role());
  }

  private SyntheticPrincipal authenticate(String authorization) {
    Matcher matcher = BEARER_TOKEN.matcher(authorization);
    if (!matcher.matches()) {
      throw sessionInvalid();
    }
    return sessions.findActive(hash(matcher.group(1)), Instant.now()).orElseThrow(this::sessionInvalid);
  }

  private CommerceException sessionInvalid() {
    return new CommerceException(HttpStatus.UNAUTHORIZED, "SESSION_INVALID", "The local demo session is invalid");
  }

  private static String createToken() {
    byte[] token = new byte[32];
    RANDOM.nextBytes(token);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
  }

  private static String hash(String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void logSessionEvent(String eventName, UUID sessionId, SyntheticRole role) {
    LOGGER.atInfo()
        .addKeyValue("eventName", eventName)
        .addKeyValue("userIdHash", hash(sessionId.toString()))
        .addKeyValue("role", role.name())
        .log("Synthetic demo session updated");
  }

  public record IssuedSession(UUID id, String role, String token, Instant expiresAt) {}

  public record SessionStatus(UUID id, String role, Instant expiresAt) {}
}
