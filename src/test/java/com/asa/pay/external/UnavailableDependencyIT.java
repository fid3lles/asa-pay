package com.asa.pay.external;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Com a API externa sempre falhando (failure-rate=1.0), após esgotar os retries a
 * orquestradora deve falhar rápido com 503 (anti-cascata), em vez de pendurar a chamada.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "security.hmac.enabled=false",
        "management.tracing.enabled=false",
        "external.api.simulated-failure-rate=1.0",
        "external.api.simulated-latency-ms=0"
})
class UnavailableDependencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void authorize_comDependenciaIndisponivel_retorna503() throws Exception {
        String body = "{ \"nsu\": \"999\", \"amount\": 10.00, \"terminalId\": \"T-9\" }";
        mockMvc.perform(post("/v1/pos/transactions/authorize")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable());
    }
}
