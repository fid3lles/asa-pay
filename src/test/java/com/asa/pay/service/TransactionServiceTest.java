package com.asa.pay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.asa.pay.api.dto.AuthorizeRequest;
import com.asa.pay.api.dto.ConfirmRequest;
import com.asa.pay.api.dto.VoidRequest;
import com.asa.pay.domain.Transaction;
import com.asa.pay.domain.TransactionStatus;
import com.asa.pay.exception.InvalidTransactionStateException;
import com.asa.pay.exception.TransactionNotFoundException;
import com.asa.pay.external.ExternalAuthorizerClient;
import com.asa.pay.repository.TransactionRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    private static final BigDecimal AMOUNT = new BigDecimal("199.90");

    @Mock
    private TransactionRepository repository;

    @Mock
    private ExternalAuthorizerClient externalClient;

    @InjectMocks
    private TransactionService service;

    @Test
    void authorize_quandoNova_geraTransactionIdEPersiste() {
        when(repository.findByTerminalIdAndNsu("T-1000", "123")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.authorize(new AuthorizeRequest("123", AMOUNT, "T-1000"));

        assertThat(response.transactionId()).isNotBlank();
        assertThat(response.terminalId()).isEqualTo("T-1000");
        verify(externalClient).authorize(anyString(), eq("T-1000"), eq("123"), eq(AMOUNT));
        verify(repository).saveAndFlush(any(Transaction.class));
    }

    @Test
    void authorize_quandoJaExiste_eIdempotenteSemChamarExterno() {
        var existing = new Transaction("01EXISTING", "123", "T-1000", AMOUNT, TransactionStatus.AUTHORIZED);
        when(repository.findByTerminalIdAndNsu("T-1000", "123")).thenReturn(Optional.of(existing));

        var response = service.authorize(new AuthorizeRequest("123", AMOUNT, "T-1000"));

        assertThat(response.transactionId()).isEqualTo("01EXISTING");
        verifyNoInteractions(externalClient);
        verify(repository, never()).saveAndFlush(any(Transaction.class));
    }

    @Test
    void confirm_quandoAutorizada_chamaExternoEConfirma() {
        var t = new Transaction("01ABC", "123", "T-1000", AMOUNT, TransactionStatus.AUTHORIZED);
        when(repository.findByTransactionId("01ABC")).thenReturn(Optional.of(t));

        service.confirm(new ConfirmRequest("01ABC"));

        verify(externalClient).confirm("01ABC");
        assertThat(t.getStatus()).isEqualTo(TransactionStatus.CONFIRMED);
    }

    @Test
    void confirm_quandoJaConfirmada_eNoOp() {
        var t = new Transaction("01ABC", "123", "T-1000", AMOUNT, TransactionStatus.CONFIRMED);
        when(repository.findByTransactionId("01ABC")).thenReturn(Optional.of(t));

        service.confirm(new ConfirmRequest("01ABC"));

        verify(externalClient, never()).confirm(anyString());
        assertThat(t.getStatus()).isEqualTo(TransactionStatus.CONFIRMED);
    }

    @Test
    void confirm_quandoDesfeita_lancaConflito() {
        var t = new Transaction("01ABC", "123", "T-1000", AMOUNT, TransactionStatus.VOIDED);
        when(repository.findByTransactionId("01ABC")).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.confirm(new ConfirmRequest("01ABC")))
                .isInstanceOf(InvalidTransactionStateException.class);
        verifyNoInteractions(externalClient);
    }

    @Test
    void confirm_quandoInexistente_lancaNotFound() {
        when(repository.findByTransactionId("01NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(new ConfirmRequest("01NOPE")))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void void_porNsuETerminal_quandoAutorizada_desfaz() {
        var t = new Transaction("01ABC", "123", "T-1000", AMOUNT, TransactionStatus.AUTHORIZED);
        when(repository.findByTerminalIdAndNsu("T-1000", "123")).thenReturn(Optional.of(t));

        service.voidTransaction(new VoidRequest(null, "123", "T-1000"));

        verify(externalClient).voidTransaction("01ABC");
        assertThat(t.getStatus()).isEqualTo(TransactionStatus.VOIDED);
    }

    @Test
    void void_quandoJaDesfeita_eNoOp() {
        var t = new Transaction("01ABC", "123", "T-1000", AMOUNT, TransactionStatus.VOIDED);
        when(repository.findByTransactionId("01ABC")).thenReturn(Optional.of(t));

        service.voidTransaction(new VoidRequest("01ABC", null, null));

        verify(externalClient, never()).voidTransaction(anyString());
    }

    @Test
    void void_quandoConfirmada_lancaConflito() {
        var t = new Transaction("01ABC", "123", "T-1000", AMOUNT, TransactionStatus.CONFIRMED);
        when(repository.findByTransactionId("01ABC")).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.voidTransaction(new VoidRequest("01ABC", null, null)))
                .isInstanceOf(InvalidTransactionStateException.class);
        verifyNoInteractions(externalClient);
    }
}
