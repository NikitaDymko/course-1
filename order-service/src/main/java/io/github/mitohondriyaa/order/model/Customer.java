package io.github.mitohondriyaa.order.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Встроенный объект Customer в составе заказа.
 */
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class Customer {
    @Column(name = "customer_email")
    private String email;
    @Column(name = "customer_first_name")
    private String firstName;
    @Column(name = "customer_last_name")
    private String lastName;
    @Column(name = "customer_phone")
    private String phone;
    @Column(name = "customer_consent")
    private Boolean personalDataConsent;
}
