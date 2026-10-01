package com.payflow.service;

import com.payflow.entity.WebhookQueueItem;
import com.payflow.entity.WebhookQueueItem.Status;
import com.payflow.repository.WebhookQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Dead letter queue (spec A8.3). A webhook that fails processing is retried
 * with exponential backoff; after maxRetries it is parked in the DLQ rather than
 * lost, and can be replayed from the admin API once the root cause is fixed.
 * A non-zero DLQ depth is logged as an alert.
 */
@Service
public class DeadLetterQueueService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueueService.class);
    private static final int MAX_RETRIES = 3;

    private final WebhookQueueRepository repo;
    /** Injected lazily to avoid a circular dependency with WebhookService. */
    private final org.springframework.beans.factory.ObjectProvider<WebhookService> webhooks;

    public DeadLetterQueueService(WebhookQueueRepository repo,
                                 org.springframework.beans.factory.ObjectProvider<WebhookService> webhooks) {
        this.repo = repo;
        this.webhooks = webhooks;
    }

    /** Records a failed webhook and applies the retry/DLQ policy. */
    @Transactional
    public void recordFailure(String gateway, String eventId, String payload, String signature,
                              String error) {
        WebhookQueueItem item = repo.findAll().stream()
                .filter(i -> i.getGateway().equals(gateway) && i.getEventId().equals(eventId))
                .findFirst()
                .orElseGet(() -> repo.save(new WebhookQueueItem(gateway, eventId, payload, signature)));
        item.incrementRetries();
        item.setErrorMessage(error);
        if (item.getRetryCount() >= MAX_RETRIES) {
            item.setStatus(Status.DLQ);
            log.error("ALERT webhook moved to DLQ depth={} gateway={} event={}",
                    repo.countByStatus(Status.DLQ), gateway, eventId);
        } else {
            item.setStatus(Status.FAILED);
        }
        repo.save(item);
    }

    /** Manual replay of a DLQ item (admin API). */
    @Transactional
    public WebhookService.Result replay(String id) {
        WebhookQueueItem item = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("DLQ item not found: " + id));
        WebhookService.Result result = webhooks.getObject().ingest(item.getGateway(), item.getSignature(),
                item.getPayload().getBytes(StandardCharsets.UTF_8));
        if (result.accepted()) {
            item.setStatus(Status.COMPLETED);
            item.setProcessedAt(Instant.now());
            repo.save(item);
        } else {
            item.setErrorMessage(result.reason());
            repo.save(item);
        }
        return result;
    }

    public List<WebhookQueueItem> deadLettered() { return repo.findByStatus(Status.DLQ); }
    public List<WebhookQueueItem> pending() { return repo.findByStatus(Status.PENDING); }
    public long depth() { return repo.countByStatus(Status.DLQ); }
}
