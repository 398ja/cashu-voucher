package xyz.tcheeric.cashu.voucher.domain;

import lombok.experimental.UtilityClass;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Cryptographic fingerprinting for Cashu vouchers.
 *
 * <p>Provides collision-resistant fingerprints for vouchers based on their
 * unique identifiers. The fingerprint is deterministic and can be used for:
 * <ul>
 *   <li>Duplicate detection during redemption</li>
 *   <li>Idempotency key generation for API calls</li>
 *   <li>Tracking redemption attempts across distributed systems</li>
 * </ul>
 *
 * <h3>Algorithm</h3>
 * <p>The fingerprint is computed as SHA-256 of a canonical string representation:
 * <pre>
 *   SHA-256(voucherId || "|" || issuerId || "|" || signature)
 * </pre>
 *
 * <p>Including the signature ensures that even if a voucher ID is reused
 * (which should not happen), different signatures produce different fingerprints.
 *
 * <h3>Security Properties</h3>
 * <ul>
 *   <li><b>Deterministic</b>: Same voucher always produces same fingerprint</li>
 *   <li><b>Collision-resistant</b>: Different vouchers produce different fingerprints</li>
 *   <li><b>Non-reversible</b>: Cannot derive voucher details from fingerprint</li>
 * </ul>
 *
 * @see SignedVoucher
 * @see VoucherSecret
 */
@UtilityClass
public class VoucherFingerprint {

    private static final String SEPARATOR = "|";

    /**
     * Computes the fingerprint for a signed voucher.
     *
     * @param voucher the signed voucher to fingerprint (must not be null)
     * @return 64-character hex-encoded SHA-256 hash
     * @throws IllegalArgumentException if voucher is null
     */
    public String compute(SignedVoucher voucher) {
        Objects.requireNonNull(voucher, "Voucher must not be null");
        return compute(voucher.getSecret());
    }

    /**
     * Computes the fingerprint for a voucher secret.
     *
     * <p>The fingerprint includes:
     * <ul>
     *   <li>Voucher ID (UUID)</li>
     *   <li>Issuer ID</li>
     *   <li>Signature (if present)</li>
     * </ul>
     *
     * @param secret the voucher secret to fingerprint (must not be null)
     * @return 64-character hex-encoded SHA-256 hash
     * @throws IllegalArgumentException if secret is null or missing required fields
     */
    public String compute(VoucherSecret secret) {
        Objects.requireNonNull(secret, "Secret must not be null");

        if (secret.getVoucherId() == null) {
            throw new IllegalArgumentException("VoucherSecret must have a voucher ID");
        }
        if (secret.getIssuerId() == null || secret.getIssuerId().isBlank()) {
            throw new IllegalArgumentException("VoucherSecret must have an issuer ID");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(secret.getVoucherId().toString());
        sb.append(SEPARATOR);
        sb.append(secret.getIssuerId());

        // Include signature if available for additional uniqueness
        if (secret.getIssuerSignature() != null && !secret.getIssuerSignature().isBlank()) {
            sb.append(SEPARATOR);
            sb.append(secret.getIssuerSignature());
        }

        return sha256Hex(sb.toString());
    }

    /**
     * Computes the fingerprint from raw voucher components.
     *
     * <p>This method is useful when you have the components but not
     * the full VoucherSecret object.
     *
     * @param voucherId the voucher ID (must not be null or blank)
     * @param issuerId the issuer ID (must not be null or blank)
     * @param signature the signature hex string (may be null)
     * @return 64-character hex-encoded SHA-256 hash
     */
    public String compute(String voucherId, String issuerId, String signature) {
        Objects.requireNonNull(voucherId, "Voucher ID must not be null");
        Objects.requireNonNull(issuerId, "Issuer ID must not be null");

        if (voucherId.isBlank()) {
            throw new IllegalArgumentException("Voucher ID must not be blank");
        }
        if (issuerId.isBlank()) {
            throw new IllegalArgumentException("Issuer ID must not be blank");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(voucherId);
        sb.append(SEPARATOR);
        sb.append(issuerId);

        if (signature != null && !signature.isBlank()) {
            sb.append(SEPARATOR);
            sb.append(signature);
        }

        return sha256Hex(sb.toString());
    }

    /**
     * Validates that a fingerprint matches the expected format.
     *
     * @param fingerprint the fingerprint to validate
     * @return true if the fingerprint is a valid 64-character hex string
     */
    public boolean isValid(String fingerprint) {
        if (fingerprint == null || fingerprint.length() != 64) {
            return false;
        }
        return fingerprint.matches("^[0-9a-f]+$");
    }

    /**
     * Computes SHA-256 hash and returns as lowercase hex string.
     */
    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in Java
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
