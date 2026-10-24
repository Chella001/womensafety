package com.womensafety.notification;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Validates Twilio's {@code X-Twilio-Signature}: Base64(HMAC-SHA1(authToken, fullUrl + each POST parameter name and
 * value, sorted by name)). The URL is the one WE configured for the callback, never one rebuilt from request headers.
 * Anything that does not verify is treated as forged.
 */
public final class TwilioSignature {

    private TwilioSignature() {
    }

    public static String sign(String authToken, String url, Map<String, String> params) {
        StringBuilder data = new StringBuilder(url);
        new TreeMap<>(params).forEach((name, value) -> data.append(name).append(value));
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 is unavailable", e);
        }
    }

    public static boolean isValid(String authToken, String url, Map<String, String> params, String signature) {
        if (authToken == null || authToken.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        byte[] expected = sign(authToken, url, params).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
    }
}
