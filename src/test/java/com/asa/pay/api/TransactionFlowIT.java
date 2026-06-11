package com.asa.pay.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.asa.pay.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Fluxo ponta-a-ponta dos três endpoints, idempotência e máquina de estados,
 * batendo contra um Postgres real.
 */
class TransactionFlowIT extends AbstractIntegrationTest {

    private static final String AUTHORIZE = "/v1/pos/transactions/authorize";
    private static final String CONFIRM = "/v1/pos/transactions/confirm";
    private static final String VOID = "/v1/pos/transactions/void";

    private static final String AUTH_BODY = """
            { "nsu": "123456", "amount": 199.90, "terminalId": "T-1000" }
            """;

    @Test
    void authorize_retorna200ComTransactionId() throws Exception {
        mockMvc.perform(post(AUTHORIZE).contentType(MediaType.APPLICATION_JSON).content(AUTH_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andExpect(jsonPath("$.nsu").value("123456"))
                .andExpect(jsonPath("$.terminalId").value("T-1000"));
    }

    @Test
    void authorize_eIdempotentePorTerminalEnsu() throws Exception {
        String first = authorizeAndGetTransactionId();
        String second = authorizeAndGetTransactionId();

        assertThat(first).isEqualTo(second);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void confirm_retorna204_eRepeticaoEhNoOp() throws Exception {
        String txId = authorizeAndGetTransactionId();
        String confirmBody = "{ \"transactionId\": \"" + txId + "\" }";

        mockMvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isNoContent());
    }

    @Test
    void void_porTransactionId_retorna204_eRepeticaoEhNoOp() throws Exception {
        String txId = authorizeAndGetTransactionId();
        String voidBody = "{ \"transactionId\": \"" + txId + "\" }";

        mockMvc.perform(post(VOID).contentType(MediaType.APPLICATION_JSON).content(voidBody))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(VOID).contentType(MediaType.APPLICATION_JSON).content(voidBody))
                .andExpect(status().isNoContent());
    }

    @Test
    void void_porNsuETerminal_retorna204() throws Exception {
        authorizeAndGetTransactionId();
        String voidBody = "{ \"nsu\": \"123456\", \"terminalId\": \"T-1000\" }";

        mockMvc.perform(post(VOID).contentType(MediaType.APPLICATION_JSON).content(voidBody))
                .andExpect(status().isNoContent());
    }

    @Test
    void confirm_depoisDeVoid_retorna409() throws Exception {
        String txId = authorizeAndGetTransactionId();
        String byId = "{ \"transactionId\": \"" + txId + "\" }";

        mockMvc.perform(post(VOID).contentType(MediaType.APPLICATION_JSON).content(byId))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON).content(byId))
                .andExpect(status().isConflict());
    }

    @Test
    void authorize_comAmountInvalido_retorna400() throws Exception {
        String invalid = "{ \"nsu\": \"123456\", \"amount\": -1, \"terminalId\": \"T-1000\" }";

        mockMvc.perform(post(AUTHORIZE).contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
    }

    private String authorizeAndGetTransactionId() throws Exception {
        String json = mockMvc.perform(post(AUTHORIZE)
                        .contentType(MediaType.APPLICATION_JSON).content(AUTH_BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("transactionId").asText();
    }
}
