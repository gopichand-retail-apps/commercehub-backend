package com.commercehub.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.info.BuildProperties;
import jakarta.servlet.http.HttpServletResponse;

@RestController
public class HealthController {
  private final BuildProperties buildProperties;

  public HealthController(BuildProperties buildProperties) {
    this.buildProperties = buildProperties;
  }

  @GetMapping("/health")
  public HealthResponse health() {
    return new HealthResponse("ok", "commercehub-api");
  }

  @GetMapping("/api/v1/version")
  public VersionResponse version(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return new VersionResponse("commercehub-api", buildProperties.getVersion());
  }

  public record HealthResponse(String status, String service) {}

  public record VersionResponse(String service, String version) {}
}
