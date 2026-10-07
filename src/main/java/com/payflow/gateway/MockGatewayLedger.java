package com.payflow.gateway;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The simulated gateways' own books: what each mock gateway believes it has
 * charged. It gives the simulation the properties the orchestrator relies on
 * in production:
 * <ul>
 *   <li>gateway-side idempotency: the same idempotency token returns the same charge (FS-03, C1.4)</li>
 *   <li>a status API that can disagree with our records (A5.5, FS-04 late success, FS-11)</li>
 *   <li>a settlement report per reference (C2.4)</li>
 * </ul>
 * Test and harness code can override statuses through {@code /api/v1/mock/...}.
 */
@Component
public class MockGatewayLedger {

    /** One charge as the gateway sees it. */
    public static final class Charge {
        private final String gateway;
        private final String reference;
        private final UUID transactionId;
        private final long amountPaise;
        private final Instant createdAt = Instant.now();
        private final Instant collectExpiresAt;
        private volatile GatewayStatus status;
        private final AtomicLong captured = new AtomicLong();
        private final AtomicLong refunded = new AtomicLong();

        Charge(String gateway, String reference, UUID transactionId, long amountPaise, GatewayStatus status,
               Instant collectExpiresAt) {
            this.gateway = gateway;
            this.reference = reference;
            this.transactionId = transactionId;
            this.amountPaise = amountPaise;
            this.status = status;
            this.collectExpiresAt = collectExpiresAt;
        }

        public String gateway() { return gateway; }
        public String reference() { return reference; }
        public UUID transactionId() { return transactionId; }
        public long amountPaise() { return amountPaise; }
        public Instant createdAt() { return createdAt; }
        public GatewayStatus status() { return status; }
        public long capturedPaise() { return captured.get(); }
        public long refundedPaise() { return refunded.get(); }
        public Instant collectExpiresAt() { return collectExpiresAt; }
    }

    private final Map<String, Charge> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, Charge> byReference = new ConcurrentHashMap<>();
    private final Map<String, Charge> byTransaction = new ConcurrentHashMap<>();
    private final Map<String, GatewayStatus> statusOverrides = new ConcurrentHashMap<>();
    private final Map<String, GatewayStatus> settlementOverrides = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> chargesCreated = new ConcurrentHashMap<>();

    /** Creates the charge once per idempotency token; a replayed token returns the original charge. */
    public Charge authorise(String gateway, String idempotencyKey, UUID transactionId, long amountPaise,
                            GatewayStatus initialStatus, Instant collectExpiresAt, Supplier<String> referenceFactory) {
        return byIdempotencyKey.computeIfAbsent(gateway + ":" + idempotencyKey, k -> {
            Charge c = new Charge(gateway, referenceFactory.get(), transactionId, amountPaise, initialStatus,
                    collectExpiresAt);
            if (initialStatus == GatewayStatus.CAPTURED) c.captured.set(amountPaise);
            byReference.put(gateway + ":" + c.reference, c);
            if (transactionId != null) byTransaction.put(gateway + ":" + transactionId, c);
            chargesCreated.computeIfAbsent(gateway, g -> new AtomicLong()).incrementAndGet();
            return c;
        });
    }

    public Optional<Charge> find(String gateway, String reference, UUID transactionId) {
        Charge c = reference == null ? null : byReference.get(gateway + ":" + reference);
        if (c == null && transactionId != null) c = byTransaction.get(gateway + ":" + transactionId);
        return Optional.ofNullable(c);
    }

    public void capture(Charge c, long amountPaise) {
        long total = c.captured.addAndGet(amountPaise);
        c.status = GatewayStatus.CAPTURED;
        if (total > c.amountPaise) c.captured.set(c.amountPaise);
    }

    public void refund(Charge c, long amountPaise) {
        c.refunded.addAndGet(amountPaise);
        if (c.refunded.get() >= c.captured.get()) c.status = GatewayStatus.REFUNDED;
    }

    public void setStatus(Charge c, GatewayStatus status) {
        c.status = status;
    }

    /** The status the gateway's status API would report right now. */
    public GatewayStatus currentStatus(Charge c) {
        GatewayStatus override = statusOverrides.get(c.gateway + ":" + c.reference);
        if (override != null) return override;
        if (c.status == GatewayStatus.PENDING && c.collectExpiresAt != null
                && Instant.now().isAfter(c.collectExpiresAt)) {
            return GatewayStatus.EXPIRED;
        }
        return c.status;
    }

    /** Settlement report entry: overrides first, otherwise captured charges settle. */
    public GatewayStatus settlementStatus(String gateway, String reference) {
        GatewayStatus override = settlementOverrides.get(gateway + ":" + reference);
        if (override != null) return override;
        Charge c = byReference.get(gateway + ":" + reference);
        if (c == null) return GatewayStatus.UNKNOWN;
        GatewayStatus s = currentStatus(c);
        return switch (s) {
            case CAPTURED, REFUNDED -> GatewayStatus.SETTLED;
            case FAILED, REVERSED -> s;
            default -> GatewayStatus.PENDING;
        };
    }

    /** Harness hook: make the status API report {@code status} for a reference. */
    public void overrideStatus(String gateway, String reference, GatewayStatus status) {
        statusOverrides.put(gateway + ":" + reference, status);
    }

    /** Harness hook: make the settlement report show {@code status} for a reference. */
    public void overrideSettlement(String gateway, String reference, GatewayStatus status) {
        settlementOverrides.put(gateway + ":" + reference, status);
    }

    public long chargesCreated(String gateway) {
        AtomicLong n = chargesCreated.get(gateway);
        return n == null ? 0 : n.get();
    }

    public void reset() {
        byIdempotencyKey.clear();
        byReference.clear();
        byTransaction.clear();
        statusOverrides.clear();
        settlementOverrides.clear();
        chargesCreated.clear();
    }
}
