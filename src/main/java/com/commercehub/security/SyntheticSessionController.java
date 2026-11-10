package com.commercehub.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions")
@Validated
public class SyntheticSessionController {
  private final SyntheticSessionService sessions;

  public SyntheticSessionController(SyntheticSessionService sessions) {
    this.sessions = sessions;
  }

  @GetMapping("/roles")
  public ResponseEntity<RoleListResponse> roles() {
    List<String> roles = Arrays.stream(SyntheticRole.values()).map(Enum::name).toList();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new RoleListResponse(roles));
  }

  @PostMapping
  public ResponseEntity<CreatedSessionResponse> create(@Valid @RequestBody CreateSessionRequest request) {
    SyntheticSessionService.IssuedSession created = sessions.create(request.role());
    var session = new SessionTokenResponse(created.id(), created.role(), created.token(), created.expiresAt());
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(new CreatedSessionResponse(session));
  }

  @GetMapping("/current")
  public ResponseEntity<CurrentSessionResponse> current(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    SyntheticSessionService.SessionStatus current = sessions.current(authorization);
    var session = new SessionStatusResponse(current.id(), current.role(), current.expiresAt());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new CurrentSessionResponse(session));
  }

  @DeleteMapping("/current")
  public ResponseEntity<Void> revoke(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    sessions.revoke(authorization);
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  public record CreateSessionRequest(
      @NotBlank @Size(max = 16) String role) {}

  public record RoleListResponse(List<String> roles) {}

  public record SessionTokenResponse(UUID id, String role, String token, Instant expiresAt) {}

  public record CreatedSessionResponse(SessionTokenResponse session) {}

  public record SessionStatusResponse(UUID id, String role, Instant expiresAt) {}

  public record CurrentSessionResponse(SessionStatusResponse session) {}
}
