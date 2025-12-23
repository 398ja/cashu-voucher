package xyz.tcheeric.cashu.voucher.domain;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.common.BaseKey;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.voucher.domain.util.VoucherSerializationUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Gift card voucher secret (Model B - spendable only at issuing merchant).
 *
 * <p>VoucherSecret represents a non-deterministic gift card voucher that follows the Cashu
 * protocol's secret structure. Unlike {@link xyz.tcheeric.cashu.common.DeterministicSecret},
 * vouchers are NOT deterministic and MUST be backed up to Nostr (or other storage) for recovery.
 *
 * <h3>Model B Constraint</h3>
 * <p>Vouchers can only be redeemed (for goods/services) at the issuing merchant.
 * Swaps at the mint are allowed and essential for P2P transfers and double-spend prevention.
 * Model B enforcement (merchant-only redemption) happens at the application layer.
 *
 * <h3>Immutability</h3>
 * <p>This class is immutable once created. All fields are final and the canonical byte
 * representation is deterministic for signature generation.
 *
 * <h3>Serialization</h3>
 * <p>VoucherSecret uses canonical CBOR serialization (via {@link VoucherSerializationUtils})
 * to ensure deterministic byte representation for ED25519 signature generation. Field ordering
 * is alphabetical to guarantee consistency.
 *
 * <p>Jackson JSON serialization uses {@link VoucherSecretSerializer} to serialize as hex string.
 *
 * @see SignedVoucher
 * @see VoucherSignatureService
 * @see VoucherSecretSerializer
 * @see <a href="https://github.com/cashubtc/nuts">Cashu NUTs Specification</a>
 */
@Getter
@EqualsAndHashCode(callSuper = false)
@JsonSerialize(using = VoucherSecretSerializer.class)
public final class VoucherSecret extends BaseKey implements Secret {

    /**
     * Unique identifier for this voucher (UUID).
     */
    private final String voucherId;

    /**
     * Identifier of the merchant/entity that issued this voucher.
     */
    private final String issuerId;

    /**
     * Currency unit (e.g., "sat" for satoshis, "usd" for US dollars).
     */
    private final String unit;

    /**
     * Face value of the voucher in the smallest unit (e.g., satoshis, cents).
     * Must be positive.
     */
    private final long faceValue;

    /**
     * Optional expiry timestamp (Unix epoch seconds).
     * Null means no expiry.
     */
    private final Long expiresAt;

    /**
     * Optional memo/description for the voucher.
     */
    private final String memo;

    /**
     * Backing strategy determining token amount and split capability.
     * Required field - merchant must specify at issuance.
     */
    private final BackingStrategy backingStrategy;

    /**
     * Issuance ratio: face value per sat.
     * Used to calculate face value from token amount after splits.
     * Example: 0.01 means €0.01 per sat (so €10 = 1000 sats).
     */
    private final double issuanceRatio;

    /**
     * Number of decimal places for the face value currency.
     * Example: 2 for EUR (cents), 0 for JPY.
     */
    private final int faceDecimals;

    /**
     * Arbitrary merchant-defined metadata.
     * Can contain business-specific data like passenger info, event details, etc.
     * Must be CBOR-serializable. Immutable after creation.
     */
    private final Map<String, Object> merchantMetadata;

    /**
     * Private constructor - use factory methods to create instances.
     *
     * @param voucherId unique voucher identifier
     * @param issuerId issuer identifier
     * @param unit currency unit
     * @param faceValue face value (must be positive)
     * @param expiresAt optional expiry timestamp
     * @param memo optional memo
     * @param backingStrategy backing strategy (required)
     * @param issuanceRatio face value per sat (must be positive)
     * @param faceDecimals decimal places for face value (must be non-negative)
     * @param merchantMetadata optional merchant-defined metadata
     */
    private VoucherSecret(
            @NonNull String voucherId,
            @NonNull String issuerId,
            @NonNull String unit,
            long faceValue,
            Long expiresAt,
            String memo,
            @NonNull BackingStrategy backingStrategy,
            double issuanceRatio,
            int faceDecimals,
            Map<String, Object> merchantMetadata
    ) {
        super(new byte[0]); // Initialize BaseKey with empty array, will use toCanonicalBytes()

        if (faceValue <= 0) {
            throw new IllegalArgumentException("Face value must be positive, got: " + faceValue);
        }
        if (voucherId.isBlank()) {
            throw new IllegalArgumentException("Voucher ID cannot be blank");
        }
        if (issuerId.isBlank()) {
            throw new IllegalArgumentException("Issuer ID cannot be blank");
        }
        if (unit.isBlank()) {
            throw new IllegalArgumentException("Unit cannot be blank");
        }
        if (expiresAt != null && expiresAt <= 0) {
            throw new IllegalArgumentException("Expiry timestamp must be positive if provided");
        }
        if (issuanceRatio <= 0) {
            throw new IllegalArgumentException("Issuance ratio must be positive, got: " + issuanceRatio);
        }
        if (faceDecimals < 0) {
            throw new IllegalArgumentException("Face decimals must be non-negative, got: " + faceDecimals);
        }

        this.voucherId = voucherId;
        this.issuerId = issuerId;
        this.unit = unit;
        this.faceValue = faceValue;
        this.expiresAt = expiresAt;
        this.memo = memo;
        this.backingStrategy = backingStrategy;
        this.issuanceRatio = issuanceRatio;
        this.faceDecimals = faceDecimals;
        this.merchantMetadata = merchantMetadata != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(merchantMetadata))
                : Collections.emptyMap();

