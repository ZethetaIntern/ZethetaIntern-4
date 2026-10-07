package com.payflow.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.payflow.entity.IdempotencyKey;
import com.payflow.repository.IdempotencyKeyRepository;
import com.payflow.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A4 idempotency: replay, conflicts, payload mismatch, retry after failure, expiry and cleanup. */
class IdempotencyIntegrationTest extends IntegrationTest {

    @Autowired IdempotencyService idempotency;
    @Autowired IdempotencyKeyRepository keys;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void completedRequestReplaysTheCachedResponseQuickly() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> req = paymentRequest(45_000, "CARD");
        MvcResult first = createPayment(key, req);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("false");

        long best = Long.MAX_VALUE;
        MvcResult replay = null;
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            replay = createPayment(key, req);
            best = Math.min(best, System.nanoTime() - start);
        }
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(replay)).isEqualTo(body(first));
        assertThat(best / 1_000_000).as("B3 idempotency check < 10 ms").isLessThan(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodyIsRejected() throws Exception {
        String key = UUID.randomUUID().toString();
        createPayment(key, paymentRequest(1_000, "CARD"));
        MvcResult r = createPayment(key, paymentRequest(2_000, "CARD"));
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(r).toString()).contains("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void missingIdempotencyKeyIsABadRequest() throws Exception {
        MvcResult r = perform(authed(post("/api/v1/payments")).content(json.writeValueAsString(paymentRequest(1_000, "CARD"))));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(r).toString()).contains("MISSING_HEADER");
    }

    @Test
    void declinedPaymentIsCachedSoARetryIsNotChargedAgain() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> req = paymentRequest(3_000, "UPI");
        MvcResult first = createPayment(key, req, "X-Mock-Response", "decline");
        assertThat(first.getResponse().getStatus()).isEqualTo(402);
        MvcResult retry = createPayment(key, req);
        assertThat(retry.getResponse().getStatus()).isEqualTo(402);
        assertThat(retry.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(ledger.chargesCreated("upi")).isZero();
    }

    @Test
    void failedKeyCanBeRetried() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        String key = UUID.randomUUID().toString();
        tx.execute(s -> idempotency.claim("default", key, "h", "/p"));
        idempotency.fail("default", key);
        IdempotencyService.Claim retry = tx.execute(s -> idempotency.claim("default", key, "h", "/p"));
        assertThat(retry.kind()).isEqualTo(IdempotencyService.Kind.RESUME);
        IdempotencyService.Claim concurrent = tx.execute(s -> idempotency.claim("default", key, "h", "/p"));
        assertThat(concurrent.kind()).isEqualTo(IdempotencyService.Kind.IN_PROGRESS);
        idempotency.complete("default", key, 201, Map.of("ok", true));
        IdempotencyService.Claim done = tx.execute(s -> idempotency.claim("default", key, "h", "/p"));
        assertThat(done.kind()).isEqualTo(IdempotencyService.Kind.REPLAY);
        assertThat(done.key().getResponseBody()).containsEntry("ok", true);
    }

    @Test
    void expiredKeysAreReusableAndPurged() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        String key = UUID.randomUUID().toString();
        tx.execute(s -> idempotency.claim("default", key, "h", "/p"));
        idempotency.complete("default", key, 200, Map.of());
        jdbc.update("UPDATE idempotency_keys SET expires_at = NOW() - INTERVAL '1 hour' WHERE key = ?", key);
        IdempotencyService.Claim reuse = tx.execute(s -> idempotency.claim("default", key, "different", "/p"));
        assertThat(reuse.kind()).isEqualTo(IdempotencyService.Kind.PROCEED_NEW);

        jdbc.update("UPDATE idempotency_keys SET expires_at = NOW() - INTERVAL '1 hour'");
        assertThat(idempotency.purgeExpired()).isEqualTo(1);
        assertThat(keys.count()).isZero();
    }

    @Test
    void keysArePrimaryKeyedByMerchantAndKey() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(s -> idempotency.claim("m1", "shared", "h", "/p"));
        tx.execute(s -> idempotency.claim("m2", "shared", "h", "/p"));
        assertThat(keys.findById(new IdempotencyKey.Pk("m1", "shared"))).isPresent();
        assertThat(keys.findById(new IdempotencyKey.Pk("m2", "shared"))).isPresent();
        assertThat(jdbc.queryForList("SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid "
                + "AND a.attnum = ANY(i.indkey) WHERE i.indrelid = 'idempotency_keys'::regclass AND i.indisprimary",
                String.class)).containsExactlyInAnyOrder("merchant_id", "key");
    }

    @Test
    void captureAndRefundAcceptIdempotencyKeys() throws Exception {
        Map<String, Object> req = paymentRequest(60_000, "CARD");
        req.put("capture_mode", "MANUAL");
        Map<String, Object> p = body(createPayment(UUID.randomUUID().toString(), req));
        String captureKey = UUID.randomUUID().toString();
        MvcResult c1 = postJson("/api/v1/payments/" + p.get("id") + "/capture", Map.of("amount_paise", 20_000),
                "Idempotency-Key", captureKey);
        MvcResult c2 = postJson("/api/v1/payments/" + p.get("id") + "/capture", Map.of("amount_paise", 20_000),
                "Idempotency-Key", captureKey);
        assertThat(body(c1)).containsEntry("captured_paise", 20_000);
        assertThat(c2.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(getPayment(p.get("id"))).containsEntry("captured_paise", 20_000);

        String refundKey = UUID.randomUUID().toString();
        postJson("/api/v1/payments/" + p.get("id") + "/refund", Map.of("amount_paise", 5_000), "Idempotency-Key", refundKey);
        postJson("/api/v1/payments/" + p.get("id") + "/refund", Map.of("amount_paise", 5_000), "Idempotency-Key", refundKey);
        assertThat(getPayment(p.get("id"))).containsEntry("refunded_paise", 5_000);
    }

    @Test
    void requestHashIsCanonical() {
        assertThat(IdempotencyService.requestHash(Map.of("a", 1, "b", "x")))
                .isEqualTo(IdempotencyService.requestHash(new java.util.TreeMap<>(Map.of("b", "x", "a", 1))))
                .hasSize(64);
        assertThat(IdempotencyService.sha256("abc")).hasSize(64);
    }
}
