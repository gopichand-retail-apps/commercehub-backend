package com.commercehub.catalog;

import com.commercehub.api.CommerceException;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@Validated
public class ProductController {
  private final ProductCatalogService catalog;

  public ProductController(ProductCatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping
  public ProductListResponse list(
      @RequestParam(required = false) @Size(max = 120) String q,
      @RequestParam(required = false) @Size(max = 60) String category) {
    return new ProductListResponse(catalog.findProducts(q, category));
  }

  @GetMapping("/{slug}")
  public Product product(@PathVariable @Size(max = 120) String slug) {
    return catalog.findBySlug(slug)
        .orElseThrow(() -> new CommerceException(
            HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product was not found"));
  }
}
