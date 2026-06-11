package com.asa.pay.external;

import java.math.BigDecimal;

public interface ExternalAuthorizerClient {

    void authorize(String transactionId, String terminalId, String nsu, BigDecimal amount);

    void confirm(String transactionId);

    void voidTransaction(String transactionId);
}
