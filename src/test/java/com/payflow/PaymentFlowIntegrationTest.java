package com.payflow;

import com.payflow.domain.TransactionState;
import com.payflow.service.PaymentService;
import com.payflow.service.StateService;
import com.payflow.service.WebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired PaymentService payments;
    @Autowired WebhookService webhooks;
    @Autowired StateService states;

    private String createAndProcess(String idemKey) throws Exception {
        var mvcResult = mvc.perform(post("/payments")
                        .header("X-API-Key", "pk_test_payflow")
                        .header("Idempotency-Key", idemKey)
                        .contentType("application/json")
                        .content("{\"merchantOrderId\":\"ORD-" + idemKey + "\",\"amount\":250000,"
                                + "\"currency\":\"INR\",\"paymentMethod\":\"card\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var body = mvcResult.getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.transaction.id");
    }

    @Test
    void createAndProcessReachesTerminalState() throws Exception {
        String id = createAndProcess("idem-ok-1");
        var view = payments.view(id);
        assertTrue(view.txn().getState() == TransactionState.CAPTURED
                        || view.txn().getState() == TransactionState.PARTIALLY_CAPTURED
                        || view.txn().getState() == TransactionState.FAILED_TERMINAL,
                "unexpected state " + view.txn().getState());
        assertFalse(view.audit().isEmpty(), "audit trail must exist");
        assertFalse(view.attempts().isEmpty());
    }

    @Test
    void idempotentReplayReturnsOriginal() throws Exception {
        String first = createAndProcess("idem-replay-1");
        mvc.perform(post("/payments")
                        .header("X-API-Key", "pk_test_payflow")
                        .header("Idempotency-Key", "idem-replay-1")
                        .contentType("application/json")
                        .content("{\"merchantOrderId\":\"ORD-X\",\"amount\":250000,"
                                + "\"currency\":\"INR\",\"paymentMethod\":\"card\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.transaction_id").value(first));
    }

    @Test
    void missingIdempotencyKeyRejected() throws Exception {
        mvc.perform(post("/payments")
                        .header("X-API-Key", "pk_test_payflow")
                        .contentType("application/json")
                        .content("{\"merchantOrderId\":\"O\",\"amount\":100,"
                                + "\"currency\":\"INR\",\"paymentMethod\":\"card\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingApiKeyRejected() throws Exception {
        mvc.perform(post("/payments")
                        .header("Idempotency-Key", "x")
                        .contentType("application/json")
                        .content("{\"merchantOrderId\":\"O\",\"amount\":100,"
                                + "\"currency\":\"INR\",\"paymentMethod\":\"card\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void failoverAttemptsRecorded() throws Exception {
        String id = createAndProcess("idem-failover-1");
        var view = payments.view(id);
        assertTrue(view.attempts().size() <= 3);
        assertTrue(view.attempts().stream().allMatch(a -> a.getAttemptNo() >= 1));
    }

    @Test
    void auditTrailIsComplete() throws Exception {
        String id = createAndProcess("idem-audit-1");
        var view = payments.view(id);
        assertTrue(view.audit().size() >= 3);
        assertTrue(view.audit().stream().anyMatch(a -> a.getToState() == TransactionState.CREATED));
    }
}
