package com.payflow.webhook;

import com.payflow.alert.AlertService;
import com.payflow.entity.WebhookQueueItem;
import com.payflow.error.ApiException;
import com.payflow.repository.WebhookQueueRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queue bookkeeping for the webhook pipeline (A8.3): competing-consumer claims,
 * retry with exponential backoff, the dead letter queue, and manual replay.
 */
@Service
public class WebhookQueueService {

    /** Lease given to a worker that claimed an item; an expired lease makes the item claimable again. */
    private static final long LEASE_SECONDS = 60;
    private static final long BACKOFF_BASE_MS = 2_000;

    private final WebhookQueueRepository queue;
    private final AlertService alerts;

    public WebhookQueueService(WebhookQueueRepository queue, AlertService alerts) {
        this.queue = queue;
        this.alerts = alerts;
    }

    /** Claims due items with FOR UPDATE SKIP LOCKED so concurrent workers never share an item. */
    @Transactional
    public List<Long> claimDue(int limit) {
        List<WebhookQueueItem> due = queue.claimDue(limit);
        Instant lease = Instant.now().plusSeconds(LEASE_SECONDS);
        due.forEach(i -> {
            i.setStatus(WebhookQueueItem.Status.PROCESSING);
            i.setNextRetryAt(lease);
        });
        return due.stream().map(WebhookQueueItem::getId).toList();
    }

    /**
     * Schedules a retry ({@code 2s, 4s, 8s}); after {@code max_retries} the item moves
     * to the DLQ and an alert fires (any non-zero DLQ depth is alert-worthy).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryLater(long id, String reason) {
        queue.findByIdForUpdate(id).ifPresent(item -> {
            item.setRetryCount(item.getRetryCount() + 1);
            item.setErrorMessage(truncate(reason));
            if (item.getRetryCount() >= item.getMaxRetries()) {
                deadLetter(item, reason);
            } else {
                item.setStatus(WebhookQueueItem.Status.FAILED);
                item.setNextRetryAt(Instant.now().plusMillis(BACKOFF_BASE_MS << (item.getRetryCount() - 1)));
            }
        });
    }

    /** Joins the caller's transaction; used for non-retryable rejections. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deadLetter(WebhookQueueItem item, String reason) {
        item.setStatus(WebhookQueueItem.Status.DLQ);
        item.setErrorMessage(truncate(reason));
        item.setNextRetryAt(null);
        alerts.raise("WEBHOOK_DLQ", "CRITICAL", "webhook moved to dead letter queue: " + reason, Map.of(
                "queue_id", String.valueOf(item.getId()), "gateway", item.getGateway(), "event_id", item.getEventId(),
                "dlq_depth", queue.countByStatus(WebhookQueueItem.Status.DLQ) + 1));
    }

    /** Resets a DLQ item so it is processed again (admin replay). */
    @Transactional
    public WebhookQueueItem resetForReplay(long id) {
        WebhookQueueItem item = queue.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("DLQ item " + id));
        if (item.getStatus() != WebhookQueueItem.Status.DLQ && item.getStatus() != WebhookQueueItem.Status.FAILED) {
            throw ApiException.unprocessable("NOT_REPLAYABLE", "only DLQ or FAILED items can be replayed, item is "
                    + item.getStatus());
        }
        item.setStatus(WebhookQueueItem.Status.PENDING);
        item.setRetryCount(0);
        item.setErrorMessage(null);
        item.setNextRetryAt(Instant.now());
        return item;
    }

    public long depth() {
        return queue.countByStatus(WebhookQueueItem.Status.DLQ);
    }

    public List<WebhookQueueItem> deadLettered() {
        return queue.findByStatusOrderByCreatedAtAsc(WebhookQueueItem.Status.DLQ);
    }

    public Map<String, Long> statusCounts() {
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        for (WebhookQueueItem.Status s : WebhookQueueItem.Status.values()) m.put(s.name(), queue.countByStatus(s));
        return m;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}
