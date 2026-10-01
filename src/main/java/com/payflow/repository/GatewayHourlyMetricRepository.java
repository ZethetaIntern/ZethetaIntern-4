package com.payflow.repository;

import com.payflow.entity.GatewayHourlyMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GatewayHourlyMetricRepository extends JpaRepository<GatewayHourlyMetric, String> {
    List<GatewayHourlyMetric> findByGatewayOrderByRecordedAtDesc(String gateway);
}
