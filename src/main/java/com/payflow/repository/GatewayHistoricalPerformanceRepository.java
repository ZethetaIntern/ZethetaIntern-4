package com.payflow.repository;

import com.payflow.entity.GatewayHistoricalPerformance;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GatewayHistoricalPerformanceRepository
        extends JpaRepository<GatewayHistoricalPerformance, GatewayHistoricalPerformance.Pk> {
    List<GatewayHistoricalPerformance> findByGatewayOrderByBandStartHourAsc(String gateway);
}
