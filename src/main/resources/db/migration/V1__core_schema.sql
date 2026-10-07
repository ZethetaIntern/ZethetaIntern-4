-- PayFlow payment orchestration schema (PostgreSQL 15+).
-- Money is always BIGINT paise (A6.2). Never FLOAT/DOUBLE/REAL for amounts.
-- The ten tables listed in A6.1 are created with exactly the names from the brief.

-- ---------------------------------------------------------------------------
-- transactions: core record with current state (A6.1)
-- ---------------------------------------------------------------------------
CREATE TABLE transactions (
    id                  UUID PRIMARY KEY,
    merchant_id         VARCHAR(64)  NOT NULL,
    idempotency_key     VARCHAR(255) NOT NULL,
    merchant_order_id   VARCHAR(255) NOT NULL,
    amount_paise        BIGINT       NOT NULL CHECK (amount_paise > 0),
    currency            VARCHAR(3)   NOT NULL,
    payment_method      VARCHAR(20)  NOT NULL
        CHECK (payment_method IN ('CARD', 'UPI', 'NETBANKING', 'WALLET')),
    capture_mode        VARCHAR(16)  NOT NULL DEFAULT 'AUTOMATIC'
        CHECK (capture_mode IN ('AUTOMATIC', 'MANUAL')),
    upi_flow            VARCHAR(16)
        CHECK (upi_flow IS NULL OR upi_flow IN ('INTENT', 'COLLECT')),
    state               VARCHAR(32)  NOT NULL CHECK (state IN (
        'CREATED', 'ROUTE_SELECTED', 'AUTH_INITIATED', 'AUTHORISED', 'AUTH_FAILED',
        'CAPTURE_INITIATED', 'CAPTURED', 'PARTIALLY_CAPTURED', 'CAPTURE_FAILED',
        'REFUND_INITIATED', 'REFUNDED', 'FAILED', 'ABANDONED', 'VOID_INITIATED', 'VOIDED',
        'AUTH_EXPIRED', 'SETTLED', 'PARTIALLY_REFUNDED', 'REFUND_FAILED',
        'DISPUTE_OPENED', 'DISPUTE_RESOLVED', 'RECONCILIATION_MISMATCH')),
    version             BIGINT       NOT NULL DEFAULT 0,
    gateway             VARCHAR(32),
    gateway_reference   VARCHAR(255),
    captured_paise      BIGINT       NOT NULL DEFAULT 0 CHECK (captured_paise >= 0),
    released_paise      BIGINT       NOT NULL DEFAULT 0 CHECK (released_paise >= 0),
    refunded_paise      BIGINT       NOT NULL DEFAULT 0 CHECK (refunded_paise >= 0),
    attempts_made       INTEGER      NOT NULL DEFAULT 0,
    retry_count         INTEGER      NOT NULL DEFAULT 0,
    next_retry_at       TIMESTAMPTZ,
    failure_code        VARCHAR(64),
    failure_reason      VARCHAR(512),
    trace_id            UUID         NOT NULL,
    authorised_at       TIMESTAMPTZ,
    auth_expires_at     TIMESTAMPTZ,
    settled_at          TIMESTAMPTZ,
    settlement_batch_id VARCHAR(64),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_txn_merchant_idem UNIQUE (merchant_id, idempotency_key),
    -- the hold can never be over-consumed and we can never refund more than we captured
    CONSTRAINT ck_txn_hold CHECK (captured_paise + released_paise <= amount_paise),
    CONSTRAINT ck_txn_refund CHECK (refunded_paise <= captured_paise)
);
CREATE INDEX idx_txn_merchant_order ON transactions (merchant_order_id);
CREATE INDEX idx_txn_gateway_ref ON transactions (gateway_reference);
CREATE INDEX idx_txn_state ON transactions (state);
CREATE INDEX idx_txn_created_at ON transactions (created_at);
CREATE INDEX idx_txn_retry ON transactions (next_retry_at) WHERE next_retry_at IS NOT NULL;
CREATE INDEX idx_txn_auth_expiry ON transactions (auth_expires_at) WHERE auth_expires_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- transaction_state_log: immutable audit trail (A2.3)
-- ---------------------------------------------------------------------------
CREATE TABLE transaction_state_log (
    id                UUID PRIMARY KEY,
    transaction_id    UUID         NOT NULL REFERENCES transactions (id),
    from_state        VARCHAR(32),
    to_state          VARCHAR(32)  NOT NULL,
    event             VARCHAR(100) NOT NULL,
    gateway_reference VARCHAR(255),
    gateway_response  JSONB,
    metadata          JSONB,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(100) NOT NULL
);
CREATE INDEX idx_tsl_transaction ON transaction_state_log (transaction_id);
CREATE INDEX idx_tsl_created_at ON transaction_state_log (created_at);
CREATE INDEX idx_tsl_states ON transaction_state_log (from_state, to_state);

