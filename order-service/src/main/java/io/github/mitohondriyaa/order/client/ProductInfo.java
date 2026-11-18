package io.github.mitohondriyaa.order.client;

import java.math.BigDecimal;

/**
 * Данные товара, необходимые для оформления заказа:
 * цена и срок гарантии берутся из каталога.
 */
public record ProductInfo(
    String id,
    String sku,
    String name,
    BigDecimal price,
    Integer warrantyMonths
) {}
