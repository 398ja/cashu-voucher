package xyz.tcheeric.cashu.voucher.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.common.VoucherSecret;

import java.util.Objects;
import java.util.UUID;

/**
 * A voucher secret with an issuer's cryptographic signature.
 *
 * <p>SignedVoucher represents a complete voucher that has been cryptographically signed by
 * the issuing merchant. It wraps a {@link VoucherSecret} that stores the signature and
 * public key as NUT-10 compliant tags.
 *
 * <h3>Verification</h3>
 * <p>The signature can be verified using {@link #verify()} which delegates to
 * {@link VoucherSignatureService}. A voucher is considered fully valid if:
 * <ul>
 *   <li>The signature is cryptographically valid ({@link #verify()} returns true)</li>
 *   <li>The voucher has not expired ({@link #isExpired()} returns false)</li>
 * </ul>
 *
 * <h3>Model B Constraint</h3>
 * <p>Signed vouchers are only redeemable with the issuing merchant identified by
 * {@code secret.getIssuerId()}. They cannot be redeemed at the mint.
 *
 * @see VoucherSecret
 * @see VoucherSignatureService
 * @see VoucherValidator
 */
@Getter
@EqualsAndHashCode
public final class SignedVoucher {

    /**
     * The voucher secret containing all voucher details including signature and public key.
     */
    @JsonProperty("secret")
    private final VoucherSecret secret;

    /**
     * Creates a signed voucher from a VoucherSecret that already has signature and public key set.
     *
     * @param secret the voucher secret with signature and public key tags set
     * @throws IllegalArgumentException if the secret doesn't have signature or public key
     */
    public SignedVoucher(@NonNull VoucherSecret secret) {
        if (!secret.isSigned()) {
            throw new IllegalArgumentException("VoucherSecret must have signature and public key set");
        }
        this.secret = secret;
    }

    /**
     * Creates a signed voucher from components (for deserialization compatibility).
     *
     * @param secret the voucher secret
     * @param issuerSignature the issuer's signature (64 bytes for Schnorr)
     * @param issuerPublicKey the issuer's public key hex string
     * @throws IllegalArgumentException if any parameter is invalid
     */
    @JsonCreator
    public SignedVoucher(
            @NonNull @JsonProperty("secret") VoucherSecret secret,
            @NonNull @JsonProperty("issuerSignature") byte[] issuerSignature,
            @NonNull @JsonProperty("issuerPublicKey") String issuerPublicKey
    ) {
        if (issuerSignature.length != 64) {
            throw new IllegalArgumentException(
                    "Invalid signature length: expected 64 bytes (Schnorr), got " + issuerSignature.length);
        }
        if (issuerPublicKey.isBlank()) {
            throw new IllegalArgumentException("Issuer public key cannot be blank");
        }

        // Store signature and public key in the secret's tags
        secret.setIssuerSignature(Hex.toHexString(issuerSignature));
        secret.setIssuerPublicKey(issuerPublicKey);
        this.secret = secret;
    }

    /**
     * Creates a signed voucher with all voucher details.
     *
     * @param voucherId unique voucher identifier
     * @param issuerId merchant identifier
     * @param unit currency unit
     * @param faceValue face value in smallest unit
     * @param expiresAt optional expiry timestamp
     * @param memo optional description
     * @param backingStrategy backing strategy
     * @param issuanceRatio issuance ratio
     * @param faceDecimals decimal places
     * @param issuerSignature hex-encoded signature
     * @param issuerPublicKey hex-encoded public key
     * @return new SignedVoucher instance
     */
    public static SignedVoucher create(
            @NonNull UUID voucherId,
            @NonNull String issuerId,
            @NonNull String unit,
            long faceValue,
            Long expiresAt,
            String memo,
            @NonNull BackingStrategy backingStrategy,
            double issuanceRatio,
            int faceDecimals,
            @NonNull String issuerSignature,
            @NonNull String issuerPublicKey
    ) {
        VoucherSecret secret = VoucherSecret.builder()
                .voucherId(voucherId)
                .issuerId(issuerId)
                .unit(unit)
                .faceValue(faceValue)
                .expiresAt(expiresAt)
                .memo(memo)
                .backingStrategy(backingStrategy.name())
                .issuanceRatio(issuanceRatio)
                .faceDecimals(faceDecimals)
                .issuerSignature(issuerSignature)
                .issuerPublicKey(issuerPublicKey)
                .build();

        return new SignedVoucher(secret);
    }

