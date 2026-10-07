package com.payflow.gateway;

import com.payflow.config.PayFlowProperties;
import com.payflow.domain.PaymentMethod;
import com.payflow.entity.Transaction;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared behaviour of the four mock gateways (Day 5-6, ADR-002). Each adapter
 * supplies its own reference format, idempotency mechanism, error vocabulary and
 * response shape; this base applies the B4.3 mock-control headers and keeps the
 * gateway-side books in {@link MockGatewayLedger}.
 *
 * <p>Without control headers every call succeeds, so the harness warm-up is
 * deterministic. {@code payflow.mock.random-failures=true} injects the A3.4
 * failure rates instead.</p>
 */
public abstract class SimulatedGateway implements PaymentGateway {

    /** The gateway's own read timeout used when simulating "no response" (A1.3). */
    private static final long SIMULATED_HANG_MS = 30_000;

    protected final MockGatewayLedger ledger;
    private final PayFlowProperties props;
    private final java.time.Duration upiCollectWindow;

    protected SimulatedGateway(MockGatewayLedger ledger, PayFlowProperties props) {
        this.ledger = ledger;
        this.props = props;
        this.upiCollectWindow = props.upi().collectWindow();
    }

    /** Gateway-specific reference, e.g. {@code pay_L1a2b3c4d5e6} for Razorpay. */
    protected abstract String newReference();

    /** How this gateway receives the idempotency token (header or parameter name). */
    protected abstract String idempotencyField();

    /** Gateway-specific decline code and description (translated by the error catalog, A7.2). */
    protected abstract String[] declineCodeAndDescription();

    /** Typical P95 latency from the A3.4 dataset, used when latency simulation is on. */
    protected abstract long typicalLatencyMs();

    /** Failure rate from the A3.4 dataset, used when random failures are on. */
    protected abstract double historicalFailureRate();

    /** Gateway-shaped response body for an authorisation / payment object. */
    protected abstract Map<String, Object> paymentObject(MockGatewayLedger.Charge charge, GatewayStatus status,
                                                         String currency, PaymentMethod method);

    /** Whether authorisation returns a separate hold (two-phase) or settles instantly. */
    protected boolean instantCapture() {
        return false;
    }

    @Override
    public AuthResponse authorize(AuthRequest req) throws GatewayException {
        long responseDelay = simulate("auth");
        GatewayStatus initial = initialAuthStatus(req);
        Instant collectExpiry = initial == GatewayStatus.PENDING ? Instant.now().plus(upiCollectWindow) : null;
        MockGatewayLedger.Charge charge = ledger.authorise(name(), req.idempotencyKey(), req.transactionId(),
                req.amountPaise(), initial, collectExpiry, this::newReference);
        // The gateway has processed the payment; its HTTP response may still be slow (FS-06).
        sleep(responseDelay);
        GatewayStatus status = ledger.currentStatus(charge);
        Map<String, Object> raw = paymentObject(charge, status, req.currency(), req.method());
        raw.put("_request_headers", requestHeaders(req.traceId(), req.idempotencyKey()));
        return new AuthResponse(status, charge.reference(), raw);
    }

    private GatewayStatus initialAuthStatus(AuthRequest req) {
        MockControl.Mode mode = MockControl.current().modeFor(name(), "auth");
        if (mode == MockControl.Mode.PENDING || req.upiFlow() == Transaction.UpiFlow.COLLECT) {
            return GatewayStatus.PENDING;
        }
        return instantCapture() ? GatewayStatus.CAPTURED : GatewayStatus.AUTHORISED;
    }

    @Override
    public CaptureResponse capture(CaptureRequest req) throws GatewayException {
        sleep(simulate("capture"));
        MockGatewayLedger.Charge charge = requireCharge(req.reference(), req.transactionId());
        if (ledger.currentStatus(charge) == GatewayStatus.EXPIRED) {
            throw GatewayException.declined(name(), "AUTH_EXPIRED", "authorisation hold has expired");
        }
        ledger.capture(charge, req.amountPaise());
        Map<String, Object> raw = paymentObject(charge, GatewayStatus.CAPTURED, "INR", null);
        raw.put("amount_captured", req.amountPaise());
        raw.put("_request_headers", requestHeaders(req.traceId(), req.idempotencyKey()));
        return new CaptureResponse(charge.reference(), req.amountPaise(), raw);
    }

