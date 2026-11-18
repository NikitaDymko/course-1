package io.github.mitohondriyaa.order.service;

import io.github.mitohondriyaa.order.exception.DiscountNotApprovedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Расчет стоимости заказа.
 *
 * S = Σ pi · qi
 * C = S · (1 – d / 100)
 *
 * Денежные величины хранятся в десятичном формате, округление выполняется
 * один раз для итоговой стоимости.
 */
@Component
public class PriceCalculator {

    /** Порог скидки, которую продавец может назначить без руководителя, %. */
    @Value("${order.discount.threshold:5}")
    private BigDecimal discountThreshold;

    /**
     * Сумма заказа без скидки.
     */
    public BigDecimal calculateTotal(BigDecimal price, int quantity) {
        return price.multiply(BigDecimal.valueOf(quantity))
            .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Итоговая стоимость. Округление до 0,01 выполняется один раз.
     */
    public BigDecimal calculateFinalAmount(BigDecimal total, BigDecimal discount) {
        BigDecimal safeDiscount = discount == null ? BigDecimal.ZERO : discount;

        return total
            .multiply(BigDecimal.ONE.subtract(
                safeDiscount.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
            ))
            .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Скидка свыше порога допускается только с подтверждением руководителя.
     */
    public void validateDiscount(BigDecimal discount, boolean managerApproved) {
        BigDecimal safeDiscount = discount == null ? BigDecimal.ZERO : discount;

        if (safeDiscount.compareTo(discountThreshold) > 0 && !managerApproved) {
            throw new DiscountNotApprovedException(
                "Discount above %s%% requires manager approval".formatted(discountThreshold)
            );
        }
    }

    public BigDecimal getDiscountThreshold() {
        return discountThreshold;
    }
}
