package com.asa.pay.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record AuthorizeRequest(

        @NotBlank(message = "nsu é obrigatório")
        String nsu,

        @NotNull(message = "amount é obrigatório")
        @Positive(message = "amount deve ser positivo")
        BigDecimal amount,

        @NotBlank(message = "terminalId é obrigatório")
        String terminalId
) {
}
