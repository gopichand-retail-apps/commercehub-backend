package com.commercehub.order;

import com.commercehub.order.CommerceResponses.OrderLine;
import com.commercehub.order.CommerceResponses.OrderQuote;
import com.commercehub.order.CommerceResponses.OrderReceipt;
import com.commercehub.order.CommerceResponses.QuoteLine;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
  private final JdbcTemplate jdbc;
  private final RowMapper<StoredOrder> storedOrderMapper = (result, row) -> new StoredOrder(
      result.getObject("id", UUID.class),
      result.getString("request_hash"),
      result.getString("access_token_hash"),
      result.getObject("owner_session_id", UUID.class));

  public OrderRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<StoredOrder> findByIdempotencyKey(String key) {
    return jdbc.query("""
        SELECT id, request_hash, access_token_hash, owner_session_id
        FROM customer_orders WHERE idempotency_key = ?
        """, storedOrderMapper, key).stream().findFirst();
  }

  public Optional<StoredOrder> findStoredOrder(UUID id) {
    return jdbc.query("""
        SELECT id, request_hash, access_token_hash, owner_session_id
        FROM customer_orders WHERE id = ?
        """, storedOrderMapper, id).stream().findFirst();
  }

  public void insertOrder(
      UUID id,
      String accessTokenHash,
      String idempotencyKey,
      String requestHash,
      UUID ownerSessionId,
      OrderQuote quote) {
    jdbc.update("""
        INSERT INTO customer_orders (
          id, access_token_hash, idempotency_key, request_hash, owner_session_id, status,
          subtotal_minor, discount_minor, shipping_minor, tax_minor, total_minor,
          tax_basis_points, promo_code, payment_method
        ) VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, ?, ?, ?, 'SIMULATED')
        """, id, accessTokenHash, idempotencyKey, requestHash, ownerSessionId, quote.subtotalMinor(),
        quote.discountMinor(), quote.shippingMinor(), quote.taxMinor(), quote.totalMinor(),
        quote.taxBasisPoints(), quote.promoCode());
  }

  public void insertLine(UUID orderId, QuoteLine line) {
    jdbc.update("""
        INSERT INTO order_lines (
          id, order_id, product_id, product_name, product_image_url,
          quantity, unit_price_minor, discount_minor, tax_minor, line_total_minor
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), orderId, line.productId(), line.name(), line.imageUrl(),
        line.quantity(), line.unitPriceMinor(), line.discountMinor(), line.taxMinor(), line.totalMinor());
  }

  public boolean decrementStock(UUID productId, int quantity) {
    return jdbc.update("""
        UPDATE products
        SET stock_quantity = stock_quantity - ?
        WHERE id = ? AND available = TRUE AND stock_quantity >= ?
        """, quantity, productId, quantity) == 1;
  }

  public Optional<OrderReceipt> findReceipt(UUID id) {
    List<OrderHeader> headers = jdbc.query("""
        SELECT id, status, subtotal_minor, discount_minor, shipping_minor,
          tax_minor, total_minor, tax_basis_points, promo_code, created_at
        FROM customer_orders WHERE id = ?
        """, (result, row) -> new OrderHeader(
        result.getObject("id", UUID.class),
        result.getString("status"),
        result.getLong("subtotal_minor"),
        result.getLong("discount_minor"),
        result.getLong("shipping_minor"),
        result.getLong("tax_minor"),
        result.getLong("total_minor"),
        result.getInt("tax_basis_points"),
        result.getString("promo_code"),
        result.getTimestamp("created_at").toInstant()), id);
    if (headers.isEmpty()) {
      return Optional.empty();
    }

    OrderHeader header = headers.get(0);
    List<OrderLine> lines = jdbc.query("""
        SELECT product_id, product_name, product_image_url, quantity,
          unit_price_minor, discount_minor, tax_minor, line_total_minor
        FROM order_lines WHERE order_id = ? ORDER BY id
        """, (result, row) -> new OrderLine(
        result.getObject("product_id", UUID.class),
        result.getString("product_name"),
        result.getString("product_image_url"),
        result.getInt("quantity"),
        result.getLong("unit_price_minor"),
        result.getLong("discount_minor"),
        result.getLong("tax_minor"),
        result.getLong("line_total_minor")), id);

    return Optional.of(new OrderReceipt(header.id(), header.status(), header.subtotalMinor(),
        header.discountMinor(), header.shippingMinor(), header.taxMinor(), header.totalMinor(),
        header.taxBasisPoints(), header.promoCode(), "USD", lines, header.createdAt()));
  }

  public record StoredOrder(UUID id, String requestHash, String accessTokenHash, UUID ownerSessionId) {}

  private record OrderHeader(
      UUID id,
      String status,
      long subtotalMinor,
      long discountMinor,
      long shippingMinor,
      long taxMinor,
      long totalMinor,
      int taxBasisPoints,
      String promoCode,
      Instant createdAt) {}
}
