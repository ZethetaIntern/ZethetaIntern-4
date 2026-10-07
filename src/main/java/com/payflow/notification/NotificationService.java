package com.payflow.notification;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.payflow.entity.NotificationOutbox;
import com.payflow.entity.Transaction;
import com.payflow.repository.NotificationOutboxRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notification dispatcher (A5.2, optional stage) backed by a transactional
 * outbox. Notifications are enqueued alongside the state change and delivered
 * by {@link #dispatchPending()}; delivery here is a structured log line standing
 * in for SMS/e-mail/merchant webhooks.
 */
@Service
public class NotificationService {

    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String UPI_COLLECT_EXPIRED = "UPI_COLLECT_EXPIRED";
    public static final String AUTH_HOLD_EXPIRED = "AUTH_HOLD_EXPIRED";
    public static final String PAYMENT_CAPTURED = "PAYMENT_CAPTURED";

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationOutboxRepository outbox;

    public NotificationService(NotificationOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Transactional
    public NotificationOutbox enqueue(Transaction t, String template, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchant_id", t.getMerchantId());
        payload.put("merchant_order_id", t.getMerchantOrderId());
        payload.put("transaction_id", t.getId().toString());
        payload.put("state", t.getState().name());
        payload.put("amount_paise", t.getAmountPaise());
        payload.put("message", message);
        return outbox.save(new NotificationOutbox(t.getId(), template, payload));
    }

    /** Delivers pending notifications; returns how many were sent. */
    @Transactional
    public int dispatchPending() {
        List<NotificationOutbox> pending = outbox.findTop100ByStatusOrderByCreatedAtAsc(NotificationOutbox.Status.PENDING);
        for (NotificationOutbox n : pending) {
            log.info("customer notification sent {} {} {} {}", kv("component", "notification_dispatcher"),
                    kv("template", n.getTemplate()), kv("transaction_id", n.getTransactionId()),
                    kv("payload", n.getPayload()));
            n.markSent();
        }
        return pending.size();
    }

    public List<NotificationOutbox> forTransaction(java.util.UUID transactionId) {
        return outbox.findByTransactionIdOrderByCreatedAtAsc(transactionId);
    }
}