    @Override
    public RefundResponse refund(RefundRequest req) throws GatewayException {
        sleep(simulate("refund"));
        MockGatewayLedger.Charge charge = requireCharge(req.reference(), req.transactionId());
        ledger.refund(charge, req.amountPaise());
        String refundId = "rfnd_" + shortId();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", refundId);
        raw.put("payment_id", charge.reference());
        raw.put("amount", req.amountPaise());
        raw.put("status", "processed");
        raw.put("_request_headers", requestHeaders(req.traceId(), req.refundId().toString()));
        return new RefundResponse(refundId, raw);
    }

    @Override
    public void voidAuthorisation(VoidRequest req) throws GatewayException {
        sleep(simulate("void"));
        MockGatewayLedger.Charge charge = requireCharge(req.reference(), req.transactionId());
        if (charge.capturedPaise() == 0) ledger.setStatus(charge, GatewayStatus.VOIDED);
    }

    @Override
    public StatusResponse fetchStatus(String reference, UUID transactionId) throws GatewayException {
        sleep(simulate("status"));
        return ledger.find(name(), reference, transactionId)
                .map(c -> {
                    GatewayStatus s = ledger.currentStatus(c);
                    Map<String, Object> raw = paymentObject(c, s, "INR", null);
                    // A capture the gateway processed but never acknowledged (late success) covers the hold.
                    long captured = s == GatewayStatus.CAPTURED && c.capturedPaise() == 0
                            ? c.amountPaise() : c.capturedPaise();
                    return new StatusResponse(s, c.reference(), captured, raw);
                })
                .orElseGet(() -> new StatusResponse(GatewayStatus.UNKNOWN, reference, 0, Map.of()));
    }

    @Override
    public Map<String, SettlementEntry> settlementReport(Collection<String> references) {
        String batch = name() + "_setl_" + java.time.LocalDate.now();
        Map<String, SettlementEntry> report = new LinkedHashMap<>();
        for (String ref : references) {
            GatewayStatus s = ledger.settlementStatus(name(), ref);
            report.put(ref, new SettlementEntry(s, s == GatewayStatus.SETTLED ? batch : null));
        }
        return report;
    }

    private MockGatewayLedger.Charge requireCharge(String reference, UUID transactionId) throws GatewayException {
        return ledger.find(name(), reference, transactionId)
                .orElseThrow(() -> GatewayException.declined(name(), "PAYMENT_NOT_FOUND",
                        "no payment with reference " + reference));
    }

    /**
     * Applies the B4.3 control headers for this gateway and operation. Failure
     * modes are raised after the configured delay; for a successful call the
     * delay is returned so it is spent after the gateway records the operation.
     */
    private long simulate(String operation) throws GatewayException {
        MockControl control = MockControl.current();
        if (control.isDown(name())) {
            sleep(5);
            throw GatewayException.unreachable(name());
        }
        long delay = control.delayMsFor(name(), operation);
        MockControl.Mode mode = control.modeFor(name(), operation);
        if (mode == null) {
            simulateNaturalBehaviour(operation);
            return delay;
        }
        if (mode != MockControl.Mode.SUCCESS && mode != MockControl.Mode.PENDING) sleep(delay);
        switch (mode) {
            case TIMEOUT -> {
                sleep(SIMULATED_HANG_MS); // the orchestrator's attempt budget interrupts this
                throw GatewayException.timeout(name(), SIMULATED_HANG_MS);
            }
            case SERVER_ERROR -> throw GatewayException.serverError(name(), 502, "Bad Gateway");
            case RATE_LIMIT -> throw GatewayException.rateLimited(name(), control.retryAfterMs());
            case DECLINE -> {
                String[] d = declineCodeAndDescription();
                throw GatewayException.declined(name(), d[0], d[1]);
            }
            default -> { /* SUCCESS / PENDING */ }
        }
        return delay;
    }

    private void simulateNaturalBehaviour(String operation) throws GatewayException {
        if (props.mock().simulateLatency()) {
            long base = typicalLatencyMs() / 4;
            sleep(base + ThreadLocalRandom.current().nextLong(Math.max(1, base)));
        }
        if (props.mock().randomFailures() && "auth".equals(operation)
                && ThreadLocalRandom.current().nextDouble() < historicalFailureRate()) {
            throw GatewayException.serverError(name(), 503, "Service Unavailable");
        }
    }

    /** Headers an HTTP adapter would send: trace propagation (A8.5) and the idempotency token. */
    protected Map<String, Object> requestHeaders(String traceId, String idempotencyKey) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("X-Trace-Id", traceId);
        h.put(idempotencyField(), idempotencyKey);
        return h;
    }

    protected static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    protected static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private static void sleep(long ms) throws GatewayException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException("mock", com.payflow.domain.AttemptOutcome.TIMEOUT, "INTERRUPTED",
                    "call abandoned by orchestrator", null, 0);
        }
    }
}
