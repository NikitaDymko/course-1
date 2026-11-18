package io.github.mitohondriyaa.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Запрос на оформление заказа: несколько позиций, контактные данные покупателя
 * и скидка. Скидка свыше порога (5 %) требует подтверждения руководителя.
 */
public record OrderRequest(
    @NotEmpty(message = "order must contain at least one item")
    @Valid
    List<OrderItemRequest> items,
    @NotNull(message = "customer mustn't be null")
    @Valid
    CustomerRequest customer,
    @DecimalMin(value = "0.0", message = "discount mustn't be negative")
    @DecimalMax(value = "100.0", message = "discount mustn't exceed 100")
    BigDecimal discount,
    Boolean managerApproved
) {}
