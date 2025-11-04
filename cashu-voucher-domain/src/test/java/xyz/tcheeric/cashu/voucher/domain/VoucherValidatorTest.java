package xyz.tcheeric.cashu.voucher.domain;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link VoucherValidator}.
 *
 * <p>This test class verifies:
 * <ul>
 *   <li>Comprehensive voucher validation</li>
 *   <li>Signature verification</li>
 *   <li>Expiry validation</li>
 *   <li>Face value validation</li>
 *   <li>Issuer validation</li>
 *   <li>ValidationResult behavior</li>
 * </ul>
 */
@DisplayName("VoucherValidator")
class VoucherValidatorTest {

    private static String issuerPrivateKeyHex;
    private static String issuerPublicKeyHex;
    private static final String ISSUER_ID = "merchant123";
    private static final String UNIT = "sat";
    private static final long FACE_VALUE = 10000L;

    @BeforeAll
    static void setupKeys() {
        // Generate a test ED25519 key pair
        SecureRandom random = new SecureRandom();
        byte[] privateKeyBytes = new byte[32];
        random.nextBytes(privateKeyBytes);

        Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(privateKeyBytes, 0);
        byte[] publicKeyBytes = privateKey.generatePublicKey().getEncoded();

        issuerPrivateKeyHex = Hex.toHexString(privateKeyBytes);
        issuerPublicKeyHex = Hex.toHexString(publicKeyBytes);
    }

