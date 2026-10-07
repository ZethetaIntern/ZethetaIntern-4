package com.payflow.entity;

import com.payflow.domain.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gateway connection details, capability matrix, contract terms and feature
 * flags (table {@code gateway_config}, spec A6.1). Editable at runtime through
 * {@code PUT /api/v1/gateways/{name}/config}.
 */
@Entity
@Table(name = "gateway_config")
public class GatewayConfig {

    public enum RateLimitStrategy { TOKEN_BUCKET, SLIDING_WINDOW, RETRY_AFTER_BACKOFF }

    @Id
    @Column(name = "gateway_name", length = 32)
    private String gatewayName;

    @Column(name = "display_name", nullable = false, length = 64)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Comma-separated {@link PaymentMethod} names. */
    @Column(name = "supported_methods", nullable = false, length = 128)
    private String supportedMethods;

    @Column(name = "supported_currencies", nullable = false, length = 128)
    private String supportedCurrencies;

    /** Percentage fee in basis points (200 = 2.00%). */
    @Column(name = "fee_bps", nullable = false)
    private int feeBps;

    @Column(name = "fixed_fee_paise", nullable = false)
    private long fixedFeePaise;

    /** The gateway's own authorisation timeout (A1.3). The orchestrator's attempt budget is shorter. */
    @Column(name = "auth_timeout_ms", nullable = false)
    private int authTimeoutMs;

    @Column(name = "rate_limit_per_sec", nullable = false)
    private int rateLimitPerSec;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "rate_limit_strategy", nullable = false, length = 32)
    private RateLimitStrategy rateLimitStrategy;

    @Column(name = "supports_auth_capture", nullable = false)
    private boolean supportsAuthCapture;

    @Column(name = "supports_partial_refund", nullable = false)
    private boolean supportsPartialRefund;

    @Column(name = "auth_hold_days", nullable = false)
    private int authHoldDays;

    @Column(name = "refund_window_days", nullable = false)
    private int refundWindowDays;

    @Column(name = "settlement_cycle", nullable = false, length = 32)
    private String settlementCycle;

    @Column(name = "webhook_signature_scheme", nullable = false, length = 32)
    private String webhookSignatureScheme;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Set<PaymentMethod> methods() {
        return Arrays.stream(supportedMethods.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(PaymentMethod::parse)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean supports(PaymentMethod method) {
        return methods().contains(method);
    }

    public boolean supportsCurrency(String currency) {
        return Arrays.stream(supportedCurrencies.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .anyMatch(c -> c.equals(currency.toUpperCase(Locale.ROOT)));
    }

    /** Fee for a transaction, in paise, rounded half-up (integer arithmetic only). */
    public long feeFor(long amountPaise) {
        return (amountPaise * feeBps + 5_000) / 10_000 + fixedFeePaise;
    }

    public String getGatewayName() { return gatewayName; }
    public void setGatewayName(String gatewayName) { this.gatewayName = gatewayName; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSupportedMethods() { return supportedMethods; }
    public void setSupportedMethods(String supportedMethods) { this.supportedMethods = supportedMethods; }
    public String getSupportedCurrencies() { return supportedCurrencies; }
    public void setSupportedCurrencies(String supportedCurrencies) { this.supportedCurrencies = supportedCurrencies; }
    public int getFeeBps() { return feeBps; }
    public void setFeeBps(int feeBps) { this.feeBps = feeBps; }
    public long getFixedFeePaise() { return fixedFeePaise; }
    public void setFixedFeePaise(long fixedFeePaise) { this.fixedFeePaise = fixedFeePaise; }
    public int getAuthTimeoutMs() { return authTimeoutMs; }
    public void setAuthTimeoutMs(int authTimeoutMs) { this.authTimeoutMs = authTimeoutMs; }
    public int getRateLimitPerSec() { return rateLimitPerSec; }
    public void setRateLimitPerSec(int rateLimitPerSec) { this.rateLimitPerSec = rateLimitPerSec; }
    public RateLimitStrategy getRateLimitStrategy() { return rateLimitStrategy; }
    public void setRateLimitStrategy(RateLimitStrategy s) { this.rateLimitStrategy = s; }
    public boolean isSupportsAuthCapture() { return supportsAuthCapture; }
    public void setSupportsAuthCapture(boolean v) { this.supportsAuthCapture = v; }
    public boolean isSupportsPartialRefund() { return supportsPartialRefund; }
    public void setSupportsPartialRefund(boolean v) { this.supportsPartialRefund = v; }
    public int getAuthHoldDays() { return authHoldDays; }
    public void setAuthHoldDays(int authHoldDays) { this.authHoldDays = authHoldDays; }
    public int getRefundWindowDays() { return refundWindowDays; }
    public void setRefundWindowDays(int refundWindowDays) { this.refundWindowDays = refundWindowDays; }
    public String getSettlementCycle() { return settlementCycle; }
    public void setSettlementCycle(String settlementCycle) { this.settlementCycle = settlementCycle; }
    public String getWebhookSignatureScheme() { return webhookSignatureScheme; }
    public void setWebhookSignatureScheme(String s) { this.webhookSignatureScheme = s; }
    public Instant getUpdatedAt() { return updatedAt; }
}
