package io.github.mitohondriyaa.order.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
    String productId,
    Integer quantity,
    BigDecimal price,
    Integer warrantyMonths,
    BigDecimal lineTotal
) {}
