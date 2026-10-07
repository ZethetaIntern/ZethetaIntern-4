package com.payflow.web;

import com.payflow.tracing.TraceFilter;
import com.payflow.webhook.WebhookIngestionService;
import com.payflow.webhook.WebhookSignatureVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gateway webhook receivers (A7.1 #9-#12). The body is read as raw bytes because
 * signatures are computed over the exact bytes received. Responses: 200 for
 * processed, queued and duplicate events; 401 for a bad signature; 400 for a
 * malformed body; 422 when a correctly signed event fails the C4.3 checks.
 */
@RestController
@RequestMapping(value = "/api/v1/webhooks", consumes = MediaType.ALL_VALUE)
@Tag(name = "Webhooks", description = "Gateway callbacks; authenticated by signature, not API key")
public class WebhookController {

    private final WebhookIngestionService ingestion;

    public WebhookController(WebhookIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping("/razorpay")
    @Operation(summary = "Razorpay webhook receiver (#9)", description = "X-Razorpay-Signature: hex HMAC-SHA256 of the raw body")
    public ResponseEntity<Map<String, Object>> razorpay(
            @Parameter(description = "hex HMAC-SHA256") @RequestHeader(value = WebhookSignatureVerifier.RAZORPAY_HEADER,
                    required = false) String signature,
            @RequestBody byte[] body, HttpServletRequest request) {
        return handle("razorpay", body, request);
    }

    @PostMapping("/stripe")
    @Operation(summary = "Stripe webhook receiver (#10)",
            description = "Stripe-Signature: t=<unix>,v1=<hex HMAC-SHA256 of t.body>")
    public ResponseEntity<Map<String, Object>> stripe(
            @RequestHeader(value = WebhookSignatureVerifier.STRIPE_HEADER, required = false) String signature,
            @RequestBody byte[] body, HttpServletRequest request) {
        return handle("stripe", body, request);
    }

    @PostMapping("/payu")
    @Operation(summary = "PayU webhook receiver (#11)", description = "X-PayU-Signature: hex HMAC-SHA512 of the raw body")
    public ResponseEntity<Map<String, Object>> payu(
            @RequestHeader(value = WebhookSignatureVerifier.PAYU_HEADER, required = false) String signature,
            @RequestBody byte[] body, HttpServletRequest request) {
        return handle("payu", body, request);
    }

    @PostMapping("/upi")
    @Operation(summary = "UPI (NPCI) callback receiver (#12)",
            description = "X-NPCI-Signature: base64 SHA256withRSA of the raw body")
    public ResponseEntity<Map<String, Object>> upi(
            @RequestHeader(value = WebhookSignatureVerifier.UPI_HEADER, required = false) String signature,
            @RequestBody byte[] body, HttpServletRequest request) {
        return handle("upi", body, request);
    }

    private ResponseEntity<Map<String, Object>> handle(String gateway, byte[] body, HttpServletRequest request) {
        WebhookIngestionService.IngestResult r = ingestion.ingest(gateway, lowerCaseHeaders(request), body,
                TraceFilter.clientIp(request), request.getHeader("User-Agent"), request.getRequestURI());
        return ResponseEntity.status(r.status()).body(r.body());
    }

    static Map<String, String> lowerCaseHeaders(HttpServletRequest request) {
        Map<String, String> h = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String n = names.nextElement();
            h.put(n.toLowerCase(Locale.ROOT), request.getHeader(n));
        }
        return h;
    }
}
