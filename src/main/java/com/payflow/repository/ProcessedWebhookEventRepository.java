package com.payflow.repository;

import com.payflow.entity.ProcessedWebhookEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedWebhookEventRepository
        extends JpaRepository<ProcessedWebhookEvent, ProcessedWebhookEvent.Pk> {

    /**
     * A5.4 deduplication as a single atomic statement: returns 1 for a new event,
     * 0 for a duplicate. Concurrent deliveries of the same event race on the
     * primary key, so exactly one of them gets 1.
     */
    @Modifying
    @Query(value = "INSERT INTO processed_webhook_events (event_id, gateway, event_type, payload_hash, "
            + "transaction_id, processed_at) VALUES (:eventId, :gateway, :eventType, :payloadHash, "
            + ":transactionId, NOW()) ON CONFLICT (gateway, event_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("gateway") String gateway, @Param("eventId") String eventId,
                       @Param("eventType") String eventType, @Param("payloadHash") String payloadHash,
                       @Param("transactionId") UUID transactionId);

    long countByGateway(String gateway);
}
