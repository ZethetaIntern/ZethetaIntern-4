package com.payflow.repository;

import com.payflow.entity.IdempotencyKey;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, IdempotencyKey.Pk> {

    /** A8.2: insert-if-absent; returns 1 when this request now owns the key, 0 when it already existed. */
    @Modifying
    @Query(value = "INSERT INTO idempotency_keys (merchant_id, key, request_hash, request_path, status, "
            + "created_at, updated_at, expires_at) VALUES (:merchantId, :key, :hash, :path, 'PROCESSING', "
            + "NOW(), NOW(), NOW() + INTERVAL '24 hours') ON CONFLICT (merchant_id, key) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("merchantId") String merchantId, @Param("key") String key,
                       @Param("hash") String requestHash, @Param("path") String requestPath);

    /** Background cleanup of expired keys (A4.2). */
    @Modifying
    @Query("delete from IdempotencyKey k where k.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
