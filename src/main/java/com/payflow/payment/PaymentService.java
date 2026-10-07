package com.payflow.payment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.domain.AuditEvent;
import com.payflow.domain.PaymentMethod;
import com.payflow.domain.TransactionState;
import com.payflow.entity.IdempotencyKey;
import com.payflow.entity.Transaction;
import com.payflow.error.ApiException;
import com.payflow.error.ErrorMapper;
import com.payflow.error.ErrorResponse;
import com.payflow.idempotency.IdempotencyService;
import com.payflow.repository.TransactionRepository;
import com.payflow.statemachine.Audit;
import com.payflow.statemachine.AuditWriter;
import com.payflow.tracing.TraceContext;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * API-facing payment operations. Every mutating operation goes through the
 * idempotency layer: the first request with a key owns it, a concurrent
 * duplicate gets 409, and a later duplicate receives the cached response (A4.1).
 */
@Service
public class PaymentService {

    /** Status, body and whether the body is a cached replay. */
    public record ApiResult(int status, Map<String, Object> body, boolean replayed) {}

    /** Validated input for creating a payment. */
    public record CreatePaymentCommand(String merchantOrderId, long amountPaise, String currency, String paymentMethod,
                                       String captureMode, String upiFlow) {}

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final TransactionRepository transactions;
    private final IdempotencyService idempotency;
    private final AuditWriter auditWriter;
    private final AuthorisationOrchestrator orchestrator;
    private final CaptureService captures;
    private final RefundService refunds;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public PaymentService(TransactionRepository transactions, IdempotencyService idempotency, AuditWriter auditWriter,
                          AuthorisationOrchestrator orchestrator, CaptureService captures, RefundService refunds,
                          ObjectMapper mapper, PlatformTransactionManager txManager) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.auditWriter = auditWriter;
        this.orchestrator = orchestrator;
        this.captures = captures;
        this.refunds = refunds;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    private record Step(UUID transactionId, ApiResult replay) {}

