package com.payflow.repository;

import com.payflow.entity.CircuitBreakerConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CircuitBreakerConfigRepository extends JpaRepository<CircuitBreakerConfig, Long> {
    Optional<CircuitBreakerConfig> findByGatewayAndPaymentMethod(String gateway, String paymentMethod);
}
