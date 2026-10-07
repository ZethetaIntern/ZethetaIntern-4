package com.payflow.web;

import com.payflow.domain.TransactionState;
import com.payflow.repository.TransactionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Business analytics (A7.1 #21-#22), computed with SQL aggregates. */
@RestController
@RequestMapping("/api/v1/analytics")
@Validated
@Tag(name = "Analytics", description = "Success rate and volume analytics")
public class AnalyticsController {

    private static final Set<TransactionState> SUCCESS = EnumSet.of(TransactionState.CAPTURED,
            TransactionState.PARTIALLY_CAPTURED, TransactionState.SETTLED, TransactionState.PARTIALLY_REFUNDED,
            TransactionState.REFUNDED, TransactionState.REFUND_INITIATED, TransactionState.REFUND_FAILED,
            TransactionState.DISPUTE_OPENED, TransactionState.DISPUTE_RESOLVED);
    private static final Set<TransactionState> FAILED = EnumSet.of(TransactionState.FAILED,
            TransactionState.AUTH_EXPIRED, TransactionState.ABANDONED);

    private final TransactionRepository transactions;

    public AnalyticsController(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    @GetMapping("/success-rate")
    @Operation(summary = "Success rate per gateway over a window (#21)",
            description = "success = payment reached CAPTURED (or a later state); failed = FAILED / AUTH_EXPIRED / ABANDONED")
    public Map<String, Object> successRate(@RequestParam(value = "window_hours", defaultValue = "24")
                                           @Positive @Max(2160) int windowHours) {
        Instant since = Instant.now().minus(windowHours, ChronoUnit.HOURS);
        List<Map<String, Object>> rows = transactions.outcomeCountsByGateway(since, SUCCESS, FAILED).stream()
                .map(r -> {
                    long total = ((Number) r[1]).longValue();
                    long ok = ((Number) r[2]).longValue();
                    long failed = ((Number) r[3]).longValue();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("gateway", r[0]);
                    m.put("transactions", total);
                    m.put("succeeded", ok);
                    m.put("failed", failed);
                    m.put("success_rate", total == 0 ? 0.0 : Math.round(10_000.0 * ok / total) / 10_000.0);
                    return m;
                }).toList();
        long total = rows.stream().mapToLong(m -> (long) m.get("transactions")).sum();
        long ok = rows.stream().mapToLong(m -> (long) m.get("succeeded")).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window_hours", windowHours);
        out.put("overall_success_rate", total == 0 ? 0.0 : Math.round(10_000.0 * ok / total) / 10_000.0);
        out.put("gateways", rows);
        return out;
    }

    @GetMapping("/volume")
    @Operation(summary = "Transaction volume by state, payment method and hour (#22)")
    public Map<String, Object> volume(@RequestParam(value = "window_hours", defaultValue = "24")
                                      @Positive @Max(2160) int windowHours) {
        Instant since = Instant.now().minus(windowHours, ChronoUnit.HOURS);
        Map<String, Object> byState = new LinkedHashMap<>();
        long count = 0;
        long paise = 0;
        for (Object[] r : transactions.volumeByState(since)) {
            long c = ((Number) r[1]).longValue();
            long a = ((Number) r[2]).longValue();
            byState.put(String.valueOf(r[0]), Map.of("count", c, "amount_paise", a));
            count += c;
            paise += a;
        }
        Map<String, Object> byMethod = new LinkedHashMap<>();
        for (Object[] r : transactions.volumeByMethod(since)) {
            byMethod.put(String.valueOf(r[0]), Map.of("count", ((Number) r[1]).longValue(),
                    "amount_paise", ((Number) r[2]).longValue()));
        }
        List<Map<String, Object>> hourly = transactions.volumeByHour(since).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hour", String.valueOf(r[0]));
            m.put("count", ((Number) r[1]).longValue());
            m.put("amount_paise", ((Number) r[2]).longValue());
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window_hours", windowHours);
        out.put("total_count", count);
        out.put("total_amount_paise", paise);
        out.put("total_amount", BigDecimal.valueOf(paise, 2).toPlainString());
        out.put("by_state", byState);
        out.put("by_payment_method", byMethod);
        out.put("hourly", hourly);
        return out;
    }
}
