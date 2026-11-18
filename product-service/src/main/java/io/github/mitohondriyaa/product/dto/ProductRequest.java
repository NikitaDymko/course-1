package io.github.mitohondriyaa.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Map;

public record ProductRequest(
    @NotBlank(message = "SKU mustn't be blank")
    String sku,
    @NotBlank(message = "Name mustn't be blank")
    String name,
    @NotBlank(message = "Description mustn't be blank")
    String description,
    @NotNull(message = "Price mustn't be null")
    @DecimalMin(value = "1.0", message = "Price must be at least 1.0")
    BigDecimal price,
    @NotNull(message = "Warranty months mustn't be null")
    @Min(value = 0, message = "Warranty months mustn't be negative")
    Integer warrantyMonths,
    Map<String, String> attributes
) {}
