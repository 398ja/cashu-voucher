package xyz.tcheeric.cashu.voucher.domain;

import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.VoucherSecret;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link VoucherSignatureService}.
 *
 * <p>This test class verifies:
 * <ul>
 *   <li>secp256k1/Schnorr signature generation</li>
 *   <li>Signature verification</li>
 *   <li>Key validation</li>
 *   <li>Error handling for invalid inputs</li>
 *   <li>Signature verifiability</li>
 *   <li>Cross-verification between sign and verify</li>
 * </ul>
 */
@DisplayName("VoucherSignatureService")
class VoucherSignatureServiceTest {

    private static String issuerPrivateKeyHex;
    private static String issuerPublicKeyHex;
    private static final String ISSUER_ID = "merchant123";
    private static final String UNIT = "EUR";
    private static final long FACE_VALUE = 1000L; // €10.00 in cents
    private static final BackingStrategy DEFAULT_STRATEGY = BackingStrategy.PROPORTIONAL;
    private static final double DEFAULT_ISSUANCE_RATIO = 0.01;
    private static final int DEFAULT_FACE_DECIMALS = 2;

    @BeforeAll
    static void setupKeys() {
        // Generate a test secp256k1 key pair (Schnorr/Nostr compatible)
        byte[] privateKeyBytes = Schnorr.generatePrivateKey();
        byte[] publicKeyBytes = Schnorr.genPubKey(privateKeyBytes);

        issuerPrivateKeyHex = Hex.toHexString(privateKeyBytes);
        issuerPublicKeyHex = Hex.toHexString(publicKeyBytes);
    }

    /**
     * Helper method to create a VoucherSecret with default backing strategy parameters.
     */
    private VoucherSecret createVoucherSecret(String issuerId, String unit, long faceValue,
                                              Long expiresAt, String memo) {
        return VoucherSecret.builder()
                .issuerId(issuerId)
                .unit(unit)
                .faceValue(faceValue)
                .expiresAt(expiresAt)
                .memo(memo)
                .backingStrategy(DEFAULT_STRATEGY.name())
                .issuanceRatio(DEFAULT_ISSUANCE_RATIO)
                .faceDecimals(DEFAULT_FACE_DECIMALS)
                .build();
    }

    /**
     * Helper method to create a VoucherSecret with specified voucher ID.
     * Converts arbitrary strings to deterministic UUIDs for testing.
     */
    private VoucherSecret createVoucherSecretWithId(String voucherId, String issuerId, String unit,
                                                    long faceValue, Long expiresAt, String memo) {
        UUID id = UUID.nameUUIDFromBytes(voucherId.getBytes());
        return VoucherSecret.builder()
                .voucherId(id)
                .issuerId(issuerId)
                .unit(unit)
                .faceValue(faceValue)
                .expiresAt(expiresAt)
                .memo(memo)
                .backingStrategy(DEFAULT_STRATEGY.name())
                .issuanceRatio(DEFAULT_ISSUANCE_RATIO)
                .faceDecimals(DEFAULT_FACE_DECIMALS)
                .build();
    }

    @Nested
    @DisplayName("sign()")
    class SignTests {

        /**
         * Tests that a 64-byte Schnorr signature is generated.
         */
        @Test
        @DisplayName("should generate 64-byte signature")
        void shouldGenerate64ByteSignature() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);

