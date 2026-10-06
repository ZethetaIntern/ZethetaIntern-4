CREATE TABLE transactions (
    id VARCHAR(36) PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    merchant_id VARCHAR(255) NOT NULL,
    merchant_order_id VARCHAR(255) NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    currency VARCHAR(8) NOT NULL,
    payment_method VARCHAR(32) NOT NULL,
    state VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    gateway VARCHAR(32),
    gateway_reference VARCHAR(255),
    failure_reason VARCHAR(512),
    attempts_made INTEGER NOT NULL DEFAULT 0,
    captured_paise BIGINT NOT NULL DEFAULT 0 CHECK (captured_paise >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_txn_merchant_idem UNIQUE (merchant_id, idempotency_key)
);
CREATE INDEX idx_txn_order ON transactions (merchant_order_id);
CREATE INDEX idx_txn_state ON transactions (state);
CREATE INDEX idx_txn_gateway_ref ON transactions (gateway_reference);

CREATE TABLE transaction_state_log (
    id VARCHAR(36) PRIMARY KEY,
    transaction_id VARCHAR(36) NOT NULL REFERENCES transactions(id),
    from_state VARCHAR(32),
    to_state VARCHAR(32),
    actor VARCHAR(64) NOT NULL,
    detail TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_log_txn ON transaction_state_log (transaction_id);
CREATE INDEX idx_log_created ON transaction_state_log (created_at);

CREATE TABLE gateway_routes (
    gateway VARCHAR(32) PRIMARY KEY,
    supports_upi BOOLEAN NOT NULL DEFAULT TRUE,
    cost_bps INTEGER NOT NULL,
    fixed_cost_paise INTEGER NOT NULL,
    base_latency_ms INTEGER NOT NULL,
    healthy BOOLEAN NOT NULL DEFAULT TRUE,
    success_count INTEGER NOT NULL DEFAULT 0,
    failure_count INTEGER NOT NULL DEFAULT 0,
    total_latency_ms BIGINT NOT NULL DEFAULT 0,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    circuit_open_until TIMESTAMP WITH TIME ZONE NOT NULL,
    probe_lease_until TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE routing_config (
    id BIGINT PRIMARY KEY,
    weight_success DOUBLE PRECISION NOT NULL,
    weight_latency DOUBLE PRECISION NOT NULL,
    weight_cost DOUBLE PRECISION NOT NULL,
    weight_health DOUBLE PRECISION NOT NULL,
    weight_method_fit DOUBLE PRECISION NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE gateway_attempts (
    id VARCHAR(36) PRIMARY KEY,
    transaction_id VARCHAR(36) NOT NULL REFERENCES transactions(id),
    gateway VARCHAR(32) NOT NULL,
    attempt_no INTEGER NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    latency_ms BIGINT NOT NULL,
    error VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_att_txn ON gateway_attempts (transaction_id);
CREATE INDEX idx_att_gateway_time ON gateway_attempts (gateway, created_at);

CREATE TABLE idempotency_keys (
    idem_pk VARCHAR(64) PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    merchant_id VARCHAR(255) NOT NULL,
    transaction_id VARCHAR(36) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_idem_merchant_key UNIQUE (merchant_id, idempotency_key)
);
CREATE INDEX idx_idem_merchant_key ON idempotency_keys (merchant_id, idempotency_key);
CREATE INDEX idx_idem_expires ON idempotency_keys (expires_at);

CREATE TABLE processed_webhook_events (
    event_key VARCHAR(320) PRIMARY KEY,
    gateway VARCHAR(32) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    transaction_id VARCHAR(36),
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE reconciliation_log (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    transaction_id VARCHAR(36),
    discrepancy_type VARCHAR(64) NOT NULL,
    detail TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_recon_run ON reconciliation_log (run_id);
CREATE INDEX idx_recon_txn ON reconciliation_log (transaction_id);

CREATE TABLE refunds (
    id VARCHAR(36) PRIMARY KEY,
    transaction_id VARCHAR(36) NOT NULL REFERENCES transactions(id),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    gateway VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_refund_txn ON refunds (transaction_id);

CREATE TABLE gateway_hourly_metrics (
    id VARCHAR(32) PRIMARY KEY,
    gateway VARCHAR(32) NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    success_rate DOUBLE PRECISION NOT NULL,
    p95_latency_ms INTEGER NOT NULL,
    transaction_count INTEGER NOT NULL
);
CREATE INDEX idx_hourly_gw ON gateway_hourly_metrics (gateway, recorded_at);

CREATE TABLE gateway_route_selections (
    id VARCHAR(36) PRIMARY KEY,
    transaction_id VARCHAR(36) NOT NULL REFERENCES transactions(id),
    gateway VARCHAR(32) NOT NULL,
    score DOUBLE PRECISION NOT NULL,
    rank INTEGER NOT NULL,
    attempt_no INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_route_sel_txn ON gateway_route_selections (transaction_id);

CREATE TABLE webhook_queue (
    id VARCHAR(36) PRIMARY KEY,
    gateway VARCHAR(32) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    signature TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 3,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_webhook_queue_status ON webhook_queue (status);

CREATE TABLE anomalies (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    transaction_id VARCHAR(36) NOT NULL REFERENCES transactions(id),
    internal_state VARCHAR(32) NOT NULL,
    gateway_status VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    detail TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    alerted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_anomaly_txn ON anomalies (transaction_id);
CREATE INDEX idx_anomaly_run ON anomalies (run_id);
