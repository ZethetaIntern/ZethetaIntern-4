package com.payflow.repository;

import com.payflow.entity.NotificationOutbox;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {
    List<NotificationOutbox> findTop100ByStatusOrderByCreatedAtAsc(NotificationOutbox.Status status);

    List<NotificationOutbox> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);
}
