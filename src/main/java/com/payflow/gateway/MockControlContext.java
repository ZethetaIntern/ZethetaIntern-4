package com.payflow.gateway;

import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Scope;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Holds the per-request mock control (spec B4.3) parsed from the harness
 * headers so the orchestrator can bind it to gateway worker threads.
 */
@Component
@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
@RequestScope
public class MockControlContext {

    private MockControl control = MockControl.fromHeaders(java.util.Map.of());

    public void set(MockControl control) {
        this.control = control;
    }

    public MockControl get() {
        return control;
    }
}
