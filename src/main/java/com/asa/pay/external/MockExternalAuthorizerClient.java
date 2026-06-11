package com.asa.pay.external;

import com.asa.pay.exception.ExternalApiException;
import com.asa.pay.exception.ExternalApiUnavailableException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MockExternalAuthorizerClient implements ExternalAuthorizerClient {

    private static final Logger log = LoggerFactory.getLogger(MockExternalAuthorizerClient.class);
    private static final String INSTANCE = "externalApi";

    @Value("${external.api.simulated-failure-rate:0.0}")
    private double failureRate;

    @Value("${external.api.simulated-latency-ms:30}")
    private long latencyMs;

    @Retry(name = INSTANCE, fallbackMethod = "fallbackAuthorize")
    @CircuitBreaker(name = INSTANCE)
    @Bulkhead(name = INSTANCE)
    @Override
    public void authorize(String transactionId, String terminalId, String nsu, BigDecimal amount) {
        simulateCall("authorize transactionId=" + transactionId);
    }

    @Retry(name = INSTANCE, fallbackMethod = "fallbackById")
    @CircuitBreaker(name = INSTANCE)
    @Bulkhead(name = INSTANCE)
    @Override
    public void confirm(String transactionId) {
        simulateCall("confirm transactionId=" + transactionId);
    }

    @Retry(name = INSTANCE, fallbackMethod = "fallbackById")
    @CircuitBreaker(name = INSTANCE)
    @Bulkhead(name = INSTANCE)
    @Override
    public void voidTransaction(String transactionId) {
        simulateCall("void transactionId=" + transactionId);
    }

    private void simulateCall(String op) {
        try {
            Thread.sleep(latencyMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalApiException("Chamada externa interrompida em " + op, e);
        }
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new ExternalApiException("Falha simulada na API externa: " + op);
        }
        log.info("API externa OK: {}", op);
    }

    @SuppressWarnings("unused")
    private void fallbackAuthorize(String transactionId, String terminalId, String nsu,
                                   BigDecimal amount, Throwable t) {
        throw unavailable("authorize", t);
    }

    @SuppressWarnings("unused")
    private void fallbackById(String transactionId, Throwable t) {
        throw unavailable("confirm/void", t);
    }

    private ExternalApiUnavailableException unavailable(String op, Throwable t) {
        log.warn("API externa indisponível em [{}] - acionando fallback: {}", op, t.toString());
        return new ExternalApiUnavailableException(
                "API externa indisponível ao executar " + op, t);
    }
}