    private SignedVoucher createValidVoucher() {
        VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, null);
        return VoucherSignatureService.createSigned(secret, issuerPrivateKeyHex, issuerPublicKeyHex);
    }

    private SignedVoucher createValidVoucherWithExpiry(Long expiresAt) {
        VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, expiresAt, null);
        return VoucherSignatureService.createSigned(secret, issuerPrivateKeyHex, issuerPublicKeyHex);
    }

    @Nested
    @DisplayName("ValidationResult")
    class ValidationResultTests {

        @Test
        @DisplayName("success() should create valid result with no errors")
        void successShouldCreateValidResult() {
            // When
            VoucherValidator.ValidationResult result = VoucherValidator.ValidationResult.success();

            // Then
            assertThat(result.isValid()).isTrue();
            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getErrorMessage()).isEmpty();
        }

        @Test
        @DisplayName("failure(String) should create invalid result with one error")
        void failureWithSingleErrorShouldCreateInvalidResult() {
            // Given
            String error = "Test error";

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.ValidationResult.failure(error);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors()).contains(error);
            assertThat(result.getErrorMessage()).isEqualTo(error);
        }

        @Test
        @DisplayName("failure(List) should create invalid result with multiple errors")
        void failureWithMultipleErrorsShouldCreateInvalidResult() {
            // Given
            var errors = java.util.List.of("Error 1", "Error 2", "Error 3");

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.ValidationResult.failure(errors);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).hasSize(3);
            assertThat(result.getErrors()).containsExactly("Error 1", "Error 2", "Error 3");
            assertThat(result.getErrorMessage()).isEqualTo("Error 1; Error 2; Error 3");
        }

        @Test
        @DisplayName("failure(List) should reject empty error list")
        void failureWithEmptyListShouldThrow() {
            // When / Then
            assertThatThrownBy(() -> VoucherValidator.ValidationResult.failure(java.util.Collections.emptyList()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Errors list cannot be empty");
        }

        @Test
        @DisplayName("getErrors() should return unmodifiable list")
        void getErrorsShouldReturnUnmodifiableList() {
            // Given
            VoucherValidator.ValidationResult result = VoucherValidator.ValidationResult.failure("Error");

            // When / Then
            assertThatThrownBy(() -> result.getErrors().add("New error"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("toString() should include status and errors")
        void toStringShouldIncludeStatusAndErrors() {
            // Given
            VoucherValidator.ValidationResult successResult = VoucherValidator.ValidationResult.success();
            VoucherValidator.ValidationResult failureResult = VoucherValidator.ValidationResult.failure("Error");

            // When
            String successString = successResult.toString();
            String failureString = failureResult.toString();

            // Then
            assertThat(successString).contains("valid=true");
            assertThat(failureString).contains("valid=false");
            assertThat(failureString).contains("Error");
        }
    }

    @Nested
    @DisplayName("validate()")
    class ValidateTests {

        @Test
        @DisplayName("should validate a correct voucher")
        void shouldValidateCorrectVoucher() {
            // Given
            SignedVoucher voucher = createValidVoucher();

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validate(voucher);

            // Then
            assertThat(result.isValid()).isTrue();
            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("should reject voucher with invalid signature")
        void shouldRejectVoucherWithInvalidSignature() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] badSignature = new byte[64]; // Invalid signature (all zeros)
            SignedVoucher voucher = new SignedVoucher(secret, badSignature, issuerPublicKeyHex);

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validate(voucher);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).contains("Invalid issuer signature");
        }

        @Test
        @DisplayName("should reject expired voucher")
        void shouldRejectExpiredVoucher() {
            // Given
            Long pastExpiry = Instant.now().minusSeconds(3600).getEpochSecond();
            SignedVoucher voucher = createValidVoucherWithExpiry(pastExpiry);

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validate(voucher);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).contains("Voucher has expired");
        }

        @Test
        @DisplayName("should accept voucher with future expiry")
        void shouldAcceptVoucherWithFutureExpiry() {
            // Given
            Long futureExpiry = Instant.now().plusSeconds(3600).getEpochSecond();
            SignedVoucher voucher = createValidVoucherWithExpiry(futureExpiry);

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validate(voucher);

            // Then
            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("should collect multiple validation errors")
        void shouldCollectMultipleValidationErrors() {
            // Given - Create an expired voucher with invalid signature
            VoucherSecret secret = VoucherSecret.create(
                    ISSUER_ID,
                    UNIT,
                    FACE_VALUE,
                    Instant.now().minusSeconds(3600).getEpochSecond(),
                    null
            );
            byte[] badSignature = new byte[64];
            SignedVoucher voucher = new SignedVoucher(secret, badSignature, issuerPublicKeyHex);

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validate(voucher);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(result.getErrors()).contains("Invalid issuer signature", "Voucher has expired");
        }
    }

    @Nested
    @DisplayName("validateWithIssuer()")
    class ValidateWithIssuerTests {

        @Test
        @DisplayName("should validate voucher with correct issuer")
        void shouldValidateVoucherWithCorrectIssuer() {
            // Given
            SignedVoucher voucher = createValidVoucher();

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validateWithIssuer(voucher, ISSUER_ID);

            // Then
            assertThat(result.isValid()).isTrue();
            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("should reject voucher with wrong issuer")
        void shouldRejectVoucherWithWrongIssuer() {
            // Given
            SignedVoucher voucher = createValidVoucher();
            String wrongIssuerId = "different-merchant";

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validateWithIssuer(voucher, wrongIssuerId);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrorMessage())
                    .contains("issued by")
                    .contains(ISSUER_ID)
                    .contains("expected issuer")
                    .contains(wrongIssuerId);
        }

        @Test
        @DisplayName("should fail early if standard validation fails")
        void shouldFailEarlyIfStandardValidationFails() {
            // Given - Invalid voucher (expired)
            Long pastExpiry = Instant.now().minusSeconds(3600).getEpochSecond();
            SignedVoucher voucher = createValidVoucherWithExpiry(pastExpiry);

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validateWithIssuer(voucher, ISSUER_ID);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).contains("Voucher has expired");
        }
    }

    @Nested
    @DisplayName("validateSignatureOnly()")
    class ValidateSignatureOnlyTests {

        @Test
        @DisplayName("should validate only signature for valid voucher")
        void shouldValidateOnlySignatureForValidVoucher() {
            // Given
            SignedVoucher voucher = createValidVoucher();

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validateSignatureOnly(voucher);

            // Then
            assertThat(result.isValid()).isTrue();
            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("should reject invalid signature")
        void shouldRejectInvalidSignature() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] badSignature = new byte[64];
            SignedVoucher voucher = new SignedVoucher(secret, badSignature, issuerPublicKeyHex);

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validateSignatureOnly(voucher);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).contains("Invalid issuer signature");
        }

        @Test
        @DisplayName("should ignore expiry when validating signature only")
        void shouldIgnoreExpiryWhenValidatingSignatureOnly() {
            // Given - Expired but valid signature
            Long pastExpiry = Instant.now().minusSeconds(3600).getEpochSecond();
            SignedVoucher voucher = createValidVoucherWithExpiry(pastExpiry);

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validateSignatureOnly(voucher);

            // Then
            assertThat(result.isValid()).isTrue();
            assertThat(result.getErrors()).isEmpty();
        }
    }

    @Nested
    @DisplayName("validateExpiryOnly()")
    class ValidateExpiryOnlyTests {

        @Test
        @DisplayName("should validate non-expired voucher")
        void shouldValidateNonExpiredVoucher() {
            // Given
            Long futureExpiry = Instant.now().plusSeconds(3600).getEpochSecond();
            SignedVoucher voucher = createValidVoucherWithExpiry(futureExpiry);

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validateExpiryOnly(voucher);

            // Then
            assertThat(result.isValid()).isTrue();
            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("should validate voucher with no expiry")
        void shouldValidateVoucherWithNoExpiry() {
            // Given
            SignedVoucher voucher = createValidVoucher();

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validateExpiryOnly(voucher);

            // Then
            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("should reject expired voucher")
        void shouldRejectExpiredVoucher() {
            // Given
            Long pastExpiry = Instant.now().minusSeconds(3600).getEpochSecond();
            SignedVoucher voucher = createValidVoucherWithExpiry(pastExpiry);

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validateExpiryOnly(voucher);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).contains("Voucher has expired");
        }

        @Test
        @DisplayName("should ignore signature when validating expiry only")
        void shouldIgnoreSignatureWhenValidatingExpiryOnly() {
            // Given - Invalid signature but not expired
            VoucherSecret secret = VoucherSecret.create(
                    ISSUER_ID,
                    UNIT,
                    FACE_VALUE,
                    Instant.now().plusSeconds(3600).getEpochSecond(),
                    null
            );
            byte[] badSignature = new byte[64];
            SignedVoucher voucher = new SignedVoucher(secret, badSignature, issuerPublicKeyHex);

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validateExpiryOnly(voucher);

            // Then
            assertThat(result.isValid()).isTrue();
        }
    }

    @Nested
    @DisplayName("isValid()")
    class IsValidTests {

        @Test
        @DisplayName("should return true for valid voucher")
        void shouldReturnTrueForValidVoucher() {
            // Given
            SignedVoucher voucher = createValidVoucher();

            // When / Then
            assertThat(VoucherValidator.isValid(voucher)).isTrue();
        }

        @Test
        @DisplayName("should return false for invalid voucher")
        void shouldReturnFalseForInvalidVoucher() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] badSignature = new byte[64];
            SignedVoucher voucher = new SignedVoucher(secret, badSignature, issuerPublicKeyHex);

            // When / Then
            assertThat(VoucherValidator.isValid(voucher)).isFalse();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("should validate voucher with minimum face value")
        void shouldValidateVoucherWithMinimumFaceValue() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, 1L, null, null);
            SignedVoucher voucher = VoucherSignatureService.createSigned(secret, issuerPrivateKeyHex, issuerPublicKeyHex);

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validate(voucher);

            // Then
            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("should validate voucher with large face value")
        void shouldValidateVoucherWithLargeFaceValue() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, Long.MAX_VALUE, null, null);
            SignedVoucher voucher = VoucherSignatureService.createSigned(secret, issuerPrivateKeyHex, issuerPublicKeyHex);

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validate(voucher);

            // Then
            assertThat(result.isValid()).isTrue();
        }

        @Test
        @DisplayName("should validate voucher with memo")
        void shouldValidateVoucherWithMemo() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, "Test memo");
            SignedVoucher voucher = VoucherSignatureService.createSigned(secret, issuerPrivateKeyHex, issuerPublicKeyHex);

            // When
            VoucherValidator.ValidationResult result = VoucherValidator.validate(voucher);

            // Then
            assertThat(result.isValid()).isTrue();
        }
    }
}