-- Immutability is enforced by the database, not just by convention (A2.3, P5).
CREATE FUNCTION forbid_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tsl_immutable
    BEFORE UPDATE OR DELETE ON transaction_state_log
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();

-- ---------------------------------------------------------------------------
-- gateway_routes: which gateway was chosen for a transaction and why (A6.1)
-- ---------------------------------------------------------------------------
CREATE TABLE gateway_routes (
    id             UUID PRIMARY KEY,
    transaction_id UUID             NOT NULL REFERENCES transactions (id),
    attempt_no     INTEGER          NOT NULL,
    gateway        VARCHAR(32)      NOT NULL,
    rank           INTEGER          NOT NULL,
    score          DOUBLE PRECISION NOT NULL,
    selected       BOOLEAN          NOT NULL,
    health_status  VARCHAR(16)      NOT NULL,
    breakdown      JSONB            NOT NULL,
    reason         VARCHAR(255),
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_routes_transaction ON gateway_routes (transaction_id);
CREATE INDEX idx_routes_gateway ON gateway_routes (gateway);
CREATE INDEX idx_routes_score ON gateway_routes (score);

-- ---------------------------------------------------------------------------
-- idempotency_keys (A4.2), scoped per merchant (FS-13)
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    merchant_id    VARCHAR(64)  NOT NULL,
    key            VARCHAR(255) NOT NULL,
    request_hash   VARCHAR(64)  NOT NULL,
    request_path   VARCHAR(255) NOT NULL,
    transaction_id UUID,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING'
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    response_code  INTEGER,
    response_body  JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW() + INTERVAL '24 hours',
    PRIMARY KEY (merchant_id, key)
);
-- Full index: the cleanup job deletes expired rows of every status, so a partial
-- index excluding COMPLETED rows (as in the brief) cannot serve it. See docs/errors-found.md.
CREATE INDEX idx_idempotency_expires ON idempotency_keys (expires_at);

-- ---------------------------------------------------------------------------
-- processed_webhook_events: deduplication store (A5.4)
-- ---------------------------------------------------------------------------
CREATE TABLE processed_webhook_events (
    event_id       VARCHAR(255) NOT NULL,
    gateway        VARCHAR(50)  NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload_hash   VARCHAR(64)  NOT NULL,
    transaction_id UUID,
    processed_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (gateway, event_id)
);
CREATE INDEX idx_pwe_transaction ON processed_webhook_events (transaction_id);

