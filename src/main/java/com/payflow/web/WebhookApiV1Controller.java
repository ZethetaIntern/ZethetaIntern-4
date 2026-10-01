package com.payflow.web;

import com.payflow.service.WebhookService;
import com.payflow.service.WebhookService.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Gateway webhook receivers. Spec A7.1 requires a dedicated endpoint per
 * gateway (items 9-12); the handler is shared because signature verification
 * and reconciliation are identical apart from the HMAC algorithm.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookApiV1Controller {

    private final WebhookService webhooks;

    public WebhookApiV1Controller(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/razorpay")
    public ResponseEntity<?> razorpay(@RequestHeader(value = "X-Payflow-Signature", required = false) String sig,
                                      @RequestBody byte[] body) {
        return handle("razorpay", sig, body);
    }

    @PostMapping("/stripe")
    public ResponseEntity<?> stripe(@RequestHeader(value = "X-Payflow-Signature", required = false) String sig,
                                    @RequestBody byte[] body) {
        return handle("stripe", sig, body);
    }

    @PostMapping("/payu")
    public ResponseEntity<?> payu(@RequestHeader(value = "X-Payflow-Signature", required = false) String sig,
                                  @RequestBody byte[] body) {
        return handle("payu", sig, body);
    }

    @PostMapping("/upi")
    public ResponseEntity<?> upi(@RequestHeader(value = "X-Payflow-Signature", required = false) String sig,
                                 @RequestBody byte[] body) {
        return handle("upi", sig, body);
    }

    private ResponseEntity<?> handle(String gateway, String signature, byte[] body) {
        Result r = webhooks.ingest(gateway, signature, body);
        if (!r.accepted()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(r);
        }
        return ResponseEntity.ok(r);
    }
}
