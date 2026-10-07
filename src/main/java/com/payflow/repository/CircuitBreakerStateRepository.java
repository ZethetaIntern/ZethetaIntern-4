package com.payflow.repository;

import com.payflow.entity.CircuitBreakerState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CircuitBreakerStateRepository extends JpaRepository<CircuitBreakerState, CircuitBreakerState.Pk> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CircuitBreakerState c where c.gateway = :gateway and c.paymentMethod = :method")
    Optional<CircuitBreakerState> findForUpdate(@Param("gateway") String gateway, @Param("method") String method);

    /** Creates the CLOSED row on first use; safe under concurrency. */
    @Modifying
    @Query(value = "INSERT INTO circuit_breaker_state (gateway, payment_method, state, updated_at, version) "
            + "VALUES (:gateway, :method, 'CLOSED', NOW(), 0) ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("gateway") String gateway, @Param("method") String method);

    List<CircuitBreakerState> findByGatewayOrderByPaymentMethodAsc(String gateway);
}
