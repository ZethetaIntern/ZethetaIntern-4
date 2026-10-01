package com.payflow.repository;

import com.payflow.entity.ReconciliationLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationLogRepository extends JpaRepository<ReconciliationLog, String> {}
