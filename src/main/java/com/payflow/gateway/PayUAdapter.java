package com.payflow.gateway;

import com.payflow.config.PayFlowProperties;
import com.payflow.domain.PaymentMethod;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** PayU mock: numeric {@code mihpayid}, idempotency through the {@code txnid} parameter, amounts in rupees. */
@Component
public class PayUAdapter extends SimulatedGateway {

    public PayUAdapter(MockGatewayLedger ledger, PayFlowProperties props) {
        super(ledger, props);
    }

    @Override
    public String name() {
        return "payu";
    }

    @Override
    protected String newReference() {
        return String.valueOf(10_000_000_000L + Math.abs(java.util.UUID.randomUUID().getMostSignificantBits()
                % 89_999_999_999L));
    }

    @Override
    protected String idempotencyField() {
        return "txnid";
    }

    @Override
    protected String[] declineCodeAndDescription() {
        return new String[]{"E308", "Transaction declined by issuing bank: insufficient funds."};
    }

    @Override
    protected long typicalLatencyMs() {
        return 750;
    }

    @Override
    protected double historicalFailureRate() {
        return 0.082;
    }

    @Override
    protected Map<String, Object> paymentObject(MockGatewayLedger.Charge c, GatewayStatus status, String currency,
                                                PaymentMethod method) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("mihpayid", c.reference());
        p.put("txnid", String.valueOf(c.transactionId()));
        p.put("amount", BigDecimal.valueOf(c.amountPaise(), 2).toPlainString());
        p.put("status", switch (status) {
            case AUTHORISED, CAPTURED, REFUNDED -> "success";
            case PENDING -> "pending";
            default -> "failure";
        });
        p.put("unmappedstatus", switch (status) {
            case AUTHORISED -> "auth";
            case CAPTURED -> "captured";
            case REFUNDED -> "refunded";
            case VOIDED -> "cancelled";
            default -> lower(status.name());
        });
        p.put("mode", method == null ? "CC" : switch (method) {
            case CARD -> "CC";
            case NETBANKING -> "NB";
            case WALLET -> "CASH";
            case UPI -> "UPI";
        });
        return p;
    }
}
