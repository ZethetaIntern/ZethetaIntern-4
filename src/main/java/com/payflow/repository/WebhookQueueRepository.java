package com.payflow.repository;

import com.payflow.entity.WebhookQueueItem;
import com.payflow.entity.WebhookQueueItem.Status;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookQueueRepository extends JpaRepository<WebhookQueueItem, Long> {

    /**
     * Competing consumers: each worker claims different rows (FOR UPDATE SKIP LOCKED).
     * PROCESSING rows whose lease ({@code next_retry_at}) expired belong to a crashed worker.
     */
    @Query(value = "SELECT * FROM webhook_queue WHERE status IN ('PENDING', 'FAILED', 'PROCESSING') "
            + "AND next_retry_at <= NOW() ORDER BY next_retry_at LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<WebhookQueueItem> claimDue(@Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WebhookQueueItem w where w.id = :id")
    Optional<WebhookQueueItem> findByIdForUpdate(@Param("id") Long id);

    List<WebhookQueueItem> findByStatusOrderByCreatedAtAsc(Status status);

    long countByStatus(Status status);

    Optional<WebhookQueueItem> findByGatewayAndEventId(String gateway, String eventId);
}
