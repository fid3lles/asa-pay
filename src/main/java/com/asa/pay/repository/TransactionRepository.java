package com.asa.pay.repository;

import com.asa.pay.domain.Transaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByTerminalIdAndNsu(String terminalId, String nsu);

    Optional<Transaction> findByTransactionId(String transactionId);
}
