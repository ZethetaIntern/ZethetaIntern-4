package com.payflow.alert;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.payflow.entity.Anomaly;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Alert dispatch (A5.5 step 4, A8.3 DLQ monitoring). Alerts are emitted as
 * structured ERROR log lines with {@code "alert": true}, which a log pipeline
 * routes to paging (PagerDuty / Slack). The most recent alerts are also kept
 * in memory for the admin API.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger("ALERT");
    private static final int KEEP = 200;

    /** An alert raised by the system. */
    public record Alert(String type, String severity, String message, Map<String, Object> context,
                        java.time.Instant at) {}

    private final List<Alert> recent = new CopyOnWriteArrayList<>();

    public void anomaly(Anomaly a) {
        raise("RECONCILIATION_ANOMALY", a.getSeverity().name(), a.getDetail(), Map.of(
                "anomaly_id", a.getId().toString(), "transaction_id", a.getTransactionId().toString(),
                "anomaly_type", a.getAnomalyType(), "internal_state", a.getInternalState(),
                "gateway_status", a.getGatewayStatus()));
        a.setAlerted(true);
    }

    public void raise(String type, String severity, String message, Map<String, Object> context) {
        log.error("ALERT {} {} {} {} {}", kv("alert", true), kv("alert_type", type), kv("severity", severity),
                kv("alert_message", message), kv("context", context));
        recent.add(0, new Alert(type, severity, message, context, java.time.Instant.now()));
        while (recent.size() > KEEP) recent.remove(recent.size() - 1);
    }

    public List<Alert> recent() {
        return List.copyOf(recent);
    }
}
