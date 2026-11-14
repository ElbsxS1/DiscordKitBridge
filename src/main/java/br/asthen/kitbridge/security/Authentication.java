package br.asthen.kitbridge.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Bearer-token authentication using a constant-time comparison of SHA-256 digests. */
public final class Authentication {

    public static final int MIN_LENGTH = 24;

    private final byte[] expected;

    public Authentication(String secret) {
        if (isWeak(secret)) throw new IllegalArgumentException("Secret is missing or too weak");
        this.expected = sha256(secret);
    }

    /** @param authorizationHeader raw value of the Authorization header, e.g. "Bearer abc..." */
    public boolean verify(String authorizationHeader) {
        if (authorizationHeader == null) return false;
        String h = authorizationHeader.strip();
        if (h.length() < 8 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        String token = h.substring(7).strip();
        return MessageDigest.isEqual(sha256(token), expected);
    }

    public static boolean isWeak(String secret) {
        return secret == null || secret.isBlank() || secret.equals("CHANGE_ME") || secret.length() < MIN_LENGTH;
    }

    /** 48 URL-safe characters from a CSPRNG. */
    public static String generateToken() {
        byte[] b = new byte[36];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
