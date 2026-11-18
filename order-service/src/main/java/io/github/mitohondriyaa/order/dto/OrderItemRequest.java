package io.github.mitohondriyaa.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OrderItemRequest(
    @NotBlank(message = "productId mustn't be blank")
    String productId,
    @NotNull(message = "quantity mustn't be null")
    @Min(value = 1, message = "quantity must be at least 1")
    Integer quantity
) {}
