package xyz.tcheeric.cashu.voucher.domain;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;

import java.util.Objects;

/**
 * A voucher secret with an issuer's cryptographic signature.
 *
 * <p>SignedVoucher represents a complete voucher that has been cryptographically signed by
 * the issuing merchant. It combines the voucher secret with the issuer's ED25519 signature
 * and public key for verification.
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
 * {@code secret.issuerId}. They cannot be redeemed at the mint.
 *
 * <h3>Immutability</h3>
 * <p>This class is immutable once created. All fields are final.
 *
 * @see VoucherSecret
 * @see VoucherSignatureService
 * @see VoucherValidator
 */
@Getter
@EqualsAndHashCode
public final class SignedVoucher {

    /**
     * The voucher secret containing all voucher details.
     */
    private final VoucherSecret secret;

    /**
     * The issuer's ED25519 signature over the canonical bytes of the secret.
     */
    private final byte[] issuerSignature;

    /**
     * The issuer's ED25519 public key (hex-encoded).
     * Used to verify the signature.
     */
    private final String issuerPublicKey;

    /**
     * Creates a signed voucher.
     *
     * @param secret the voucher secret (must not be null)
     * @param issuerSignature the issuer's signature (must not be null, 64 bytes for ED25519)
     * @param issuerPublicKey the issuer's public key hex string (must not be null)
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public SignedVoucher(
            @NonNull VoucherSecret secret,
            @NonNull byte[] issuerSignature,
            @NonNull String issuerPublicKey
    ) {
        if (issuerSignature.length != 64) {
            throw new IllegalArgumentException(
                    "Invalid signature length: expected 64 bytes (ED25519), got " + issuerSignature.length);
        }
        if (issuerPublicKey.isBlank()) {
            throw new IllegalArgumentException("Issuer public key cannot be blank");
        }

        this.secret = secret;
        this.issuerSignature = issuerSignature.clone(); // Defensive copy
        this.issuerPublicKey = issuerPublicKey;
    }

    /**
     * Verifies the cryptographic signature of this voucher.
     *
     * <p>Delegates to {@link VoucherSignatureService#verify(VoucherSecret, byte[], String)}
     * to perform ED25519 signature verification.
     *
     * @return true if the signature is valid, false otherwise
     */
    public boolean verify() {
        return VoucherSignatureService.verify(secret, issuerSignature, issuerPublicKey);
    }

    /**
     * Checks if this voucher has expired.
     *
     * <p>Delegates to {@link VoucherSecret#isExpired()}.
     *
     * @return true if expired, false if valid or no expiry set
     */
    public boolean isExpired() {
        return secret.isExpired();
    }

    /**
     * Checks if this voucher is fully valid (signature valid AND not expired).
     *
     * <p>A voucher is considered valid if:
     * <ul>
     *   <li>The signature verifies correctly</li>
     *   <li>The voucher has not expired</li>
     * </ul>
     *
     * @return true if signature is valid and voucher not expired, false otherwise
     */
    public boolean isValid() {
        return verify() && !isExpired();
    }

    /**
     * Returns a defensive copy of the signature bytes.
     *
     * @return copy of the signature bytes
     */
    public byte[] getIssuerSignature() {
        return issuerSignature.clone();
    }

    /**
     * Returns a string representation for debugging.
     *
     * @return human-readable string with voucher metadata
     */
    @Override
    public String toString() {
        return "SignedVoucher{" +
                "voucherId='" + secret.getVoucherId() + '\'' +
                ", issuerId='" + secret.getIssuerId() + '\'' +
                ", faceValue=" + secret.getFaceValue() +
                ", unit='" + secret.getUnit() + '\'' +
                ", expired=" + isExpired() +
                ", signatureValid=" + verify() +
                ", issuerPublicKey='" + issuerPublicKey.substring(0, Math.min(16, issuerPublicKey.length())) + "...'" +
                '}';
    }

    /**
     * Returns a detailed string representation including full secret metadata.
     *
     * @return detailed human-readable string
     */
    public String toStringWithMetadata() {
        return "SignedVoucher{" +
                "secret=" + secret.toStringWithMetadata() +
                ", signatureValid=" + verify() +
                ", issuerPublicKey='" + issuerPublicKey + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SignedVoucher that = (SignedVoucher) o;
        return secret.equals(that.secret) &&
                java.util.Arrays.equals(issuerSignature, that.issuerSignature) &&
                issuerPublicKey.equals(that.issuerPublicKey);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(secret, issuerPublicKey);
        result = 31 * result + java.util.Arrays.hashCode(issuerSignature);
        return result;
    }
}
