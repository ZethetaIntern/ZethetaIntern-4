package com.payflow.repository;

import com.payflow.entity.GatewayRoute;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

public interface GatewayRouteRepository extends JpaRepository<GatewayRoute, String> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update GatewayRoute r set r.probeLeaseUntil = :leaseUntil "
            + "where r.gateway = :gateway and r.healthy = false "
            + "and r.circuitOpenUntil <= CURRENT_TIMESTAMP and r.probeLeaseUntil <= CURRENT_TIMESTAMP")
    int claimHalfOpenProbe(@Param("gateway") String gateway, @Param("leaseUntil") Instant leaseUntil);
}
