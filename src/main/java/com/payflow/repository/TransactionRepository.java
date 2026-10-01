package com.payflow.repository;

import com.payflow.entity.*;
import com.payflow.domain.TransactionState;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select t from Transaction t where t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") String id);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    List<Transaction> findByMerchantOrderId(String merchantOrderId);

    List<Transaction> findByGatewayReference(String gatewayReference);

    Optional<Transaction> findFirstByStateOrderByUpdatedAtAsc(TransactionState state);

    List<Transaction> findByStateIn(List<TransactionState> states);

    Optional<Transaction> findFirstByStateAndGatewayIsNotNull(TransactionState state);
}
