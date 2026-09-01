package xyz.tcheeric.cashu.voucher.domain;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signing and canonical bytes for {@code P2PK_VOUCHER}, a voucher that is also P2PK-locked.
 *
 * <p>The property that matters most here is one this file cannot assert alone: widening
 * {@link VoucherCanonicalBytes} to read the kind from the secret must not change the bytes for
 * an ordinary {@code VOUCHER}, because that would invalidate every signature ever issued.
 * {@link VoucherCanonicalBytesTest} and {@link VoucherGoldenVectorTest} pin those bytes
 * literally and are the real guard. What is added here is that the new kind produces
 * <em>different</em> bytes, and that the difference is the kind itself.
 */
class P2PKVoucherSignatureTest {

    /** secp256k1 generator, even-y — the key a witness must sign for. */
    private static final String SPENDING_KEY =
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";

    private static final String NONCE =
            "0000000000000000000000000000000000000000000000000000000000aa";

    private static final String ISSUER_PRIVKEY =
            "f6b7737ceb512d66070b27fc20994e612e55d6974dbaf37511cfabc62d59cafd";

    private static final String ISSUER_PUBKEY =
            "28615b57803d0b05418a13677ab2aa62a51868799892414a73c5d99e02c3fcec";

    private static P2PKVoucherSecret locked() {
        P2PKVoucherSecret secret = new P2PKVoucherSecret(Hex.decode(SPENDING_KEY));
        secret.setNonce(NONCE);
        secret.setVoucherId("123e4567-e89b-12d3-a456-426614174000");
        secret.setIssuerId("test-issuer");
        secret.setUnit("sat");
        secret.setFaceValue(5000L);
        secret.setExpiresAt(1893456000L);
        return secret;
    }

    private static String canonical(WellKnownSecret secret) {
        return new String(VoucherCanonicalBytes.of(secret), StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("canonical bytes")
    class CanonicalBytes {

        @Test
        @DisplayName("open with the P2PK_VOUCHER kind, not VOUCHER")
        void kindIsInTheSignedBytes() {
            // The reason the kind is read from the secret rather than hardcoded: the signature
            // has to commit to which kind it was made for. Otherwise a signature over an
            // unlocked voucher would verify against a locked one carrying the same metadata.
            assertThat(canonical(locked())).startsWith("[\"P2PK_VOUCHER\",");
        }

        @Test
        @DisplayName("carry the spending key in the data position")
        void spendingKeyIsInData() {
            assertThat(canonical(locked()))
                    .contains("\"" + SPENDING_KEY + "\"");
        }

        @Test
        @DisplayName("carry the voucher id as a tag, since data holds the lock")
        void voucherIdIsATag() {
            assertThat(canonical(locked()))
                    .contains("[\"voucher_id\",\"123e4567-e89b-12d3-a456-426614174000\"]");
        }

        @Test
        @DisplayName("write numeric voucher tags as bare numbers, as for an ordinary voucher")
        void numericTagsUnchanged() {
            // The numeric-tag rule is keyed on the tag name, so it applies to both kinds. A
            // divergence here would mean two signing rules for one vocabulary.
            String bytes = canonical(locked());

            assertThat(bytes).contains("[\"face_value\",5000]");
            assertThat(bytes).contains("[\"expires_at\",1893456000]");
        }

        @Test
        @DisplayName("omit the signature tags, which are added after signing")
        void signatureTagsExcluded() {
            P2PKVoucherSecret secret = locked();
            secret.setIssuerSignature("de".repeat(32));
            secret.setIssuerPublicKey(ISSUER_PUBKEY);

            assertThat(canonical(secret))
                    .doesNotContain("issuer_sig")
                    .doesNotContain("issuer_pubkey");
        }

        @Test
        @DisplayName("differ from an ordinary voucher's bytes")
        void differsFromPlainVoucher() {
            // Same metadata, different kind. If these collided, the kind would not be covered
            // by the signature and a locked voucher could be presented as an unlocked one.
            VoucherSecret plain = VoucherSecret.builder()
                    .voucherId(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
                    .nonce(NONCE)
                    .issuerId("test-issuer")
                    .unit("sat")
                    .faceValue(5000L)
                    .expiresAt(1893456000L)
                    .build();

            assertThat(canonical(locked())).isNotEqualTo(canonical(plain));
        }

        @Test
        @DisplayName("refuse a kind that carries no voucher metadata")
        void refusesNonVoucherKind() {
            // Signing an arbitrary P2PK secret with an issuer key would produce something that
            // means nothing but looks like an issuer's attestation.
            P2PKSecret notAVoucher = new P2PKSecret(Hex.decode(SPENDING_KEY));

            assertThatThrownBy(() -> VoucherCanonicalBytes.of(notAVoucher))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("VOUCHER or P2PK_VOUCHER");
        }
    }

    @Nested
    @DisplayName("sign and verify")
    class SignAndVerify {

        @Test
        @DisplayName("a locked voucher signs and verifies")
        void roundTrips() {
            P2PKVoucherSecret secret = locked();

            byte[] signature = VoucherSignatureService.sign(secret, ISSUER_PRIVKEY);

            assertThat(signature).hasSize(64);
            assertThat(VoucherSignatureService.verify(secret, signature, ISSUER_PUBKEY)).isTrue();
        }

        @Test
        @DisplayName("verify() reads the signature and key from the tags")
        void verifiesFromTags() {
            P2PKVoucherSecret secret = locked();
            byte[] signature = VoucherSignatureService.sign(secret, ISSUER_PRIVKEY);
            secret.setIssuerSignature(Hex.toHexString(signature));
            secret.setIssuerPublicKey(ISSUER_PUBKEY);

            assertThat(VoucherSignatureService.verify(secret)).isTrue();
        }

        @Test
        @DisplayName("an unsigned voucher does not verify")
        void unsignedDoesNotVerify() {
            assertThat(VoucherSignatureService.verify(locked())).isFalse();
        }

        @Test
        @DisplayName("tampering with the metadata breaks the signature")
        void tamperedMetadataFails() {
            P2PKVoucherSecret secret = locked();
            byte[] signature = VoucherSignatureService.sign(secret, ISSUER_PRIVKEY);

            secret.setFaceValue(9999L);

            assertThat(VoucherSignatureService.verify(secret, signature, ISSUER_PUBKEY)).isFalse();
        }

        @Test
        @DisplayName("tampering with the spending key breaks the signature")
        void tamperedLockFails() {
            // The property the whole design rests on: an attacker cannot re-point a signed
            // voucher at their own key, because the lock is inside what the issuer signed.
            P2PKVoucherSecret secret = locked();
            byte[] signature = VoucherSignatureService.sign(secret, ISSUER_PRIVKEY);

            // 2G — a different valid point.
            secret.setData(Hex.decode(
                    "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"));

            assertThat(VoucherSignatureService.verify(secret, signature, ISSUER_PUBKEY)).isFalse();
        }

        @Test
        @DisplayName("a signature made for the locked form does not verify against the plain form")
        void signatureDoesNotCrossKinds() {
            // The concrete reason the kind is in the signed bytes.
            P2PKVoucherSecret secret = locked();
            byte[] signature = VoucherSignatureService.sign(secret, ISSUER_PRIVKEY);

            VoucherSecret plain = VoucherSecret.builder()
                    .voucherId(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
                    .nonce(NONCE)
                    .issuerId("test-issuer")
                    .unit("sat")
                    .faceValue(5000L)
                    .expiresAt(1893456000L)
                    .build();

            assertThat(VoucherSignatureService.verify(plain, signature, ISSUER_PUBKEY)).isFalse();
        }
    }
}
