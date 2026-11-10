package com.commercehub.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public final class CommerceRequests {
  private CommerceRequests() {}

  public record Item(
      @NotNull UUID productId,
      @Min(1) @Max(20) int quantity) {}

  public record Order(
      @NotEmpty @Size(max = 30) List<@Valid Item> items,
      @Size(max = 32) String promoCode) {}
}
