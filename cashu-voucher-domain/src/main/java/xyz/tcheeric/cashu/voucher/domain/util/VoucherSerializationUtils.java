package xyz.tcheeric.cashu.voucher.domain.util;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import lombok.NonNull;

import java.io.IOException;
import java.util.Map;

/**
 * Utility class for canonical CBOR serialization of voucher data.
 *
 * <p>Provides deterministic serialization required for voucher signature generation and verification.
 * Uses CBOR (Concise Binary Object Representation) to ensure stable byte representation across
 * platforms and implementations.
 *
 * <p>Reads are strict, the same as the TypeScript wallet's decoder (cashu-voucher#64, review N7):
 * a map with a repeated key, or with a key that is not a text string, is refused rather than read
 * last-wins or with the key stringified.
 *
 * <p>Thread-safe and stateless.
 *
 * @see <a href="https://cbor.io/">CBOR Specification</a>
 */
public final class VoucherSerializationUtils {

    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(
            CBORFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private VoucherSerializationUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Serializes a map to CBOR bytes in canonical form.
     *
     * <p>The input map must have deterministic ordering (e.g., LinkedHashMap)
     * to ensure consistent byte output for signature generation.
     *
     * @param data the map to serialize (must not be null)
     * @return CBOR-encoded bytes
     * @throws IllegalArgumentException if serialization fails
     */
    public static byte[] toCbor(@NonNull Map<String, Object> data) {
        try {
            return CBOR_MAPPER.writeValueAsBytes(data);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to serialize voucher data to CBOR", e);
        }
    }

    /**
     * Deserializes CBOR bytes to a map.
     *
     * @param cborBytes the CBOR-encoded bytes (must not be null)
     * @return the deserialized map
     * @throws IllegalArgumentException if the bytes are not well-formed CBOR, a map has a
     *         repeated key, or a map key is not a text string
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromCbor(@NonNull byte[] cborBytes) {
        new TextKeys(cborBytes).check();
        try {
            return CBOR_MAPPER.readValue(cborBytes, Map.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to deserialize CBOR data", e);
        }
    }

    /** Structural walk refusing any map key that is not a text string (Jackson stringifies them). */
    private static final class TextKeys {
        private static final int MAX_DEPTH = 64;
        private final byte[] b;
        private int pos;

        TextKeys(byte[] b) {
            this.b = b;
        }

        void check() {
            item(0);
        }

        private int next() {
            if (pos >= b.length) {
                throw new IllegalArgumentException("Failed to deserialize CBOR data: truncated");
            }
            return b[pos++] & 0xff;
        }

        /** The argument of an initial byte, or -1 for an indefinite length. */
        private long argument(int info) {
            if (info < 24) {
                return info;
            }
            int n = switch (info) {
                case 24 -> 1;
                case 25 -> 2;
                case 26 -> 4;
                case 27 -> 8;
                case 31 -> 0;
                default -> throw new IllegalArgumentException("Failed to deserialize CBOR data: bad length");
            };
            if (n == 0) {
                return -1;
            }
            long v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 8) | next();
            }
            return v;
        }

        private void skip(long n) {
            if (n < 0 || n > b.length - pos) {
                throw new IllegalArgumentException("Failed to deserialize CBOR data: truncated");
            }
            pos += (int) n;
        }

        private boolean isBreak() {
            if (pos < b.length && (b[pos] & 0xff) == 0xff) {
                pos++;
                return true;
            }
            return false;
        }

        /** Reads one item, returning its major type. */
        private int item(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("Failed to deserialize CBOR data: too deep");
            }
            int ib = next();
            int major = ib >>> 5;
            long arg = argument(ib & 0x1f);
            switch (major) {
                case 0, 1 -> {
                    if (arg < 0) {
                        throw new IllegalArgumentException("Failed to deserialize CBOR data: bad integer");
                    }
                }
                case 2, 3 -> {
                    if (arg < 0) {
                        while (!isBreak()) {
                            if (item(depth + 1) != major) {
                                throw new IllegalArgumentException("Failed to deserialize CBOR data: bad chunk");
                            }
                        }
                    } else {
                        skip(arg);
                    }
                }
                case 4 -> {
                    for (long i = 0; arg < 0 ? !isBreak() : i < arg; i++) {
                        item(depth + 1);
                    }
                }
                case 5 -> {
                    for (long i = 0; arg < 0 ? !isBreak() : i < arg; i++) {
                        if (item(depth + 1) != 3) {
                            throw new IllegalArgumentException("Invalid voucher document: map key is not a text string");
                        }
                        item(depth + 1);
                    }
                }
                case 6 -> item(depth + 1);
                default -> {
                    // simple values and floats: the argument bytes were already consumed
                }
            }
            return major;
        }
    }
}
