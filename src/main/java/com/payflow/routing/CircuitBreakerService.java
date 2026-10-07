package com.payflow.routing;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.payflow.domain.HealthStatus;
import com.payflow.domain.PaymentMethod;
import com.payflow.entity.CircuitBreakerConfig;
import com.payflow.entity.CircuitBreakerState;
import com.payflow.entity.CircuitBreakerState.State;
import com.payflow.repository.CircuitBreakerConfigRepository;
import com.payflow.repository.CircuitBreakerStateRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Three-state circuit breaker (spec A3.3), one circuit per (gateway, payment
 * method). Thresholds come from {@code circuit_breaker_config} and can be
 * changed at runtime; the state lives in {@code circuit_breaker_state} so all
 * instances share it.
 *
 * <pre>
 * CLOSED --(failure_threshold consecutive failures)--> OPEN
 * OPEN   --(open_timeout elapsed)-----------------------> HALF_OPEN
 * HALF_OPEN --(half_open_max_requests probes succeed)---> CLOSED
 * HALF_OPEN --(any probe fails)-------------------------> OPEN (timeout restarts)
 * </pre>
 */
@Service
public class CircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerService.class);
    private static final Duration CONFIG_TTL = Duration.ofSeconds(5);

    /** Result of asking whether a request may go to a gateway. */
    public enum Permit { ALLOWED, PROBE, REJECTED }

    private final CircuitBreakerStateRepository states;
    private final CircuitBreakerConfigRepository configs;
    private final GatewayMetricsService metrics;
    private final RoutingConfigService routingConfig;
    private final TransactionTemplate tx;
    private volatile List<CircuitBreakerConfig> configCache;
    private volatile Instant configCachedAt = Instant.EPOCH;

    public CircuitBreakerService(CircuitBreakerStateRepository states, CircuitBreakerConfigRepository configs,
                                 GatewayMetricsService metrics, RoutingConfigService routingConfig,
                                 PlatformTransactionManager txManager) {
        this.states = states;
        this.configs = configs;
        this.metrics = metrics;
        this.routingConfig = routingConfig;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Effective configuration: the most specific matching row wins. */
    public CircuitBreakerConfig config(String gateway, PaymentMethod method) {
        List<CircuitBreakerConfig> all = configCache;
        if (all == null || Instant.now().isAfter(configCachedAt.plus(CONFIG_TTL))) {
            all = configs.findAll();
            configCache = all;
            configCachedAt = Instant.now();
        }
        return all.stream().filter(c -> c.matches(gateway, method.name()))
                .max(Comparator.comparingInt(CircuitBreakerConfig::specificity))
                .orElseGet(() -> new CircuitBreakerConfig("*", "*", 5, 30_000, 1));
    }

    public void invalidateConfig() {
        configCache = null;
    }

    /** Current state, reporting OPEN circuits whose timeout has elapsed as HALF_OPEN. */
    public State state(String gateway, PaymentMethod method) {
        Optional<CircuitBreakerState> row = states.findById(new CircuitBreakerState.Pk(gateway, method.name()));
        if (row.isEmpty()) return State.CLOSED;
        CircuitBreakerState s = row.get();
        if (s.getState() == State.OPEN && timeoutElapsed(s, config(gateway, method))) return State.HALF_OPEN;
        return s.getState();
    }

    /** A3.2 HealthScore input: DOWN when OPEN, DEGRADED when HALF_OPEN or below the success threshold. */
    public HealthStatus health(String gateway, PaymentMethod method) {
        State s = state(gateway, method);
        if (s == State.OPEN) return HealthStatus.DOWN;
        if (s == State.HALF_OPEN) return HealthStatus.DEGRADED;
        RoutingConfigService.Snapshot cfg = routingConfig.current();
        GatewayMetricsService.WindowStats w = metrics.window(gateway, cfg.windowMinutes());
        if (w.attempts() >= cfg.minSamples()
                && (double) w.successes() / w.attempts() < cfg.degradedSuccessRate()) {
            return HealthStatus.DEGRADED;
        }
        return HealthStatus.HEALTHY;
    }

    /** Asks for permission to call the gateway; HALF_OPEN admits only {@code half_open_max_requests} probes. */
    public Permit acquire(String gateway, PaymentMethod method) {
        Optional<CircuitBreakerState> fast = states.findById(new CircuitBreakerState.Pk(gateway, method.name()));
        if (fast.isEmpty() || fast.get().getState() == State.CLOSED) return Permit.ALLOWED;
        CircuitBreakerConfig cfg = config(gateway, method);
        return tx.execute(status -> {
            CircuitBreakerState s = lockOrCreate(gateway, method);
            Instant now = Instant.now();
            if (s.getState() == State.OPEN) {
                if (!timeoutElapsed(s, cfg)) return Permit.REJECTED;
                s.halfOpen();
                logTransition(gateway, method, State.OPEN, State.HALF_OPEN);
            }
            if (s.getState() == State.HALF_OPEN) {
                boolean staleProbe = s.getUpdatedAt() != null
                        && s.getUpdatedAt().plusMillis(cfg.getOpenTimeoutMs()).isBefore(now);
                if (staleProbe) s.setHalfOpenInFlight(0);
                if (s.getHalfOpenInFlight() + s.getHalfOpenSuccesses() >= cfg.getHalfOpenMaxRequests()) {
                    return Permit.REJECTED;
                }
                s.setHalfOpenInFlight(s.getHalfOpenInFlight() + 1);
                states.save(s);
                return Permit.PROBE;
            }
            return Permit.ALLOWED;
        });
    }

    public void onSuccess(String gateway, PaymentMethod method) {
        Optional<CircuitBreakerState> fast = states.findById(new CircuitBreakerState.Pk(gateway, method.name()));
        if (fast.isEmpty() || (fast.get().getState() == State.CLOSED && fast.get().getConsecutiveFailures() == 0)) {
            return;
        }
        CircuitBreakerConfig cfg = config(gateway, method);
        tx.executeWithoutResult(status -> {
            CircuitBreakerState s = lockOrCreate(gateway, method);
            switch (s.getState()) {
                case CLOSED -> s.setConsecutiveFailures(0);
                case HALF_OPEN -> {
                    s.setHalfOpenInFlight(Math.max(0, s.getHalfOpenInFlight() - 1));
                    s.setHalfOpenSuccesses(s.getHalfOpenSuccesses() + 1);
                    if (s.getHalfOpenSuccesses() >= cfg.getHalfOpenMaxRequests()) {
                        s.close();
                        logTransition(gateway, method, State.HALF_OPEN, State.CLOSED);
                    }
                }
                default -> { /* OPEN: a late success from before the trip keeps the circuit open */ }
            }
            states.save(s);
        });
    }

    public void onFailure(String gateway, PaymentMethod method) {
        CircuitBreakerConfig cfg = config(gateway, method);
        tx.executeWithoutResult(status -> {
            CircuitBreakerState s = lockOrCreate(gateway, method);
            Instant now = Instant.now();
            s.setLastFailureAt(now);
            switch (s.getState()) {
                case CLOSED -> {
                    s.setConsecutiveFailures(s.getConsecutiveFailures() + 1);
                    if (s.getConsecutiveFailures() >= cfg.getFailureThreshold()) {
                        s.open(now);
                        logTransition(gateway, method, State.CLOSED, State.OPEN);
                    }
                }
                case HALF_OPEN -> {
                    s.open(now);
                    logTransition(gateway, method, State.HALF_OPEN, State.OPEN);
                }
                default -> { /* OPEN: already open */ }
            }
            states.save(s);
        });
    }

    /** Operator / harness control: trip a circuit immediately. */
    public void forceOpen(String gateway, PaymentMethod method) {
        tx.executeWithoutResult(status -> {
            CircuitBreakerState s = lockOrCreate(gateway, method);
            s.open(Instant.now());
            states.save(s);
        });
        logTransition(gateway, method, null, State.OPEN);
    }

    public void reset(String gateway, PaymentMethod method) {
        tx.executeWithoutResult(status -> {
            CircuitBreakerState s = lockOrCreate(gateway, method);
            s.close();
            states.save(s);
        });
    }

    public List<CircuitBreakerState> statesFor(String gateway) {
        return states.findByGatewayOrderByPaymentMethodAsc(gateway);
    }

    private CircuitBreakerState lockOrCreate(String gateway, PaymentMethod method) {
        states.insertIfAbsent(gateway, method.name());
        return states.findForUpdate(gateway, method.name()).orElseThrow();
    }

    private static boolean timeoutElapsed(CircuitBreakerState s, CircuitBreakerConfig cfg) {
        return s.getOpenedAt() == null || !Instant.now().isBefore(s.getOpenedAt().plusMillis(cfg.getOpenTimeoutMs()));
    }

    private static void logTransition(String gateway, PaymentMethod method, State from, State to) {
        log.warn("circuit breaker state change {} {} {} {} {}", kv("component", "circuit_breaker"),
                kv("gateway", gateway), kv("payment_method", method), kv("from", from), kv("to", to));
    }
}
