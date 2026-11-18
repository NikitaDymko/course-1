package io.github.mitohondriyaa.order.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class DiscountNotApprovedException extends RuntimeException {
    public DiscountNotApprovedException(String message) {
        super(message);
    }
}
