package com.payflow.repository;

import com.payflow.entity.SecurityAuditLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SecurityAuditLogRepository extends JpaRepository<SecurityAuditLog, Long> {
    List<SecurityAuditLog> findTop100ByOrderByCreatedAtDesc();

    List<SecurityAuditLog> findByEventTypeOrderByCreatedAtDesc(String eventType);
}
