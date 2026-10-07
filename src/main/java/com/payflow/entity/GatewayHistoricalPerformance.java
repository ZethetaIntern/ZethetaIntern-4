package com.payflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import org.hibernate.annotations.Immutable;

/** One row of the A3.4 historical dataset: a gateway's performance in a time-of-day band. */
@Entity
@Immutable
@IdClass(GatewayHistoricalPerformance.Pk.class)
@Table(name = "gateway_historical_performance")
public class GatewayHistoricalPerformance {

    /** Composite primary key (gateway, band_start_hour). */
    public static class Pk implements Serializable {
        private String gateway;
        private int bandStartHour;

        protected Pk() {}

        public Pk(String gateway, int bandStartHour) {
            this.gateway = gateway;
            this.bandStartHour = bandStartHour;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Pk p && bandStartHour == p.bandStartHour
                    && java.util.Objects.equals(gateway, p.gateway);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(gateway, bandStartHour);
        }
    }

    @Id
    @Column(nullable = false, length = 32)
    private String gateway;

    @Id
    @Column(name = "band_start_hour", nullable = false)
    private int bandStartHour;

    @Column(name = "band_end_hour", nullable = false)
    private int bandEndHour;

    @Column(name = "success_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal successRate;

    @Column(name = "p95_latency_ms", nullable = false)
    private int p95LatencyMs;

    @Column(nullable = false)
    private int transactions;

    @Column(name = "fee_bps", nullable = false)
    private int feeBps;

    @Column(name = "fixed_fee_paise", nullable = false)
    private long fixedFeePaise;

    protected GatewayHistoricalPerformance() {}

    public boolean covers(int hourOfDay) {
        return hourOfDay >= bandStartHour && hourOfDay < bandEndHour;
    }

    public String getGateway() { return gateway; }
    public int getBandStartHour() { return bandStartHour; }
    public int getBandEndHour() { return bandEndHour; }
    public BigDecimal getSuccessRate() { return successRate; }
    public int getP95LatencyMs() { return p95LatencyMs; }
    public int getTransactions() { return transactions; }
    public int getFeeBps() { return feeBps; }
    public long getFixedFeePaise() { return fixedFeePaise; }
}
