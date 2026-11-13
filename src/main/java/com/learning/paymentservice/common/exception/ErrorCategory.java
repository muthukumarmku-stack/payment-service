package com.learning.paymentservice.common.exception;

/**
 * What kind of failure a {@link BusinessException} is. The web layer turns each
 * category into an HTTP status, so the domain and service code never mention HTTP.
 */
public enum ErrorCategory {
    /** The request itself is wrong. HTTP 400. */
    INVALID_REQUEST,
    /** The thing asked for does not exist. HTTP 404. */
    NOT_FOUND,
    /** The request clashes with the current state. HTTP 409. */
    CONFLICT,
    /** Well-formed, but cannot be processed as sent. HTTP 422. */
    UNPROCESSABLE
}
