package com.winlator.star.store;

import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Generates PKCE (Proof Key for Code Exchange) values required by the
 * Amazon Games OAuth2 device-registration flow.
 *
 * DEVICE_TYPE = "A2UMVHOX7UP4V7"  (Amazon Gaming Launcher device type)
 */
public class AmazonPKCEGenerator {

    private static final String DEVICE_TYPE = "A2UMVHOX7UP4V7";

    /** One-time per install UUID (hex, no dashes, uppercase). */
    public static String generateDeviceSerial() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    /**
     * clientId = hex-encode UTF-8 bytes of "serial#DEVICE_TYPE"
     * e.g. "serial#A2UMVHOX7UP4V7" → lower-hex string
     */
    public static String generateClientId(String serial) {
        byte[] bytes = (serial + "#" + DEVICE_TYPE).getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /** 32 SecureRandom bytes encoded as Base64 URL_SAFE | NO_PADDING | NO_WRAP */
    public static String generateCodeVerifier() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    /** SHA-256(verifier bytes) encoded as Base64 URL_SAFE | NO_PADDING | NO_WRAP (S256) */
    public static String generateCodeChallenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    /**
     * SHA-256 of input string, returned as UPPERCASE hex.
     * Used for the GetEntitlements hardwareHash field.
     */
    public static String sha256Upper(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString().toUpperCase();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
