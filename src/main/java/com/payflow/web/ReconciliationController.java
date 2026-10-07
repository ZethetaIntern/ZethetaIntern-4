package com.payflow.web;

import com.payflow.entity.ReconciliationRun;
import com.payflow.error.ApiException;
import com.payflow.reconciliation.ReconciliationService;
import com.payflow.repository.AnomalyRepository;
import com.payflow.repository.ReconciliationRunRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reconciliation engine (A7.1 #19-#20). */
@RestController
@RequestMapping("/api/v1/reconciliation")
@Validated
@Tag(name = "Reconciliation", description = "Reconciliation runs against gateway status and settlement data")
public class ReconciliationController {

    private final ReconciliationService reconciliation;
    private final ReconciliationRunRepository runs;
    private final AnomalyRepository anomalies;

    public ReconciliationController(ReconciliationService reconciliation, ReconciliationRunRepository runs,
                                    AnomalyRepository anomalies) {
        this.reconciliation = reconciliation;
        this.runs = runs;
        this.anomalies = anomalies;
    }

    @PostMapping("/trigger")
    @Operation(summary = "Run reconciliation now (#19)", description = "stale_threshold_seconds overrides the default "
            + "5-minute threshold for this run")
    public ResponseEntity<ReconciliationRun> trigger(
            @RequestParam(value = "stale_threshold_seconds", required = false) @PositiveOrZero Long staleSeconds) {
        ReconciliationRun run = staleSeconds == null
                ? reconciliation.run(ReconciliationRun.Trigger.MANUAL)
                : reconciliation.run(ReconciliationRun.Trigger.MANUAL, Duration.ofSeconds(staleSeconds));
        return ResponseEntity.status(HttpStatus.CREATED).body(run);
    }

    @GetMapping("/reports/{runId}")
    @Operation(summary = "Reconciliation report for a run (#20)")
    public Map<String, Object> report(@PathVariable String runId) {
        ReconciliationRun run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("reconciliation run " + runId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", run);
        out.put("entries", reconciliation.entries(runId));
        out.put("anomalies", anomalies.findByRunId(runId));
        return out;
    }
}
