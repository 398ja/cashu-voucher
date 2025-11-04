package xyz.tcheeric.cashu.voucher.domain.util;

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
 * <p>Thread-safe and stateless.
 *
 * @see <a href="https://cbor.io/">CBOR Specification</a>
 */
public final class VoucherSerializationUtils {

    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());

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
     * @throws IllegalArgumentException if deserialization fails
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromCbor(@NonNull byte[] cborBytes) {
        try {
            return CBOR_MAPPER.readValue(cborBytes, Map.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to deserialize CBOR data", e);
        }
    }
}