        // Update BaseKey bytes with canonical representation
        this.setBytes(toCanonicalBytes());
    }

    /**
     * Creates a new voucher with auto-generated UUID.
     *
     * @param issuerId issuer identifier
     * @param unit currency unit
     * @param faceValue face value (must be positive)
     * @param expiresAt optional expiry timestamp (Unix epoch seconds)
     * @param memo optional memo
     * @param backingStrategy backing strategy (required)
     * @param issuanceRatio face value per sat (must be positive)
     * @param faceDecimals decimal places for face value (must be non-negative)
     * @param merchantMetadata optional merchant-defined metadata
     * @return new VoucherSecret instance
     * @throws IllegalArgumentException if parameters are invalid
     */
    public static VoucherSecret create(
            @NonNull String issuerId,
            @NonNull String unit,
            long faceValue,
            Long expiresAt,
            String memo,
            @NonNull BackingStrategy backingStrategy,
            double issuanceRatio,
            int faceDecimals,
            Map<String, Object> merchantMetadata
    ) {
        return new VoucherSecret(
                UUID.randomUUID().toString(),
                issuerId,
                unit,
                faceValue,
                expiresAt,
                memo,
                backingStrategy,
                issuanceRatio,
                faceDecimals,
                merchantMetadata
        );
    }

    /**
     * Creates a voucher with specified voucher ID.
     *
     * <p>This method is primarily for deserialization and testing.
     *
     * @param voucherId voucher identifier
     * @param issuerId issuer identifier
     * @param unit currency unit
     * @param faceValue face value (must be positive)
     * @param expiresAt optional expiry timestamp (Unix epoch seconds)
     * @param memo optional memo
     * @param backingStrategy backing strategy (required)
     * @param issuanceRatio face value per sat (must be positive)
     * @param faceDecimals decimal places for face value (must be non-negative)
     * @param merchantMetadata optional merchant-defined metadata
     * @return new VoucherSecret instance
     * @throws IllegalArgumentException if parameters are invalid
     */
    public static VoucherSecret create(
            @NonNull String voucherId,
            @NonNull String issuerId,
            @NonNull String unit,
            long faceValue,
            Long expiresAt,
            String memo,
            @NonNull BackingStrategy backingStrategy,
            double issuanceRatio,
            int faceDecimals,
            Map<String, Object> merchantMetadata
    ) {
        return new VoucherSecret(
                voucherId,
                issuerId,
                unit,
                faceValue,
                expiresAt,
                memo,
                backingStrategy,
                issuanceRatio,
                faceDecimals,
                merchantMetadata
        );
    }

    /**
     * Returns a builder for creating VoucherSecret instances with fluent API.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for VoucherSecret with fluent API.
     */
    public static class Builder {
        private String voucherId;
        private String issuerId;
        private String unit;
        private long faceValue;
        private Long expiresAt;
        private String memo;
        private BackingStrategy backingStrategy;
        private double issuanceRatio;
        private int faceDecimals;
        private Map<String, Object> merchantMetadata;

        public Builder voucherId(String voucherId) {
            this.voucherId = voucherId;
            return this;
        }

        public Builder issuerId(String issuerId) {
            this.issuerId = issuerId;
            return this;
        }

        public Builder unit(String unit) {
            this.unit = unit;
            return this;
        }

        public Builder faceValue(long faceValue) {
            this.faceValue = faceValue;
            return this;
        }

        public Builder expiresAt(Long expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder memo(String memo) {
            this.memo = memo;
            return this;
        }

        public Builder backingStrategy(BackingStrategy backingStrategy) {
            this.backingStrategy = backingStrategy;
            return this;
        }

        public Builder issuanceRatio(double issuanceRatio) {
            this.issuanceRatio = issuanceRatio;
            return this;
        }

        public Builder faceDecimals(int faceDecimals) {
            this.faceDecimals = faceDecimals;
            return this;
        }

        public Builder merchantMetadata(Map<String, Object> merchantMetadata) {
            this.merchantMetadata = merchantMetadata;
            return this;
        }

        public VoucherSecret build() {
            String id = voucherId != null ? voucherId : UUID.randomUUID().toString();
            return new VoucherSecret(
                    id,
                    issuerId,
                    unit,
                    faceValue,
                    expiresAt,
                    memo,
                    backingStrategy,
                    issuanceRatio,
                    faceDecimals,
                    merchantMetadata
            );
        }
    }

    /**
     * Canonical serialization for deterministic signing.
     *
     * <p>Fields are serialized to CBOR in alphabetical order:
     * <ol>
     *   <li>backingStrategy</li>
     *   <li>expiresAt (if present)</li>
     *   <li>faceDecimals</li>
     *   <li>faceValue</li>
     *   <li>issuanceRatio</li>
     *   <li>issuerId</li>
     *   <li>memo (if present)</li>
     *   <li>merchantMetadata (if not empty)</li>
     *   <li>unit</li>
     *   <li>voucherId</li>
     * </ol>
     *
     * @return CBOR-encoded bytes representing this voucher
     */
    public byte[] toCanonicalBytes() {
        Map<String, Object> map = new LinkedHashMap<>();

        // Alphabetical ordering for deterministic serialization
        map.put("backingStrategy", backingStrategy.name());
        if (expiresAt != null) {
            map.put("expiresAt", expiresAt);
        }
        map.put("faceDecimals", faceDecimals);
        map.put("faceValue", faceValue);
        map.put("issuanceRatio", issuanceRatio);
        map.put("issuerId", issuerId);
        if (memo != null && !memo.isBlank()) {
            map.put("memo", memo);
        }
        if (merchantMetadata != null && !merchantMetadata.isEmpty()) {
            map.put("merchantMetadata", merchantMetadata);
        }
        map.put("unit", unit);
        map.put("voucherId", voucherId);

        return VoucherSerializationUtils.toCbor(map);
    }

    /**
     * Returns hex-encoded canonical representation.
     *
     * <p>This is the serialization format used in Cashu tokens.
     * For JSON serialization, see {@link VoucherSecretSerializer}.
     *
     * @return hex-encoded string of canonical CBOR bytes
     */
    public String toHexString() {
        return Hex.toHexString(toCanonicalBytes());
    }

    /**
     * Alias for {@link #toCanonicalBytes()} for Secret interface.
     *
     * @return canonical CBOR bytes
     */
    @Override
    public byte[] toBytes() {
        return toCanonicalBytes();
    }

    /**
     * Returns canonical bytes for Secret interface.
     *
     * @return canonical CBOR bytes
     */
    @Override
    public byte[] getData() {
        return toCanonicalBytes();
    }

    /**
     * VoucherSecret is immutable - data cannot be modified after creation.
     *
     * @param data ignored
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    public void setData(@NonNull byte[] data) {
        throw new UnsupportedOperationException("VoucherSecret is immutable");
    }

    /**
     * Checks if this voucher has expired.
     *
     * @return true if expired, false if valid or no expiry set
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().getEpochSecond() > expiresAt;
    }

    /**
     * Checks if this voucher is valid (not expired).
     *
     * @return true if not expired or no expiry set, false if expired
     */
    public boolean isValid() {
        return !isExpired();
    }

    /**
     * Returns hex-encoded string representation.
     *
     * <p>Note: JSON serialization uses {@link VoucherSecretSerializer} instead.
     *
     * @return hex string for display/logging (without sensitive data)
     */
    @Override
    public String toString() {
        return toHexString();
    }

    /**
     * Returns a detailed string representation including metadata.
     * Useful for debugging and logging.
     *
     * @return human-readable string with voucher metadata
     */
    public String toStringWithMetadata() {
        StringBuilder sb = new StringBuilder();
        sb.append("VoucherSecret{");
        sb.append("voucherId='").append(voucherId).append('\'');
        sb.append(", issuerId='").append(issuerId).append('\'');
        sb.append(", unit='").append(unit).append('\'');
        sb.append(", faceValue=").append(faceValue);
        sb.append(", faceDecimals=").append(faceDecimals);
        sb.append(", backingStrategy=").append(backingStrategy);
        sb.append(", issuanceRatio=").append(issuanceRatio);
        if (expiresAt != null) {
            sb.append(", expiresAt=").append(expiresAt);
            sb.append(" (").append(isExpired() ? "EXPIRED" : "valid").append(")");
        }
        if (memo != null) {
            sb.append(", memo='").append(memo).append('\'');
        }
        if (!merchantMetadata.isEmpty()) {
            sb.append(", merchantMetadata=").append(merchantMetadata);
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        VoucherSecret that = (VoucherSecret) o;
        return faceValue == that.faceValue &&
                faceDecimals == that.faceDecimals &&
                Double.compare(issuanceRatio, that.issuanceRatio) == 0 &&
                voucherId.equals(that.voucherId) &&
                issuerId.equals(that.issuerId) &&
                unit.equals(that.unit) &&
                backingStrategy == that.backingStrategy &&
                Objects.equals(expiresAt, that.expiresAt) &&
                Objects.equals(memo, that.memo) &&
                Objects.equals(merchantMetadata, that.merchantMetadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), voucherId, issuerId, unit, faceValue, expiresAt, memo,
                backingStrategy, issuanceRatio, faceDecimals, merchantMetadata);
    }
}
