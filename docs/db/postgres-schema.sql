-- PayFlow schema — PostgreSQL 15+ (authoritative DDL; Hibernate ddl-auto=update
-- also generates this schema for H2-based dev/test runs).

CREATE TABLE IF NOT EXISTS transactions (
    id                       VARCHAR(64) PRIMARY KEY,
    idempotency_key          VARCHAR(255) NOT NULL UNIQUE,
    merchant_order_id        VARCHAR(255) NOT NULL,
    amount_paise             BIGINT       NOT NULL CHECK (amount_paise > 0),
    currency                 VARCHAR(8)   NOT NULL DEFAULT 'INR',
    payment_method           VARCHAR(32)  NOT NULL,
    state                    VARCHAR(32)  NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0,
    gateway                  VARCHAR(50),
    gateway_reference        VARCHAR(255),
    failure_reason           VARCHAR(512),
    attempts_made            INT          NOT NULL DEFAULT 0,
    captured_paise           BIGINT       NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_txn_order       ON transactions (merchant_order_id);
CREATE INDEX IF NOT EXISTS idx_txn_state       ON transactions (state);
CREATE INDEX IF NOT EXISTS idx_txn_gateway_ref ON transactions (gateway_reference);

CREATE TABLE IF NOT EXISTS transaction_state_log (
    id             VARCHAR(64) PRIMARY KEY,
    transaction_id VARCHAR(64)  NOT NULL,
    from_state     VARCHAR(32),
    to_state       VARCHAR(32),
    actor          VARCHAR(64)  NOT NULL,
    detail         TEXT,
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_log_txn     ON transaction_state_log (transaction_id);
CREATE INDEX IF NOT EXISTS idx_log_created ON transaction_state_log (created_at);

CREATE TABLE IF NOT EXISTS gateway_routes (
    gateway              VARCHAR(50) PRIMARY KEY,
    supports_upi         BOOLEAN     NOT NULL DEFAULT TRUE,
    cost_bps             INT         NOT NULL,
    fixed_cost_paise     INT         NOT NULL,
    base_latency_ms      INT         NOT NULL,
    healthy              BOOLEAN     NOT NULL DEFAULT TRUE,
    success_count        INT         NOT NULL DEFAULT 0,
    failure_count        INT         NOT NULL DEFAULT 0,
    total_latency_ms     BIGINT      NOT NULL DEFAULT 0,
    consecutive_failures INT         NOT NULL DEFAULT 0,
    circuit_open_until   TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS routing_config (
    id               BIGINT PRIMARY KEY,
    weight_success   DOUBLE PRECISION NOT NULL,
    weight_latency   DOUBLE PRECISION NOT NULL,
    weight_cost      DOUBLE PRECISION NOT NULL,
    weight_health    DOUBLE PRECISION NOT NULL,
    weight_method_fit DOUBLE PRECISION NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS gateway_attempts (
    id             VARCHAR(64) PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL,
    gateway        VARCHAR(50) NOT NULL,
    attempt_no     INT         NOT NULL,
    outcome        VARCHAR(16) NOT NULL,
    latency_ms     BIGINT,
    error          VARCHAR(255),
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_att_txn ON gateway_attempts (transaction_id);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    transaction_id  VARCHAR(64) NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS processed_webhook_events (
    event_key      VARCHAR(320) PRIMARY KEY,
    gateway        VARCHAR(50)  NOT NULL,
    event_id       VARCHAR(255) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload_hash   VARCHAR(64)  NOT NULL,
    transaction_id VARCHAR(64),
    processed_at   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS reconciliation_log (
    id               VARCHAR(64) PRIMARY KEY,
    run_id           VARCHAR(64) NOT NULL,
    transaction_id   VARCHAR(64),
    discrepancy_type VARCHAR(64) NOT NULL,
    detail           TEXT,
    created_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_recon_run ON reconciliation_log (run_id);
CREATE INDEX IF NOT EXISTS idx_recon_txn ON reconciliation_log (transaction_id);

CREATE TABLE IF NOT EXISTS refunds (
    id             VARCHAR(64) PRIMARY KEY,
    transaction_id VARCHAR(64) NOT NULL,
    amount_paise   BIGINT      NOT NULL,
    gateway        VARCHAR(50) NOT NULL,
    status         VARCHAR(32) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_refund_txn ON refunds (transaction_id);
