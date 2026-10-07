package com.payflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** One routing weight or threshold (table {@code routing_config}, keyed by {@code config_key}). */
@Entity
@Table(name = "routing_config")
public class RoutingConfigEntry {

    @Id
    @Column(name = "config_key", length = 64)
    private String configKey;

    @Column(name = "config_value", nullable = false, precision = 12, scale = 4)
    private BigDecimal configValue;

    @Column(nullable = false)
    private String description;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy = "system";

    protected RoutingConfigEntry() {}

    public RoutingConfigEntry(String configKey, BigDecimal configValue, String description) {
        this.configKey = configKey;
        this.configValue = configValue;
        this.description = description;
    }

    public void update(BigDecimal value, String by) {
        this.configValue = value;
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }

    public String getConfigKey() { return configKey; }
    public BigDecimal getConfigValue() { return configValue; }
    public String getDescription() { return description; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
