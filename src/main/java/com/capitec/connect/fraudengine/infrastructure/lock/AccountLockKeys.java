package com.capitec.connect.fraudengine.infrastructure.lock;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Derives the PostgreSQL advisory-lock key for an account.
 *
 * <p>The key must be stable across processes and restarts, so it is derived from the account id
 * with SHA-256 rather than from {@code hashCode()}. The namespace constant keeps these locks in
 * their own key space so they cannot collide with advisory locks taken elsewhere in the database.
 */
public final class AccountLockKeys {

    /** Arbitrary but fixed namespace, so account locks never collide with other advisory locks. */
    private static final long NAMESPACE = 0x5EED_AC00_0000_0001L;

    private AccountLockKeys() {
    }

    public static long forAccount(String accountId) {
        byte[] digest = sha256(accountId.getBytes(StandardCharsets.UTF_8));
        long key = ByteBuffer.wrap(digest).getLong();
        return key ^ NAMESPACE;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required to derive account lock keys", ex);
        }
    }
}
