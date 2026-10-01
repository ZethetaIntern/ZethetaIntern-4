package com.payflow.gateway;

/** Gateway adapter contract. Two-phase: authorize then capture. */
public interface GatewayClient {
    AuthResult authorize(String gateway, long amountPaise, String reference) throws GatewayException;
    CaptureResult capture(String gateway, String reference, long amountPaise) throws GatewayException;
    boolean refund(String gateway, String reference, long amountPaise);

    /** Release an uncaptured authorisation hold (A7.1 #5). */
    default void voidAuthorisation(String gateway, String reference) { }

    record AuthResult(boolean ok, String reference, String errorCode, long latencyMs) {}

    record CaptureResult(boolean ok, long capturedPaise, String errorCode, long latencyMs) {}

    class GatewayException extends Exception {
        public final String gateway;
        public final String code;
        public GatewayException(String gateway, String code, String message) {
            super(message);
            this.gateway = gateway;
            this.code = code;
        }
    }

    class GatewayTimeout extends GatewayException {
        public GatewayTimeout(String gateway) {
            super(gateway, "TIMEOUT", "gateway did not respond within budget: " + gateway);
        }
    }
}
