package com.payflow.repository;

import com.payflow.entity.ProcessedWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedWebhookEventRepository extends JpaRepository<ProcessedWebhookEvent, String> {
    boolean existsByEventKey(String eventKey);
}
