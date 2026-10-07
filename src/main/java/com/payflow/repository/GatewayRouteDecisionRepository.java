package com.payflow.repository;

import com.payflow.entity.GatewayRouteDecision;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GatewayRouteDecisionRepository extends JpaRepository<GatewayRouteDecision, UUID> {
    List<GatewayRouteDecision> findByTransactionIdOrderByAttemptNoAscRankAsc(UUID transactionId);
}
