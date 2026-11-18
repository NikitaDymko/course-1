package io.github.mitohondriyaa.order.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Позиция заказа. Цена и срок гарантии сохраняются на момент оформления,
 * чтобы последующее изменение прайс-листа не меняло ранее рассчитанную сумму.
 */
@Entity
@Table(name = "t_order_items")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private String productId;

    @Column(nullable = false)
    private Integer quantity;

    /** Цена позиции на момент оформления, руб. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    /** Срок гарантии на момент оформления, мес. */
    @Column(name = "warranty_months", nullable = false)
    private Integer warrantyMonths;

    public BigDecimal getLineTotal() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
