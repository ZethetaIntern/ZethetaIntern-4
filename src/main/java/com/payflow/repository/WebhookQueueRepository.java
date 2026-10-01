package com.payflow.repository;

import com.payflow.entity.WebhookQueueItem;
import com.payflow.entity.WebhookQueueItem.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WebhookQueueRepository extends JpaRepository<WebhookQueueItem, String> {
    List<WebhookQueueItem> findByStatus(Status status);
    long countByStatus(Status status);
}
