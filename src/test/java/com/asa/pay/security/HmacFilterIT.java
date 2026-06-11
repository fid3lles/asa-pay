package com.asa.pay.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
 * Verifica a autenticação HMAC com a verificação LIGADA e um segredo conhecido.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "security.hmac.enabled=true",
        "security.hmac.secret=integration-test-secret",
        "security.hmac.timestamp-tolerance-seconds=300",
        "management.tracing.enabled=false"
})
class HmacFilterIT {

    private static final String SECRET = "integration-test-secret";
    private static final String PATH = "/v1/pos/transactions/authorize";
    private static final String BODY = "{ \"nsu\": \"123456\", \"amount\": 199.90, \"terminalId\": \"T-1000\" }";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void semHeaders_retorna401() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void assinaturaInvalida_retorna401() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header(HmacAuthenticationFilter.TIMESTAMP_HEADER, ts)
                        .header(HmacAuthenticationFilter.SIGNATURE_HEADER, "deadbeef"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void timestampForaDaJanela_retorna401() throws Exception {
        String staleTs = String.valueOf(Instant.now().getEpochSecond() - 3600);
        String signature = sign(staleTs, BODY);
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header(HmacAuthenticationFilter.TIMESTAMP_HEADER, staleTs)
                        .header(HmacAuthenticationFilter.SIGNATURE_HEADER, signature))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void assinaturaValida_passaERetorna200() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String signature = sign(ts, BODY);
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header(HmacAuthenticationFilter.TIMESTAMP_HEADER, ts)
                        .header(HmacAuthenticationFilter.SIGNATURE_HEADER, signature))
                .andExpect(status().isOk());
    }

    private String sign(String timestamp, String body) {
        String canonical = HmacSigner.canonicalString(timestamp, "POST", PATH, body);
        return HmacSigner.hexSignature(SECRET, canonical);
    }
}