-- ---------------------------------------------------------------------------
-- webhook_queue: persistent event queue + dead letter queue (A5.2, A8.3)
-- ---------------------------------------------------------------------------
CREATE TABLE webhook_queue (
    id             BIGSERIAL PRIMARY KEY,
    gateway        VARCHAR(50)  NOT NULL,
    event_id       VARCHAR(255) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    transaction_id UUID,
    payload        JSONB        NOT NULL,
    signature      TEXT         NOT NULL,
    source_ip      VARCHAR(64),
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DLQ')),
    retry_count    INTEGER      NOT NULL DEFAULT 0,
    max_retries    INTEGER      NOT NULL DEFAULT 3,
    next_retry_at  TIMESTAMPTZ,
    error_message  TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at   TIMESTAMPTZ,
    CONSTRAINT uk_webhook_queue_event UNIQUE (gateway, event_id)
);
CREATE INDEX idx_webhook_queue_pending ON webhook_queue (next_retry_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_webhook_queue_status ON webhook_queue (status);

-- ---------------------------------------------------------------------------
-- gateway_config: connection details, capabilities and feature flags (A6.1)
-- ---------------------------------------------------------------------------
CREATE TABLE gateway_config (
    gateway_name             VARCHAR(32)  PRIMARY KEY,
    display_name             VARCHAR(64)  NOT NULL,
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    supported_methods        VARCHAR(128) NOT NULL,
    supported_currencies     VARCHAR(128) NOT NULL,
    fee_bps                  INTEGER      NOT NULL CHECK (fee_bps >= 0),
    fixed_fee_paise          BIGINT       NOT NULL CHECK (fixed_fee_paise >= 0),
    auth_timeout_ms          INTEGER      NOT NULL,
    rate_limit_per_sec       INTEGER      NOT NULL CHECK (rate_limit_per_sec > 0),
    rate_limit_strategy      VARCHAR(32)  NOT NULL
        CHECK (rate_limit_strategy IN ('TOKEN_BUCKET', 'SLIDING_WINDOW', 'RETRY_AFTER_BACKOFF')),
    supports_auth_capture    BOOLEAN      NOT NULL,
    supports_partial_refund  BOOLEAN      NOT NULL,
    auth_hold_days           INTEGER      NOT NULL,
    refund_window_days       INTEGER      NOT NULL,
    settlement_cycle         VARCHAR(32)  NOT NULL,
    webhook_signature_scheme VARCHAR(32)  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- routing_config: weights and thresholds as key/value rows (A3.1, A6.1)
-- ---------------------------------------------------------------------------
CREATE TABLE routing_config (
    config_key   VARCHAR(64)   PRIMARY KEY,
    config_value NUMERIC(12,4) NOT NULL,
    description  VARCHAR(255)  NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_by   VARCHAR(100)  NOT NULL DEFAULT 'migration'
);

-- ---------------------------------------------------------------------------
-- Circuit breaker: per gateway AND per payment method, DB-configurable (A3.3)
-- ---------------------------------------------------------------------------
CREATE TABLE circuit_breaker_config (
    id                     BIGSERIAL PRIMARY KEY,
    gateway                VARCHAR(32) NOT NULL,  -- '*' = any gateway
    payment_method         VARCHAR(20) NOT NULL,  -- '*' = any method
    failure_threshold      INTEGER     NOT NULL CHECK (failure_threshold > 0),
    open_timeout_ms        INTEGER     NOT NULL CHECK (open_timeout_ms > 0),
    half_open_max_requests INTEGER     NOT NULL CHECK (half_open_max_requests > 0),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_cb_config UNIQUE (gateway, payment_method)
);

CREATE TABLE circuit_breaker_state (
    gateway              VARCHAR(32) NOT NULL,
    payment_method       VARCHAR(20) NOT NULL,
    state                VARCHAR(16) NOT NULL CHECK (state IN ('CLOSED', 'OPEN', 'HALF_OPEN')),
    consecutive_failures INTEGER     NOT NULL DEFAULT 0,
    half_open_in_flight  INTEGER     NOT NULL DEFAULT 0,
    half_open_successes  INTEGER     NOT NULL DEFAULT 0,
    opened_at            TIMESTAMPTZ,
    last_failure_at      TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version              BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (gateway, payment_method)
);

-- ---------------------------------------------------------------------------
-- gateway_attempts: one row per gateway API call (latency + outcome tracking)
-- ---------------------------------------------------------------------------
CREATE TABLE gateway_attempts (
    id             UUID PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES transactions (id),
    gateway        VARCHAR(32) NOT NULL,
    payment_method VARCHAR(20) NOT NULL,
    operation      VARCHAR(16) NOT NULL CHECK (operation IN ('AUTH', 'CAPTURE', 'REFUND', 'VOID', 'STATUS')),
    attempt_no     INTEGER     NOT NULL,
    outcome        VARCHAR(16) NOT NULL CHECK (outcome IN (
        'SUCCESS', 'PENDING', 'DECLINED', 'TIMEOUT', 'UNREACHABLE', 'SERVER_ERROR', 'RATE_LIMITED')),
    latency_ms     BIGINT      NOT NULL,
    http_status    INTEGER,
    error_code     VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_attempts_transaction ON gateway_attempts (transaction_id);
CREATE INDEX idx_attempts_gateway_time ON gateway_attempts (gateway, created_at);

-- ---------------------------------------------------------------------------
-- gateway_health_metrics: per-minute performance aggregates (A6.1)
-- ---------------------------------------------------------------------------
CREATE TABLE gateway_health_metrics (
    id             BIGSERIAL PRIMARY KEY,
    gateway        VARCHAR(32)      NOT NULL,
    recorded_at    TIMESTAMPTZ      NOT NULL,
    total_count    INTEGER          NOT NULL,
    success_count  INTEGER          NOT NULL,
    success_rate   DOUBLE PRECISION NOT NULL,
    p95_latency_ms INTEGER          NOT NULL,
    avg_latency_ms INTEGER          NOT NULL,
    health_status  VARCHAR(16)      NOT NULL CHECK (health_status IN ('HEALTHY', 'DEGRADED', 'DOWN')),
    CONSTRAINT uk_health_metric_minute UNIQUE (gateway, recorded_at)
);
CREATE INDEX idx_health_metrics_gateway_time ON gateway_health_metrics (gateway, recorded_at);

-- Historical dataset from A3.4 (time-of-day bands); the router's cold-start prior.
CREATE TABLE gateway_historical_performance (
    gateway         VARCHAR(32)  NOT NULL,
    band_start_hour INTEGER      NOT NULL CHECK (band_start_hour BETWEEN 0 AND 23),
    band_end_hour   INTEGER      NOT NULL CHECK (band_end_hour BETWEEN 1 AND 24),
    success_rate    NUMERIC(5,4) NOT NULL,
    p95_latency_ms  INTEGER      NOT NULL,
    transactions    INTEGER      NOT NULL,
    fee_bps         INTEGER      NOT NULL,
    fixed_fee_paise BIGINT       NOT NULL,
    PRIMARY KEY (gateway, band_start_hour)
);

-- ---------------------------------------------------------------------------
-- refunds (A6.1)
-- ---------------------------------------------------------------------------
CREATE TABLE refunds (
    id                UUID PRIMARY KEY,
    transaction_id    UUID         NOT NULL REFERENCES transactions (id),
    amount_paise      BIGINT       NOT NULL CHECK (amount_paise > 0),
    gateway           VARCHAR(32)  NOT NULL,
    gateway_refund_id VARCHAR(255),
    state             VARCHAR(20)  NOT NULL CHECK (state IN ('INITIATED', 'PROCESSED', 'FAILED')),
    failure_reason    VARCHAR(512),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_refunds_transaction ON refunds (transaction_id);
CREATE INDEX idx_refunds_gateway_refund ON refunds (gateway_refund_id);
CREATE INDEX idx_refunds_state ON refunds (state);

-- ---------------------------------------------------------------------------
-- Reconciliation (A5.5): run summaries, per-transaction log, anomaly table
-- ---------------------------------------------------------------------------
CREATE TABLE reconciliation_runs (
    run_id      VARCHAR(64) PRIMARY KEY,
    trigger     VARCHAR(16) NOT NULL CHECK (trigger IN ('SCHEDULED', 'MANUAL')),
    status      VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    started_at  TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT,
    scanned     INTEGER     NOT NULL DEFAULT 0,
    stale       INTEGER     NOT NULL DEFAULT 0,
    overridden  INTEGER     NOT NULL DEFAULT 0,
    settled     INTEGER     NOT NULL DEFAULT 0,
    anomalies   INTEGER     NOT NULL DEFAULT 0
);

CREATE TABLE reconciliation_log (
    id               UUID PRIMARY KEY,
    run_id           VARCHAR(64) NOT NULL REFERENCES reconciliation_runs (run_id),
    transaction_id   UUID,
    discrepancy_type VARCHAR(64) NOT NULL,
    internal_state   VARCHAR(32),
    gateway_status   VARCHAR(32),
    action_taken     VARCHAR(64) NOT NULL,
    detail           TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_recon_log_run ON reconciliation_log (run_id);
CREATE INDEX idx_recon_log_transaction ON reconciliation_log (transaction_id);
CREATE INDEX idx_recon_log_type ON reconciliation_log (discrepancy_type);

CREATE TABLE anomalies (
    id             UUID PRIMARY KEY,
    run_id         VARCHAR(64) NOT NULL,
    transaction_id UUID        NOT NULL REFERENCES transactions (id),
    anomaly_type   VARCHAR(64) NOT NULL,
    internal_state VARCHAR(32) NOT NULL,
    gateway_status VARCHAR(32) NOT NULL,
    severity       VARCHAR(16) NOT NULL CHECK (severity IN ('WARNING', 'CRITICAL')),
    status         VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    detail         TEXT,
    alerted        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_anomalies_transaction ON anomalies (transaction_id);
CREATE INDEX idx_anomalies_run ON anomalies (run_id);

-- ---------------------------------------------------------------------------
-- Security audit log (FS-10): rejected webhooks / API keys, with source IP
-- ---------------------------------------------------------------------------
CREATE TABLE security_audit_log (
    id           BIGSERIAL PRIMARY KEY,
    event_type   VARCHAR(64)  NOT NULL,
    gateway      VARCHAR(32),
    source_ip    VARCHAR(64)  NOT NULL,
    user_agent   VARCHAR(255),
    request_path VARCHAR(255) NOT NULL,
    detail       JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_security_audit_created ON security_audit_log (created_at);
CREATE INDEX idx_security_audit_ip ON security_audit_log (source_ip);

CREATE TRIGGER trg_security_audit_immutable
    BEFORE UPDATE OR DELETE ON security_audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();

-- ---------------------------------------------------------------------------
-- notification_outbox: customer notifications (FS-12), sent by the dispatcher
-- ---------------------------------------------------------------------------
CREATE TABLE notification_outbox (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES transactions (id),
    template       VARCHAR(64) NOT NULL,
    payload        JSONB       NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at        TIMESTAMPTZ
);
CREATE INDEX idx_notification_pending ON notification_outbox (created_at) WHERE status = 'PENDING';
