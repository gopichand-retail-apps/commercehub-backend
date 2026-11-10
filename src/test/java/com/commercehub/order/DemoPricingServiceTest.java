package com.commercehub.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercehub.api.CommerceException;
import com.commercehub.catalog.Product;
import com.commercehub.catalog.ProductCatalogService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DemoPricingServiceTest {
  private static final UUID PRODUCT_ID = UUID.fromString("2f611742-c6b9-3aba-856f-c32761861692");

  @Test
  void calculatesStandardShippingWithoutTaxOrDiscount() {
    ProductCatalogService catalog = mock(ProductCatalogService.class);
    when(catalog.findById(PRODUCT_ID, false)).thenReturn(Optional.of(product(1_000, 5)));
    DemoPricingService pricing = new DemoPricingService(catalog, 0);

    var quote = pricing.calculate(Map.of(PRODUCT_ID, 1), null, false);

    assertThat(quote.subtotalMinor()).isEqualTo(1_000);
    assertThat(quote.discountMinor()).isZero();
    assertThat(quote.shippingMinor()).isEqualTo(495);
    assertThat(quote.taxMinor()).isZero();
    assertThat(quote.totalMinor()).isEqualTo(1_495);
    verify(catalog).findById(PRODUCT_ID, false);
  }

  @Test
  void appliesNormalizedPromoTaxAndFreeShippingAtTheServerThreshold() {
    ProductCatalogService catalog = mock(ProductCatalogService.class);
    when(catalog.findById(PRODUCT_ID, true)).thenReturn(Optional.of(product(10_000, 2)));
    DemoPricingService pricing = new DemoPricingService(catalog, 1_000);

    var quote = pricing.calculate(Map.of(PRODUCT_ID, 1), " hello10 ", true);

    assertThat(quote.promoCode()).isEqualTo("HELLO10");
    assertThat(quote.discountMinor()).isEqualTo(1_000);
    assertThat(quote.taxMinor()).isEqualTo(900);
    assertThat(quote.shippingMinor()).isZero();
    assertThat(quote.totalMinor()).isEqualTo(9_900);
    assertThat(quote.lines()).singleElement().satisfies(line -> {
      assertThat(line.discountMinor()).isEqualTo(1_000);
      assertThat(line.taxMinor()).isEqualTo(900);
      assertThat(line.totalMinor()).isEqualTo(9_900);
    });
    verify(catalog).findById(PRODUCT_ID, true);
  }

  @Test
  void rejectsInvalidTaxRatesPromoCodesMissingProductsAndInsufficientStock() {
    ProductCatalogService catalog = mock(ProductCatalogService.class);
    assertThatThrownBy(() -> new DemoPricingService(catalog, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DemoPricingService(catalog, 10_001))
        .isInstanceOf(IllegalArgumentException.class);

    DemoPricingService pricing = new DemoPricingService(catalog, 0);
    when(catalog.findById(PRODUCT_ID, false)).thenReturn(Optional.of(product(1_000, 1)));
    assertThatThrownBy(() -> pricing.calculate(Map.of(PRODUCT_ID, 2), null, false))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("INSUFFICIENT_STOCK"));

    when(catalog.findById(PRODUCT_ID, false)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> pricing.calculate(Map.of(PRODUCT_ID, 1), null, false))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> {
          assertThat(((CommerceException) error).status()).isEqualTo(HttpStatus.NOT_FOUND);
          assertThat(((CommerceException) error).code()).isEqualTo("PRODUCT_NOT_FOUND");
        });

    assertThatThrownBy(() -> pricing.calculate(Map.of(), "NOT-REAL", false))
        .isInstanceOf(CommerceException.class)
        .satisfies(error -> assertThat(((CommerceException) error).code()).isEqualTo("PROMO_CODE_INVALID"));
    assertThat(pricing.calculate(Map.of(), "   ", false).promoCode()).isNull();
    assertThat(pricing.calculate(Map.of(), null, false).totalMinor()).isEqualTo(495);
  }

  private static Product product(long priceMinor, int stockQuantity) {
    return new Product(
        PRODUCT_ID,
        "daybreak-coffee",
        "Daybreak Coffee",
        "Small-batch, whole bean",
        "A fictional demo product.",
        "Pantry",
        priceMinor,
        stockQuantity,
        "/products/daybreak-coffee.jpg",
        true);
  }
}
