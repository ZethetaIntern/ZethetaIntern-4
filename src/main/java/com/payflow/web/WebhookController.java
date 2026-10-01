package com.payflow.web;

import com.payflow.service.WebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Webhook ingestion endpoints for all 4 gateways. */
@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/{gateway}")
    public ResponseEntity<?> ingest(@PathVariable String gateway,
                                    @RequestHeader(value = "X-Payflow-Signature", required = false) String signature,
                                    @RequestBody byte[] body) {
        WebhookService.Result r = webhooks.ingest(gateway, signature, body);
        if (!r.accepted()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(r);
        }
        return ResponseEntity.ok(r);
    }
}
