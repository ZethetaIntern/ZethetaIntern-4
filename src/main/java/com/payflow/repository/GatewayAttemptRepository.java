package com.payflow.repository;

import com.payflow.entity.GatewayAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GatewayAttemptRepository extends JpaRepository<GatewayAttempt, String> {
    List<GatewayAttempt> findByTransactionIdOrderByAttemptNoAsc(String transactionId);
}
