package com.payflow.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/payments}. Amounts are integer paise (A6.2). */
@Schema(example = "{\"merchant_order_id\":\"ORD-10045\",\"amount_paise\":120000,\"currency\":\"INR\","
        + "\"payment_method\":\"CARD\",\"capture_mode\":\"AUTOMATIC\"}")
public record CreatePaymentRequest(
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9._:/-]+$",
                message = "may contain only letters, digits and . _ : / -")
        @JsonAlias("merchantOrderId")
        @Schema(description = "Merchant's order reference", example = "ORD-10045")
        String merchantOrderId,

        @NotNull @Positive @Max(value = 99_999_999_999L, message = "must not exceed 999,999,999.99 rupees")
        @JsonAlias({"amount", "amountPaise"})
        @Schema(description = "Amount in paise (₹1,200.00 = 120000)", example = "120000")
        Long amountPaise,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "must be an ISO-4217 code")
        @Schema(description = "ISO-4217 currency, default INR", example = "INR")
        String currency,

        @NotBlank @JsonAlias("paymentMethod")
        @Schema(description = "CARD, UPI, NETBANKING or WALLET", example = "CARD")
        String paymentMethod,

        @JsonAlias("captureMode")
        @Schema(description = "AUTOMATIC (default) captures right after authorisation; MANUAL waits for /capture",
                example = "AUTOMATIC")
        String captureMode,

        @JsonAlias("upiFlow")
        @Schema(description = "UPI only: INTENT (default, instant) or COLLECT (customer approves within 5 minutes)",
                example = "COLLECT")
        String upiFlow) {}
