package com.payflow.repository;

import com.payflow.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RefundRepository extends JpaRepository<Refund, String> {
    List<Refund> findByTransactionId(String transactionId);
}
