package com.commercehub.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercehub.api.CommerceException;
import com.commercehub.catalog.ProductCatalogService;
import com.commercehub.order.CommerceResponses.OrderLine;
import com.commercehub.order.CommerceResponses.OrderQuote;
import com.commercehub.order.CommerceResponses.OrderReceipt;
import com.commercehub.order.CommerceResponses.OrderResponse;
import com.commercehub.order.CommerceResponses.QuoteLine;
import com.commercehub.order.OrderRepository.StoredOrder;
import com.commercehub.security.SyntheticPrincipal;
import com.commercehub.security.SyntheticRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class CommerceServiceTest {
  private static final UUID PRODUCT_ID = UUID.fromString("2f611742-c6b9-3aba-856f-c32761861692");
  private static final UUID ORDER_ID = UUID.fromString("40a43d73-13de-4e15-9483-46f2a0a9ef71");
  private static final String IDEMPOTENCY_KEY = "request-key-123";
  private static final String ACCESS_KEY = "A".repeat(43);

  @Test
  void rejectsMissingOrdersAndMergedQuantitiesAboveTheLimit() {
    CommerceService service = service(mock(ProductCatalogService.class), mock(DemoPricingService.class),
        mock(OrderRepository.class), mock(TransactionTemplate.class));

    assertThatThrownBy(() -> service.quote(null))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("INVALID_ITEMS"));
    assertThatThrownBy(() -> service.quote(new CommerceRequests.Order(List.of(), null)))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("INVALID_ITEMS"));
    assertThatThrownBy(() -> service.quote(new CommerceRequests.Order(null, null)))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("INVALID_ITEMS"));
    assertThatThrownBy(() -> service.quote(new CommerceRequests.Order(
        List.of(new CommerceRequests.Item(PRODUCT_ID, 11), new CommerceRequests.Item(PRODUCT_ID, 10)), null)))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("INVALID_QUANTITY"));
  }

  @Test
  void mergesDuplicateItemsBeforeServerPricing() {
    ProductCatalogService catalog = mock(ProductCatalogService.class);
    DemoPricingService pricing = mock(DemoPricingService.class);
    OrderRepository orders = mock(OrderRepository.class);
    OrderQuote quote = quote();
    when(pricing.calculate(Map.of(PRODUCT_ID, 2), null, false)).thenReturn(quote);
    CommerceService service = service(catalog, pricing, orders, mock(TransactionTemplate.class));

    assertThat(service.quote(new CommerceRequests.Order(
        List.of(new CommerceRequests.Item(PRODUCT_ID, 1), new CommerceRequests.Item(PRODUCT_ID, 1)), null))
        .quote()).isEqualTo(quote);
    verify(pricing).calculate(Map.of(PRODUCT_ID, 2), null, false);
  }

  @Test
  void createsCustomerSessionOrderWithPromoAndWithoutGuestAccessKey() {
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    runTransactionInline(transactions);
    DemoPricingService pricing = mock(DemoPricingService.class);
    OrderRepository orders = mock(OrderRepository.class);
    OrderQuote quote = quote();
    SyntheticPrincipal principal = new SyntheticPrincipal(UUID.randomUUID(), SyntheticRole.CUSTOMER);
    CommerceRequests.Order request = new CommerceRequests.Order(
        List.of(new CommerceRequests.Item(PRODUCT_ID, 1)), "HELLO10");
    when(orders.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty(), Optional.empty());
    when(pricing.calculate(Map.of(PRODUCT_ID, 1), "HELLO10", true)).thenReturn(quote);
    when(orders.decrementStock(PRODUCT_ID, 1)).thenReturn(true);
    when(orders.findReceipt(any(UUID.class))).thenReturn(Optional.of(receipt()));
    CommerceService service = service(
        mock(ProductCatalogService.class),
        pricing,
        orders,
        transactions);

    OrderResponse response = service.createOrder(request, IDEMPOTENCY_KEY, null, principal);

    assertThat(response.duplicate()).isFalse();
    verify(pricing).calculate(Map.of(PRODUCT_ID, 1), "HELLO10", true);
    verify(orders).insertOrder(
        any(UUID.class),
        isNull(),
        eq(IDEMPOTENCY_KEY),
        any(String.class),
        eq(principal.id()),
        eq(quote));
  }

  @Test
  void deniesGuestAccessToAnOrderOwnedByASyntheticSession() {
    OrderRepository orders = mock(OrderRepository.class);
    UUID ownerSessionId = UUID.randomUUID();
    StoredOrder sessionOwned = new StoredOrder(
        ORDER_ID,
        requestHash(PRODUCT_ID, 1, null),
        null,
        ownerSessionId);
    when(orders.findStoredOrder(ORDER_ID)).thenReturn(Optional.of(sessionOwned));
    CommerceService service = service(
        mock(ProductCatalogService.class),
        mock(DemoPricingService.class),
        orders,
        mock(TransactionTemplate.class));

    assertThatThrownBy(() -> service.findOrder(ORDER_ID, ACCESS_KEY, null))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("ORDER_NOT_FOUND"));
    verify(orders, never()).findReceipt(ORDER_ID);
  }

  @Test
  void validatesIdempotencyAndGuestAccessHeadersBeforeStartingATransaction() {
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    CommerceService service = service(
        mock(ProductCatalogService.class),
        mock(DemoPricingService.class),
        mock(OrderRepository.class),
        transactions);
    CommerceRequests.Order request = request();

    assertThatThrownBy(() -> service.createOrder(request, "short", ACCESS_KEY, null))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED"));
    assertThatThrownBy(() -> service.createOrder(request, null, ACCESS_KEY, null))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED"));
    assertThatThrownBy(() -> service.createOrder(request, IDEMPOTENCY_KEY, null, null))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("ORDER_ACCESS_KEY_REQUIRED"));
    assertThatThrownBy(() -> service.createOrder(request, IDEMPOTENCY_KEY, "short", null))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("ORDER_ACCESS_KEY_REQUIRED"));
    assertThatThrownBy(() -> service.createOrder(
        request,
        IDEMPOTENCY_KEY,
        null,
        new SyntheticPrincipal(ORDER_ID, SyntheticRole.MERCHANT)))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("ROLE_FORBIDDEN"));
    verify(transactions, never()).execute(any());
  }

  @Test
  void returnsTheConcurrentCommittedIdempotentOrderWithoutDecrementingStock() {
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    runTransactionInline(transactions);
    ProductCatalogService catalog = mock(ProductCatalogService.class);
    DemoPricingService pricing = mock(DemoPricingService.class);
    OrderRepository orders = mock(OrderRepository.class);
    OrderQuote quote = quote();
    StoredOrder existing = guestOrder(requestHash(PRODUCT_ID, 1, null));
    when(orders.findByIdempotencyKey(IDEMPOTENCY_KEY))
        .thenReturn(Optional.empty(), Optional.of(existing));
    when(pricing.calculate(Map.of(PRODUCT_ID, 1), null, true)).thenReturn(quote);
    when(orders.findReceipt(ORDER_ID)).thenReturn(Optional.of(receipt()));
    CommerceService service = service(catalog, pricing, orders, transactions);

    OrderResponse response = service.createOrder(request(), IDEMPOTENCY_KEY, ACCESS_KEY, null);

    assertThat(response.duplicate()).isTrue();
    assertThat(response.order()).isEqualTo(receipt());
    verify(orders, never()).decrementStock(any(UUID.class), anyInt());
  }

  @Test
  void convertsAConcurrentUniqueKeyViolationToTheExistingOrder() {
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    when(transactions.execute(any())).thenThrow(new DuplicateKeyException("duplicate idempotency key"));
    OrderRepository orders = mock(OrderRepository.class);
    StoredOrder existing = guestOrder(requestHash(PRODUCT_ID, 1, null));
    when(orders.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));
    when(orders.findReceipt(ORDER_ID)).thenReturn(Optional.of(receipt()));
    CommerceService service = service(
        mock(ProductCatalogService.class),
        mock(DemoPricingService.class),
        orders,
        transactions);

    OrderResponse response = service.createOrder(request(), IDEMPOTENCY_KEY, ACCESS_KEY, null);

    assertThat(response.duplicate()).isTrue();
    assertThat(response.order().orderId()).isEqualTo(ORDER_ID);
  }

  @Test
  void reportsAStockRaceAfterPricingAsAConflict() {
    TransactionTemplate transactions = mock(TransactionTemplate.class);
    runTransactionInline(transactions);
    DemoPricingService pricing = mock(DemoPricingService.class);
    OrderRepository orders = mock(OrderRepository.class);
    when(orders.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty(), Optional.empty());
    when(pricing.calculate(Map.of(PRODUCT_ID, 1), null, true)).thenReturn(quote());
    when(orders.decrementStock(PRODUCT_ID, 1)).thenReturn(false);
    CommerceService service = service(
        mock(ProductCatalogService.class),
        pricing,
        orders,
        transactions);

    assertThatThrownBy(() -> service.createOrder(request(), IDEMPOTENCY_KEY, ACCESS_KEY, null))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> {
          assertThat(((CommerceException) error).status()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(((CommerceException) error).code()).isEqualTo("INSUFFICIENT_STOCK");
        });
  }

  private static CommerceService service(
      ProductCatalogService catalog,
      DemoPricingService pricing,
      OrderRepository orders,
      TransactionTemplate transactions) {
    return new CommerceService(catalog, pricing, orders, transactions);
  }

  @SuppressWarnings("unchecked")
  private static void runTransactionInline(TransactionTemplate transactions) {
    doAnswer(invocation -> {
      TransactionCallback<Object> callback = invocation.getArgument(0);
      return callback.doInTransaction(mock(TransactionStatus.class));
    }).when(transactions).execute(any());
  }

  private static CommerceRequests.Order request() {
    return new CommerceRequests.Order(List.of(new CommerceRequests.Item(PRODUCT_ID, 1)), null);
  }

  private static StoredOrder guestOrder(String requestHash) {
    return new StoredOrder(ORDER_ID, requestHash, sha256(ACCESS_KEY), null);
  }

  private static OrderQuote quote() {
    QuoteLine line = new QuoteLine(
        PRODUCT_ID,
        "daybreak-coffee",
        "Daybreak Coffee",
        "/products/daybreak-coffee.jpg",
        1,
        1_000,
        1_000,
        0,
        0,
        1_495);
    return new OrderQuote(List.of(line), 1_000, 0, 495, 0, 1_495, 0, null, "USD");
  }

  private static OrderReceipt receipt() {
    return new OrderReceipt(
        ORDER_ID,
        "CONFIRMED",
        1_000,
        0,
        495,
        0,
        1_495,
        0,
        null,
        "USD",
        List.of(new OrderLine(
            PRODUCT_ID,
            "Daybreak Coffee",
            "/products/daybreak-coffee.jpg",
            1,
            1_000,
            0,
            0,
            1_495)),
        Instant.parse("2026-11-01T12:00:00Z"));
  }

  private static String requestHash(UUID productId, int quantity, String promoCode) {
    String promo = promoCode == null ? "" : promoCode.trim().toUpperCase(java.util.Locale.ROOT);
    return sha256(productId + ":" + quantity + "|" + promo);
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hexadecimal = new StringBuilder(digest.length * 2);
      for (byte item : digest) {
        hexadecimal.append(String.format("%02x", item));
      }
      return hexadecimal.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
