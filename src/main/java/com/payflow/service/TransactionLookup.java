package com.payflow.service;

import com.payflow.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lookup performed in a fresh transaction so it can run after the current
 * transaction was marked rollback-only (e.g. an idempotency unique violation).
 */
@Service
public class TransactionLookup {

    private final TransactionRepository transactions;

    public TransactionLookup(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String findIdByIdempotencyKey(String key) {
        return transactions.findByIdempotencyKey(key)
                .map(t -> t.getId())
                .orElse(null);
    }
}
