package com.payflow.gateway;

import com.payflow.config.PayFlowProperties;
import com.payflow.domain.PaymentMethod;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Stripe mock: PaymentIntents ({@code pi_}), {@code Idempotency-Key} header, multi-currency. */
@Component
public class StripeAdapter extends SimulatedGateway {

    public StripeAdapter(MockGatewayLedger ledger, PayFlowProperties props) {
        super(ledger, props);
    }

    @Override
    public String name() {
        return "stripe";
    }

    @Override
    protected String newReference() {
        return "pi_" + shortId();
    }

    @Override
    protected String idempotencyField() {
        return "Idempotency-Key";
    }

    @Override
    protected String[] declineCodeAndDescription() {
        return new String[]{"card_declined", "Your card has insufficient funds."};
    }

    @Override
    protected long typicalLatencyMs() {
        return 350;
    }

    @Override
    protected double historicalFailureRate() {
        return 0.016;
    }

    @Override
    protected Map<String, Object> paymentObject(MockGatewayLedger.Charge c, GatewayStatus status, String currency,
                                                PaymentMethod method) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", c.reference());
        p.put("object", "payment_intent");
        p.put("amount", c.amountPaise());
        p.put("amount_received", c.capturedPaise());
        p.put("currency", lower(currency == null ? "INR" : currency));
        p.put("status", switch (status) {
            case AUTHORISED -> "requires_capture";
            case CAPTURED, REFUNDED -> "succeeded";
            case PENDING -> "processing";
            case VOIDED -> "canceled";
            default -> "requires_payment_method";
        });
        p.put("capture_method", "manual");
        p.put("metadata", Map.of("transaction_id", String.valueOf(c.transactionId())));
        return p;
    }
}
