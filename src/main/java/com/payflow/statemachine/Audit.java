package com.payflow.statemachine;

import com.payflow.entity.Transaction;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Describes one audited change: the event name, who triggered it, the gateway
 * evidence, extra metadata, and optionally field updates that must commit
 * atomically with the state change (e.g. the captured amount).
 */
public record Audit(String event, String createdBy, String gatewayReference,
                    Map<String, Object> gatewayResponse, Map<String, Object> metadata,
                    Consumer<Transaction> mutator) {

    public static Audit of(String event, String createdBy) {
        return new Audit(event, createdBy, null, null, new LinkedHashMap<>(), null);
    }

    public Audit gateway(String reference, Map<String, Object> response) {
        return new Audit(event, createdBy, reference, response, metadata, mutator);
    }

    public Audit meta(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        if (value != null) m.put(key, value);
        return new Audit(event, createdBy, gatewayReference, gatewayResponse, m, mutator);
    }

    public Audit meta(Map<String, ?> values) {
        Map<String, Object> m = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        values.forEach((k, v) -> {
            if (v != null) m.put(k, v);
        });
        return new Audit(event, createdBy, gatewayReference, gatewayResponse, m, mutator);
    }

    public Audit mutate(Consumer<Transaction> fieldUpdates) {
        Consumer<Transaction> combined = mutator == null ? fieldUpdates : mutator.andThen(fieldUpdates);
        return new Audit(event, createdBy, gatewayReference, gatewayResponse, metadata, combined);
    }
}
