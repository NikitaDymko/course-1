package io.github.mitohondriyaa.product.dto;

import java.math.BigDecimal;
import java.util.Map;

public record ProductResponse(
    String id,
    String sku,
    String name,
    String description,
    BigDecimal price,
    Integer warrantyMonths,
    Map<String, String> attributes
) {

}
