package com.commercehub.catalog;

import java.util.UUID;

public record Product(
    UUID id,
    String slug,
    String name,
    String subtitle,
    String description,
    String category,
    long priceMinor,
    int stockQuantity,
    String imageUrl,
    boolean featured) {}
