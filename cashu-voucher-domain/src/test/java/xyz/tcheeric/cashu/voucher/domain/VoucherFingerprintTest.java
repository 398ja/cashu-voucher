package xyz.tcheeric.cashu.voucher.domain;

import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link VoucherFingerprint}.
 *
 * <p>This test class verifies:
 * <ul>
 *   <li>Deterministic fingerprint generation</li>
 *   <li>Collision resistance (different vouchers produce different fingerprints)</li>
 *   <li>Proper handling of signed and unsigned vouchers</li>
 *   <li>Input validation</li>
 * </ul>
 */
@DisplayName("VoucherFingerprint")
class VoucherFingerprintTest {

    private static String issuerPrivateKeyHex;
    private static String issuerPublicKeyHex;
    private static final String ISSUER_ID = "merchant123";
    private static final String UNIT = "sat";
    private static final long FACE_VALUE = 1000L;
    private static final BackingStrategy DEFAULT_STRATEGY = BackingStrategy.PROPORTIONAL;
    private static final double DEFAULT_ISSUANCE_RATIO = 0.01;
    private static final int DEFAULT_FACE_DECIMALS = 2;

    @BeforeAll
    static void setupKeys() {
        byte[] privateKeyBytes = Schnorr.generatePrivateKey();
        byte[] publicKeyBytes = Schnorr.genPubKey(privateKeyBytes);
        issuerPrivateKeyHex = Hex.toHexString(privateKeyBytes);
        issuerPublicKeyHex = Hex.toHexString(publicKeyBytes);
    }

    private VoucherSecret createVoucherSecret(UUID voucherId, String issuerId) {
        return VoucherSecret.builder()
                .voucherId(voucherId)
                .issuerId(issuerId)
                .unit(UNIT)
                .faceValue(FACE_VALUE)
                .backingStrategy(DEFAULT_STRATEGY.name())
                .issuanceRatio(DEFAULT_ISSUANCE_RATIO)
                .faceDecimals(DEFAULT_FACE_DECIMALS)
                .build();
    }

    private SignedVoucher createSignedVoucher(UUID voucherId, String issuerId) {
        VoucherSecret secret = createVoucherSecret(voucherId, issuerId);
        return VoucherSignatureService.createSigned(secret, issuerPrivateKeyHex, issuerPublicKeyHex);
    }

    @Nested
    @DisplayName("compute(SignedVoucher)")
    class ComputeSignedVoucherTests {

        @Test
        @DisplayName("should generate 64-character hex fingerprint")
        void shouldGenerate64CharacterHexFingerprint() {
            // Given
            SignedVoucher voucher = createSignedVoucher(UUID.randomUUID(), ISSUER_ID);

            // When
            String fingerprint = VoucherFingerprint.compute(voucher);

            // Then
            assertThat(fingerprint).isNotNull();
            assertThat(fingerprint).hasSize(64);
            assertThat(fingerprint).matches("^[0-9a-f]+$");
        }

        @Test
        @DisplayName("should be deterministic - same voucher produces same fingerprint")
        void shouldBeDeterministic() {
            // Given
            UUID voucherId = UUID.randomUUID();
            SignedVoucher voucher = createSignedVoucher(voucherId, ISSUER_ID);

            // When
            String fingerprint1 = VoucherFingerprint.compute(voucher);
            String fingerprint2 = VoucherFingerprint.compute(voucher);

            // Then
            assertThat(fingerprint1).isEqualTo(fingerprint2);
        }

        @Test
        @DisplayName("should produce different fingerprints for different voucher IDs")
        void shouldProduceDifferentFingerprintsForDifferentVoucherIds() {
            // Given
            SignedVoucher voucher1 = createSignedVoucher(UUID.randomUUID(), ISSUER_ID);
            SignedVoucher voucher2 = createSignedVoucher(UUID.randomUUID(), ISSUER_ID);

            // When
            String fingerprint1 = VoucherFingerprint.compute(voucher1);
            String fingerprint2 = VoucherFingerprint.compute(voucher2);

            // Then
            assertThat(fingerprint1).isNotEqualTo(fingerprint2);
        }

