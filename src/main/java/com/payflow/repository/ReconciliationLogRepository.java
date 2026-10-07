package com.payflow.repository;

import com.payflow.entity.ReconciliationLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationLogRepository extends JpaRepository<ReconciliationLog, UUID> {
    List<ReconciliationLog> findByRunIdOrderByCreatedAtAsc(String runId);

    List<ReconciliationLog> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);
}
