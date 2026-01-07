package xyz.tcheeric.cashu.voucher.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import xyz.tcheeric.cashu.common.VoucherSecret;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Validates vouchers for correctness and authenticity.
 *
 * <p>This class provides comprehensive validation logic for signed vouchers, including:
 * <ul>
 *   <li>Cryptographic signature verification</li>
 *   <li>Expiry validation</li>
 *   <li>Face value validation</li>
 *   <li>Business rule validation</li>
 * </ul>
 *
 * <h3>Validation Rules</h3>
 * <p>A voucher is considered valid if ALL of the following conditions are met:
 * <ol>
 *   <li>The issuer's signature is cryptographically valid (Schnorr verification)</li>
 *   <li>The voucher has not expired (if expiry is set)</li>
 *   <li>The face value is positive</li>
 * </ol>
 *
 * <h3>Usage Examples</h3>
 * <pre>
 * // Basic validation
 * ValidationResult result = VoucherValidator.validate(signedVoucher);
 * if (result.isValid()) {
 *     // Proceed with redemption
 * } else {
 *     // Handle errors
 *     result.getErrors().forEach(System.err::println);
 * }
 *
 * // Validation with issuer check
 * ValidationResult result = VoucherValidator.validateWithIssuer(
 *     signedVoucher,
 *     expectedIssuerId
 * );
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * <p>All methods are stateless and thread-safe.
 *
 * @see SignedVoucher
 * @see VoucherSecret
 * @see VoucherSignatureService
 */
public final class VoucherValidator {

    private VoucherValidator() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Result of voucher validation containing status and error messages.
     *
     * <p>If validation succeeds, {@link #isValid()} returns true and {@link #getErrors()} is empty.
     * If validation fails, errors list contains human-readable descriptions of all validation failures.
     */
    @Getter
    @AllArgsConstructor
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> errors;

        /**
         * Creates a successful validation result.
         *
         * @return validation result indicating success
         */
        public static ValidationResult success() {
            return new ValidationResult(true, Collections.emptyList());
        }

        /**
         * Creates a failed validation result with a single error.
         *
         * @param error the error message
         * @return validation result indicating failure
         */
        public static ValidationResult failure(@NonNull String error) {
            List<String> errors = new ArrayList<>();
            errors.add(error);
            return new ValidationResult(false, errors);
        }

        /**
         * Creates a failed validation result with multiple errors.
         *
         * @param errors the list of error messages
         * @return validation result indicating failure
         */
        public static ValidationResult failure(@NonNull List<String> errors) {
            if (errors.isEmpty()) {
                throw new IllegalArgumentException("Errors list cannot be empty for failure result");
            }
            return new ValidationResult(false, new ArrayList<>(errors));
        }

        /**
         * Returns an unmodifiable view of the errors list.
         *
         * @return list of validation errors (empty if valid)
         */
        public List<String> getErrors() {
            return Collections.unmodifiableList(errors);
        }

        /**
         * Returns a formatted string of all errors.
         *
         * @return concatenated error messages, or empty string if valid
         */
        public String getErrorMessage() {
            return String.join("; ", errors);
        }

        @Override
        public String toString() {
            if (valid) {
                return "ValidationResult{valid=true}";
            } else {
                return "ValidationResult{valid=false, errors=" + errors + "}";
            }
        }
    }

    /**
     * Validates a signed voucher.
     *
     * <p>Performs comprehensive validation including:
     * <ul>
     *   <li>Signature verification</li>
     *   <li>Expiry check</li>
     *   <li>Face value validation</li>
     * </ul>
     *
     * @param voucher the signed voucher to validate (must not be null)
     * @return validation result with success status and any errors
     */
    public static ValidationResult validate(@NonNull SignedVoucher voucher) {
        List<String> errors = new ArrayList<>();
        VoucherSecret secret = voucher.getSecret();

        // Validate signature
        if (!voucher.verify()) {
            errors.add("Invalid issuer signature");
        }

        // Validate expiry
        if (voucher.isExpired()) {
            errors.add("Voucher has expired");
        }

        // Validate face value
        Long faceValue = secret.getFaceValue();
        if (faceValue == null || faceValue <= 0) {
            errors.add("Invalid face value: must be positive");
        }

        // Validate issuer public key is not blank
        if (voucher.getIssuerPublicKey() == null || voucher.getIssuerPublicKey().isBlank()) {
            errors.add("Issuer public key is missing or blank");
        }

        // Validate voucher ID is not blank
        if (secret.getVoucherId() == null) {
            errors.add("Voucher ID is missing");
        }

        // Validate issuer ID is not blank
        String issuerId = secret.getIssuerId();
        if (issuerId == null || issuerId.isBlank()) {
            errors.add("Issuer ID is missing or blank");
        }

        // Validate unit is not blank
        String unit = secret.getUnit();
        if (unit == null || unit.isBlank()) {
            errors.add("Unit is missing or blank");
        }

        if (errors.isEmpty()) {
            return ValidationResult.success();
        } else {
            return ValidationResult.failure(errors);
        }
    }

    /**
     * Validates a signed voucher and checks that it was issued by the expected issuer.
     *
     * <p>This is useful for merchant-side validation to ensure the voucher
     * was issued by their own merchant account.
     *
     * @param voucher the signed voucher to validate (must not be null)
     * @param expectedIssuerId the expected issuer ID (must not be null)
     * @return validation result with success status and any errors
     */
    public static ValidationResult validateWithIssuer(
            @NonNull SignedVoucher voucher,
            @NonNull String expectedIssuerId
    ) {
        // First perform standard validation
        ValidationResult standardResult = validate(voucher);
        if (!standardResult.isValid()) {
            return standardResult;
        }

        // Check issuer ID matches
        String actualIssuerId = voucher.getSecret().getIssuerId();
        if (!expectedIssuerId.equals(actualIssuerId)) {
            return ValidationResult.failure(
                    "Voucher was issued by '" + actualIssuerId +
                            "' but expected issuer is '" + expectedIssuerId + "'"
            );
        }

        return ValidationResult.success();
    }

    /**
     * Validates just the cryptographic signature of a voucher.
     *
     * <p>This is a lightweight check that only verifies the signature,
     * without checking expiry or other business rules.
     *
     * @param voucher the signed voucher to validate (must not be null)
     * @return validation result for signature only
     */
    public static ValidationResult validateSignatureOnly(@NonNull SignedVoucher voucher) {
        if (voucher.verify()) {
            return ValidationResult.success();
        } else {
            return ValidationResult.failure("Invalid issuer signature");
        }
    }

    /**
     * Validates just the expiry of a voucher.
     *
     * <p>This is useful for periodic checks of voucher validity
     * without re-verifying the signature.
     *
     * @param voucher the signed voucher to validate (must not be null)
     * @return validation result for expiry only
     */
    public static ValidationResult validateExpiryOnly(@NonNull SignedVoucher voucher) {
        if (voucher.isExpired()) {
            return ValidationResult.failure("Voucher has expired");
        } else {
            return ValidationResult.success();
        }
    }

    /**
     * Quick check if a voucher is valid (no detailed errors).
     *
     * <p>This is a convenience method equivalent to {@code validate(voucher).isValid()}.
     *
     * @param voucher the signed voucher to check (must not be null)
     * @return true if voucher is valid, false otherwise
     */
    public static boolean isValid(@NonNull SignedVoucher voucher) {
        return validate(voucher).isValid();
    }
}
