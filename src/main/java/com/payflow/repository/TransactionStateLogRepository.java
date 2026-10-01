package com.payflow.repository;

import com.payflow.entity.TransactionStateLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TransactionStateLogRepository extends JpaRepository<TransactionStateLog, String> {
    List<TransactionStateLog> findByTransactionIdOrderByCreatedAtAsc(String transactionId);
}
