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
 * Unit tests for {@link SignedVoucher}.
 *
 * <p>This test class verifies:
 * <ul>
 *   <li>Voucher creation with valid signatures</li>
 *   <li>Signature verification</li>
 *   <li>Validity checks (signature + expiry)</li>
 *   <li>Expiry handling</li>
 *   <li>Equality and hashing</li>
 *   <li>Defensive copying</li>
 * </ul>
 */
@DisplayName("SignedVoucher")
class SignedVoucherTest {

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
    @DisplayName("Creation")
    class Creation {

        @Test
        @DisplayName("should create signed voucher with valid parameters")
        void shouldCreateSignedVoucherWithValidParameters() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);

            // When
            SignedVoucher voucher = new SignedVoucher(secret, signature, issuerPublicKeyHex);

            // Then
            assertThat(voucher).isNotNull();
            assertThat(voucher.getSecret()).isEqualTo(secret);
            assertThat(voucher.getIssuerSignature()).isEqualTo(signature);
            assertThat(voucher.getIssuerPublicKey()).isEqualTo(issuerPublicKeyHex);
        }

        @Test
        @DisplayName("should reject null secret")
        void shouldRejectNullSecret() {
            // Given
            byte[] signature = new byte[64];

            // When / Then
            assertThatThrownBy(() -> new SignedVoucher(null, signature, issuerPublicKeyHex))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject null signature")
        void shouldRejectNullSignature() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, null);

            // When / Then
            assertThatThrownBy(() -> new SignedVoucher(secret, null, issuerPublicKeyHex))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject null public key")
        void shouldRejectNullPublicKey() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] signature = new byte[64];

            // When / Then
            assertThatThrownBy(() -> new SignedVoucher(secret, signature, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject blank public key")
        void shouldRejectBlankPublicKey() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] signature = new byte[64];

            // When / Then
            assertThatThrownBy(() -> new SignedVoucher(secret, signature, ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("public key cannot be blank");
        }

        @Test
        @DisplayName("should reject invalid signature length")
        void shouldRejectInvalidSignatureLength() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] invalidSignature = new byte[32]; // Wrong length (should be 64)

            // When / Then
            assertThatThrownBy(() -> new SignedVoucher(secret, invalidSignature, issuerPublicKeyHex))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid signature length")
                    .hasMessageContaining("expected 64 bytes");
        }

        @Test
        @DisplayName("should accept 64-byte signature")
        void shouldAccept64ByteSignature() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] signature = new byte[64]; // Correct length

            // When / Then
            assertThatNoException().isThrownBy(() -> new SignedVoucher(secret, signature, issuerPublicKeyHex));
        }
    }

    @Nested
    @DisplayName("Signature Verification")
    class SignatureVerification {

        @Test
        @DisplayName("should verify valid signature")
        void shouldVerifyValidSignature() {
            // Given
            SignedVoucher voucher = createValidVoucher();

            // When / Then
            assertThat(voucher.verify()).isTrue();
        }

        @Test
        @DisplayName("should reject invalid signature")
        void shouldRejectInvalidSignature() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] invalidSignature = new byte[64]; // All zeros - invalid
            SignedVoucher voucher = new SignedVoucher(secret, invalidSignature, issuerPublicKeyHex);

            // When / Then
            assertThat(voucher.verify()).isFalse();
        }

        @Test
        @DisplayName("should reject signature from wrong key")
        void shouldRejectSignatureFromWrongKey() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, null);

            // Generate another key pair
            SecureRandom random = new SecureRandom();
            byte[] wrongPrivateKeyBytes = new byte[32];
            random.nextBytes(wrongPrivateKeyBytes);
            String wrongPrivateKeyHex = Hex.toHexString(wrongPrivateKeyBytes);

            // Sign with one key, verify with another
            byte[] signature = VoucherSignatureService.sign(secret, wrongPrivateKeyHex);
            SignedVoucher voucher = new SignedVoucher(secret, signature, issuerPublicKeyHex);

            // When / Then
            assertThat(voucher.verify()).isFalse();
        }

        @Test
        @DisplayName("should detect modified voucher secret")
        void shouldDetectModifiedVoucherSecret() {
            // Given
            VoucherSecret originalSecret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] signature = VoucherSignatureService.sign(originalSecret, issuerPrivateKeyHex);

            // Create a different secret with modified face value
            VoucherSecret modifiedSecret = VoucherSecret.create(
                    originalSecret.getVoucherId(), // Same ID
                    ISSUER_ID,
                    UNIT,
                    FACE_VALUE + 1000, // Modified value
                    null,
                    null
            );

            SignedVoucher voucher = new SignedVoucher(modifiedSecret, signature, issuerPublicKeyHex);

            // When / Then
            assertThat(voucher.verify()).isFalse();
        }
    }

    @Nested
    @DisplayName("Expiry")
    class Expiry {

        @Test
        @DisplayName("should report not expired when no expiry set")
        void shouldReportNotExpiredWhenNoExpirySet() {
            // Given
            SignedVoucher voucher = createValidVoucher();

            // When / Then
            assertThat(voucher.isExpired()).isFalse();
        }

        @Test
        @DisplayName("should report not expired when expiry is in future")
        void shouldReportNotExpiredWhenExpiryIsInFuture() {
            // Given
            Long futureExpiry = Instant.now().plusSeconds(3600).getEpochSecond();
            SignedVoucher voucher = createValidVoucherWithExpiry(futureExpiry);

            // When / Then
            assertThat(voucher.isExpired()).isFalse();
        }

        @Test
        @DisplayName("should report expired when expiry is in past")
        void shouldReportExpiredWhenExpiryIsInPast() {
            // Given
            Long pastExpiry = Instant.now().minusSeconds(3600).getEpochSecond();
            SignedVoucher voucher = createValidVoucherWithExpiry(pastExpiry);

            // When / Then
            assertThat(voucher.isExpired()).isTrue();
        }
    }

    @Nested
    @DisplayName("Validity")
    class Validity {

        @Test
        @DisplayName("should be valid when signature is correct and not expired")
        void shouldBeValidWhenSignatureCorrectAndNotExpired() {
            // Given
            SignedVoucher voucher = createValidVoucher();

            // When / Then
            assertThat(voucher.isValid()).isTrue();
        }

        @Test
        @DisplayName("should be invalid when signature is incorrect")
        void shouldBeInvalidWhenSignatureIncorrect() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] invalidSignature = new byte[64];
            SignedVoucher voucher = new SignedVoucher(secret, invalidSignature, issuerPublicKeyHex);

            // When / Then
            assertThat(voucher.isValid()).isFalse();
        }

        @Test
        @DisplayName("should be invalid when expired even with valid signature")
        void shouldBeInvalidWhenExpiredEvenWithValidSignature() {
            // Given
            Long pastExpiry = Instant.now().minusSeconds(3600).getEpochSecond();
            SignedVoucher voucher = createValidVoucherWithExpiry(pastExpiry);

            // When / Then
            assertThat(voucher.verify()).isTrue(); // Signature is valid
            assertThat(voucher.isExpired()).isTrue(); // But expired
            assertThat(voucher.isValid()).isFalse(); // So overall invalid
        }

        @Test
        @DisplayName("should be valid with future expiry and valid signature")
        void shouldBeValidWithFutureExpiryAndValidSignature() {
            // Given
            Long futureExpiry = Instant.now().plusSeconds(3600).getEpochSecond();
            SignedVoucher voucher = createValidVoucherWithExpiry(futureExpiry);

            // When / Then
            assertThat(voucher.verify()).isTrue();
            assertThat(voucher.isExpired()).isFalse();
            assertThat(voucher.isValid()).isTrue();
        }
    }

    @Nested
    @DisplayName("Defensive Copying")
    class DefensiveCopying {

        @Test
        @DisplayName("should return defensive copy of signature")
        void shouldReturnDefensiveCopyOfSignature() {
            // Given
            SignedVoucher voucher = createValidVoucher();

            // When
            byte[] signature1 = voucher.getIssuerSignature();
            byte[] signature2 = voucher.getIssuerSignature();

            // Then
            assertThat(signature1).isNotSameAs(signature2); // Different array instances
            assertThat(signature1).isEqualTo(signature2); // But same content
        }

        @Test
        @DisplayName("should not allow modification of signature through getter")
        void shouldNotAllowModificationOfSignatureThroughGetter() {
            // Given
            SignedVoucher voucher = createValidVoucher();
            byte[] originalSignature = voucher.getIssuerSignature().clone();

            // When - Modify the returned array
            byte[] signature = voucher.getIssuerSignature();
            signature[0] = (byte) ~signature[0]; // Flip bits

            // Then - Original signature should be unchanged
            byte[] unchangedSignature = voucher.getIssuerSignature();
            assertThat(unchangedSignature).isEqualTo(originalSignature);
        }

        @Test
        @DisplayName("should not be affected by modifications to constructor signature parameter")
        void shouldNotBeAffectedByModificationsToConstructorSignatureParameter() {
            // Given
            VoucherSecret secret = VoucherSecret.create(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);
            byte[] originalSignature = signature.clone();

            // When
            SignedVoucher voucher = new SignedVoucher(secret, signature, issuerPublicKeyHex);
            signature[0] = (byte) ~signature[0]; // Modify the original array

            // Then
            assertThat(voucher.getIssuerSignature()).isEqualTo(originalSignature);
        }
    }

    @Nested
    @DisplayName("Equality and Hashing")
    class EqualityAndHashing {

        @Test
        @DisplayName("should be equal when all fields match")
        void shouldBeEqualWhenAllFieldsMatch() {
            // Given
            VoucherSecret secret = VoucherSecret.create("test-id", ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);

            SignedVoucher voucher1 = new SignedVoucher(secret, signature.clone(), issuerPublicKeyHex);
            SignedVoucher voucher2 = new SignedVoucher(secret, signature.clone(), issuerPublicKeyHex);

            // When / Then
            assertThat(voucher1).isEqualTo(voucher2);
            assertThat(voucher1.hashCode()).isEqualTo(voucher2.hashCode());
        }

        @Test
        @DisplayName("should not be equal when secrets differ")
        void shouldNotBeEqualWhenSecretsDiffer() {
            // Given
            VoucherSecret secret1 = VoucherSecret.create("id1", ISSUER_ID, UNIT, FACE_VALUE, null, null);
            VoucherSecret secret2 = VoucherSecret.create("id2", ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] signature = VoucherSignatureService.sign(secret1, issuerPrivateKeyHex);

            SignedVoucher voucher1 = new SignedVoucher(secret1, signature, issuerPublicKeyHex);
            SignedVoucher voucher2 = new SignedVoucher(secret2, signature, issuerPublicKeyHex);

            // When / Then
            assertThat(voucher1).isNotEqualTo(voucher2);
        }

        @Test
        @DisplayName("should not be equal when signatures differ")
        void shouldNotBeEqualWhenSignaturesDiffer() {
            // Given
            VoucherSecret secret = VoucherSecret.create("test-id", ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] signature1 = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);
            byte[] signature2 = signature1.clone();
            signature2[0] = (byte) ~signature2[0]; // Modify one byte

            SignedVoucher voucher1 = new SignedVoucher(secret, signature1, issuerPublicKeyHex);
            SignedVoucher voucher2 = new SignedVoucher(secret, signature2, issuerPublicKeyHex);

            // When / Then
            assertThat(voucher1).isNotEqualTo(voucher2);
        }

        @Test
        @DisplayName("should not be equal when public keys differ")
        void shouldNotBeEqualWhenPublicKeysDiffer() {
            // Given
            VoucherSecret secret = VoucherSecret.create("test-id", ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);
            String differentPublicKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

            SignedVoucher voucher1 = new SignedVoucher(secret, signature, issuerPublicKeyHex);
            SignedVoucher voucher2 = new SignedVoucher(secret, signature, differentPublicKey);

            // When / Then
            assertThat(voucher1).isNotEqualTo(voucher2);
        }

        @Test
        @DisplayName("should be reflexive (equals itself)")
        void shouldBeReflexive() {
            // Given
            SignedVoucher voucher = createValidVoucher();

            // When / Then
            assertThat(voucher).isEqualTo(voucher);
        }

        @Test
        @DisplayName("should not equal null")
        void shouldNotEqualNull() {
            // Given
            SignedVoucher voucher = createValidVoucher();

            // When / Then
            assertThat(voucher).isNotEqualTo(null);
        }

        @Test
        @DisplayName("should not equal different type")
        void shouldNotEqualDifferentType() {
            // Given
            SignedVoucher voucher = createValidVoucher();

            // When / Then
            assertThat(voucher).isNotEqualTo("not a voucher");
        }
    }

    @Nested
    @DisplayName("String Representations")
    class StringRepresentations {

        @Test
        @DisplayName("toString() should include key information")
        void toStringShouldIncludeKeyInformation() {
            // Given
            SignedVoucher voucher = createValidVoucher();

            // When
            String str = voucher.toString();

            // Then
            assertThat(str).contains("SignedVoucher");
            assertThat(str).contains("voucherId");
            assertThat(str).contains("issuerId");
            assertThat(str).contains("faceValue");
            assertThat(str).contains("unit");
        }

        @Test
        @DisplayName("toStringWithMetadata() should include detailed information")
        void toStringWithMetadataShouldIncludeDetailedInformation() {
            // Given
            SignedVoucher voucher = createValidVoucher();

            // When
            String str = voucher.toStringWithMetadata();

            // Then
            assertThat(str).contains("SignedVoucher");
            assertThat(str).contains("secret=");
            assertThat(str).contains("signatureValid");
            assertThat(str).contains("issuerPublicKey");
        }
    }
}
