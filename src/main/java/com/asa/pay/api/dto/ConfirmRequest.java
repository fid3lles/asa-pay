package com.asa.pay.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmRequest(

        @NotBlank(message = "transactionId é obrigatório")
        String transactionId
) {
}
