package com.payflow.repository;

import com.payflow.entity.TransactionStateLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionStateLogRepository extends JpaRepository<TransactionStateLog, UUID> {
    List<TransactionStateLog> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    List<TransactionStateLog> findByTransactionIdAndEventOrderByCreatedAtAsc(UUID transactionId, String event);

    long countByEvent(String event);
}
