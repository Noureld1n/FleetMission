package com.fleetcontrol.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** API-key wire format is {@code prefix.secret}; only the SHA-256 secret hash is stored. */
public record ApiKeyCredential(String prefix, String secret) {
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_-]{4,64}");

    public static ApiKeyCredential parse(String presentedKey) {
        if (presentedKey == null) {
            return null;
        }
        int separator = presentedKey.indexOf('.');
        if (separator < 0 || separator == presentedKey.length() - 1) {
            return null;
        }
        String prefix = presentedKey.substring(0, separator);
        String secret = presentedKey.substring(separator + 1);
        if (!PREFIX.matcher(prefix).matches() || secret.isBlank()) {
            return null;
        }
        return new ApiKeyCredential(prefix, secret);
    }

    public boolean matchesHash(String storedHash) {
        if (storedHash == null) {
            return false;
        }
        String actualHash = sha256Hex(secret);
        byte[] actual = actualHash.getBytes(StandardCharsets.US_ASCII);
        byte[] expected = storedHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actual, expected);
    }

    public static String sha256Hex(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
