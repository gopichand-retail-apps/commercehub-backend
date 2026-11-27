package com.commercehub.events;

import com.commercehub.api.CommerceException;
import com.commercehub.security.SyntheticPrincipal;
import com.commercehub.security.SyntheticRole;
import com.commercehub.security.SyntheticSessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@ConditionalOnProperty(name = "commercehub.events.enabled", havingValue = "true")
public class PipelineController {
  private final PipelineAdmin admin;
  private final SyntheticSessionService sessions;
  private final ReplayLimiter limiter;
  private final BrokerProbe broker;
  private final String origin;

  public PipelineController(PipelineAdmin admin, SyntheticSessionService sessions, ReplayLimiter limiter,
      @Value("${commercehub.events.operator-origin}") String origin, BrokerProbe broker) {
    URI uri = URI.create(origin);
    if (!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1", "localhost").contains(uri.getHost()) ||
        !origin.equals("http://" + uri.getAuthority())) {
      throw new IllegalArgumentException("Local operator origin required");
    }
    this.admin = admin;
    this.sessions = sessions;
    this.limiter = limiter;
    this.origin = origin;
    this.broker = broker;
  }

  @GetMapping("/outbox")
  public ResponseEntity<?> list(HttpServletRequest request, @RequestParam(defaultValue = "pending") String state,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
    authorize(request);
    return response(Map.of("events", admin.list(state, page, size), "page", page, "size", size));
  }

  @GetMapping("/event-pipeline/health")
  public ResponseEntity<?> health(HttpServletRequest request) {
    authorize(request);
    return response(Map.of("database", admin.health(), "broker", broker.sample()));
  }

  @PostMapping("/event-pipeline/retry/{eventId}")
  public ResponseEntity<?> retry(HttpServletRequest request, @PathVariable String eventId) {
    SyntheticPrincipal actor = authorize(request);
    if (!origin.equals(request.getHeader("Origin"))) throw denied();
    if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) throw invalid();
    final UUID id;
    try {
      id = UUID.fromString(eventId);
      if (!id.toString().equals(eventId)) throw invalid();
    } catch (IllegalArgumentException error) { throw invalid(); }
    if (!limiter.allow(actor.id(), System.currentTimeMillis())) {
      throw new CommerceException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Try again later");
    }
    boolean duplicate = admin.retry(id, request.getHeader("Idempotency-Key"), actor.id());
    return response(Map.of("eventId", id, "retryRequested", true, "duplicate", duplicate));
  }

  private SyntheticPrincipal authorize(HttpServletRequest request) {
    if (!Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").contains(request.getRemoteAddr()) ||
        !URI.create(origin).getAuthority().equals(request.getHeader("Host"))) throw denied();
    SyntheticPrincipal actor = sessions.requiredPrincipal(request.getHeader("Authorization"));
    if (actor.role() != SyntheticRole.SUPPORT) throw denied();
    return actor;
  }

  private static ResponseEntity<?> response(Object body) {
    return ResponseEntity.ok().header("Cache-Control", "no-store", "X-Content-Type-Options", "nosniff").body(body);
  }
  private static CommerceException denied() {
    return new CommerceException(HttpStatus.FORBIDDEN, "ROLE_FORBIDDEN", "This action is unavailable");
  }
  private static CommerceException invalid() {
    return new CommerceException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Review the request and try again");
  }
}
