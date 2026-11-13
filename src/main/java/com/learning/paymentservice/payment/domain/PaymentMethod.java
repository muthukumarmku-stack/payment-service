package com.learning.paymentservice.payment.domain;

/**
 * How the payer pays. Must stay in sync with the {@code ck_payments_method}
 * check constraint in the Flyway migrations.
 */
public enum PaymentMethod {
    CARD,
    BANK_TRANSFER,
    UPI,
    WALLET
}
