package com.payflow.service;

import com.payflow.entity.Anomaly;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Alert dispatch for reconciliation anomalies (A5.5 step 4, FS-11).
 * Structured JSON-style log line; swap for PagerDuty/Slack/webhook in production.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    public void dispatch(Anomaly anomaly) {
        String payload = "{\"level\":\"" + (anomaly.getSeverity() == Anomaly.Severity.CRITICAL ? "ERROR" : "WARN")
                + "\",\"component\":\"reconciliation\",\"anomaly_id\":\"" + anomaly.getId()
                + "\",\"transaction_id\":\"" + anomaly.getTransactionId()
                + "\",\"run_id\":\"" + anomaly.getRunId()
                + "\",\"internal_state\":\"" + anomaly.getInternalState()
                + "\",\"gateway_status\":\"" + anomaly.getGatewayStatus()
                + "\",\"message\":\"" + anomaly.getDetail() + "\"}";
        log.error("ALERT {}", payload);
        anomaly.setAlerted(true);
    }
}
