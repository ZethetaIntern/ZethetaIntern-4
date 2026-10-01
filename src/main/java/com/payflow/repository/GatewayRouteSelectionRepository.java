package com.payflow.repository;

import com.payflow.entity.GatewayRouteSelection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/** Records which gateway was selected for a transaction and why (spec A6.1). */
public interface GatewayRouteSelectionRepository extends JpaRepository<GatewayRouteSelection, String> {
    List<GatewayRouteSelection> findByTransactionId(String transactionId);
}
