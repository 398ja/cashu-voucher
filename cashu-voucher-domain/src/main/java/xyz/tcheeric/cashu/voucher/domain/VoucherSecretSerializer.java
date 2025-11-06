package xyz.tcheeric.cashu.voucher.domain;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.bouncycastle.util.encoders.Hex;

import java.io.IOException;

/**
 * Custom Jackson serializer for VoucherSecret.
 *
 * <p>Serializes VoucherSecret as a hex-encoded string of its canonical CBOR bytes.
 * This is required for Cashu protocol compatibility.
 */
public class VoucherSecretSerializer extends JsonSerializer<VoucherSecret> {

    @Override
    public void serialize(VoucherSecret value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        if (value == null) {
            gen.writeNull();
        } else {
            gen.writeString(Hex.toHexString(value.toCanonicalBytes()));
        }
    }
}
