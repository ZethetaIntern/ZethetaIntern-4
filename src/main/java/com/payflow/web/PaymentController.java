package com.payflow.web;

import com.payflow.core.RoutingEngine;
import com.payflow.entity.RoutingConfig;
import com.payflow.repository.RoutingConfigRepository;
import com.payflow.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.payflow.repository.GatewayRouteRepository;
import com.payflow.repository.GatewayAttemptRepository;
import com.payflow.repository.TransactionStateLogRepository;
import com.payflow.repository.RefundRepository;
import com.payflow.core.RoutingEngine.RankedGateway;

import java.util.List;
import java.util.Map;

/** Payment API (spec A7.1). */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    public record CreateRequest(@NotBlank String merchantOrderId, @Positive long amount,
                                String currency, @NotBlank String paymentMethod) {}

    private final PaymentService payments;
    private final GatewayAttemptRepository attempts;
    private final TransactionStateLogRepository logs;
    private final RefundRepository refunds;

    public PaymentController(PaymentService payments, GatewayAttemptRepository attempts,
                             TransactionStateLogRepository logs, RefundRepository refunds) {
        this.payments = payments;
        this.attempts = attempts;
        this.logs = logs;
        this.refunds = refunds;
    }

    /** Create + process end-to-end. Idempotency-Key header required. */
    @PostMapping
    public ResponseEntity<?> createAndProcess(@RequestHeader(value = "Idempotency-Key", required = false) String idem,
                                              @Valid @RequestBody CreateRequest req) {
        if (idem == null || idem.isBlank()) {
            return ResponseEntity.badRequest().body(GlobalExceptionHandler.error(
                    "MISSING_IDEMPOTENCY_KEY", "Idempotency-Key header is required"));
        }
        var txn = payments.create(idem, req.merchantOrderId(), req.amount(),
                req.currency() == null ? "INR" : req.currency(), req.paymentMethod());
        var result = payments.process(txn.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("replayed", false, "transaction", result));
    }

    @GetMapping("/{id}")
    public PaymentService.TransactionView get(@PathVariable String id) {
        return payments.view(id);
    }

    @GetMapping("/order/{merchantOrderId}")
    public List<?> byOrder(@PathVariable String merchantOrderId) {
        return payments.viewAllByOrder(merchantOrderId);
    }

    /** Explicit capture of an authorised transaction (two-phase flow). */
    @PostMapping("/{id}/capture")
    public Object capture(@PathVariable String id) {
        var txn = payments.getTransaction(id);
        return payments.captureFlow(id, txn.getGateway(), txn.getGatewayReference(),
                txn.getAttemptsMade() + 1);
    }

    @PostMapping("/{id}/refund")
    public Object refund(@PathVariable String id, @RequestParam(defaultValue = "0") long amount) {
        var txn = payments.getTransaction(id);
        long amt = amount > 0 ? amount : txn.getCapturedPaise() > 0 ? txn.getCapturedPaise() : txn.getAmountPaise();
        return payments.refund(id, amt, "api");
    }

    @GetMapping("/{id}/audit")
    public List<?> audit(@PathVariable String id) {
        return logs.findByTransactionIdOrderByCreatedAtAsc(id);
    }

    @GetMapping("/{id}/attempts")
    public List<?> attempts(@PathVariable String id) {
        return attempts.findByTransactionIdOrderByAttemptNoAsc(id);
    }

    @GetMapping("/{id}/refunds")
    public List<?> refunds(@PathVariable String id) {
        return refunds.findByTransactionId(id);
    }
}
