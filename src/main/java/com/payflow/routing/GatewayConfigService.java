package com.payflow.routing;

import com.payflow.entity.GatewayConfig;
import com.payflow.error.ApiException;
import com.payflow.repository.GatewayConfigRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cached access to {@code gateway_config}; updates invalidate the cache immediately. */
@Service
public class GatewayConfigService {

    private static final Duration TTL = Duration.ofSeconds(5);

    private final GatewayConfigRepository repo;
    private volatile Map<String, GatewayConfig> cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public GatewayConfigService(GatewayConfigRepository repo) {
        this.repo = repo;
    }

    public List<GatewayConfig> all() {
        return snapshot().values().stream()
                .sorted(java.util.Comparator.comparing(GatewayConfig::getGatewayName)).toList();
    }

    public Optional<GatewayConfig> find(String gateway) {
        return Optional.ofNullable(snapshot().get(gateway));
    }

    public GatewayConfig require(String gateway) {
        return find(gateway).orElseThrow(() -> ApiException.notFound("gateway '" + gateway + "'"));
    }

    @Transactional
    public GatewayConfig update(String gateway, java.util.function.Consumer<GatewayConfig> changes) {
        GatewayConfig g = repo.findById(gateway).orElseThrow(() -> ApiException.notFound("gateway '" + gateway + "'"));
        changes.accept(g);
        GatewayConfig saved = repo.save(g);
        cached = null;
        return saved;
    }

    public void invalidate() {
        cached = null;
    }

    private Map<String, GatewayConfig> snapshot() {
        Map<String, GatewayConfig> c = cached;
        if (c == null || Instant.now().isAfter(cachedAt.plus(TTL))) {
            c = repo.findAll().stream().collect(Collectors.toUnmodifiableMap(GatewayConfig::getGatewayName,
                    Function.identity()));
            cached = c;
            cachedAt = Instant.now();
        }
        return c;
    }
}
