package com.commercehub.order;

import com.commercehub.api.CommerceException;
import com.commercehub.catalog.Product;
import com.commercehub.catalog.ProductCatalogService;
import com.commercehub.order.CommerceResponses.OrderQuote;
import com.commercehub.order.CommerceResponses.QuoteLine;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DemoPricingService {
  private static final long FREE_SHIPPING_THRESHOLD_MINOR = 7_500;
  private static final long STANDARD_SHIPPING_MINOR = 495;
  private static final int PROMO_DISCOUNT_BASIS_POINTS = 1_000;

  private final ProductCatalogService catalog;
  private final int taxBasisPoints;

  public DemoPricingService(
      ProductCatalogService catalog,
      @Value("${commercehub.tax-basis-points:0}") int taxBasisPoints) {
    if (taxBasisPoints < 0 || taxBasisPoints > 10_000) {
      throw new IllegalArgumentException("Demo tax basis points must be between 0 and 10000");
    }
    this.catalog = catalog;
    this.taxBasisPoints = taxBasisPoints;
  }

  public OrderQuote calculate(Map<UUID, Integer> quantities, String requestedPromoCode, boolean lockProducts) {
    String promoCode = normalizePromoCode(requestedPromoCode);
    int discountBasisPoints = promoCode == null ? 0 : PROMO_DISCOUNT_BASIS_POINTS;
    List<QuoteLine> lines = new ArrayList<>();
    long subtotalMinor = 0;
    long discountMinor = 0;
    long taxMinor = 0;

    for (Map.Entry<UUID, Integer> entry : quantities.entrySet()) {
      Product product = catalog.findById(entry.getKey(), lockProducts)
          .orElseThrow(() -> new CommerceException(
              HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "A product is no longer available"));
      int quantity = entry.getValue();
      if (product.stockQuantity() < quantity) {
        throw new CommerceException(
            HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", product.name() + " is no longer available in that quantity");
      }

      long lineSubtotal = Math.multiplyExact(product.priceMinor(), quantity);
      long lineDiscount = applyBasisPoints(lineSubtotal, discountBasisPoints);
      long lineTax = applyBasisPoints(lineSubtotal - lineDiscount, taxBasisPoints);
      long lineTotal = Math.addExact(lineSubtotal - lineDiscount, lineTax);
      subtotalMinor = Math.addExact(subtotalMinor, lineSubtotal);
      discountMinor = Math.addExact(discountMinor, lineDiscount);
      taxMinor = Math.addExact(taxMinor, lineTax);
      lines.add(new QuoteLine(product.id(), product.slug(), product.name(), product.imageUrl(), quantity,
          product.priceMinor(), lineSubtotal, lineDiscount, lineTax, lineTotal));
    }

    long discountedSubtotal = subtotalMinor - discountMinor;
    long shippingMinor = discountedSubtotal >= FREE_SHIPPING_THRESHOLD_MINOR ? 0 : STANDARD_SHIPPING_MINOR;
    long totalMinor = Math.addExact(Math.addExact(discountedSubtotal, taxMinor), shippingMinor);
    return new OrderQuote(lines, subtotalMinor, discountMinor, shippingMinor, taxMinor, totalMinor,
        taxBasisPoints, promoCode, "USD");
  }

  private static String normalizePromoCode(String requestedPromoCode) {
    if (requestedPromoCode == null || requestedPromoCode.isBlank()) {
      return null;
    }

    String normalized = requestedPromoCode.trim().toUpperCase(Locale.ROOT);
    if (!normalized.equals("HELLO10")) {
      throw new CommerceException(HttpStatus.BAD_REQUEST, "PROMO_CODE_INVALID", "That demo code is not available");
    }
    return normalized;
  }

  private static long applyBasisPoints(long amountMinor, int basisPoints) {
    BigInteger numerator = BigInteger.valueOf(amountMinor)
        .multiply(BigInteger.valueOf(basisPoints))
        .add(BigInteger.valueOf(5_000));
    return numerator.divide(BigInteger.valueOf(10_000)).longValueExact();
  }
}
