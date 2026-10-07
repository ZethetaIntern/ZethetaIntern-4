-- Reference data. Everything here is runtime-configurable through the API
-- (PUT /api/v1/gateways/{name}/config, PUT /api/v1/routing/config); nothing is hardcoded.

-- Gateway capability matrix and contract terms (A1.3, A3.4 cost column, A8.4 rate limits).
-- UPI is only served by the UPI (NPCI) gateway, per A3.1 "Payment Method Fit".
INSERT INTO gateway_config (gateway_name, display_name, enabled, supported_methods, supported_currencies,
                            fee_bps, fixed_fee_paise, auth_timeout_ms, rate_limit_per_sec, rate_limit_strategy,
                            supports_auth_capture, supports_partial_refund, auth_hold_days, refund_window_days,
                            settlement_cycle, webhook_signature_scheme)
VALUES
    ('razorpay', 'Razorpay', TRUE, 'CARD,NETBANKING,WALLET', 'INR',
     200, 200, 30000, 200, 'TOKEN_BUCKET', TRUE, TRUE, 5, 180, 'T+2 business days', 'HMAC_SHA256'),
    ('stripe', 'Stripe', TRUE, 'CARD', 'INR,USD,EUR,GBP,SGD,AED',
     250, 300, 30000, 100, 'RETRY_AFTER_BACKOFF', TRUE, TRUE, 7, 180, 'T+2 calendar days', 'STRIPE_SIGNATURE'),
    ('payu', 'PayU', TRUE, 'CARD,NETBANKING,WALLET', 'INR',
     180, 150, 45000, 150, 'SLIDING_WINDOW', TRUE, TRUE, 5, 180, 'T+3 business days', 'HMAC_SHA512'),
    ('upi', 'UPI (NPCI)', TRUE, 'UPI', 'INR',
     0, 0, 60000, 100, 'TOKEN_BUCKET', FALSE, FALSE, 0, 180, 'T+0 to T+1', 'RSA_SHA256');

-- Routing weights (A3.1 defaults) and thresholds.
INSERT INTO routing_config (config_key, config_value, description) VALUES
    ('weight.success_rate', 0.35, 'W_success: weight of sliding-window success rate'),
    ('weight.latency', 0.20, 'W_latency: weight of (1 - normalised P95 latency)'),
    ('weight.cost', 0.20, 'W_cost: weight of (1 - normalised transaction cost)'),
    ('weight.health', 0.15, 'W_health: weight of circuit-breaker health score (1 / 0.5 / 0)'),
    ('weight.method_fit', 0.10, 'W_fit: weight of payment-method fit (1 / 0)'),
    ('window.minutes', 15, 'N: sliding window length for success rate and P95 latency'),
    ('window.min_samples', 20, 'Below this many live samples the A3.4 historical band is used'),
    ('health.degraded_success_rate', 0.90, 'Success rate below this marks a CLOSED gateway as DEGRADED'),
    ('routing.degraded_margin', 0.20, 'Keep a degraded top gateway only if it leads the runner-up by more than this');

-- Circuit breaker defaults (A3.3). Rows can be added per gateway and per payment method.
INSERT INTO circuit_breaker_config (gateway, payment_method, failure_threshold, open_timeout_ms, half_open_max_requests)
VALUES ('*', '*', 5, 30000, 1);

-- Historical gateway performance dataset (A3.4), exactly as given in the brief.
INSERT INTO gateway_historical_performance
    (gateway, band_start_hour, band_end_hour, success_rate, p95_latency_ms, transactions, fee_bps, fixed_fee_paise)
VALUES
    ('razorpay', 0, 6, 0.9850, 320, 4200, 200, 200),
    ('razorpay', 6, 12, 0.9720, 450, 18500, 200, 200),
    ('razorpay', 12, 18, 0.9410, 780, 32000, 200, 200),
    ('razorpay', 18, 24, 0.9680, 520, 25300, 200, 200),
    ('stripe', 0, 6, 0.9910, 280, 3800, 250, 300),
    ('stripe', 6, 12, 0.9880, 310, 15200, 250, 300),
    ('stripe', 12, 18, 0.9750, 420, 28500, 250, 300),
    ('stripe', 18, 24, 0.9820, 350, 22100, 250, 300),
    ('payu', 0, 6, 0.9600, 400, 2100, 180, 150),
    ('payu', 6, 12, 0.9350, 620, 9800, 180, 150),
    ('payu', 12, 18, 0.8920, 950, 15500, 180, 150),
    ('payu', 18, 24, 0.9180, 750, 12200, 180, 150),
    ('upi', 0, 6, 0.9950, 180, 5600, 0, 0),
    ('upi', 6, 12, 0.9920, 210, 22000, 0, 0),
    ('upi', 12, 18, 0.9800, 350, 38000, 0, 0),
    ('upi', 18, 24, 0.9880, 250, 30500, 0, 0);
