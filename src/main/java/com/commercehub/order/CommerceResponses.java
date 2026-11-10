package com.commercehub.order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CommerceResponses {
  private CommerceResponses() {}

  public record QuoteLine(
      UUID productId,
      String slug,
      String name,
      String imageUrl,
      int quantity,
      long unitPriceMinor,
      long subtotalMinor,
      long discountMinor,
      long taxMinor,
      long totalMinor) {}

  public record OrderQuote(
      List<QuoteLine> lines,
      long subtotalMinor,
      long discountMinor,
      long shippingMinor,
      long taxMinor,
      long totalMinor,
      int taxBasisPoints,
      String promoCode,
      String currency) {}

  public record QuoteResponse(OrderQuote quote) {}

  public record OrderLine(
      UUID productId,
      String name,
      String imageUrl,
      int quantity,
      long unitPriceMinor,
      long discountMinor,
      long taxMinor,
      long totalMinor) {}

  public record OrderReceipt(
      UUID orderId,
      String status,
      long subtotalMinor,
      long discountMinor,
      long shippingMinor,
      long taxMinor,
      long totalMinor,
      int taxBasisPoints,
      String promoCode,
      String currency,
      List<OrderLine> items,
      Instant createdAt) {}

  public record OrderResponse(OrderReceipt order, boolean duplicate) {}
}
