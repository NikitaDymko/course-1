package io.github.mitohondriyaa.order.model;

/**
 * Жизненный цикл заказа.
 *
 * NEW -> ACCEPTED -> AWAITING_PAYMENT -> PAID -> READY -> ISSUED
 * REJECTED  - нет товара на складе
 * CANCELLED - отмена до оплаты
 */
public enum OrderStatus {
    NEW,
    ACCEPTED,
    AWAITING_PAYMENT,
    PAID,
    READY,
    ISSUED,
    REJECTED,
    CANCELLED
}
