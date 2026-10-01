package com.payflow.repository;

import com.payflow.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {
    java.util.List<IdempotencyKey> findByKeyAndMerchantId(String key, String merchantId);
}
