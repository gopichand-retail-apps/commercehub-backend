package com.commercehub.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class ProductCatalogService {
  private static final String PRODUCT_COLUMNS = """
      SELECT id, slug, name, subtitle, description, category, price_minor,
        stock_quantity, image_url, featured
      FROM products
      """;

  private final JdbcTemplate jdbc;
  private final RowMapper<Product> productMapper = (result, row) -> new Product(
      result.getObject("id", UUID.class),
      result.getString("slug"),
      result.getString("name"),
      result.getString("subtitle"),
      result.getString("description"),
      result.getString("category"),
      result.getLong("price_minor"),
      result.getInt("stock_quantity"),
      result.getString("image_url"),
      result.getBoolean("featured"));

  public ProductCatalogService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Product> findProducts(String query, String category) {
    StringBuilder sql = new StringBuilder(PRODUCT_COLUMNS).append(" WHERE available = TRUE");
    List<Object> parameters = new ArrayList<>();
    appendSearchFilter(sql, parameters, query);
    appendCategoryFilter(sql, parameters, category);
    sql.append(" ORDER BY featured DESC, name ASC LIMIT 100");
    return jdbc.query(sql.toString(), productMapper, parameters.toArray());
  }

  public Optional<Product> findBySlug(String slug) {
    return jdbc.query(PRODUCT_COLUMNS + " WHERE slug = ? AND available = TRUE", productMapper, slug)
        .stream()
        .findFirst();
  }

  public Optional<Product> findById(UUID id, boolean lockForUpdate) {
    String lockClause = lockForUpdate ? " FOR UPDATE" : "";
    return jdbc.query(PRODUCT_COLUMNS + " WHERE id = ? AND available = TRUE" + lockClause,
        productMapper, id)
        .stream()
        .findFirst();
  }

  private static void appendSearchFilter(StringBuilder sql, List<Object> parameters, String query) {
    if (query == null || query.isBlank()) {
      return;
    }

    String pattern = "%" + query.trim().toLowerCase() + "%";
    sql.append(" AND (LOWER(name) LIKE ? OR LOWER(description) LIKE ? OR LOWER(category) LIKE ?)");
    parameters.add(pattern);
    parameters.add(pattern);
    parameters.add(pattern);
  }

  private static void appendCategoryFilter(StringBuilder sql, List<Object> parameters, String category) {
    if (category == null || category.isBlank()) {
      return;
    }

    sql.append(" AND LOWER(category) = ?");
    parameters.add(category.trim().toLowerCase());
  }
}
