package com.payflow.web;

import com.payflow.entity.GatewayAttempt;
import com.payflow.entity.GatewayRouteDecision;
import com.payflow.entity.Refund;
import com.payflow.entity.TransactionStateLog;
import com.payflow.payment.PaymentService;
import com.payflow.payment.PaymentView;
import com.payflow.repository.GatewayAttemptRepository;
import com.payflow.repository.GatewayRouteDecisionRepository;
import com.payflow.repository.RefundRepository;
import com.payflow.repository.TransactionRepository;
import com.payflow.repository.TransactionStateLogRepository;
import com.payflow.web.dto.AmountRequest;
import com.payflow.web.dto.CreatePaymentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Payment resource endpoints (A7.1 #1-#8). */
@RestController
@RequestMapping("/api/v1/payments")
@Validated
@Tag(name = "Payments", description = "Create, capture, void and refund payments")
public class PaymentController {

    static final String MERCHANT_HEADER = "X-Merchant-Id";
    static final String MERCHANT_PATTERN = "^[A-Za-z0-9_-]{1,64}$";

    private final PaymentService payments;
    private final TransactionRepository transactions;
    private final TransactionStateLogRepository stateLogs;
    private final RefundRepository refunds;
    private final GatewayRouteDecisionRepository routes;
    private final GatewayAttemptRepository attempts;

    public PaymentController(PaymentService payments, TransactionRepository transactions,
                             TransactionStateLogRepository stateLogs, RefundRepository refunds,
                             GatewayRouteDecisionRepository routes, GatewayAttemptRepository attempts) {
        this.payments = payments;
        this.transactions = transactions;
        this.stateLogs = stateLogs;
        this.refunds = refunds;
        this.routes = routes;
        this.attempts = attempts;
    }

    @PostMapping
    @Operation(summary = "Initiate a payment (#1)", description = "Routes to the best gateway, fails over within 2 s, "
            + "and auto-captures unless capture_mode=MANUAL. Returns 201 when authorised/captured, 202 when queued "
            + "(UPI collect pending or every gateway throttled), 402 on decline. A duplicate Idempotency-Key gets "
            + "409 while the first request is in flight and the cached response afterwards.")
    public ResponseEntity<Map<String, Object>> create(
            @Parameter(description = "Client-generated key (UUID recommended), scoped to the merchant")
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @RequestHeader(value = MERCHANT_HEADER, defaultValue = "default") @Pattern(regexp = MERCHANT_PATTERN)
            String merchantId,
            @Valid @RequestBody CreatePaymentRequest request) {
        PaymentService.ApiResult r = payments.create(new PaymentService.CreatePaymentCommand(request.merchantOrderId(),
                request.amountPaise(), request.currency(), request.paymentMethod(), request.captureMode(),
                request.upiFlow()), merchantId, idempotencyKey.trim());
        return respond(r);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Retrieve a payment by id (#2)")
    public PaymentView get(@PathVariable UUID id,
                           @RequestHeader(value = MERCHANT_HEADER, defaultValue = "default") String merchantId) {
        return PaymentView.of(payments.requireOwned(id, merchantId));
    }

    @GetMapping
    @Operation(summary = "Retrieve payments by merchant order id (#3)")
    public List<PaymentView> byOrder(@RequestParam("merchant_order_id") @NotBlank @Size(max = 128) String merchantOrderId,
                                     @RequestHeader(value = MERCHANT_HEADER, defaultValue = "default") String merchantId) {
        return transactions.findByMerchantIdAndMerchantOrderIdOrderByCreatedAtDesc(merchantId, merchantOrderId)
                .stream().map(PaymentView::of).toList();
    }

    @PostMapping("/{id}/capture")
    @Operation(summary = "Capture an authorised payment, fully or partially (#4)",
            description = "Omit amount_paise to capture the whole remaining hold. A partial capture leaves the payment "
                    + "PARTIALLY_CAPTURED with remaining_hold_paise available for another capture or a void.")
    public ResponseEntity<Map<String, Object>> capture(
            @PathVariable UUID id, @RequestBody(required = false) @Valid AmountRequest body,
            @RequestParam(value = "amount_paise", required = false) Long amountParam,
            @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 255) String idempotencyKey,
            @RequestHeader(value = MERCHANT_HEADER, defaultValue = "default") String merchantId) {
        Long amount = body != null && body.amountPaise() != null ? body.amountPaise() : amountParam;
        return respond(payments.capture(id, amount, merchantId, idempotencyKey));
    }

    @PostMapping("/{id}/void")
    @Operation(summary = "Void an authorisation, or release the remaining hold of a partial capture (#5)")
    public ResponseEntity<Map<String, Object>> voidPayment(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 255) String idempotencyKey,
            @RequestHeader(value = MERCHANT_HEADER, defaultValue = "default") String merchantId) {
        return respond(payments.voidPayment(id, merchantId, idempotencyKey));
    }

    @PostMapping("/{id}/refund")
    @Operation(summary = "Refund a captured or settled payment, fully or partially (#6)")
    public ResponseEntity<Map<String, Object>> refund(
            @PathVariable UUID id, @RequestBody(required = false) @Valid AmountRequest body,
            @RequestParam(value = "amount_paise", required = false) Long amountParam,
            @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 255) String idempotencyKey,
            @RequestHeader(value = MERCHANT_HEADER, defaultValue = "default") String merchantId) {
        Long amount = body != null && body.amountPaise() != null ? body.amountPaise() : amountParam;
        String reason = body == null ? null : body.reason();
        return respond(payments.refund(id, amount, reason, merchantId, idempotencyKey));
    }

    @GetMapping("/{id}/refunds")
    @Operation(summary = "List refunds for a payment (#7)")
    public List<Refund> refunds(@PathVariable UUID id,
                                @RequestHeader(value = MERCHANT_HEADER, defaultValue = "default") String merchantId) {
        payments.requireOwned(id, merchantId);
        return refunds.findByTransactionIdOrderByCreatedAtAsc(id);
    }

    @GetMapping("/{id}/timeline")
    @Operation(summary = "State transition history from the immutable audit trail (#8)")
    public List<TransactionStateLog> timeline(@PathVariable UUID id,
                                              @RequestHeader(value = MERCHANT_HEADER, defaultValue = "default")
                                              String merchantId) {
        payments.requireOwned(id, merchantId);
        return stateLogs.findByTransactionIdOrderByCreatedAtAsc(id);
    }

    @GetMapping("/{id}/routing")
    @Operation(summary = "Routing decisions for a payment: every candidate, its score and factor breakdown")
    public List<GatewayRouteDecision> routing(@PathVariable UUID id,
                                              @RequestHeader(value = MERCHANT_HEADER, defaultValue = "default")
                                              String merchantId) {
        payments.requireOwned(id, merchantId);
        return routes.findByTransactionIdOrderByAttemptNoAscRankAsc(id);
    }

    @GetMapping("/{id}/attempts")
    @Operation(summary = "Every gateway call made for a payment, with outcome and latency")
    public List<GatewayAttempt> attempts(@PathVariable UUID id,
                                         @RequestHeader(value = MERCHANT_HEADER, defaultValue = "default")
                                         String merchantId) {
        payments.requireOwned(id, merchantId);
        return attempts.findByTransactionIdOrderByCreatedAtAsc(id);
    }

    static ResponseEntity<Map<String, Object>> respond(PaymentService.ApiResult r) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(r.status())
                .header("Idempotent-Replayed", String.valueOf(r.replayed()));
        String timing = com.payflow.tracing.RequestTiming.serverTimingHeader();
        if (timing != null) b.header("Server-Timing", timing); // B3: time to gateway call initiated
        return b.body(r.body());
    }
}
