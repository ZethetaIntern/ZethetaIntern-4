package com.payflow.repository;

import com.payflow.entity.GatewayConfig;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GatewayConfigRepository extends JpaRepository<GatewayConfig, String> {
    List<GatewayConfig> findAllByOrderByGatewayNameAsc();
}
