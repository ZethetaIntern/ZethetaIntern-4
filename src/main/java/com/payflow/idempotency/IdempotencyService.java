package com.payflow.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.payflow.entity.IdempotencyKey;
import com.payflow.repository.IdempotencyKeyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency layer (spec A4.1 / A4.2 / A8.2, FS-03, FS-09, FS-13).
 *
 * <pre>
 * claim():   pg_advisory_xact_lock(hashtext('idem_' || merchant || ':' || key))
 *            existing?  PROCESSING -> 409 IN_PROGRESS
 *                       COMPLETED  -> REPLAY cached response
 *                       FAILED     -> RESUME (an unexpected error may be retried)
 *                       different request body -> 422 MISMATCH
 *            absent    -> INSERT ... status='PROCESSING' (this request now owns the key)
 * complete(): status='COMPLETED', response_code, response_body cached for replays
 * fail():     status='FAILED' so the client may retry
 * </pre>
 *
 * The advisory lock serialises concurrent requests for the same key even on
 * different application servers; the composite primary key
 * {@code (merchant_id, key)} is the correctness backstop.
 */
@Service
public class IdempotencyService {

    /** Outcome of trying to take ownership of a key. */
    public enum Kind { PROCEED_NEW, RESUME, REPLAY, IN_PROGRESS, MISMATCH }

    public record Claim(Kind kind, IdempotencyKey key) {}

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @PersistenceContext
    private EntityManager em;

    private final IdempotencyKeyRepository repo;

    public IdempotencyService(IdempotencyKeyRepository repo) {
        this.repo = repo;
    }

    /** Must run inside the caller's transaction so key ownership and the work it guards commit together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Claim claim(String merchantId, String key, String requestHash, String requestPath) {
        em.createNativeQuery("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:lk))) AS l")
                .setParameter("lk", "idem_" + merchantId + ":" + key)
                .getSingleResult();

        IdempotencyKey existing = repo.findById(new IdempotencyKey.Pk(merchantId, key)).orElse(null);
        if (existing != null) {
            em.refresh(existing);
            if (existing.isExpired()) {
                repo.delete(existing);
                repo.flush();
            } else if (!existing.getRequestHash().equals(requestHash)) {
                return new Claim(Kind.MISMATCH, existing);
            } else {
                return switch (existing.getStatus()) {
                    case PROCESSING -> new Claim(Kind.IN_PROGRESS, existing);
                    case COMPLETED -> new Claim(Kind.REPLAY, existing);
                    case FAILED -> {
                        existing.setStatus(IdempotencyKey.Status.PROCESSING);
                        existing.setExpiresAt(Instant.now().plus(IdempotencyKey.TTL));
                        yield new Claim(Kind.RESUME, repo.save(existing));
                    }
                };
            }
        }
        if (repo.insertIfAbsent(merchantId, key, requestHash, requestPath) == 0) {
            return new Claim(Kind.IN_PROGRESS, repo.findById(new IdempotencyKey.Pk(merchantId, key)).orElse(null));
        }
        return new Claim(Kind.PROCEED_NEW, repo.findById(new IdempotencyKey.Pk(merchantId, key)).orElseThrow());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void attachTransaction(String merchantId, String key, UUID transactionId) {
        repo.findById(new IdempotencyKey.Pk(merchantId, key)).ifPresent(k -> k.setTransactionId(transactionId));
    }

    /** Caches the response so a replay returns exactly what the original request returned. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String merchantId, String key, int responseCode, Map<String, Object> responseBody) {
        repo.findById(new IdempotencyKey.Pk(merchantId, key))
                .ifPresent(k -> k.complete(IdempotencyKey.Status.COMPLETED, responseCode, responseBody));
    }

    /** Step 5 of A4.1: an unexpected error releases the key so the client can retry. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String merchantId, String key) {
        repo.findById(new IdempotencyKey.Pk(merchantId, key)).ifPresent(k -> k.setStatus(IdempotencyKey.Status.FAILED));
    }

    /** Background cleanup: {@code DELETE FROM idempotency_keys WHERE expires_at < NOW()}. */
    @Transactional
    public int purgeExpired() {
        return repo.deleteExpired(Instant.now());
    }

    /** SHA-256 over a canonical (key-sorted) JSON rendering of the request. */
    public static String requestHash(Map<String, ?> canonicalRequest) {
        try {
            byte[] json = CANONICAL.writeValueAsBytes(new TreeMap<>(canonicalRequest));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot hash request", e);
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
