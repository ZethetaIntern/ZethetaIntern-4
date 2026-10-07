package com.payflow.webhook;

import com.payflow.config.PayFlowProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Webhook signature verification for all four gateways (spec A5.3, A1.3):
 *
 * <table>
 *   <tr><th>Gateway</th><th>Header</th><th>Scheme</th></tr>
 *   <tr><td>Razorpay</td><td>X-Razorpay-Signature</td><td>hex HMAC-SHA256(secret, raw body)</td></tr>
 *   <tr><td>Stripe</td><td>Stripe-Signature: t=..,v1=..</td><td>hex HMAC-SHA256(secret, t + "." + raw body),
 *       timestamp within tolerance (replay protection)</td></tr>
 *   <tr><td>PayU</td><td>X-PayU-Signature</td><td>hex HMAC-SHA512(salt, raw body)</td></tr>
 *   <tr><td>UPI (NPCI)</td><td>X-NPCI-Signature</td><td>base64 SHA256withRSA over raw body, NPCI certificate</td></tr>
 * </table>
 *
 * <p>Signatures are always computed over the <em>raw request bytes</em>, never a
 * re-serialised JSON object, and compared in constant time
 * ({@link MessageDigest#isEqual}).</p>
 */
@Component
public class WebhookSignatureVerifier {

    public static final String RAZORPAY_HEADER = "X-Razorpay-Signature";
    public static final String STRIPE_HEADER = "Stripe-Signature";
    public static final String PAYU_HEADER = "X-PayU-Signature";
    public static final String UPI_HEADER = "X-NPCI-Signature";

    /** Result of verifying one delivery. */
    public record Verification(boolean valid, String reason) {
        static Verification ok() {
            return new Verification(true, null);
        }

        static Verification fail(String reason) {
            return new Verification(false, reason);
        }
    }

    private final PayFlowProperties.Webhooks cfg;
    private final PublicKey upiPublicKey;
    private final PrivateKey upiMockPrivateKey;

    public WebhookSignatureVerifier(PayFlowProperties props) {
        this.cfg = props.webhooks();
        this.upiPublicKey = loadPublicKey(cfg.upiPublicKeyPem());
        this.upiMockPrivateKey = loadPrivateKey(cfg.upiPrivateKeyPem());
    }

    /** @param headers request headers with lower-case names */
    public Verification verify(String gateway, Map<String, String> headers, byte[] rawBody) {
        try {
            return switch (gateway) {
                case "razorpay" -> hmacMatches("HmacSHA256", cfg.razorpaySecret(), rawBody,
                        headers.get(RAZORPAY_HEADER.toLowerCase(Locale.ROOT)));
                case "stripe" -> verifyStripe(headers.get(STRIPE_HEADER.toLowerCase(Locale.ROOT)), rawBody);
                case "payu" -> hmacMatches("HmacSHA512", cfg.payuSecret(), rawBody,
                        headers.get(PAYU_HEADER.toLowerCase(Locale.ROOT)));
                case "upi" -> verifyUpi(headers.get(UPI_HEADER.toLowerCase(Locale.ROOT)), rawBody);
                default -> Verification.fail("unknown_gateway");
            };
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Verification.fail("signature_verification_error");
        }
    }

    private Verification hmacMatches(String algorithm, String secret, byte[] body, String header)
            throws GeneralSecurityException {
        if (header == null || header.isBlank()) return Verification.fail("missing_signature");
        String expected = hmacHex(algorithm, secret, body);
        return constantTimeEquals(expected, header.trim().toLowerCase(Locale.ROOT))
                ? Verification.ok() : Verification.fail("signature_mismatch");
    }

    private Verification verifyStripe(String header, byte[] body) throws GeneralSecurityException {
        if (header == null || header.isBlank()) return Verification.fail("missing_signature");
        String timestamp = null;
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if (kv[0].equals("t")) timestamp = kv[1];
            if (kv[0].equals("v1")) candidates.add(kv[1].toLowerCase(Locale.ROOT));
        }
        if (timestamp == null || candidates.isEmpty()) return Verification.fail("malformed_signature_header");
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return Verification.fail("malformed_signature_header");
        }
        byte[] prefix = (timestamp + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        String expected = hmacHex("HmacSHA256", cfg.stripeSecret(), signed);
        boolean match = false;
        for (String c : candidates) match |= constantTimeEquals(expected, c);
        if (!match) return Verification.fail("signature_mismatch");
        if (Math.abs(Instant.now().getEpochSecond() - ts) > cfg.stripeToleranceSec()) {
            return Verification.fail("timestamp_outside_tolerance");
        }
        return Verification.ok();
    }

    private Verification verifyUpi(String header, byte[] body) throws GeneralSecurityException {
        if (header == null || header.isBlank()) return Verification.fail("missing_signature");
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(upiPublicKey);
        sig.update(body);
        return sig.verify(Base64.getDecoder().decode(header.trim()))
                ? Verification.ok() : Verification.fail("signature_mismatch");
    }

    /**
     * Produces the headers a gateway would send for {@code body}. Used by the mock
     * gateways (which deliver webhooks to this service) and by tests; the UPI
     * signature uses the simulator's stand-in for the NPCI private key.
     */
    public Map<String, String> sign(String gateway, byte[] body) {
        try {
            Map<String, String> h = new LinkedHashMap<>();
            switch (gateway) {
                case "razorpay" -> h.put(RAZORPAY_HEADER, hmacHex("HmacSHA256", cfg.razorpaySecret(), body));
                case "stripe" -> {
                    long t = Instant.now().getEpochSecond();
                    byte[] prefix = (t + ".").getBytes(StandardCharsets.UTF_8);
                    byte[] signed = new byte[prefix.length + body.length];
                    System.arraycopy(prefix, 0, signed, 0, prefix.length);
                    System.arraycopy(body, 0, signed, prefix.length, body.length);
                    h.put(STRIPE_HEADER, "t=" + t + ",v1=" + hmacHex("HmacSHA256", cfg.stripeSecret(), signed));
                }
                case "payu" -> h.put(PAYU_HEADER, hmacHex("HmacSHA512", cfg.payuSecret(), body));
                case "upi" -> {
                    Signature sig = Signature.getInstance("SHA256withRSA");
                    sig.initSign(upiMockPrivateKey);
                    sig.update(body);
                    h.put(UPI_HEADER, Base64.getEncoder().encodeToString(sig.sign()));
                }
                default -> throw new IllegalArgumentException("unknown gateway: " + gateway);
            }
            return h;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot sign webhook", e);
        }
    }

    static String hmacHex(String algorithm, String secret, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
        return HexFormat.of().formatHex(mac.doFinal(data));
    }

    /** Constant-time comparison: never {@code String.equals} on secrets (A5.3). */
    static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static PublicKey loadPublicKey(String pem) {
        try {
            byte[] der = pemBody(pem == null ? classpath("keys/npci-mock-public.pem") : pem);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("cannot load NPCI public key", e);
        }
    }

    private static PrivateKey loadPrivateKey(String pem) {
        try {
            byte[] der = pemBody(pem == null ? classpath("keys/npci-mock-private.pem") : pem);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("cannot load mock NPCI private key", e);
        }
    }

    private static String classpath(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    private static byte[] pemBody(String pem) {
        String b64 = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(b64);
    }
}
