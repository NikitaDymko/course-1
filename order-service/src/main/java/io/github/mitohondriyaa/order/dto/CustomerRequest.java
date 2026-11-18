package io.github.mitohondriyaa.order.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Контактные данные покупателя. Согласие на обработку персональных данных
 * обязательно.
 */
public record CustomerRequest(
    @NotBlank(message = "email mustn't be blank")
    @Email(message = "email must be valid")
    String email,
    @NotBlank(message = "firstName mustn't be blank")
    String firstName,
    @NotBlank(message = "lastName mustn't be blank")
    String lastName,
    String phone,
    @NotNull(message = "personalDataConsent must be specified")
    Boolean personalDataConsent
) {}
