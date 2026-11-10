package com.commercehub.catalog;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DemoCatalogInitializer implements CommandLineRunner {
  private final JdbcTemplate jdbc;

  public DemoCatalogInitializer(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void run(String... args) {
    Long count = jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class);
    if (count != null && count > 0) {
      return;
    }

    for (SeedProduct product : products()) {
      jdbc.update("""
          INSERT INTO products (
            id, slug, name, subtitle, description, category, price_minor,
            stock_quantity, image_url, featured
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, product.id(), product.slug(), product.name(), product.subtitle(),
          product.description(), product.category(), product.priceMinor(),
          product.stockQuantity(), product.imageUrl(), product.featured());
    }
  }

  private static List<SeedProduct> products() {
    return List.of(
        new SeedProduct("daybreak-coffee", "Daybreak Coffee", "Small-batch, whole bean",
            "A balanced medium roast sourced from a fictional cooperative. 12 oz.",
            "Pantry", 1800, 24, "/products/daybreak-coffee.jpg", true),
        new SeedProduct("dawn-stoneware-mug", "Dawn Stoneware Mug", "Hand-finished in a soft glaze",
            "A sturdy everyday mug with a comfortable handle. 12 oz.",
            "Home", 2800, 13, "/products/dawn-stoneware-mug.jpg", true),
        new SeedProduct("trail-steel-bottle", "Trail Steel Bottle", "Double-wall stainless steel",
            "A reusable bottle designed for an easy commute. 20 oz.",
            "Travel", 3400, 18, "/products/trail-steel-bottle.jpg", true),
        new SeedProduct("field-notebook-set", "Field Notebook Set", "Three pocket-sized journals",
            "Recycled-paper notebooks with a durable, thread-bound cover.",
            "Stationery", 1600, 30, "/products/field-notebook-set.jpg", false),
        new SeedProduct("everyday-daypack", "Everyday Daypack", "Lightweight canvas for the commute",
          "A practical everyday backpack with a roomy main compartment and easy top zip.",
          "Travel", 2400, 16, "/products/everyday-daypack.jpg", true),
        new SeedProduct("cedar-hand-wash", "Cedar Hand Wash", "Plant-based, refillable bottle",
            "A gentle, fictional botanical wash in a reusable amber-glass bottle.",
            "Bath", 1450, 21, "/products/cedar-hand-wash.jpg", false),
        new SeedProduct("pocket-speaker", "Pocket Speaker", "Compact wireless audio",
            "A small demo speaker with a warm sound profile and an 8-hour battery.",
            "Technology", 5200, 7, "/products/pocket-speaker.jpg", false),
        new SeedProduct("reading-lamp", "Reading Lamp", "Powder-coated metal, warm LED",
            "An adjustable desk lamp with a weighted base and simple controls.",
            "Home", 6800, 5, "/products/reading-lamp.jpg", false));
  }

  private record SeedProduct(
      String slug,
      String name,
      String subtitle,
      String description,
      String category,
      long priceMinor,
      int stockQuantity,
      String imageUrl,
      boolean featured) {
    UUID id() {
      return UUID.nameUUIDFromBytes(("commercehub:" + slug).getBytes(StandardCharsets.UTF_8));
    }
  }
}
