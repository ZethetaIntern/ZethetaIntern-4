package com.payflow.jobs;

import com.payflow.entity.ReconciliationRun;
import com.payflow.reconciliation.ReconciliationService;
import com.payflow.tracing.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduling of the background jobs (Spring Scheduler, per the approved Java stack).
 * Each run gets its own trace id. Disabled with {@code payflow.jobs.enabled=false} (tests).
 */
@Component
@ConditionalOnProperty(prefix = "payflow.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final MaintenanceService maintenance;
    private final ReconciliationService reconciliation;

    public ScheduledJobs(MaintenanceService maintenance, ReconciliationService reconciliation) {
        this.maintenance = maintenance;
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${payflow.jobs.webhook-queue-ms:1000}")
    public void webhookQueue() {
        run("webhook_queue_worker", maintenance::processWebhookQueue);
    }

    @Scheduled(fixedDelayString = "${payflow.jobs.payment-retry-ms:1000}")
    public void paymentRetries() {
        run("payment_retry_worker", maintenance::retryParkedPayments);
    }

    /** A5.5: every 15 minutes by default. */
    @Scheduled(fixedDelayString = "${payflow.reconciliation.interval-ms:900000}",
            initialDelayString = "${payflow.reconciliation.initial-delay-ms:120000}")
    public void reconcile() {
        run("reconciliation", () -> {
            reconciliation.run(ReconciliationRun.Trigger.SCHEDULED);
            return 1;
        });
    }

    @Scheduled(fixedDelayString = "${payflow.jobs.upi-expiry-ms:15000}")
    public void upiCollectExpiry() {
        run("upi_collect_expiry", maintenance::expireUpiCollects);
    }

    @Scheduled(fixedDelayString = "${payflow.jobs.auth-expiry-ms:300000}")
    public void authHoldExpiry() {
        run("auth_hold_expiry", maintenance::expireAuthorisationHolds);
    }

    @Scheduled(fixedDelayString = "${payflow.jobs.abandon-ms:300000}")
    public void abandonment() {
        run("abandonment", maintenance::abandonStalePayments);
    }

    @Scheduled(cron = "5 * * * * *")
    public void healthMetrics() {
        run("health_metrics_flush", maintenance::flushHealthMetrics);
    }

    @Scheduled(fixedDelayString = "${payflow.jobs.idempotency-purge-ms:600000}")
    public void idempotencyPurge() {
        run("idempotency_purge", maintenance::purgeIdempotencyKeys);
    }

    @Scheduled(fixedDelayString = "${payflow.jobs.notification-ms:2000}")
    public void notifications() {
        run("notification_dispatcher", maintenance::dispatchNotifications);
    }

    private void run(String name, java.util.function.IntSupplier job) {
        TraceContext.runWithNewTrace(name, () -> {
            try {
                job.getAsInt();
            } catch (RuntimeException e) {
                // A failing job must never kill the scheduler thread; it runs again next tick.
                log.error("background job failed: {}", name, e);
            }
        });
    }
}
