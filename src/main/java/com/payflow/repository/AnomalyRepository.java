package com.payflow.repository;

import com.payflow.entity.Anomaly;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AnomalyRepository extends JpaRepository<Anomaly, String> {
    List<Anomaly> findByRunId(String runId);
    long countByAlertedFalse();
}
