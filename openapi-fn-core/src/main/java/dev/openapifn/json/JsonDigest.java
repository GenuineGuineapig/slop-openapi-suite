package dev.openapifn.json;

import dev.openapifn.fn.Result;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.TreeMap;

/**
 * A SHA-256 digest of a JSON value, equal for values JSON Schema considers equal: numbers by their
 * mathematical value ({@code 1} and {@code 1.0}) and objects whatever the order of their members.
 * It lets a long sequence be checked for duplicates ({@code uniqueItems}) with 32 bytes per item.
 *
 * @param high the first half of the digest
 * @param low the second half
 */
public record JsonDigest(Hash high, Hash low) {

    /**
     * Sixteen bytes of a digest.
     *
     * @param first the first eight bytes
     * @param second the next eight bytes
     */
    public record Hash(long first, long second) {
    }

    /** The digest of {@code value}. */
    public static JsonDigest of(JsonValue value) {
        var digest = sha256();
        update(digest, value);
        var bytes = ByteBuffer.wrap(digest.digest());
        return new JsonDigest(new Hash(bytes.getLong(), bytes.getLong()), new Hash(bytes.getLong(), bytes.getLong()));
    }

    private static MessageDigest sha256() {
        // Every Java platform has SHA-256.
        return Result.attempt(() -> MessageDigest.getInstance("SHA-256"), Exception::getMessage).orElseThrow(IllegalStateException::new);
    }

    /** A canonical, self-delimiting encoding: a type tag, then the content (lengths before variable parts). */
    private static void update(MessageDigest digest, JsonValue value) {
        switch (value) {
            case JsonNull ignored -> digest.update((byte) 'n');
            case JsonBoolean bool -> digest.update((byte) (bool.value() ? 't' : 'f'));
            case JsonNumber number -> {
                var canonical = number.value().stripTrailingZeros();
                digest.update((byte) 'd');
                text(digest, canonical.unscaledValue().toString());
                text(digest, Integer.toString(canonical.scale()));
            }
            case JsonString string -> {
                digest.update((byte) 's');
                text(digest, string.value());
            }
            case JsonArray array -> {
                digest.update((byte) '[');
                text(digest, Integer.toString(array.size()));
                array.values().forEach(item -> update(digest, item));
            }
            case JsonObject object -> {
                digest.update((byte) '{');
                text(digest, Integer.toString(object.members().size()));
                new TreeMap<>(object.members()).forEach((name, member) -> {
                    text(digest, name);
                    update(digest, member);
                });
            }
        }
    }

    private static void text(MessageDigest digest, String text) {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
