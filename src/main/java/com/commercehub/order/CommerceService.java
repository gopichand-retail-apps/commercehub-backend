package com.commercehub.order;

import com.commercehub.api.CommerceException;
import com.commercehub.catalog.Product;
import com.commercehub.catalog.ProductCatalogService;
import com.commercehub.order.OrderRepository.StoredOrder;
import com.commercehub.order.CommerceResponses.OrderQuote;
import com.commercehub.order.CommerceResponses.OrderReceipt;
import com.commercehub.order.CommerceResponses.OrderResponse;
import com.commercehub.order.CommerceResponses.QuoteResponse;
import com.commercehub.security.SyntheticPrincipal;
import com.commercehub.security.SyntheticRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CommerceService {
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
  private static final Pattern ACCESS_KEY = Pattern.compile("[A-Za-z0-9_-]{43}");
  private static final Logger LOGGER = LoggerFactory.getLogger(CommerceService.class);

  private final ProductCatalogService catalog;
  private final DemoPricingService pricing;
  private final OrderRepository orders;
  private final TransactionTemplate transactions;

  public CommerceService(
      ProductCatalogService catalog,
      DemoPricingService pricing,
      OrderRepository orders,
      TransactionTemplate transactions) {
    this.catalog = catalog;
    this.pricing = pricing;
    this.orders = orders;
    this.transactions = transactions;
  }

  public QuoteResponse quote(CommerceRequests.Order request) {
    Map<UUID, Integer> quantities = normalizeItems(request);
    return new QuoteResponse(pricing.calculate(quantities, request.promoCode(), false));
  }

  public OrderResponse createOrder(
      CommerceRequests.Order request,
      String idempotencyKey,
      String accessKey,
      SyntheticPrincipal principal) {
    validateHeaders(idempotencyKey, accessKey, principal);
    String requestHash = hash(requestFingerprint(normalizeItems(request), request.promoCode()));

    try {
      return transactions.execute(status -> createOrderTransaction(
          request, idempotencyKey, accessKey, requestHash, principal));
    } catch (DuplicateKeyException duplicate) {
      return duplicateOrder(idempotencyKey, requestHash, accessKey, principal);
    }
  }

  public OrderResponse findOrder(UUID orderId, String accessKey, SyntheticPrincipal principal) {
    requireCustomer(principal);
    if (principal == null && !isAccessKey(accessKey)) {
      throw orderNotFound();
    }

    StoredOrder stored = orders.findStoredOrder(orderId).orElseThrow(CommerceService::orderNotFound);
    assertOwner(stored, accessKey, principal);
    OrderReceipt receipt = orders.findReceipt(orderId).orElseThrow(CommerceService::orderNotFound);
    return new OrderResponse(receipt, false);
  }

  private OrderResponse createOrderTransaction(
      CommerceRequests.Order request,
      String idempotencyKey,
      String accessKey,
      String requestHash,
      SyntheticPrincipal principal) {
    Optional<StoredOrder> existing = orders.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      return duplicateOrder(existing.get(), requestHash, accessKey, principal);
    }

    Map<UUID, Integer> quantities = normalizeItems(request);
    OrderQuote quote = pricing.calculate(quantities, request.promoCode(), true);
    Optional<StoredOrder> committedDuplicate = orders.findByIdempotencyKey(idempotencyKey);
    if (committedDuplicate.isPresent()) {
      return duplicateOrder(committedDuplicate.get(), requestHash, accessKey, principal);
    }

    for (Map.Entry<UUID, Integer> item : quantities.entrySet()) {
      if (!orders.decrementStock(item.getKey(), item.getValue())) {
        throw new CommerceException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "A product is no longer available");
      }
    }

    UUID orderId = UUID.randomUUID();
    String accessKeyHash = principal == null ? hash(accessKey) : null;
    UUID ownerSessionId = principal == null ? null : principal.id();
    orders.insertOrder(orderId, accessKeyHash, idempotencyKey, requestHash, ownerSessionId, quote);
    quote.lines().forEach(line -> orders.insertLine(orderId, line));
    OrderReceipt receipt = orders.findReceipt(orderId).orElseThrow();
    logOrderCreated(orderId, quote);
    return new OrderResponse(receipt, false);
  }

  private OrderResponse duplicateOrder(
      String key,
      String requestHash,
      String accessKey,
      SyntheticPrincipal principal) {
    StoredOrder existing = orders.findByIdempotencyKey(key).orElseThrow(CommerceService::orderNotFound);
    return duplicateOrder(existing, requestHash, accessKey, principal);
  }

  private OrderResponse duplicateOrder(
      StoredOrder existing,
      String requestHash,
      String accessKey,
      SyntheticPrincipal principal) {
    assertOwner(existing, accessKey, principal);
    if (!existing.requestHash().equals(requestHash)) {
      throw new CommerceException(
          HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "That key was used for a different demo order");
    }
    OrderReceipt receipt = orders.findReceipt(existing.id()).orElseThrow(CommerceService::orderNotFound);
    return new OrderResponse(receipt, true);
  }

  private Map<UUID, Integer> normalizeItems(CommerceRequests.Order request) {
    if (request == null || request.items() == null || request.items().isEmpty()) {
      throw new CommerceException(HttpStatus.BAD_REQUEST, "INVALID_ITEMS", "Add an item before checkout");
    }

    Map<UUID, Integer> quantities = new TreeMap<>();
    request.items().forEach(item -> quantities.merge(item.productId(), item.quantity(), Math::addExact));
    if (quantities.values().stream().anyMatch(quantity -> quantity > 20)) {
      throw new CommerceException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY", "The quantity limit is 20 per product");
    }
    return quantities;
  }

  private static String requestFingerprint(Map<UUID, Integer> quantities, String promoCode) {
    String items = quantities.entrySet().stream()
        .map(item -> item.getKey() + ":" + item.getValue())
        .collect(Collectors.joining(","));
    String promo = promoCode == null ? "" : promoCode.trim().toUpperCase(java.util.Locale.ROOT);
    return items + "|" + promo;
  }

  private static void validateHeaders(
      String idempotencyKey,
      String accessKey,
      SyntheticPrincipal principal) {
    if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      throw new CommerceException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Provide a valid request key");
    }
    requireCustomer(principal);
    if (principal == null && !isAccessKey(accessKey)) {
      throw new CommerceException(
          HttpStatus.BAD_REQUEST,
          "ORDER_ACCESS_KEY_REQUIRED",
          "Refresh checkout and try again");
    }
  }

  private static boolean isAccessKey(String accessKey) {
    return accessKey != null && ACCESS_KEY.matcher(accessKey).matches();
  }

  private static void assertAccessKey(StoredOrder order, String accessKey) {
    byte[] stored = order.accessTokenHash().getBytes(StandardCharsets.US_ASCII);
    byte[] submitted = hash(accessKey).getBytes(StandardCharsets.US_ASCII);
    if (!MessageDigest.isEqual(stored, submitted)) {
      throw orderNotFound();
    }
  }

  private static void assertOwner(
      StoredOrder order,
      String accessKey,
      SyntheticPrincipal principal) {
    if (principal != null) {
      if (!principal.id().equals(order.ownerSessionId())) {
        throw orderNotFound();
      }
      return;
    }
    if (order.ownerSessionId() != null || !isAccessKey(accessKey)) {
      throw orderNotFound();
    }
    assertAccessKey(order, accessKey);
  }

  private static void requireCustomer(SyntheticPrincipal principal) {
    if (principal != null && principal.role() != SyntheticRole.CUSTOMER) {
      throw new CommerceException(
          HttpStatus.FORBIDDEN,
          "ROLE_FORBIDDEN",
          "This demo order workflow is available to the customer role only");
    }
  }

  private static String hash(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hexadecimal = new StringBuilder(digest.length * 2);
      for (byte item : digest) {
        hexadecimal.append(String.format("%02x", item));
      }
      return hexadecimal.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static CommerceException orderNotFound() {
    return new CommerceException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order was not found");
  }

  private static void logOrderCreated(UUID orderId, OrderQuote quote) {
    LOGGER.atInfo()
        .addKeyValue("eventName", "commercehub.order.created")
        .addKeyValue("entityType", "order")
        .addKeyValue("entityIdHash", hash(orderId.toString()))
        .addKeyValue("itemCount", quote.lines().size())
        .addKeyValue("totalMinor", quote.totalMinor())
        .addKeyValue("paymentMethod", "SIMULATED")
        .log("Demo order created");
  }
}
