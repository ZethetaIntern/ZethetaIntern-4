package com.payflow.repository;

import com.payflow.entity.GatewayHealthMetric;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GatewayHealthMetricRepository extends JpaRepository<GatewayHealthMetric, Long> {
    List<GatewayHealthMetric> findByGatewayAndRecordedAtAfterOrderByRecordedAtDesc(String gateway, Instant since);

    boolean existsByGatewayAndRecordedAt(String gateway, Instant recordedAt);
}
