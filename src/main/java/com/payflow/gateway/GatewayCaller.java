package com.payflow.gateway;

import com.payflow.domain.AttemptOutcome;
import com.payflow.tracing.TraceContext;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Executes gateway calls with a hard deadline. A gateway that does not answer
 * within the budget is abandoned (its virtual thread is interrupted) and the
 * call is reported as a TIMEOUT, which is what lets failover finish within
 * 2 seconds even when the gateway itself would hang for 30 (FS-01).
 *
 * <p>The MDC (trace id) and the mock control headers are copied onto the
 * worker thread so logs and simulated behaviour stay correlated.</p>
 */
@Component
public class GatewayCaller {

    /** A single gateway API call. */
    @FunctionalInterface
    public interface Call<T> {
        T run() throws GatewayException;
    }

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public <T> T call(String gateway, long timeoutMs, Call<T> call) throws GatewayException {
        Map<String, String> mdc = TraceContext.capture();
        MockControl control = MockControl.current();
        Future<T> future = executor.submit(() -> {
            TraceContext.restore(mdc);
            MockControl.bind(control);
            try {
                return call.run();
            } finally {
                MockControl.clear();
                MDC.clear();
            }
        });
        try {
            return future.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw GatewayException.timeout(gateway, timeoutMs);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof GatewayException ge) {
                throw ge;
            }
            throw new GatewayException(gateway, AttemptOutcome.SERVER_ERROR, "ADAPTER_ERROR",
                    String.valueOf(e.getCause()), null, 0);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw GatewayException.timeout(gateway, timeoutMs);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
