package com.payflow.repository;

import com.payflow.entity.Refund;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    List<Refund> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    boolean existsByGatewayRefundId(String gatewayRefundId);

    Optional<Refund> findFirstByTransactionIdAndStateOrderByCreatedAtDesc(UUID transactionId, Refund.State state);
}
