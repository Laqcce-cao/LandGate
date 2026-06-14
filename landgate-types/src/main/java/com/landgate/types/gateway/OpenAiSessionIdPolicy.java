package com.landgate.types.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * OpenAI/Codex upstream session-id derivation policy.
 *
 * <p>Aligned with Sub2API's OpenAI gateway session isolation:
 * {@code xxhash64("k{apiKeyId}:{raw}")} for raw client session identifiers,
 * and SHA-256 based UUID generation for Anthropic Messages compat
 * {@code prompt_cache_key} sessions.</p>
 */
public final class OpenAiSessionIdPolicy {

    private static final long XXH64_PRIME_1 = 0x9E3779B185EBCA87L;
    private static final long XXH64_PRIME_2 = 0xC2B2AE3D27D4EB4FL;
    private static final long XXH64_PRIME_3 = 0x165667B19E3779F9L;
    private static final long XXH64_PRIME_4 = 0x85EBCA77C2B2AE63L;
    private static final long XXH64_PRIME_5 = 0x27D4EB2F165667C5L;

    private OpenAiSessionIdPolicy() {
    }

    public static String isolateSessionId(Long apiKeyId, String raw) {
        String value = trim(raw);
        if (value.isBlank()) return "";
        long key = apiKeyId == null ? 0L : apiKeyId;
        return xxh64Hex("k" + key + ":" + value);
    }

    public static String compatSessionUuid(Long apiKeyId, String promptCacheKey) {
        String isolated = isolateSessionId(apiKeyId, promptCacheKey);
        if (isolated.isBlank()) return "";
        return generateSessionUuid(isolated);
    }

    public static String generateSessionUuid(String seed) {
        String value = seed == null ? "" : seed;
        if (value.isEmpty()) return UUID.randomUUID().toString();
        byte[] hash = sha256(value);
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x40);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        return "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x".formatted(
                hash[0] & 0xff, hash[1] & 0xff, hash[2] & 0xff, hash[3] & 0xff,
                hash[4] & 0xff, hash[5] & 0xff,
                hash[6] & 0xff, hash[7] & 0xff,
                hash[8] & 0xff, hash[9] & 0xff,
                hash[10] & 0xff, hash[11] & 0xff, hash[12] & 0xff,
                hash[13] & 0xff, hash[14] & 0xff, hash[15] & 0xff);
    }

    private static String xxh64Hex(String value) {
        return "%016x".formatted(xxh64(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static long xxh64(byte[] data) {
        int len = data.length;
        int offset = 0;
        long hash;
        if (len >= 32) {
            long v1 = XXH64_PRIME_1 + XXH64_PRIME_2;
            long v2 = XXH64_PRIME_2;
            long v3 = 0;
            long v4 = -XXH64_PRIME_1;
            int limit = len - 32;
            while (offset <= limit) {
                v1 = xxh64Round(v1, readLongLE(data, offset));
                offset += 8;
                v2 = xxh64Round(v2, readLongLE(data, offset));
                offset += 8;
                v3 = xxh64Round(v3, readLongLE(data, offset));
                offset += 8;
                v4 = xxh64Round(v4, readLongLE(data, offset));
                offset += 8;
            }
            hash = Long.rotateLeft(v1, 1)
                    + Long.rotateLeft(v2, 7)
                    + Long.rotateLeft(v3, 12)
                    + Long.rotateLeft(v4, 18);
            hash = xxh64MergeRound(hash, v1);
            hash = xxh64MergeRound(hash, v2);
            hash = xxh64MergeRound(hash, v3);
            hash = xxh64MergeRound(hash, v4);
        } else {
            hash = XXH64_PRIME_5;
        }
        hash += len;
        while (offset + 8 <= len) {
            long k1 = xxh64Round(0, readLongLE(data, offset));
            hash ^= k1;
            hash = Long.rotateLeft(hash, 27) * XXH64_PRIME_1 + XXH64_PRIME_4;
            offset += 8;
        }
        if (offset + 4 <= len) {
            hash ^= (readIntLE(data, offset) & 0xffffffffL) * XXH64_PRIME_1;
            hash = Long.rotateLeft(hash, 23) * XXH64_PRIME_2 + XXH64_PRIME_3;
            offset += 4;
        }
        while (offset < len) {
            hash ^= (data[offset] & 0xffL) * XXH64_PRIME_5;
            hash = Long.rotateLeft(hash, 11) * XXH64_PRIME_1;
            offset++;
        }
        hash ^= hash >>> 33;
        hash *= XXH64_PRIME_2;
        hash ^= hash >>> 29;
        hash *= XXH64_PRIME_3;
        hash ^= hash >>> 32;
        return hash;
    }

    private static long xxh64Round(long acc, long input) {
        acc += input * XXH64_PRIME_2;
        acc = Long.rotateLeft(acc, 31);
        acc *= XXH64_PRIME_1;
        return acc;
    }

    private static long xxh64MergeRound(long acc, long value) {
        acc ^= xxh64Round(0, value);
        acc = acc * XXH64_PRIME_1 + XXH64_PRIME_4;
        return acc;
    }

    private static long readLongLE(byte[] data, int offset) {
        return (data[offset] & 0xffL)
                | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24)
                | ((data[offset + 4] & 0xffL) << 32)
                | ((data[offset + 5] & 0xffL) << 40)
                | ((data[offset + 6] & 0xffL) << 48)
                | ((data[offset + 7] & 0xffL) << 56);
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
