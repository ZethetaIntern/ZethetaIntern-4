package com.payflow.repository;

import com.payflow.entity.GatewayAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.time.Instant;

public interface GatewayAttemptRepository extends JpaRepository<GatewayAttempt, String> {
    List<GatewayAttempt> findByTransactionIdOrderByAttemptNoAsc(String transactionId);
    Page<GatewayAttempt> findByGatewayAndCreatedAtAfterOrderByCreatedAtDesc(
            String gateway, Instant since, Pageable pageable);
}
