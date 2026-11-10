package com.commercehub.order;

import com.commercehub.order.CommerceResponses.OrderResponse;
import com.commercehub.order.CommerceResponses.QuoteResponse;
import com.commercehub.security.SyntheticPrincipal;
import com.commercehub.security.SyntheticSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@Validated
public class OrderController {
  private final CommerceService commerce;
  private final SyntheticSessionService sessions;

  public OrderController(CommerceService commerce, SyntheticSessionService sessions) {
    this.commerce = commerce;
    this.sessions = sessions;
  }

  @PostMapping("/quote")
  public QuoteResponse quote(@Valid @RequestBody CommerceRequests.Order request) {
    return commerce.quote(request);
  }

  @PostMapping
  public ResponseEntity<OrderResponse> create(
      @RequestHeader("Idempotency-Key")
      @Size(min = 8, max = 128)
      @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey,
      @RequestHeader(value = "X-Order-Access-Key", required = false)
      @Pattern(regexp = "[A-Za-z0-9_-]{43}") String accessKey,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody CommerceRequests.Order request) {
    SyntheticPrincipal principal = sessions.optionalPrincipal(authorization);
    OrderResponse response = commerce.createOrder(request, idempotencyKey, accessKey, principal);
    return ResponseEntity.status(response.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(response);
  }

  @GetMapping("/{orderId}")
  public ResponseEntity<OrderResponse> find(
      @PathVariable UUID orderId,
      @RequestHeader(value = "X-Order-Access-Key", required = false) String accessKey,
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    SyntheticPrincipal principal = sessions.optionalPrincipal(authorization);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(commerce.findOrder(orderId, accessKey, principal));
  }
}
