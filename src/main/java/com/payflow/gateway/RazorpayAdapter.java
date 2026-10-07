package com.payflow.gateway;

import com.payflow.config.PayFlowProperties;
import com.payflow.domain.PaymentMethod;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Razorpay mock: {@code pay_} references, client-generated idempotency key, INR only. */
@Component
public class RazorpayAdapter extends SimulatedGateway {

    public RazorpayAdapter(MockGatewayLedger ledger, PayFlowProperties props) {
        super(ledger, props);
    }

    @Override
    public String name() {
        return "razorpay";
    }

    @Override
    protected String newReference() {
        return "pay_" + shortId();
    }

    @Override
    protected String idempotencyField() {
        return "X-Razorpay-Idempotency-Key";
    }

    @Override
    protected String[] declineCodeAndDescription() {
        return new String[]{"BAD_REQUEST_ERROR", "The card issuer has declined this transaction."};
    }

    @Override
    protected long typicalLatencyMs() {
        return 520;
    }

    @Override
    protected double historicalFailureRate() {
        return 0.032;
    }

    @Override
    protected Map<String, Object> paymentObject(MockGatewayLedger.Charge c, GatewayStatus status, String currency,
                                                PaymentMethod method) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", c.reference());
        p.put("entity", "payment");
        p.put("amount", c.amountPaise());
        p.put("currency", currency == null ? "INR" : currency);
        p.put("status", switch (status) {
            case AUTHORISED -> "authorized";
            case CAPTURED -> "captured";
            case REFUNDED -> "refunded";
            case FAILED, REVERSED -> "failed";
            default -> lower(status.name());
        });
        p.put("method", method == null ? "card" : method.code());
        p.put("captured", c.capturedPaise() > 0);
        p.put("amount_refunded", c.refundedPaise());
        p.put("notes", Map.of("transaction_id", String.valueOf(c.transactionId())));
        return p;
    }
}
