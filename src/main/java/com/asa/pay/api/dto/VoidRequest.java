package com.asa.pay.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;

public record VoidRequest(
        String transactionId,
        String nsu,
        String terminalId
) {

    @JsonIgnore
    public boolean hasTransactionId() {
        return transactionId != null && !transactionId.isBlank();
    }

    @JsonIgnore
    public boolean hasNsuAndTerminal() {
        return nsu != null && !nsu.isBlank() && terminalId != null && !terminalId.isBlank();
    }

    @JsonIgnore
    @AssertTrue(message = "Informe transactionId OU (nsu + terminalId)")
    public boolean isIdentificationValid() {
        return hasTransactionId() || hasNsuAndTerminal();
    }
}