    /**
     * Verifies the cryptographic signature of this voucher.
     *
     * @return true if the signature is valid, false otherwise
     */
    public boolean verify() {
        return VoucherSignatureService.verify(secret);
    }

    /**
     * Checks if this voucher has expired.
     *
     * @return true if expired, false if valid or no expiry set
     */
    public boolean isExpired() {
        return secret.isExpired();
    }

    /**
     * Checks if this voucher is fully valid (signature valid AND not expired).
     *
     * @return true if signature is valid and voucher not expired, false otherwise
     */
    public boolean isValid() {
        return verify() && !isExpired();
    }

    /**
     * Returns the issuer signature as bytes.
     *
     * @return signature bytes (64 bytes), or null if not set
     */
    @JsonProperty("issuerSignature")
    public byte[] getIssuerSignature() {
        String sig = secret.getIssuerSignature();
        return sig != null ? Hex.decode(sig) : null;
    }

    /**
     * Returns the issuer public key.
     *
     * @return hex-encoded public key
     */
    @JsonProperty("issuerPublicKey")
    public String getIssuerPublicKey() {
        return secret.getIssuerPublicKey();
    }

    // ===== Convenience getters delegating to secret =====

    @JsonIgnore
    public UUID getVoucherId() {
        return secret.getVoucherId();
    }

    @JsonIgnore
    public String getIssuerId() {
        return secret.getIssuerId();
    }

    @JsonIgnore
    public String getUnit() {
        return secret.getUnit();
    }

    @JsonIgnore
    public Long getFaceValue() {
        return secret.getFaceValue();
    }

    @JsonIgnore
    public Long getExpiresAt() {
        return secret.getExpiresAt();
    }

    @JsonIgnore
    public String getMemo() {
        return secret.getMemo();
    }

    @JsonIgnore
    public BackingStrategy getBackingStrategy() {
        String strategy = secret.getBackingStrategy();
        return BackingStrategy.valueOf(strategy);
    }

    @JsonIgnore
    public double getIssuanceRatio() {
        return secret.getIssuanceRatio();
    }

    @JsonIgnore
    public int getFaceDecimals() {
        return secret.getFaceDecimals();
    }

    /**
     * Returns a string representation for debugging.
     *
     * @return human-readable string with voucher metadata
     */
    @Override
    public String toString() {
        return "SignedVoucher{" +
                "voucherId='" + getVoucherId() + '\'' +
                ", issuerId='" + getIssuerId() + '\'' +
                ", faceValue=" + getFaceValue() +
                ", unit='" + getUnit() + '\'' +
                ", expired=" + isExpired() +
                ", signatureValid=" + verify() +
                ", issuerPublicKey='" + getIssuerPublicKey().substring(0, Math.min(16, getIssuerPublicKey().length())) + "...'" +
                '}';
    }

    /**
     * Returns a detailed string representation including full secret metadata.
     *
     * @return detailed human-readable string
     */
    public String toStringWithMetadata() {
        return "SignedVoucher{" +
                "voucherId=" + getVoucherId() +
                ", issuerId='" + getIssuerId() + '\'' +
                ", unit='" + getUnit() + '\'' +
                ", faceValue=" + getFaceValue() +
                ", faceDecimals=" + getFaceDecimals() +
                ", backingStrategy=" + getBackingStrategy() +
                ", issuanceRatio=" + getIssuanceRatio() +
                ", expiresAt=" + getExpiresAt() +
                ", memo='" + getMemo() + '\'' +
                ", signatureValid=" + verify() +
                ", issuerPublicKey='" + getIssuerPublicKey() + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SignedVoucher that = (SignedVoucher) o;
        return Objects.equals(secret, that.secret);
    }

    @Override
    public int hashCode() {
        return Objects.hash(secret);
    }
}
