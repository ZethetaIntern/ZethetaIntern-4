package com.payflow.service;

import com.payflow.entity.IdempotencyKey;
import com.payflow.repository.IdempotencyKeyRepository;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Idempotency orchestration (spec A8.2, FS-03, FS-09, FS-13).
 *
 * <p>Locking strategy: on PostgreSQL an advisory lock
 * ({@code pg_advisory_xact_lock(hashtext('idem_' || key))}) serialises concurrent
 * requests for the same idempotency key; the unique primary key on
 * {@code (merchant_id, key)} is the correctness backstop on any database.</p>
 *
 * <p>Outcomes: first caller proceeds; a concurrent caller sees
 * {@code PROCESSING} and gets 409 Conflict (FS-03/FS-09); a later replay of a
 * completed request returns the original transaction (idempotent replay).</p>
 */
@Service
public class IdempotencyService {

    public enum Outcome { PROCEED, REPLAY, CONFLICT_IN_PROGRESS }

    public record Decision(Outcome outcome, String transactionId, IdempotencyKey.Status status) {}

    private final IdempotencyKeyRepository repo;
    private final com.payflow.repository.TransactionRepository transactions;
    private final EntityManager entityManager;
    private final javax.sql.DataSource dataSource;
    private volatile Boolean postgres;

    public IdempotencyService(IdempotencyKeyRepository repo,
                              com.payflow.repository.TransactionRepository transactions,
                              EntityManager entityManager, javax.sql.DataSource dataSource) {
        this.repo = repo;
        this.transactions = transactions;
        this.entityManager = entityManager;
        this.dataSource = dataSource;
    }

    private boolean isPostgres() {
        if (postgres == null) {
            synchronized (this) {
                if (postgres == null) {
                    try (java.sql.Connection c = dataSource.getConnection()) {
                        postgres = c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException("Unable to identify idempotency database", e);
                    }
                }
            }
        }
        return postgres;
    }

    /**
     * A8.2: transaction-scoped advisory lock on PostgreSQL. No-op on other engines,
     * where the unique primary key on {@code (merchant_id, key)} provides the
     * correctness guarantee instead.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void acquireAdvisoryLock(String merchantId, String key) {
        if (!isPostgres()) return;
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:lk))")
                .setParameter("lk", "idem_" + IdempotencyKey.compositeId(merchantId, key))
                .getSingleResult();
    }

    public static String requestHash(String merchantOrderId, long amount, String currency, String method) {
        String raw = merchantOrderId + "|" + amount + "|" + currency + "|" + method;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reserves the key for this request. Callers proceed only on {@link Outcome#PROCEED}.
     * Runs in its own transaction so the reservation survives the caller's rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision reserve(String merchantId, String key, String transactionId, String requestHash) {
        Optional<IdempotencyKey> existing = repo.findById(IdempotencyKey.compositeId(merchantId, key));
        if (existing.isPresent()) {
            IdempotencyKey e = existing.get();
            if (e.isExpired()) {
                repo.delete(e);
                repo.flush();
            } else {
                // Same key, different payload => genuine conflict.
                if (!e.getRequestHash().equals(requestHash)) {
                    return new Decision(Outcome.CONFLICT_IN_PROGRESS, e.getTransactionId(), e.getStatus());
                }
                return new Decision(e.getStatus() == IdempotencyKey.Status.PROCESSING
                        ? Outcome.CONFLICT_IN_PROGRESS : Outcome.REPLAY,
                        e.getTransactionId(), e.getStatus());
            }
        }
        try {
            repo.saveAndFlush(new IdempotencyKey(key, merchantId, transactionId, requestHash));
            return new Decision(Outcome.PROCEED, transactionId, IdempotencyKey.Status.PROCESSING);
        } catch (DataIntegrityViolationException e) {
            // Lost the insert race against a concurrent identical request.
            IdempotencyKey winner = repo.findById(IdempotencyKey.compositeId(merchantId, key))
                    .orElseThrow(() -> e);
            return new Decision(Outcome.CONFLICT_IN_PROGRESS, winner.getTransactionId(), winner.getStatus());
        }
    }

    /** Reads the current reservation for a key, if any (REQUIRES_NEW). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision peek(String merchantId, String key, String requestHash) {
        return repo.findById(IdempotencyKey.compositeId(merchantId, key))
                .map(e -> {
                    if (e.isExpired()) return new Decision(Outcome.PROCEED, e.getTransactionId(), e.getStatus());
                    if (!e.getRequestHash().equals(requestHash)) {
                        return new Decision(Outcome.CONFLICT_IN_PROGRESS, e.getTransactionId(), e.getStatus());
                    }
                    return new Decision(e.getStatus() == IdempotencyKey.Status.PROCESSING
                            ? Outcome.CONFLICT_IN_PROGRESS : Outcome.REPLAY,
                            e.getTransactionId(), e.getStatus());
                })
                .orElseGet(() -> new Decision(Outcome.PROCEED, null, IdempotencyKey.Status.PROCESSING));
    }

    /** Resolves the transaction that owns a key, in a fresh transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String findExistingId(String merchantId, String key) {
        return repo.findById(IdempotencyKey.compositeId(merchantId, key))
                .map(IdempotencyKey::getTransactionId)
                .orElseGet(() -> transactions.findByMerchantIdAndIdempotencyKey(merchantId, key)
                        .map(t -> t.getId())
                        .orElse(null));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String merchantId, String key, IdempotencyKey.Status status) {
        repo.findById(IdempotencyKey.compositeId(merchantId, key)).ifPresent(k -> {
            k.setStatus(status);
            repo.save(k);
        });
    }

    /** A6.1: keys are pruned after 24 hours. */
    @Transactional
    public int purgeExpired() {
        return (int) repo.findAll().stream().filter(IdempotencyKey::isExpired).peek(repo::delete).count();
    }
}
