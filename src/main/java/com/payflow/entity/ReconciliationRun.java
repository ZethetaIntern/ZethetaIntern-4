package com.payflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Summary of one reconciliation batch run (A5.5); details live in {@link ReconciliationLog}. */
@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    public enum Trigger { SCHEDULED, MANUAL }

    public enum Status { RUNNING, COMPLETED, FAILED }

    @Id
    @Column(name = "run_id", length = 64)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Trigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.RUNNING;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(nullable = false)
    private int scanned;

    @Column(nullable = false)
    private int stale;

    @Column(nullable = false)
    private int overridden;

    @Column(nullable = false)
    private int settled;

    @Column(nullable = false)
    private int anomalies;

    protected ReconciliationRun() {}

    public ReconciliationRun(String runId, Trigger trigger) {
        this.runId = runId;
        this.trigger = trigger;
    }

    public void finish(Status finalStatus, int scannedCount, int staleCount, int overriddenCount,
                       int settledCount, int anomalyCount) {
        this.status = finalStatus;
        this.scanned = scannedCount;
        this.stale = staleCount;
        this.overridden = overriddenCount;
        this.settled = settledCount;
        this.anomalies = anomalyCount;
        this.finishedAt = Instant.now();
        this.durationMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
    }

    public String getRunId() { return runId; }
    public Trigger getTrigger() { return trigger; }
    public Status getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public int getScanned() { return scanned; }
    public int getStale() { return stale; }
    public int getOverridden() { return overridden; }
    public int getSettled() { return settled; }
    public int getAnomalies() { return anomalies; }
}
