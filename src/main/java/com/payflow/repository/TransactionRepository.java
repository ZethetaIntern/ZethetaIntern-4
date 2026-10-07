package com.payflow.repository;

import com.payflow.domain.TransactionState;
import com.payflow.entity.Transaction;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /** A8.1: SELECT ... FOR UPDATE, held only for the duration of a state transition. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select t from Transaction t where t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") UUID id);

    Optional<Transaction> findByMerchantIdAndIdempotencyKey(String merchantId, String idempotencyKey);

    List<Transaction> findByMerchantIdAndMerchantOrderIdOrderByCreatedAtDesc(String merchantId, String merchantOrderId);

    List<Transaction> findByGatewayAndGatewayReference(String gateway, String gatewayReference);

    List<Transaction> findByStateInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<TransactionState> states, Instant cutoff, Pageable page);

    /** Keyset pagination over captured-but-unsettled transactions (settlement reconciliation). */
    @Query("select t from Transaction t where t.state in :states and t.gateway is not null "
            + "and t.createdAt >= :since and t.id > :afterId order by t.id")
    List<Transaction> findSettlementCandidates(@Param("states") Collection<TransactionState> states,
                                               @Param("since") Instant since,
                                               @Param("afterId") UUID afterId, Pageable page);

    List<Transaction> findByStateAndNextRetryAtBeforeOrderByNextRetryAtAsc(
            TransactionState state, Instant now, Pageable page);

    List<Transaction> findByStateInAndAuthExpiresAtBeforeOrderByAuthExpiresAtAsc(
            Collection<TransactionState> states, Instant now, Pageable page);

    List<Transaction> findByStateAndCreatedAtBeforeOrderByCreatedAtAsc(
            TransactionState state, Instant cutoff, Pageable page);

    long countByState(TransactionState state);

    /** Per-gateway outcome counts for analytics: [gateway, total, captured, failed]. */
    @Query("select t.gateway, count(t), "
            + "sum(case when t.state in :successStates then 1 else 0 end), "
            + "sum(case when t.state in :failedStates then 1 else 0 end) "
            + "from Transaction t where t.gateway is not null and t.createdAt >= :since group by t.gateway")
    List<Object[]> outcomeCountsByGateway(@Param("since") Instant since,
                                          @Param("successStates") Collection<TransactionState> successStates,
                                          @Param("failedStates") Collection<TransactionState> failedStates);

    @Query("select t.state, count(t), coalesce(sum(t.amountPaise), 0) from Transaction t "
            + "where t.createdAt >= :since group by t.state")
    List<Object[]> volumeByState(@Param("since") Instant since);

    @Query("select t.paymentMethod, count(t), coalesce(sum(t.amountPaise), 0) from Transaction t "
            + "where t.createdAt >= :since group by t.paymentMethod")
    List<Object[]> volumeByMethod(@Param("since") Instant since);

    @Query(value = "SELECT date_trunc('hour', created_at) AS bucket, COUNT(*), COALESCE(SUM(amount_paise), 0) "
            + "FROM transactions WHERE created_at >= :since GROUP BY bucket ORDER BY bucket", nativeQuery = true)
    List<Object[]> volumeByHour(@Param("since") Instant since);
}
