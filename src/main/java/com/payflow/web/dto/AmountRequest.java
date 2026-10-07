package com.payflow.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Optional body for capture and refund: omit the amount to capture/refund everything remaining. */
@Schema(example = "{\"amount_paise\":80000}")
public record AmountRequest(
        @Positive @JsonAlias({"amount", "amountPaise"})
        @Schema(description = "Amount in paise; omit for the full remaining amount", example = "80000")
        Long amountPaise,

        @Size(max = 255)
        @Schema(description = "Refund reason (refunds only)", example = "customer returned item")
        String reason) {}
