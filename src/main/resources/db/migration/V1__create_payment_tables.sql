-- V1: payments and their status-change audit trail.
-- Flyway owns this schema; Hibernate only validates it (ddl-auto: validate).

CREATE TABLE payments (
    id               UUID           PRIMARY KEY,
    reference        VARCHAR(64)    NOT NULL,
    idempotency_key  VARCHAR(100),
    amount           NUMERIC(19, 4) NOT NULL,
    currency         CHAR(3)        NOT NULL,
    method           VARCHAR(30)    NOT NULL,
    status           VARCHAR(30)    NOT NULL,
    payer_id         VARCHAR(64)    NOT NULL,
    payee_id         VARCHAR(64)    NOT NULL,
    description      VARCHAR(255),
    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,

    CONSTRAINT uk_payments_reference       UNIQUE (reference),
    CONSTRAINT uk_payments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_payments_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payments_method CHECK (method IN ('CARD', 'BANK_TRANSFER', 'UPI', 'WALLET')),
    CONSTRAINT ck_payments_status CHECK (status IN
        ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'REFUNDED'))
);

-- Supports the filtered, paged payment history (GET /api/v1/payments).
CREATE INDEX idx_payments_status     ON payments (status);
CREATE INDEX idx_payments_payer_id   ON payments (payer_id);
CREATE INDEX idx_payments_created_at ON payments (created_at DESC);

CREATE TABLE payment_status_history (
    id           BIGSERIAL    PRIMARY KEY,
    payment_id   UUID         NOT NULL,
    from_status  VARCHAR(30),             -- NULL for the initial PENDING entry
    to_status    VARCHAR(30)  NOT NULL,
    reason       VARCHAR(255),
    changed_by   VARCHAR(64),
    changed_at   TIMESTAMPTZ  NOT NULL,

    CONSTRAINT fk_status_history_payment FOREIGN KEY (payment_id) REFERENCES payments (id)
);

-- Supports GET /api/v1/payments/{id}/history (oldest first).
CREATE INDEX idx_status_history_payment ON payment_status_history (payment_id, changed_at);
