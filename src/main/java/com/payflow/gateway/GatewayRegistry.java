package com.payflow.gateway;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Looks up the adapter for a gateway name; fails fast at startup if one of the four is missing. */
@Component
public class GatewayRegistry {

    public static final Set<String> REQUIRED = Set.of("razorpay", "stripe", "payu", "upi");

    private final Map<String, PaymentGateway> adapters;

    public GatewayRegistry(List<PaymentGateway> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(PaymentGateway::name,
                Function.identity()));
        if (!this.adapters.keySet().containsAll(REQUIRED)) {
            throw new IllegalStateException("gateway adapters missing: expected " + REQUIRED
                    + " but found " + this.adapters.keySet());
        }
    }

    public PaymentGateway get(String gateway) {
        PaymentGateway g = adapters.get(gateway);
        if (g == null) throw new IllegalArgumentException("unknown gateway: " + gateway);
        return g;
    }

    public Set<String> names() {
        return adapters.keySet();
    }
}
