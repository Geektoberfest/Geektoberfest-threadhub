package com.threadhub.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes token values for storage. Refresh tokens are opaque random strings whose value is never
 * persisted: only this digest is written to the database, so a database leak does not expose usable
 * credentials.
 */
public final class TokenHashes {

    private static final String ALGORITHM = "SHA-256";

    private TokenHashes() {
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance(ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java platform, so this cannot happen on a supported JVM.
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        }
    }
}
