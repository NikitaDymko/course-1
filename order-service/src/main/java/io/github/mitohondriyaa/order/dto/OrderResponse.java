package io.github.mitohondriyaa.order.dto;

import io.github.mitohondriyaa.order.model.OrderStatus;
import io.github.mitohondriyaa.order.model.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderResponse(
    Long id,
    String orderNumber,
    OrderStatus status,
    List<OrderItemResponse> items,
    BigDecimal discount,
    BigDecimal totalAmount,
    BigDecimal finalAmount,
    PaymentMethod paymentMethod,
    String receiptNumber,
    LocalDateTime paidAt,
    LocalDateTime issuedAt,
    LocalDateTime createdAt,
    UserDetailsResponse customer
) {}
