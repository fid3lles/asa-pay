package com.asa.pay.api.dto;

import com.asa.pay.domain.Transaction;
import java.math.BigDecimal;

public record AuthorizeResponse(
        String nsu,
        BigDecimal amount,
        String terminalId,
        String transactionId
) {
    public static AuthorizeResponse from(Transaction t) {
        return new AuthorizeResponse(t.getNsu(), t.getAmount(), t.getTerminalId(), t.getTransactionId());
    }
}