    /** POST /api/v1/payments. */
    public ApiResult create(CreatePaymentCommand cmd, String merchantId, String idempotencyKey) {
        PaymentMethod method = PaymentMethod.parse(cmd.paymentMethod());
        String currency = cmd.currency() == null ? "INR" : cmd.currency().trim().toUpperCase(Locale.ROOT);
        Transaction.CaptureMode captureMode = parseEnum(Transaction.CaptureMode.class, cmd.captureMode(),
                Transaction.CaptureMode.AUTOMATIC, "capture_mode");
        Transaction.UpiFlow upiFlow = null;
        if (method == PaymentMethod.UPI) {
            upiFlow = parseEnum(Transaction.UpiFlow.class, cmd.upiFlow(), Transaction.UpiFlow.INTENT, "upi_flow");
        } else if (cmd.upiFlow() != null) {
            throw ApiException.badRequest("INVALID_REQUEST", "upi_flow is only valid for payment_method UPI");
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("merchant_order_id", cmd.merchantOrderId());
        canonical.put("amount_paise", cmd.amountPaise());
        canonical.put("currency", currency);
        canonical.put("payment_method", method.name());
        canonical.put("capture_mode", captureMode.name());
        canonical.put("upi_flow", upiFlow == null ? null : upiFlow.name());
        String hash = IdempotencyService.requestHash(canonical);
        Transaction.UpiFlow flow = upiFlow;

        Step step = tx.execute(status -> {
            IdempotencyService.Claim claim = idempotency.claim(merchantId, idempotencyKey, hash, "POST /api/v1/payments");
            return switch (claim.kind()) {
                case MISMATCH -> throw keyReused(claim.key());
                case IN_PROGRESS -> throw inProgress(claim.key());
                case REPLAY -> new Step(null, replay(claim.key()));
                case RESUME -> new Step(claim.key().getTransactionId(), null);
                case PROCEED_NEW -> {
                    Transaction t = new Transaction();
                    t.setMerchantId(merchantId);
                    t.setIdempotencyKey(idempotencyKey);
                    t.setMerchantOrderId(cmd.merchantOrderId());
                    t.setAmountPaise(cmd.amountPaise());
                    t.setCurrency(currency);
                    t.setPaymentMethod(method);
                    t.setCaptureMode(captureMode);
                    t.setUpiFlow(flow);
                    t.setTraceId(TraceContext.traceUuid());
                    Transaction saved = transactions.save(t);
                    auditWriter.write(saved, null, TransactionState.CREATED,
                            Audit.of(AuditEvent.PAYMENT_CREATED, "api:" + merchantId)
                                    .meta("idempotency_key", idempotencyKey)
                                    .meta("merchant_order_id", cmd.merchantOrderId()));
                    idempotency.attachTransaction(merchantId, idempotencyKey, saved.getId());
                    yield new Step(saved.getId(), null);
                }
            };
        });
        if (step.replay() != null) return step.replay();
        TraceContext.bindTransaction(step.transactionId(), null);
        return completeIdempotently(merchantId, idempotencyKey,
                () -> render(orchestrator.process(step.transactionId())));
    }

    /** POST /api/v1/payments/{id}/capture. */
    public ApiResult capture(UUID id, Long amountPaise, String merchantId, String idempotencyKey) {
        requireOwned(id, merchantId);
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("op", "capture");
        canonical.put("payment_id", id.toString());
        canonical.put("amount_paise", amountPaise);
        return idempotent(merchantId, idempotencyKey, "POST /api/v1/payments/{id}/capture", canonical,
                () -> render(captures.capture(id, amountPaise, "merchant:" + merchantId)));
    }

    /** POST /api/v1/payments/{id}/void. */
    public ApiResult voidPayment(UUID id, String merchantId, String idempotencyKey) {
        requireOwned(id, merchantId);
        return idempotent(merchantId, idempotencyKey, "POST /api/v1/payments/{id}/void",
                Map.of("op", "void", "payment_id", id.toString()),
                () -> render(captures.voidHold(id, "merchant:" + merchantId)));
    }

    /** POST /api/v1/payments/{id}/refund. */
    public ApiResult refund(UUID id, Long amountPaise, String reason, String merchantId, String idempotencyKey) {
        requireOwned(id, merchantId);
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("op", "refund");
        canonical.put("payment_id", id.toString());
        canonical.put("amount_paise", amountPaise);
        canonical.put("reason", reason);
        return idempotent(merchantId, idempotencyKey, "POST /api/v1/payments/{id}/refund", canonical,
                () -> render(refunds.refund(id, amountPaise, reason, "merchant:" + merchantId)));
    }

    public Transaction requireOwned(UUID id, String merchantId) {
        Transaction t = transactions.findById(id).orElseThrow(() -> ApiException.notFound("payment " + id));
        if (!t.getMerchantId().equals(merchantId)) throw ApiException.notFound("payment " + id);
        return t;
    }

    private ApiResult idempotent(String merchantId, String key, String path, Map<String, ?> canonical,
                                 Supplier<ApiResult> action) {
        if (key == null || key.isBlank()) return action.get();
        String hash = IdempotencyService.requestHash(canonical);
        IdempotencyService.Claim claim = tx.execute(s -> idempotency.claim(merchantId, key, hash, path));
        switch (claim.kind()) {
            case MISMATCH -> throw keyReused(claim.key());
            case IN_PROGRESS -> throw inProgress(claim.key());
            case REPLAY -> {
                return replay(claim.key());
            }
            default -> { /* PROCEED_NEW / RESUME */ }
        }
        return completeIdempotently(merchantId, key, action);
    }

    /** Runs the action and caches its response; deterministic 4xx errors are cached too. */
    private ApiResult completeIdempotently(String merchantId, String key, Supplier<ApiResult> action) {
        try {
            ApiResult result = action.get();
            idempotency.complete(merchantId, key, result.status(), result.body());
            return result;
        } catch (RuntimeException e) {
            ErrorMapper.Mapped mapped = ErrorMapper.map(e);
            if (mapped.status().is4xxClientError()) {
                idempotency.complete(merchantId, key, mapped.status().value(), mapped.body());
            } else {
                idempotency.fail(merchantId, key);
            }
            throw e;
        }
    }

    /** 2xx: the payment resource (plus a notice for queued payments); otherwise the A7.2 error body. */
    ApiResult render(PaymentOutcome outcome) {
        Map<String, Object> payment = mapper.convertValue(PaymentView.of(outcome.transaction()), MAP);
        if (outcome.status().is2xxSuccessful()) {
            if (outcome.isError()) {
                Map<String, Object> notice = new LinkedHashMap<>();
                notice.put("code", outcome.errorCode());
                notice.put("message", outcome.errorMessage());
                notice.put("details", outcome.errorDetails());
                payment.put("notice", notice);
            }
            return new ApiResult(outcome.status().value(), payment, false);
        }
        Map<String, Object> details = new LinkedHashMap<>(outcome.errorDetails());
        details.put("payment", payment);
        return new ApiResult(outcome.status().value(),
                ErrorResponse.body(outcome.errorCode(), outcome.errorMessage(), details), false);
    }

    private static ApiResult replay(IdempotencyKey key) {
        Map<String, Object> body = key.getResponseBody() == null ? Map.of() : key.getResponseBody();
        return new ApiResult(key.getResponseCode() == null ? 200 : key.getResponseCode(), body, true);
    }

    private static ApiException keyReused(IdempotencyKey key) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used with a different request body.",
                key.getTransactionId() == null ? Map.of() : Map.of("transaction_id", key.getTransactionId().toString()));
    }

    private static ApiException inProgress(IdempotencyKey key) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (key != null && key.getTransactionId() != null) d.put("transaction_id", key.getTransactionId().toString());
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                "A request with this Idempotency-Key is already in progress.", d);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback, String field) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_REQUEST", "invalid " + field + ": " + raw);
        }
    }
}
