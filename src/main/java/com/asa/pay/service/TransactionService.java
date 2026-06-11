package com.asa.pay.service;

import com.asa.pay.api.dto.AuthorizeRequest;
import com.asa.pay.api.dto.AuthorizeResponse;
import com.asa.pay.api.dto.ConfirmRequest;
import com.asa.pay.api.dto.VoidRequest;
import com.asa.pay.domain.Transaction;
import com.asa.pay.domain.TransactionStatus;
import com.asa.pay.exception.InvalidTransactionStateException;
import com.asa.pay.exception.TransactionNotFoundException;
import com.asa.pay.external.ExternalAuthorizerClient;
import com.asa.pay.repository.TransactionRepository;
import com.github.f4b6a3.ulid.UlidCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository repository;
    private final ExternalAuthorizerClient externalClient;

    public TransactionService(TransactionRepository repository,
                              ExternalAuthorizerClient externalClient) {
        this.repository = repository;
        this.externalClient = externalClient;
    }

    @Transactional
    public AuthorizeResponse authorize(AuthorizeRequest request) {
        var existing = repository.findByTerminalIdAndNsu(request.terminalId(), request.nsu());
        if (existing.isPresent()) {
            log.info("Authorize idempotente: transação já existe para terminalId={} nsu={}",
                    request.terminalId(), request.nsu());
            return AuthorizeResponse.from(existing.get());
        }

        String transactionId = UlidCreator.getUlid().toString();

        externalClient.authorize(transactionId, request.terminalId(), request.nsu(), request.amount());

        Transaction transaction = new Transaction(
                transactionId,
                request.nsu(),
                request.terminalId(),
                request.amount(),
                TransactionStatus.AUTHORIZED
        );

        try {
            repository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException race) {
            log.warn("Corrida de idempotência detectada para terminalId={} nsu={}; "
                            + "retornando transação existente.",
                    request.terminalId(), request.nsu());
            Transaction winner = repository.findByTerminalIdAndNsu(request.terminalId(), request.nsu())
                    .orElseThrow(() -> race);
            return AuthorizeResponse.from(winner);
        }

        log.info("Transação autorizada transactionId={}", transactionId);
        return AuthorizeResponse.from(transaction);
    }

    @Transactional
    public void confirm(ConfirmRequest request) {
        Transaction transaction = repository.findByTransactionId(request.transactionId())
                .orElseThrow(() -> new TransactionNotFoundException(
                        "Transação não encontrada: " + request.transactionId()));

        switch (transaction.getStatus()) {
            case CONFIRMED -> log.info("Confirm idempotente (no-op) transactionId={}",
                    transaction.getTransactionId());
            case VOIDED -> throw new InvalidTransactionStateException(
                    "Transação já desfeita (VOIDED) não pode ser confirmada: "
                            + transaction.getTransactionId());
            case AUTHORIZED -> {
                externalClient.confirm(transaction.getTransactionId());
                transaction.setStatus(TransactionStatus.CONFIRMED);
                log.info("Transação confirmada transactionId={}", transaction.getTransactionId());
            }
        }
    }

    @Transactional
    public void voidTransaction(VoidRequest request) {
        Transaction transaction = resolve(request);

        switch (transaction.getStatus()) {
            case VOIDED -> log.info("Void idempotente (no-op) transactionId={}",
                    transaction.getTransactionId());
            case CONFIRMED -> throw new InvalidTransactionStateException(
                    "Transação confirmada (CONFIRMED) não pode ser desfeita: "
                            + transaction.getTransactionId());
            case AUTHORIZED -> {
                externalClient.voidTransaction(transaction.getTransactionId());
                transaction.setStatus(TransactionStatus.VOIDED);
                log.info("Transação desfeita transactionId={}", transaction.getTransactionId());
            }
        }
    }

    private Transaction resolve(VoidRequest request) {
        if (request.hasTransactionId()) {
            return repository.findByTransactionId(request.transactionId())
                    .orElseThrow(() -> new TransactionNotFoundException(
                            "Transação não encontrada: " + request.transactionId()));
        }
        return repository.findByTerminalIdAndNsu(request.terminalId(), request.nsu())
                .orElseThrow(() -> new TransactionNotFoundException(
                        "Transação não encontrada para terminalId=" + request.terminalId()
                                + " nsu=" + request.nsu()));
    }
}
