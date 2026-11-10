package com.commercehub.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercehub.order.OrderRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CatalogAndOrderRepositoryTest {
  @Autowired
  private ProductCatalogService catalog;

  @Autowired
  private DemoCatalogInitializer initializer;

  @Autowired
  private OrderRepository orders;

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  void searchesProductsWithBlankAndNonblankFilters() {
    assertThat(catalog.findProducts(null, null)).hasSize(8);
    assertThat(catalog.findProducts("   ", "")).hasSize(8);
    assertThat(catalog.findProducts(" COFFEE ", null))
        .extracting(Product::slug)
        .containsExactly("daybreak-coffee");
    assertThat(catalog.findProducts(null, " home "))
        .extracting(Product::category)
        .containsOnly("Home");
    assertThat(catalog.findProducts("daypack", "Travel"))
        .extracting(Product::slug)
        .containsExactly("everyday-daypack");
  }

  @Test
  void returnsEmptyForUnavailableCatalogAndOrderRecords() {
    UUID missingId = UUID.randomUUID();

    assertThat(catalog.findBySlug("missing-demo-product")).isEmpty();
    assertThat(catalog.findById(missingId, false)).isEmpty();
    assertThat(catalog.findById(missingId, true)).isEmpty();
    assertThat(orders.findReceipt(missingId)).isEmpty();
    assertThat(orders.decrementStock(missingId, 1)).isFalse();
  }

  @Test
  void leavesTheExistingSeededCatalogUnchangedOnSubsequentInitialization() {
    Integer countBefore = jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class);

    initializer.run();

    Integer countAfter = jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class);
    assertThat(countBefore).isEqualTo(8);
    assertThat(countAfter).isEqualTo(countBefore);
  }
}
