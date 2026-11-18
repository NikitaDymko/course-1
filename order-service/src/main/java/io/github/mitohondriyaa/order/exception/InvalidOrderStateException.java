package io.github.mitohondriyaa.order.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Операция недопустима в текущем состоянии заказа
 * (например, выдача до подтверждения оплаты).
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class InvalidOrderStateException extends RuntimeException {
    public InvalidOrderStateException(String message) {
        super(message);
    }
}
