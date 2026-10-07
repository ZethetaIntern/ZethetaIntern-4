package com.payflow.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.gateway.MockGatewayLedger;
import com.payflow.ratelimit.GatewayRateLimiter;
import com.payflow.routing.CircuitBreakerService;
import com.payflow.routing.GatewayConfigService;
import com.payflow.routing.GatewayMetricsService;
import com.payflow.routing.RoutingConfigService;
import com.payflow.webhook.WebhookSignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Base class for integration tests: full Spring context on embedded PostgreSQL,
 * MockMvc for HTTP calls, and a clean database before each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    public static final String API_KEY = "pk_test_payflow";
    protected static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    protected static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {};

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected DataSource dataSource;
    @Autowired protected MockGatewayLedger ledger;
    @Autowired protected GatewayMetricsService metrics;
    @Autowired protected GatewayRateLimiter rateLimiter;
    @Autowired protected CircuitBreakerService circuits;
    @Autowired protected GatewayConfigService gatewayConfigs;
    @Autowired protected RoutingConfigService routingConfig;
    @Autowired protected WebhookSignatureVerifier signer;

    private static final String DATA_TABLES = "transaction_state_log, gateway_routes, gateway_attempts, refunds, "
            + "anomalies, reconciliation_log, reconciliation_runs, notification_outbox, idempotency_keys, "
            + "processed_webhook_events, webhook_queue, security_audit_log, gateway_health_metrics, "
            + "circuit_breaker_state, transactions";

    @BeforeEach
    void cleanState() {
        jdbc.execute("TRUNCATE " + DATA_TABLES + ", gateway_config, routing_config, circuit_breaker_config, "
                + "gateway_historical_performance RESTART IDENTITY CASCADE");
        try (var c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("db/migration/V2__seed_reference_data.sql"));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        gatewayConfigs.invalidate();
        routingConfig.invalidate();
        circuits.invalidateConfig();
        ledger.reset();
        metrics.reset();
        rateLimiter.reset();
    }

    // ---------------------------------------------------------------- HTTP helpers

    protected MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header("X-API-Key", API_KEY).contentType(MediaType.APPLICATION_JSON);
    }

    protected MvcResult perform(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn();
    }

    protected Map<String, Object> body(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isBlank() ? Map.of() : json.readValue(s, MAP);
    }

    protected List<Map<String, Object>> list(MvcResult r) throws Exception {
        return json.readValue(r.getResponse().getContentAsString(StandardCharsets.UTF_8), LIST);
    }

    /** POST /api/v1/payments with optional mock headers ("X-Mock-Response", "razorpay=timeout", ...). */
    protected MvcResult createPayment(String idempotencyKey, Map<String, Object> request, String... mockHeaders)
            throws Exception {
        MockHttpServletRequestBuilder b = authed(post("/api/v1/payments"))
                .header("Idempotency-Key", idempotencyKey)
                .content(json.writeValueAsString(request));
        for (int i = 0; i + 1 < mockHeaders.length; i += 2) b.header(mockHeaders[i], mockHeaders[i + 1]);
        return perform(b);
    }

    protected static Map<String, Object> paymentRequest(long amountPaise, String method) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("merchant_order_id", "ORD-" + UUID.randomUUID().toString().substring(0, 8));
        m.put("amount_paise", amountPaise);
        m.put("currency", "INR");
        m.put("payment_method", method);
        return m;
    }

    protected Map<String, Object> createOk(long amountPaise, String method, String... mockHeaders) throws Exception {
        MvcResult r = createPayment(UUID.randomUUID().toString(), paymentRequest(amountPaise, method), mockHeaders);
        if (r.getResponse().getStatus() >= 300) {
            throw new AssertionError("payment failed: " + r.getResponse().getStatus() + " "
                    + r.getResponse().getContentAsString());
        }
        return body(r);
    }

    protected Map<String, Object> getPayment(Object id) throws Exception {
        return body(perform(authed(get("/api/v1/payments/" + id))));
    }

    protected List<Map<String, Object>> timeline(Object id) throws Exception {
        return list(perform(authed(get("/api/v1/payments/" + id + "/timeline"))));
    }

    protected MvcResult postJson(String path, Object body, String... headers) throws Exception {
        MockHttpServletRequestBuilder b = authed(post(path));
        if (body != null) b.content(json.writeValueAsString(body));
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return perform(b);
    }

    protected MvcResult putJson(String path, Object body) throws Exception {
        return perform(authed(put(path)).content(json.writeValueAsString(body)));
    }

    protected MvcResult getJson(String path) throws Exception {
        return perform(authed(get(path)));
    }

    /** Delivers a correctly signed webhook for {@code gateway}. */
    protected MvcResult webhook(String gateway, String rawBody, String... extraHeaders) throws Exception {
        byte[] bytes = rawBody.getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequestBuilder b = post("/api/v1/webhooks/" + gateway)
                .contentType(MediaType.APPLICATION_JSON).content(bytes);
        signer.sign(gateway, bytes).forEach(b::header);
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) b.header(extraHeaders[i], extraHeaders[i + 1]);
        return perform(b);
    }

    protected String genericEvent(String eventId, String status, Object transactionId, String reference,
                                  Long amountPaise) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event_id", eventId);
        m.put("status", status);
        if (transactionId != null) m.put("transaction_id", transactionId.toString());
        if (reference != null) m.put("gateway_reference", reference);
        if (amountPaise != null) m.put("amount", amountPaise);
        m.put("currency", "INR");
        return json.writeValueAsString(m);
    }

    protected List<String> states(Object transactionId) {
        return jdbc.queryForList("SELECT to_state FROM transaction_state_log WHERE transaction_id = ?::uuid "
                + "AND (from_state IS NULL OR from_state <> to_state) AND event <> 'REJECTED_TRANSITION' "
                + "ORDER BY created_at, id", String.class, transactionId.toString());
    }

    protected String state(Object transactionId) {
        return jdbc.queryForObject("SELECT state FROM transactions WHERE id = ?::uuid", String.class,
                transactionId.toString());
    }

    /** Which gateway the router ranks first for this method right now. */
    protected String topGateway(String method, long amount) throws Exception {
        Map<String, Object> d = body(getJson("/api/v1/routing/preview?payment_method=" + method + "&amount_paise=" + amount));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranked = (List<Map<String, Object>>) d.get("ranked");
        return (String) ranked.get(0).get("gateway");
    }

    @SuppressWarnings("unchecked")
    protected List<String> rankedGateways(String method, long amount) throws Exception {
        Map<String, Object> d = body(getJson("/api/v1/routing/preview?payment_method=" + method + "&amount_paise=" + amount));
        return ((List<Map<String, Object>>) d.get("ranked")).stream().map(m -> (String) m.get("gateway")).toList();
    }
}
