package xyz.tcheeric.cashu.voucher.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.common.BaseKey;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.voucher.domain.util.VoucherSerializationUtils;

import java.time.Instant;
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
 * <p>Vouchers cannot be redeemed at the mint. They are only redeemable with the issuing merchant.
 * Any attempt to use voucher secrets in mint swap/melt operations must be rejected.
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
 * @see SignedVoucher
 * @see VoucherSignatureService
 * @see <a href="https://github.com/cashubtc/nuts">Cashu NUTs Specification</a>
 */
@Getter
@EqualsAndHashCode(callSuper = false)
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
     * Private constructor - use factory methods to create instances.
     *
     * @param voucherId unique voucher identifier
     * @param issuerId issuer identifier
     * @param unit currency unit
     * @param faceValue face value (must be positive)
     * @param expiresAt optional expiry timestamp
     * @param memo optional memo
     */
    private VoucherSecret(
            @NonNull String voucherId,
            @NonNull String issuerId,
            @NonNull String unit,
            long faceValue,
            Long expiresAt,
            String memo
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

        this.voucherId = voucherId;
        this.issuerId = issuerId;
        this.unit = unit;
        this.faceValue = faceValue;
        this.expiresAt = expiresAt;
        this.memo = memo;

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
     * @return new VoucherSecret instance
     * @throws IllegalArgumentException if parameters are invalid
     */
    public static VoucherSecret create(
            @NonNull String issuerId,
            @NonNull String unit,
            long faceValue,
            Long expiresAt,
            String memo
    ) {
        return new VoucherSecret(
                UUID.randomUUID().toString(),
                issuerId,
                unit,
                faceValue,
                expiresAt,
                memo
        );
    }

    /**
     * Creates a voucher with specified voucher ID.
     *
     * <p>This method is primarily for deserialization and testing.
     * Production code should prefer {@link #create(String, String, long, Long, String)}.
     *
     * @param voucherId voucher identifier
     * @param issuerId issuer identifier
     * @param unit currency unit
     * @param faceValue face value (must be positive)
     * @param expiresAt optional expiry timestamp (Unix epoch seconds)
     * @param memo optional memo
     * @return new VoucherSecret instance
     * @throws IllegalArgumentException if parameters are invalid
     */
    public static VoucherSecret create(
            @NonNull String voucherId,
            @NonNull String issuerId,
            @NonNull String unit,
            long faceValue,
            Long expiresAt,
            String memo
    ) {
        return new VoucherSecret(voucherId, issuerId, unit, faceValue, expiresAt, memo);
    }

    /**
     * Canonical serialization for deterministic signing.
     *
     * <p>Fields are serialized to CBOR in alphabetical order:
     * <ol>
     *   <li>expiresAt (if present)</li>
     *   <li>faceValue</li>
     *   <li>issuerId</li>
     *   <li>memo (if present)</li>
     *   <li>unit</li>
     *   <li>voucherId</li>
     * </ol>
     *
     * @return CBOR-encoded bytes representing this voucher
     */
    public byte[] toCanonicalBytes() {
        Map<String, Object> map = new LinkedHashMap<>();

        // Alphabetical ordering for deterministic serialization
        if (expiresAt != null) {
            map.put("expiresAt", expiresAt);
        }
        map.put("faceValue", faceValue);
        map.put("issuerId", issuerId);
        if (memo != null && !memo.isBlank()) {
            map.put("memo", memo);
        }
        map.put("unit", unit);
        map.put("voucherId", voucherId);

        return VoucherSerializationUtils.toCbor(map);
    }

    /**
     * Returns hex-encoded canonical representation.
     *
     * <p>This is the serialization format used in Cashu tokens and for JSON serialization.
     *
     * @return hex-encoded string of canonical CBOR bytes
     */
    @JsonValue
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
        if (expiresAt != null) {
            sb.append(", expiresAt=").append(expiresAt);
            sb.append(" (").append(isExpired() ? "EXPIRED" : "valid").append(")");
        }
        if (memo != null) {
            sb.append(", memo='").append(memo).append('\'');
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
                voucherId.equals(that.voucherId) &&
                issuerId.equals(that.issuerId) &&
                unit.equals(that.unit) &&
                Objects.equals(expiresAt, that.expiresAt) &&
                Objects.equals(memo, that.memo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), voucherId, issuerId, unit, faceValue, expiresAt, memo);
    }
}
