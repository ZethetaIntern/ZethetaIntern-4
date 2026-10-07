package com.payflow.gateway;

import com.payflow.config.PayFlowProperties;
import com.payflow.domain.PaymentMethod;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * UPI (NPCI) mock. UPI has no separate capture (A1.3 "N/A (instant)"): an
 * intent payment is CAPTURED on approval; a collect request is PENDING until
 * the customer approves or the mandate window expires (FS-12).
 */
@Component
public class UpiAdapter extends SimulatedGateway {

    public UpiAdapter(MockGatewayLedger ledger, PayFlowProperties props) {
        super(ledger, props);
    }

    @Override
    public String name() {
        return "upi";
    }

    @Override
    protected boolean instantCapture() {
        return true;
    }

    @Override
    protected String newReference() {
        // 12-digit UPI transaction reference number (RRN-style)
        return String.valueOf(400_000_000_000L + Math.abs(java.util.UUID.randomUUID().getLeastSignificantBits()
                % 99_999_999_999L));
    }

    @Override
    protected String idempotencyField() {
        return "txnRef";
    }

    @Override
    protected String[] declineCodeAndDescription() {
        return new String[]{"U30", "Debit has failed: insufficient balance in the remitter account."};
    }

    @Override
    protected long typicalLatencyMs() {
        return 250;
    }

    @Override
    protected double historicalFailureRate() {
        return 0.012;
    }

    @Override
    protected Map<String, Object> paymentObject(MockGatewayLedger.Charge c, GatewayStatus status, String currency,
                                                PaymentMethod method) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("txnId", c.reference());
        p.put("merchantTxnRef", String.valueOf(c.transactionId()));
        p.put("amount", BigDecimal.valueOf(c.amountPaise(), 2).toPlainString());
        p.put("status", switch (status) {
            case CAPTURED, REFUNDED -> "SUCCESS";
            case PENDING -> "PENDING";
            case EXPIRED -> "EXPIRED";
            default -> "FAILURE";
        });
        if (c.collectExpiresAt() != null) p.put("collectExpiry", c.collectExpiresAt().toString());
        return p;
    }
}