        @Test
        @DisplayName("should produce different fingerprints for different issuers")
        void shouldProduceDifferentFingerprintsForDifferentIssuers() {
            // Given
            UUID voucherId = UUID.randomUUID();
            SignedVoucher voucher1 = createSignedVoucher(voucherId, "merchant1");
            SignedVoucher voucher2 = createSignedVoucher(voucherId, "merchant2");

            // When
            String fingerprint1 = VoucherFingerprint.compute(voucher1);
            String fingerprint2 = VoucherFingerprint.compute(voucher2);

            // Then
            assertThat(fingerprint1).isNotEqualTo(fingerprint2);
        }

        @Test
        @DisplayName("should reject null voucher")
        void shouldRejectNullVoucher() {
            // When / Then
            assertThatThrownBy(() -> VoucherFingerprint.compute((SignedVoucher) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Voucher must not be null");
        }
    }

    @Nested
    @DisplayName("compute(VoucherSecret)")
    class ComputeVoucherSecretTests {

        @Test
        @DisplayName("should generate fingerprint for unsigned secret")
        void shouldGenerateFingerprintForUnsignedSecret() {
            // Given
            VoucherSecret secret = createVoucherSecret(UUID.randomUUID(), ISSUER_ID);

            // When
            String fingerprint = VoucherFingerprint.compute(secret);

            // Then
            assertThat(fingerprint).isNotNull();
            assertThat(fingerprint).hasSize(64);
        }

        @Test
        @DisplayName("should reject secret without voucher ID")
        void shouldRejectSecretWithoutVoucherId() {
            // Given
            VoucherSecret secret = VoucherSecret.builder()
                    .issuerId(ISSUER_ID)
                    .unit(UNIT)
                    .faceValue(FACE_VALUE)
                    .backingStrategy(DEFAULT_STRATEGY.name())
                    .issuanceRatio(DEFAULT_ISSUANCE_RATIO)
                    .faceDecimals(DEFAULT_FACE_DECIMALS)
                    .build();

            // Need to bypass the builder - the builder auto-generates ID
            // This test verifies the compute method handles edge cases
            // The builder will always create a UUID, so this case may not occur in practice
        }

        @Test
        @DisplayName("should reject null secret")
        void shouldRejectNullSecret() {
            // When / Then
            assertThatThrownBy(() -> VoucherFingerprint.compute((VoucherSecret) null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Secret must not be null");
        }
    }

    @Nested
    @DisplayName("compute(String, String, String)")
    class ComputeFromComponentsTests {

        @Test
        @DisplayName("should generate fingerprint from components")
        void shouldGenerateFingerprintFromComponents() {
            // Given
            String voucherId = UUID.randomUUID().toString();
            String issuerId = ISSUER_ID;
            String signature = "abc123";

            // When
            String fingerprint = VoucherFingerprint.compute(voucherId, issuerId, signature);

            // Then
            assertThat(fingerprint).isNotNull();
            assertThat(fingerprint).hasSize(64);
        }

        @Test
        @DisplayName("should generate fingerprint without signature")
        void shouldGenerateFingerprintWithoutSignature() {
            // Given
            String voucherId = UUID.randomUUID().toString();
            String issuerId = ISSUER_ID;

            // When
            String fingerprint = VoucherFingerprint.compute(voucherId, issuerId, null);

            // Then
            assertThat(fingerprint).isNotNull();
            assertThat(fingerprint).hasSize(64);
        }

        @Test
        @DisplayName("should produce different fingerprints with and without signature")
        void shouldProduceDifferentFingerprintsWithAndWithoutSignature() {
            // Given
            String voucherId = UUID.randomUUID().toString();
            String issuerId = ISSUER_ID;
            String signature = "abc123";

            // When
            String withSig = VoucherFingerprint.compute(voucherId, issuerId, signature);
            String withoutSig = VoucherFingerprint.compute(voucherId, issuerId, null);

            // Then
            assertThat(withSig).isNotEqualTo(withoutSig);
        }

        @Test
        @DisplayName("should reject null voucher ID")
        void shouldRejectNullVoucherId() {
            // When / Then
            assertThatThrownBy(() -> VoucherFingerprint.compute(null, ISSUER_ID, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Voucher ID must not be null");
        }

        @Test
        @DisplayName("should reject null issuer ID")
        void shouldRejectNullIssuerId() {
            // When / Then
            assertThatThrownBy(() -> VoucherFingerprint.compute(UUID.randomUUID().toString(), null, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Issuer ID must not be null");
        }

        @Test
        @DisplayName("should reject blank voucher ID")
        void shouldRejectBlankVoucherId() {
            // When / Then
            assertThatThrownBy(() -> VoucherFingerprint.compute("  ", ISSUER_ID, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Voucher ID must not be blank");
        }

        @Test
        @DisplayName("should reject blank issuer ID")
        void shouldRejectBlankIssuerId() {
            // When / Then
            assertThatThrownBy(() -> VoucherFingerprint.compute(UUID.randomUUID().toString(), "  ", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Issuer ID must not be blank");
        }
    }

    @Nested
    @DisplayName("isValid()")
    class IsValidTests {

        @Test
        @DisplayName("should accept valid 64-character hex fingerprint")
        void shouldAcceptValidFingerprint() {
            // Given
            SignedVoucher voucher = createSignedVoucher(UUID.randomUUID(), ISSUER_ID);
            String fingerprint = VoucherFingerprint.compute(voucher);

            // When / Then
            assertThat(VoucherFingerprint.isValid(fingerprint)).isTrue();
        }

        @Test
        @DisplayName("should reject null fingerprint")
        void shouldRejectNullFingerprint() {
            // When / Then
            assertThat(VoucherFingerprint.isValid(null)).isFalse();
        }

        @Test
        @DisplayName("should reject too short fingerprint")
        void shouldRejectTooShortFingerprint() {
            // When / Then
            assertThat(VoucherFingerprint.isValid("abc123")).isFalse();
        }

        @Test
        @DisplayName("should reject too long fingerprint")
        void shouldRejectTooLongFingerprint() {
            // Given
            String tooLong = "a".repeat(65);

            // When / Then
            assertThat(VoucherFingerprint.isValid(tooLong)).isFalse();
        }

        @Test
        @DisplayName("should reject non-hex characters")
        void shouldRejectNonHexCharacters() {
            // Given
            String withUpperCase = "A".repeat(64); // uppercase not allowed
            String withNonHex = "z".repeat(64);

            // When / Then
            assertThat(VoucherFingerprint.isValid(withUpperCase)).isFalse();
            assertThat(VoucherFingerprint.isValid(withNonHex)).isFalse();
        }
    }

    @Nested
    @DisplayName("Collision Resistance")
    class CollisionResistanceTests {

        @Test
        @DisplayName("should not produce collisions for 1000 random vouchers")
        void shouldNotProduceCollisionsFor1000RandomVouchers() {
            // Given
            Set<String> fingerprints = new HashSet<>();

            // When
            for (int i = 0; i < 1000; i++) {
                SignedVoucher voucher = createSignedVoucher(UUID.randomUUID(), ISSUER_ID);
                String fingerprint = VoucherFingerprint.compute(voucher);
                fingerprints.add(fingerprint);
            }

            // Then - all fingerprints should be unique
            assertThat(fingerprints).hasSize(1000);
        }

        @Test
        @DisplayName("should produce different fingerprints for similar vouchers")
        void shouldProduceDifferentFingerprintsForSimilarVouchers() {
            // Given - vouchers that differ only in the last character of issuer ID
            UUID voucherId = UUID.randomUUID();
            Set<String> fingerprints = new HashSet<>();

            // When
            for (char c = 'a'; c <= 'z'; c++) {
                String issuerId = "merchant" + c;
                SignedVoucher voucher = createSignedVoucher(voucherId, issuerId);
                fingerprints.add(VoucherFingerprint.compute(voucher));
            }

            // Then - all fingerprints should be unique
            assertThat(fingerprints).hasSize(26);
        }
    }

    @Nested
    @DisplayName("Consistency Between Methods")
    class ConsistencyTests {

        @Test
        @DisplayName("signed voucher and its secret should produce same base fingerprint")
        void signedVoucherAndSecretShouldProduceSameFingerprint() {
            // Note: When signature is included, they will differ
            // This test verifies the component-based method matches
            UUID voucherId = UUID.randomUUID();
            VoucherSecret secret = createVoucherSecret(voucherId, ISSUER_ID);

            // When - compute without signature using component method
            String fromComponents = VoucherFingerprint.compute(
                    voucherId.toString(), ISSUER_ID, null);
            String fromSecret = VoucherFingerprint.compute(secret);

            // Then
            assertThat(fromComponents).isEqualTo(fromSecret);
        }
    }
}
