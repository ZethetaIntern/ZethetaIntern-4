package com.payflow.repository;

import com.payflow.entity.GatewayAttempt;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GatewayAttemptRepository extends JpaRepository<GatewayAttempt, UUID> {
    List<GatewayAttempt> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    List<GatewayAttempt> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);
}
