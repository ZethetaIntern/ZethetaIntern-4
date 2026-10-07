package com.payflow.repository;

import com.payflow.entity.Anomaly;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnomalyRepository extends JpaRepository<Anomaly, UUID> {
    List<Anomaly> findByRunId(String runId);

    List<Anomaly> findByTransactionId(UUID transactionId);

    List<Anomaly> findAllByOrderByCreatedAtDesc();

    long countByStatus(Anomaly.Status status);
}
