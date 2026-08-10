package xyz.tcheeric.cashu.voucher.pass;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * The subset of Apple's {@code pass.json} schema this project emits.
 *
 * <p>We adopt the schema, not the platform: there is no certificate, no
 * {@code .pkpass} container, and no pass update web service. Apple Wallet never
 * renders these documents — imani's own wallet does. {@code passTypeIdentifier} and
 * {@code teamIdentifier} are schema-required and carry no meaning here.
 *
 * <p>Modelled by hand rather than via jPasskit: that library drags an APNs push
 * client and Netty into the dependency tree, which contradicts this module's design.
 *
 * <p>Serialisation is the caller's — this module declares only Jackson annotations.
 * Nulls are omitted so optional keys stay absent rather than appearing as
 * {@code null}, which Apple's schema does not permit.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PassJson(
        int formatVersion,
        String passTypeIdentifier,
        String teamIdentifier,
        String serialNumber,
        String description,
        String organizationName,
        String logoText,
        String backgroundColor,
        String foregroundColor,
        String labelColor,
        /** ISO-8601 instant, or null. A String so no Jackson java-time module is needed. */
        String expirationDate,
        boolean voided,
        StoreCard storeCard,
        List<Barcode> barcodes,
        Map<String, Object> userInfo
) {

    /** Field groups for a store card. Absent groups stay null rather than empty. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StoreCard(
            List<Field> primaryFields,
            List<Field> auxiliaryFields,
            List<Field> backFields
    ) {
    }

    /**
     * One displayed field.
     *
     * <p>{@code currencyCode} and {@code dateStyle} are mutually exclusive — a field is
     * a currency or a date, never both.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Field(
            String key,
            String label,
            Object value,
            String currencyCode,
            String dateStyle
    ) {
        /**
         * A plain field with no currency or date formatting.
         *
         * @param key field key
         * @param label displayed label
         * @param value displayed value
         * @return the field
         */
        public static Field of(String key, String label, Object value) {
            return new Field(key, label, value, null, null);
        }
    }

    /** A machine-readable code. {@code altText} is the human-readable fallback. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Barcode(
            String format,
            String message,
            String messageEncoding,
            String altText
    ) {
    }
}