            // When
            byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);

            // Then
            assertThat(signature).isNotNull();
            assertThat(signature).hasSize(64);
        }

        /**
         * Tests that multiple signatures of the same input are all verifiable.
         * Note: BIP-340 Schnorr signatures use auxiliary randomness, so signatures
         * won't be identical, but all should verify correctly.
         */
        @Test
        @DisplayName("should generate verifiable signatures for same input")
        void shouldGenerateVerifiableSignaturesForSameInput() {
            // Given
            VoucherSecret secret = createVoucherSecretWithId("fixed-id", ISSUER_ID, UNIT, FACE_VALUE, null, null);

            // When
            byte[] signature1 = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);
            byte[] signature2 = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);

            // Then - both signatures should verify correctly
            assertThat(VoucherSignatureService.verify(secret, signature1, issuerPublicKeyHex)).isTrue();
            assertThat(VoucherSignatureService.verify(secret, signature2, issuerPublicKeyHex)).isTrue();
        }

        /**
         * Tests that different secrets produce different signatures.
         */
        @Test
        @DisplayName("should generate different signatures for different secrets")
        void shouldGenerateDifferentSignaturesForDifferentSecrets() {
            // Given
            VoucherSecret secret1 = createVoucherSecretWithId("id1", ISSUER_ID, UNIT, FACE_VALUE, null, null);
            VoucherSecret secret2 = createVoucherSecretWithId("id2", ISSUER_ID, UNIT, FACE_VALUE, null, null);

            // When
            byte[] signature1 = VoucherSignatureService.sign(secret1, issuerPrivateKeyHex);
            byte[] signature2 = VoucherSignatureService.sign(secret2, issuerPrivateKeyHex);

            // Then
            assertThat(signature1).isNotEqualTo(signature2);
        }

        /**
         * Tests that null secret is rejected.
         */
        @Test
        @DisplayName("should reject null secret")
        void shouldRejectNullSecret() {
            // When / Then
            assertThatThrownBy(() -> VoucherSignatureService.sign(null, issuerPrivateKeyHex))
                    .isInstanceOf(NullPointerException.class);
        }

        /**
         * Tests that null private key is rejected.
         */
        @Test
        @DisplayName("should reject null private key")
        void shouldRejectNullPrivateKey() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);

            // When / Then
            assertThatThrownBy(() -> VoucherSignatureService.sign(secret, null))
                    .isInstanceOf(NullPointerException.class);
        }

        /**
         * Tests that invalid private key length is rejected.
         */
        @Test
        @DisplayName("should reject invalid private key length")
        void shouldRejectInvalidPrivateKeyLength() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            String shortKey = "0123456789abcdef"; // Too short

            // When / Then
            assertThatThrownBy(() -> VoucherSignatureService.sign(secret, shortKey))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid private key length");
        }

        /**
         * Tests that non-hex private key is rejected.
         */
        @Test
        @DisplayName("should reject non-hex private key")
        void shouldRejectNonHexPrivateKey() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            String invalidKey = "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ";

            // When / Then
            assertThatThrownBy(() -> VoucherSignatureService.sign(secret, invalidKey))
                    .isInstanceOf(Exception.class); // DecoderException wrapped in IllegalArgumentException
        }

        /**
         * Tests that voucher with expiry can be signed.
         */
        @Test
        @DisplayName("should handle voucher with expiry")
        void shouldHandleVoucherWithExpiry() {
            // Given
            Long expiresAt = Instant.now().plusSeconds(3600).getEpochSecond();
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, expiresAt, null);

            // When
            byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);

            // Then
            assertThat(signature).isNotNull();
            assertThat(signature).hasSize(64);
        }

        /**
         * Tests that voucher with memo can be signed.
         */
        @Test
        @DisplayName("should handle voucher with memo")
        void shouldHandleVoucherWithMemo() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, "Test memo");

            // When
            byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);

            // Then
            assertThat(signature).isNotNull();
            assertThat(signature).hasSize(64);
        }
    }

    @Nested
    @DisplayName("verify()")
    class VerifyTests {

        /**
         * Tests that a valid signature is verified successfully.
         */
        @Test
        @DisplayName("should verify valid signature")
        void shouldVerifyValidSignature() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);

            // When
            boolean valid = VoucherSignatureService.verify(secret, signature, issuerPublicKeyHex);

            // Then
            assertThat(valid).isTrue();
        }

        /**
         * Tests that an invalid signature is rejected.
         */
        @Test
        @DisplayName("should reject invalid signature")
        void shouldRejectInvalidSignature() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] invalidSignature = new byte[64]; // All zeros

            // When
            boolean valid = VoucherSignatureService.verify(secret, invalidSignature, issuerPublicKeyHex);

            // Then
            assertThat(valid).isFalse();
        }

        /**
         * Tests that a modified secret is rejected.
         */
        @Test
        @DisplayName("should reject modified secret")
        void shouldRejectModifiedSecret() {
            // Given
            VoucherSecret originalSecret = createVoucherSecretWithId("test-id", ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] signature = VoucherSignatureService.sign(originalSecret, issuerPrivateKeyHex);

            // Create modified secret with same ID but different face value
            VoucherSecret modifiedSecret = VoucherSecret.builder()
                    .voucherId(UUID.nameUUIDFromBytes("test-id".getBytes()))
                    .issuerId(ISSUER_ID)
                    .unit(UNIT)
                    .faceValue(FACE_VALUE + 1000) // Modified
                    .backingStrategy(DEFAULT_STRATEGY.name())
                    .issuanceRatio(DEFAULT_ISSUANCE_RATIO)
                    .faceDecimals(DEFAULT_FACE_DECIMALS)
                    .build();

            // When
            boolean valid = VoucherSignatureService.verify(modifiedSecret, signature, issuerPublicKeyHex);

            // Then
            assertThat(valid).isFalse();
        }

        /**
         * Tests that a signature from the wrong key is rejected.
         */
        @Test
        @DisplayName("should reject signature from wrong key")
        void shouldRejectSignatureFromWrongKey() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);

            // Generate different secp256k1 key pair
            byte[] wrongPrivateKeyBytes = Schnorr.generatePrivateKey();
            byte[] wrongPublicKeyBytes = Schnorr.genPubKey(wrongPrivateKeyBytes);
            String wrongPrivateKeyHex = Hex.toHexString(wrongPrivateKeyBytes);
            String wrongPublicKeyHex = Hex.toHexString(wrongPublicKeyBytes);

            // Sign with wrong key
            byte[] signature = VoucherSignatureService.sign(secret, wrongPrivateKeyHex);

            // When - Verify with correct public key (not the one that signed)
            boolean valid = VoucherSignatureService.verify(secret, signature, issuerPublicKeyHex);

            // Then
            assertThat(valid).isFalse();
        }

        /**
         * Tests that null secret is rejected.
         */
        @Test
        @DisplayName("should reject null secret")
        void shouldRejectNullSecret() {
            // Given
            byte[] signature = new byte[64];

            // When / Then
            assertThatThrownBy(() -> VoucherSignatureService.verify(null, signature, issuerPublicKeyHex))
                    .isInstanceOf(NullPointerException.class);
        }

        /**
         * Tests that null signature is rejected.
         */
        @Test
        @DisplayName("should reject null signature")
        void shouldRejectNullSignature() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);

            // When / Then
            assertThatThrownBy(() -> VoucherSignatureService.verify(secret, null, issuerPublicKeyHex))
                    .isInstanceOf(NullPointerException.class);
        }

        /**
         * Tests that null public key is rejected.
         */
        @Test
        @DisplayName("should reject null public key")
        void shouldRejectNullPublicKey() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] signature = new byte[64];

            // When / Then
            assertThatThrownBy(() -> VoucherSignatureService.verify(secret, signature, null))
                    .isInstanceOf(NullPointerException.class);
        }

        /**
         * Tests that invalid signature length returns false.
         */
        @Test
        @DisplayName("should return false for invalid signature length")
        void shouldReturnFalseForInvalidSignatureLength() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] shortSignature = new byte[32]; // Wrong length

            // When
            boolean valid = VoucherSignatureService.verify(secret, shortSignature, issuerPublicKeyHex);

            // Then
            assertThat(valid).isFalse();
        }

        /**
         * Tests that invalid public key length returns false.
         */
        @Test
        @DisplayName("should return false for invalid public key length")
        void shouldReturnFalseForInvalidPublicKeyLength() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);
            String shortPublicKey = "0123456789abcdef"; // Too short

            // When
            boolean valid = VoucherSignatureService.verify(secret, signature, shortPublicKey);

            // Then
            assertThat(valid).isFalse();
        }

        /**
         * Tests that malformed public key hex returns false.
         */
        @Test
        @DisplayName("should return false for malformed public key hex")
        void shouldReturnFalseForMalformedPublicKeyHex() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);
            byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);
            String malformedKey = "GGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGG";

            // When
            boolean valid = VoucherSignatureService.verify(secret, signature, malformedKey);

            // Then
            assertThat(valid).isFalse();
        }
    }

    @Nested
    @DisplayName("createSigned()")
    class CreateSignedTests {

        /**
         * Tests that a valid signed voucher is created.
         */
        @Test
        @DisplayName("should create valid signed voucher")
        void shouldCreateValidSignedVoucher() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);

            // When
            SignedVoucher signedVoucher = VoucherSignatureService.createSigned(
                    secret,
                    issuerPrivateKeyHex,
                    issuerPublicKeyHex
            );

            // Then
            assertThat(signedVoucher).isNotNull();
            assertThat(signedVoucher.getSecret()).isEqualTo(secret);
            assertThat(signedVoucher.getIssuerPublicKey()).isEqualTo(issuerPublicKeyHex);
            assertThat(signedVoucher.verify()).isTrue();
        }

        /**
         * Tests that signed voucher has 64-byte signature.
         */
        @Test
        @DisplayName("should create signed voucher with 64-byte signature")
        void shouldCreateSignedVoucherWith64ByteSignature() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);

            // When
            SignedVoucher signedVoucher = VoucherSignatureService.createSigned(
                    secret,
                    issuerPrivateKeyHex,
                    issuerPublicKeyHex
            );

            // Then
            assertThat(signedVoucher.getIssuerSignature()).hasSize(64);
        }

        /**
         * Tests that null secret is rejected.
         */
        @Test
        @DisplayName("should reject null secret")
        void shouldRejectNullSecret() {
            // When / Then
            assertThatThrownBy(() -> VoucherSignatureService.createSigned(
                    null,
                    issuerPrivateKeyHex,
                    issuerPublicKeyHex
            )).isInstanceOf(NullPointerException.class);
        }

        /**
         * Tests that null private key is rejected.
         */
        @Test
        @DisplayName("should reject null private key")
        void shouldRejectNullPrivateKey() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);

            // When / Then
            assertThatThrownBy(() -> VoucherSignatureService.createSigned(
                    secret,
                    null,
                    issuerPublicKeyHex
            )).isInstanceOf(NullPointerException.class);
        }

        /**
         * Tests that null public key is rejected.
         */
        @Test
        @DisplayName("should reject null public key")
        void shouldRejectNullPublicKey() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);

            // When / Then
            assertThatThrownBy(() -> VoucherSignatureService.createSigned(
                    secret,
                    issuerPrivateKeyHex,
                    null
            )).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Cross-Verification")
    class CrossVerification {

        /**
         * Tests that signed voucher verifies with same key pair.
         */
        @Test
        @DisplayName("signed voucher should verify with same key pair")
        void signedVoucherShouldVerifyWithSameKeyPair() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, null, null);

            // When
            byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);
            boolean valid = VoucherSignatureService.verify(secret, signature, issuerPublicKeyHex);

            // Then
            assertThat(valid).isTrue();
        }

        /**
         * Tests that multiple vouchers with different secrets all verify.
         */
        @Test
        @DisplayName("multiple vouchers with different secrets should all verify")
        void multipleVouchersWithDifferentSecretsShouldAllVerify() {
            // Given
            VoucherSecret secret1 = createVoucherSecretWithId("id1", ISSUER_ID, UNIT, 1000L, null, null);
            VoucherSecret secret2 = createVoucherSecretWithId("id2", ISSUER_ID, UNIT, 2000L, null, null);
            VoucherSecret secret3 = createVoucherSecretWithId("id3", ISSUER_ID, UNIT, 3000L, null, null);

            // When
            byte[] signature1 = VoucherSignatureService.sign(secret1, issuerPrivateKeyHex);
            byte[] signature2 = VoucherSignatureService.sign(secret2, issuerPrivateKeyHex);
            byte[] signature3 = VoucherSignatureService.sign(secret3, issuerPrivateKeyHex);

            // Then
            assertThat(VoucherSignatureService.verify(secret1, signature1, issuerPublicKeyHex)).isTrue();
            assertThat(VoucherSignatureService.verify(secret2, signature2, issuerPublicKeyHex)).isTrue();
            assertThat(VoucherSignatureService.verify(secret3, signature3, issuerPublicKeyHex)).isTrue();
        }

        /**
         * Tests that signatures are not interchangeable between vouchers.
         */
        @Test
        @DisplayName("signatures should not be interchangeable between vouchers")
        void signaturesShouldNotBeInterchangeableBetweenVouchers() {
            // Given
            VoucherSecret secret1 = createVoucherSecretWithId("id1", ISSUER_ID, UNIT, FACE_VALUE, null, null);
            VoucherSecret secret2 = createVoucherSecretWithId("id2", ISSUER_ID, UNIT, FACE_VALUE, null, null);

            byte[] signature1 = VoucherSignatureService.sign(secret1, issuerPrivateKeyHex);
            byte[] signature2 = VoucherSignatureService.sign(secret2, issuerPrivateKeyHex);

            // When / Then - Signature1 should not verify secret2
            assertThat(VoucherSignatureService.verify(secret2, signature1, issuerPublicKeyHex)).isFalse();
            // Signature2 should not verify secret1
            assertThat(VoucherSignatureService.verify(secret1, signature2, issuerPublicKeyHex)).isFalse();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        /**
         * Tests that voucher with all optional fields can be signed and verified.
         */
        @Test
        @DisplayName("should handle voucher with all optional fields")
        void shouldHandleVoucherWithAllOptionalFields() {
            // Given
            Long expiresAt = Instant.now().plusSeconds(3600).getEpochSecond();
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, FACE_VALUE, expiresAt, "Test memo");

            // When
            byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);
            boolean valid = VoucherSignatureService.verify(secret, signature, issuerPublicKeyHex);

            // Then
            assertThat(valid).isTrue();
        }

        /**
         * Tests that voucher with minimum face value can be signed and verified.
         */
        @Test
        @DisplayName("should handle voucher with minimum face value")
        void shouldHandleVoucherWithMinimumFaceValue() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, 1L, null, null);

            // When
            byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);
            boolean valid = VoucherSignatureService.verify(secret, signature, issuerPublicKeyHex);

            // Then
            assertThat(valid).isTrue();
        }

        /**
         * Tests that voucher with maximum face value can be signed and verified.
         */
        @Test
        @DisplayName("should handle voucher with maximum face value")
        void shouldHandleVoucherWithMaximumFaceValue() {
            // Given
            VoucherSecret secret = createVoucherSecret(ISSUER_ID, UNIT, Long.MAX_VALUE, null, null);

            // When
            byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);
            boolean valid = VoucherSignatureService.verify(secret, signature, issuerPublicKeyHex);

            // Then
            assertThat(valid).isTrue();
        }

        /**
         * Tests that different units produce different signatures.
         */
        @Test
        @DisplayName("should handle different units")
        void shouldHandleDifferentUnits() {
            // Given
            VoucherSecret secretSat = createVoucherSecretWithId("id1", ISSUER_ID, "sat", FACE_VALUE, null, null);
            VoucherSecret secretUsd = createVoucherSecretWithId("id2", ISSUER_ID, "usd", FACE_VALUE, null, null);

            // When
            byte[] signatureSat = VoucherSignatureService.sign(secretSat, issuerPrivateKeyHex);
            byte[] signatureUsd = VoucherSignatureService.sign(secretUsd, issuerPrivateKeyHex);

            // Then
            assertThat(VoucherSignatureService.verify(secretSat, signatureSat, issuerPublicKeyHex)).isTrue();
            assertThat(VoucherSignatureService.verify(secretUsd, signatureUsd, issuerPublicKeyHex)).isTrue();
            // Different units should produce different signatures
            assertThat(signatureSat).isNotEqualTo(signatureUsd);
        }

        /**
         * Tests that different backing strategies produce different signatures.
         */
        @Test
        @DisplayName("should handle different backing strategies")
        void shouldHandleDifferentBackingStrategies() {
            // Given
            VoucherSecret secretFixed = VoucherSecret.builder()
                    .issuerId(ISSUER_ID)
                    .unit(UNIT)
                    .faceValue(FACE_VALUE)
                    .backingStrategy(BackingStrategy.FIXED.name())
                    .issuanceRatio(DEFAULT_ISSUANCE_RATIO)
                    .faceDecimals(DEFAULT_FACE_DECIMALS)
                    .build();
            VoucherSecret secretProportional = VoucherSecret.builder()
                    .issuerId(ISSUER_ID)
                    .unit(UNIT)
                    .faceValue(FACE_VALUE)
                    .backingStrategy(BackingStrategy.PROPORTIONAL.name())
                    .issuanceRatio(DEFAULT_ISSUANCE_RATIO)
                    .faceDecimals(DEFAULT_FACE_DECIMALS)
                    .build();

            // When
            byte[] signatureFixed = VoucherSignatureService.sign(secretFixed, issuerPrivateKeyHex);
            byte[] signatureProportional = VoucherSignatureService.sign(secretProportional, issuerPrivateKeyHex);

            // Then
            assertThat(VoucherSignatureService.verify(secretFixed, signatureFixed, issuerPublicKeyHex)).isTrue();
            assertThat(VoucherSignatureService.verify(secretProportional, signatureProportional, issuerPublicKeyHex)).isTrue();
            // Different strategies should produce different signatures
            assertThat(signatureFixed).isNotEqualTo(signatureProportional);
        }

        /**
         * Tests that voucher with merchant metadata can be signed and verified.
         */
        @Test
        @DisplayName("should handle voucher with merchant metadata")
        void shouldHandleVoucherWithMerchantMetadata() {
            // Given
            VoucherSecret secret = VoucherSecret.builder()
                    .issuerId(ISSUER_ID)
                    .unit(UNIT)
                    .faceValue(FACE_VALUE)
                    .backingStrategy(DEFAULT_STRATEGY.name())
                    .issuanceRatio(DEFAULT_ISSUANCE_RATIO)
                    .faceDecimals(DEFAULT_FACE_DECIMALS)
                    .merchantMetadata("{\"event\":\"Concert\",\"seat\":\"A12\"}")
                    .build();

            // When
            byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);
            boolean valid = VoucherSignatureService.verify(secret, signature, issuerPublicKeyHex);

            // Then
            assertThat(valid).isTrue();
        }
    }
}
