package xyz.tcheeric.cashu.voucher.domain;

import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherTags;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A whole-number {@code issuance_ratio} other than 1 must verify (cashu-voucher#59).
 *
 * <h2>The bug</h2>
 *
 * <p>cashu-lib up to 0.30.6 stored a ratio of 50 as {@code String.valueOf(50.0)}, i.e.
 * {@code "50.0"}. {@code sign()} hashed the canonical bytes, in which that renders as
 * {@code 50}, so the signature was good. {@code verify()} then refused the voucher because the
 * wire spelling {@code "50.0"} was not the canonical spelling {@code "50"} (cashu-voucher#48).
 * So every MINIMAL, FIXED and merchant-denomination coupon with a whole ratio was dead.
 *
 * <h2>The fix under test</h2>
 *
 * <ol>
 *   <li>{@code sign()} refuses a secret whose numeric tags are not canonical, so an issuer can
 *       never again produce a voucher its own verifier rejects.</li>
 *   <li>{@code verify()} accepts exactly the legacy spelling the old writer produced
 *       ({@code Double.toString} of a whole number), for {@code issuance_ratio} only, because
 *       those vouchers are live and their proof secret cannot be rewritten. Every other
 *       near-miss spelling, and every integral tag, stays refused.</li>
 * </ol>
 */
class WholeIssuanceRatioTest {

    private static final String ISSUER_PRIVATE_KEY =
            "0000000000000000000000000000000000000000000000000000000000000abc";

    private static final String SPENDING_KEY =
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";

    private static String issuerPublicKey() {
        return Hex.toHexString(Schnorr.genPubKey(Hex.decode(ISSUER_PRIVATE_KEY)));
    }

    /** An unlocked voucher with no ratio tag yet, so the test controls its spelling. */
    private static VoucherSecret unlocked() {
        return VoucherSecret.builder()
                .voucherId(UUID.randomUUID())
                .issuerId("b1787b2b98c0a4f9e1d3c5a7b9e1d3c5a7b9e1d3c5a7b9e1d3c5a7b9e1d3c5a7")
                .unit("EUR")
                .faceValue(500L)
                .faceDecimals(2)
                .backingStrategy("MINIMAL")
                .build();
    }

    /** A locked voucher, the kind whose tags travel on the wire verbatim. */
    private static P2PKVoucherSecret locked() {
        P2PKVoucherSecret secret = new P2PKVoucherSecret(Hex.decode(SPENDING_KEY));
        secret.setNonce("ab".repeat(32));
        secret.setVoucherId(UUID.randomUUID().toString());
        secret.setIssuerId("acme");
        secret.setUnit("EUR");
        secret.setFaceValue(500L);
        secret.setFaceDecimals(2);
        secret.setBackingStrategy("FIXED");
        return secret;
    }

    /**
     * Signs exactly as cashu-voucher 0.15.0 to 0.17.0 did for a secret carrying {@code "50.0"}:
     * a Schnorr signature over {@code VoucherCanonicalBytes.of}, which renders the ratio as 50.
     * The current {@code sign()} refuses such a secret, which is the point, so this reproduces
     * the live vouchers it produced without going through it.
     */
    private static void signAsTheOldIssuerDid(WellKnownSecret secret) {
        byte[] hash = sha256(VoucherCanonicalBytes.of(secret));
        byte[] signature = Schnorr.sign(hash, Hex.decode(ISSUER_PRIVATE_KEY), new byte[32]);
        setSignatureTags(secret, Hex.toHexString(signature));
    }

    private static void setSignatureTags(WellKnownSecret secret, String signatureHex) {
        secret.setTag(VoucherTags.ISSUER_SIG, List.of(signatureHex));
        secret.setTag(VoucherTags.ISSUER_PUBKEY, List.of(issuerPublicKey()));
    }

    private static void setRatioSpelling(WellKnownSecret secret, String wire) {
        secret.setTag(VoucherTags.ISSUANCE_RATIO, List.of(wire));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("sign() refuses what verify() would refuse")
    class SigningIsStrict {

        // The repro from #59: an issuer signed "50.0", and its own verifier refused the result.
        // sign() must now refuse at issuance, where the mistake is cheap, not at redemption.
        @Test
        @DisplayName("an unlocked secret with ratio \"50.0\" is refused at sign time")
        void unlockedLegacySpellingIsRefused() {
            // Arrange
            VoucherSecret secret = unlocked();
            setRatioSpelling(secret, "50.0");

            // Act + Assert
            assertThatThrownBy(() -> VoucherSignatureService.sign(secret, ISSUER_PRIVATE_KEY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("issuance_ratio")
                    .hasMessageContaining("canonical");
        }

        // createSigned is the convenience path every issuer uses, so it must refuse too.
        @Test
        @DisplayName("createSigned refuses a non-canonical secret and leaves it unsigned")
        void createSignedRefuses() {
            // Arrange
            VoucherSecret secret = unlocked();
            setRatioSpelling(secret, "700.0");

            // Act + Assert
            assertThatThrownBy(() -> VoucherSignatureService.createSigned(
                    secret, ISSUER_PRIVATE_KEY, issuerPublicKey()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(secret.getIssuerSignature()).isNull();
        }

        // The locked kind signs through the same sign(), so the same rule reaches it.
        @Test
        @DisplayName("a locked secret with a non-canonical face value is refused at sign time")
        void lockedNonCanonicalIsRefused() {
            // Arrange
            P2PKVoucherSecret secret = locked();
            secret.setTag(VoucherTags.FACE_VALUE, List.of("500.0"));

            // Act + Assert
            assertThatThrownBy(() -> SignedLockedVoucher.createSigned(
                    secret, ISSUER_PRIVATE_KEY, issuerPublicKey()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("face_value");
        }

        // The canonical spelling is still signable, for both kinds, or this breaks issuance.
        @ParameterizedTest
        @ValueSource(strings = {"2", "50", "700", "0.5", "50.5"})
        @DisplayName("a canonical ratio signs and verifies")
        void canonicalSignsAndVerifies(String wire) {
            // Arrange
            VoucherSecret plain = unlocked();
            setRatioSpelling(plain, wire);
            P2PKVoucherSecret lockedSecret = locked();
            setRatioSpelling(lockedSecret, wire);

            // Act
            VoucherSignatureService.createSigned(plain, ISSUER_PRIVATE_KEY, issuerPublicKey());
            SignedLockedVoucher signedLocked = SignedLockedVoucher.createSigned(
                    lockedSecret, ISSUER_PRIVATE_KEY, issuerPublicKey());

            // Assert
            assertThat(VoucherSignatureService.verify(plain)).isTrue();
            assertThat(signedLocked.verify()).isTrue();
        }

        // The real issuer path: the builder's own spelling must be one sign() accepts. Fails on
        // cashu-lib <= 0.30.6, whose builder writes "50.0", which is why this cashu-voucher must
        // not be released against it: every whole-ratio issuance would throw.
        @ParameterizedTest
        @ValueSource(doubles = {2.0, 50.0, 700.0})
        @DisplayName("the builder's whole-ratio output is signable and verifies")
        void builderOutputIsSignable(double ratio) {
            // Arrange
            VoucherSecret secret = VoucherSecret.builder()
                    .voucherId(UUID.randomUUID()).issuerId("i").unit("EUR").faceValue(500L)
                    .faceDecimals(2).backingStrategy("MINIMAL").issuanceRatio(ratio).build();

            // Act
            VoucherSignatureService.createSigned(secret, ISSUER_PRIVATE_KEY, issuerPublicKey());

            // Assert
            assertThat(VoucherSignatureService.verify(secret)).isTrue();
        }
    }

    @Nested
    @DisplayName("live vouchers carrying the legacy \"N.0\" ratio")
    class LegacyRatioSpelling {

        // The #59 repro on the locked kind, whose proof secret carries "50.0" on the wire and
        // cannot be rewritten. It was signed over canonical 50 and must verify.
        @ParameterizedTest
        @ValueSource(strings = {"2.0", "50.0", "700.0", "1.0E7"})
        @DisplayName("a locked voucher signed with the legacy spelling verifies")
        void lockedLegacyVerifies(String wire) {
            // Arrange
            P2PKVoucherSecret secret = locked();
            setRatioSpelling(secret, wire);
            signAsTheOldIssuerDid(secret);

            // Act + Assert
            assertThat(VoucherSignatureService.verify(secret)).isTrue();
        }

        // Same for the unlocked kind as rebuilt by an old cashu-lib (UnlockedVoucherBlob, codecs).
        @Test
        @DisplayName("an unlocked voucher rebuilt with \"50.0\" verifies")
        void unlockedLegacyVerifies() {
            // Arrange
            VoucherSecret secret = unlocked();
            setRatioSpelling(secret, "50.0");
            signAsTheOldIssuerDid(secret);

            // Act + Assert
            assertThat(VoucherSignatureService.verify(secret)).isTrue();
        }

        // One signature now verifies under two spellings, "50" and "50.0". That is safe only if
        // both have exactly one value to every reader, which this pins: Java's getIssuanceRatio
        // and the canonical long agree, so a holder choosing a spelling chooses nothing.
        @ParameterizedTest
        @ValueSource(strings = {"2.0", "50.0", "700.0", "1.0E7"})
        @DisplayName("the legacy and canonical spellings read as the same ratio")
        void bothSpellingsHaveOneMeaning(String wire) {
            // Arrange
            P2PKVoucherSecret legacy = locked();
            setRatioSpelling(legacy, wire);
            signAsTheOldIssuerDid(legacy);
            P2PKVoucherSecret canonical = locked();
            canonical.setVoucherId(legacy.getTag(VoucherTags.VOUCHER_ID).getValues().get(0).toString());
            setRatioSpelling(canonical, Long.toString((long) Double.parseDouble(wire)));

            // Act
            byte[] legacyBytes = VoucherCanonicalBytes.of(legacy);
            byte[] canonicalBytes = VoucherCanonicalBytes.of(canonical);

            // Assert
            assertThat(legacyBytes).isEqualTo(canonicalBytes);
            assertThat(legacy.getIssuanceRatio()).isEqualTo(canonical.getIssuanceRatio());
            assertThat(legacy.getIssuanceRatio()).isEqualTo((double) Long.parseLong(
                    canonical.getTag(VoucherTags.ISSUANCE_RATIO).getValues().get(0).toString()));
        }

        // Only the exact Double.toString output is legacy. Every other spelling of 50 is one the
        // old writer never produced, and several of them read differently in Java and
        // TypeScript (Double.valueOf accepts "50d" and hex floats, Number() accepts "0x32"), so
        // each is refused as before: the #48 rule is not loosened beyond the one form.
        @ParameterizedTest
        @ValueSource(strings = {"50.00", "050.0", "+50.0", "-50.0", "50.", "5e1", "5.0E1", "5.0e1",
                "50.0 ", " 50.0", "50d", "50.0d", "50f", "0x1.9p5", "0x32", "Infinity", "NaN",
                "0.0", "50.0\n", "1.0E07", "10000000.0"})
        @DisplayName("any other non-canonical ratio spelling is still refused")
        void otherSpellingsRefused(String wire) {
            // Arrange
            P2PKVoucherSecret secret = locked();
            setRatioSpelling(secret, "50");
            signAsTheOldIssuerDid(secret);
            setRatioSpelling(secret, wire);

            // Act + Assert
            assertThat(VoucherSignatureService.verify(secret)).as("wire %s", wire).isFalse();
        }

        // The integral tags keep the strict rule (#48): Long.parseLong("500.0") yields no face
        // value in Java while Number() yields 500, which is the two-readings bug.
        @Test
        @DisplayName("the legacy spelling is not accepted for face_value, expires_at or face_decimals")
        void integralTagsStayStrict() {
            for (String key : List.of(VoucherTags.FACE_VALUE, VoucherTags.EXPIRES_AT, VoucherTags.FACE_DECIMALS)) {
                // Arrange
                P2PKVoucherSecret secret = locked();
                secret.setExpiresAt(2000000000L);
                setRatioSpelling(secret, "50.0");
                String value = secret.getTag(key).getValues().get(0).toString();
                secret.setTag(key, List.of(value + ".0"));
                signAsTheOldIssuerDid(secret);

                // Act + Assert
                assertThat(VoucherSignatureService.verify(secret)).as(key).isFalse();
            }
        }

        // verifyStrict is for secrets minted under the canonical form only (terminal
        // credentials), so it gets no legacy window of any kind.
        @Test
        @DisplayName("verifyStrict still refuses the legacy spelling")
        void strictVerifyHasNoLegacyWindow() {
            // Arrange
            P2PKVoucherSecret secret = locked();
            setRatioSpelling(secret, "50.0");
            signAsTheOldIssuerDid(secret);

            // Act + Assert
            assertThat(VoucherSignatureService.verifyStrict(secret)).isFalse();
        }

        // hasCanonicalNumericTags keeps its strict meaning, because sign() and callers that are
        // about to sign rely on it. Only verification has the legacy window.
        @Test
        @DisplayName("hasCanonicalNumericTags still says \"50.0\" is not canonical")
        void canonicalCheckIsUnchanged() {
            // Arrange
            P2PKVoucherSecret secret = locked();
            setRatioSpelling(secret, "50.0");

            // Act + Assert
            assertThat(VoucherCanonicalBytes.hasCanonicalNumericTags(secret)).isFalse();
            assertThat(VoucherCanonicalBytes.hasVerifiableNumericTags(secret)).isTrue();
        }

        // Double.toString switches to E notation at 1e7, so the legacy spelling of ten million is
        // "1.0E7". The TypeScript verifier asserts the same table (wholeIssuanceRatio.test.ts), so
        // the two cannot disagree about which string an old issuer wrote.
        @ParameterizedTest
        @CsvSource({"1.0E7, true", "1.2E7, true", "9999999.0, true",
                "10000000.0, false", "1.0e7, false", "1E7, false",
                // long saturates at 2^63, so nothing larger has a legacy spelling
                "9.223372036854776E18, true", "1.0E19, false", "1.8446744073709552E19, false",
                "-1.0E19, false"})
        @DisplayName("the legacy spelling follows Double.toString across the 1e7 boundary")
        void legacySpellingBoundaryMatchesTypeScript(String wire, boolean accepted) {
            // Arrange
            P2PKVoucherSecret secret = locked();
            setRatioSpelling(secret, wire);

            // Act + Assert
            assertThat(VoucherCanonicalBytes.hasVerifiableNumericTags(secret)).as(wire).isEqualTo(accepted);
        }
    }
}
