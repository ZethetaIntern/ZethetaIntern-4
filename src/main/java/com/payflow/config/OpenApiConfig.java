package com.payflow.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.0 description (A7, Day 11-12). Every operation documents the A7.2
 * error body for its error codes; the exported spec lives in
 * {@code docs/api-specification.yaml}.
 */
@Configuration
public class OpenApiConfig {

    private static final String ERROR_REF = "#/components/schemas/Error";

    @Bean
    public OpenAPI payflowOpenApi() {
        ObjectSchema errorBody = new ObjectSchema();
        errorBody.addProperty("code", new StringSchema().example("PAYMENT_AUTH_FAILED"));
        errorBody.addProperty("message", new StringSchema().example("Payment authorisation was declined by the issuing bank."));
        errorBody.addProperty("details", new ObjectSchema().example(Map.of("gateway", "razorpay",
                "gateway_error_code", "BAD_REQUEST_ERROR",
                "gateway_error_description", "The card issuer has declined this transaction.",
                "suggestion", "Please try a different payment method or contact your bank.")));
        errorBody.addProperty("request_id", new StringSchema().example("req_a1b2c3d4e5f6"));
        errorBody.addProperty("trace_id", new StringSchema().example("a1b2c3d4-e5f6-7890-abcd-ef1234567890"));
        errorBody.addProperty("timestamp", new StringSchema().example("2025-03-19T10:30:00.000Z"));
        Schema<?> error = new ObjectSchema().addProperty("error", errorBody).description("A7.2 standard error format");

        return new OpenAPI()
                .info(new Info().title("PayFlow Payment Orchestration API").version("1.0.0")
                        .description("Multi-gateway payment orchestration (Razorpay, Stripe, PayU, UPI) with "
                                + "intelligent routing, 2-second failover, idempotency, webhook reconciliation "
                                + "and a full audit trail. Amounts are integer paise."))
                .components(new Components()
                        .addSchemas("Error", error)
                        .addSecuritySchemes("apiKey", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-API-Key")))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }

    /** Adds the standard error responses (with A7.2 examples) to every operation. */
    @Bean
    public OpenApiCustomizer standardErrorResponses() {
        Map<String, String[]> errors = Map.of(
                "400", new String[]{"Validation failed", "VALIDATION_FAILED", "request validation failed"},
                "401", new String[]{"Missing/invalid API key or webhook signature", "UNAUTHORIZED",
                        "A valid X-API-Key header is required."},
                "404", new String[]{"Resource not found", "NOT_FOUND", "payment 9f1c... not found"},
                "409", new String[]{"Idempotency conflict or invalid state transition", "INVALID_STATE_TRANSITION",
                        "Invalid state transition CREATED -> REFUNDED ... Valid transitions from CREATED: "
                                + "[ABANDONED, FAILED, ROUTE_SELECTED]"},
                "422", new String[]{"Business rule violated", "INVALID_CAPTURE_AMOUNT",
                        "capture amount must be between 1 and the remaining hold"},
                "503", new String[]{"Service temporarily unavailable", "SERVICE_UNAVAILABLE",
                        "The service is temporarily overloaded. Please retry shortly."});
        Schema<?> errorSchema = payflowOpenApi().getComponents().getSchemas().get("Error");
        return api -> {
            if (api.getComponents() == null) api.setComponents(new Components());
            api.getComponents().addSchemas("Error", errorSchema);
            api.getPaths().values().forEach(path -> path.readOperations().forEach(op -> errors.forEach(
                (status, d) -> {
                    if (op.getResponses() != null && op.getResponses().containsKey(status)) return;
                    Example ex = new Example().value(Map.of("error", Map.of("code", d[1], "message", d[2],
                            "details", Map.of(), "request_id", "req_a1b2c3d4e5f6",
                            "timestamp", "2025-03-19T10:30:00.000Z")));
                    op.getResponses().addApiResponse(status, new ApiResponse().description(d[0])
                            .content(new Content().addMediaType("application/json", new MediaType()
                                    .schema(new Schema<>().$ref(ERROR_REF)).addExamples(d[1], ex))));
                })));
        };
    }
}
