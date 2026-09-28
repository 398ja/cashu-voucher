package xyz.tcheeric.cashu.voucher.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherTags;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One signature must not verify over two different readings of the same number.
 *
 * <h2>The bug</h2>
 *
 * <p>{@code VoucherCanonicalBytes} normalises numeric tags before hashing, so
 * {@code "1000"}, {@code "01000"}, {@code "1000.0"} and {@code "1e3"} all render as the same
 * canonical bytes and therefore all satisfy one signature. That alone would be harmless if
 * every reader agreed on the value.
 *
 * <p>They do not. {@code VoucherSecret.getFaceValue} parses with {@code Long.parseLong}, which
 * REFUSES {@code "1000.0"} and {@code "1e3"}, while the TypeScript wallet reads both as 1000
 * through {@code Number()}. So one signed voucher has two readings: a face value on one side
 * and nothing on the other. A holder can pick whichever suits them, and dropping the Java
 * face-value clamp is what that buys.
 *
 * <h2>The fix under test</h2>
 *
 * <p>A signature is accepted only when each numeric tag's WIRE string already equals its
 * canonical rendering. Normalising for the hash is still right, because the hash must be
 * stable. What changes is that a voucher whose bytes needed normalising is refused rather
 * than silently accepted in one of several forms.
 *
 * <p>Filed as cashu-voucher#48, and a prerequisite for the gateway re-signing endpoint: an
 * endpoint that signs a secret a wallet supplied must not be willing to sign an ambiguous one.
 */
class CanonicalNumericTagTest {

    /** A throwaway issuer key. Deterministic so a failure is reproducible. */
    private static final String ISSUER_PRIVATE_KEY =
            "0000000000000000000000000000000000000000000000000000000000000abc";

    /**
     * The matching x-only public key.
     *
     * Derived rather than hardcoded. `verify(secret)` reads the key from the voucher's own
     * `issuer_pubkey` tag, so a signature signed with one key and labelled with another fails
     * for a reason that has nothing to do with what these tests are about. That cost three
     * confusing control failures before the derivation went in.
     */
    private static String issuerPublicKey() {
        try {
            return Hex.toHexString(Schnorr.genPubKey(Hex.decode(ISSUER_PRIVATE_KEY)));
        } catch (Exception cannotDerive) {
            throw new IllegalStateException("cannot derive the test issuer key", cannotDerive);
        }
    }

    /** Signs a secret and labels it with the key it was signed by. */
    private static void signAs(VoucherSecret secret) {
        VoucherSignatureService.createSigned(secret, ISSUER_PRIVATE_KEY, issuerPublicKey());
    }

    /**
     * A signed voucher whose numeric tags are written exactly as the canonicaliser would.
     * This is what the issuer actually produces.
     */
    private static VoucherSecret canonicalVoucher() {
        VoucherSecret secret = VoucherSecret.builder()
                .voucherId(UUID.randomUUID())
                .issuerId("b1787b2b98c0a4f9e1d3c5a7b9e1d3c5a7b9e1d3c5a7b9e1d3c5a7b9e1d3c5a7")
                .unit("EUR")
                .faceValue(1000L)
                .faceDecimals(2)
                .build();
        signAs(secret);
        return secret;
    }

    /** Rewrites one tag's value on the wire, leaving the signature untouched. */
    private static void rewriteTag(WellKnownSecret secret, String key, String value) {
        List<WellKnownSecret.Tag> tags = secret.getTags();
        for (WellKnownSecret.Tag tag : tags) {
            if (key.equals(tag.getKey())) {
                tag.setValues(List.of(value));
                return;
            }
        }
        throw new IllegalStateException("no " + key + " tag to rewrite");
    }

    @Test
    @DisplayName("a voucher whose numbers are already canonical still verifies")
    void canonicalFormStillVerifies() {
        VoucherSecret secret = canonicalVoucher();

        assertTrue(VoucherSignatureService.verify(secret),
                "the issuer's own output must keep verifying, or this fix breaks every voucher");
    }

    /**
     * The reading that differs between Java and TypeScript, and therefore the one that matters
     * most. Java's {@code Long.parseLong("1000.0")} throws and yields no face value at all,
     * while the wallet's {@code Number("1000.0")} yields 1000.
     */
    @Test
    @DisplayName("a face value rewritten as 1000.0 is refused")
    void decimalPointFaceValueIsRefused() {
        VoucherSecret secret = canonicalVoucher();
        rewriteTag(secret, VoucherTags.FACE_VALUE, "1000.0");

        assertFalse(VoucherSignatureService.verify(secret),
                "1000.0 and 1000 must not both satisfy one signature");
    }

    @Test
    @DisplayName("a face value rewritten in exponent form is refused")
    void exponentFaceValueIsRefused() {
        VoucherSecret secret = canonicalVoucher();
        rewriteTag(secret, VoucherTags.FACE_VALUE, "1e3");

        assertFalse(VoucherSignatureService.verify(secret),
                "1e3 and 1000 must not both satisfy one signature");
    }

    @Test
    @DisplayName("a face value with a leading zero is refused")
    void leadingZeroFaceValueIsRefused() {
        VoucherSecret secret = canonicalVoucher();
        rewriteTag(secret, VoucherTags.FACE_VALUE, "01000");

        assertFalse(VoucherSignatureService.verify(secret),
                "01000 and 1000 must not both satisfy one signature");
    }

    @Test
    @DisplayName("a face value with surrounding whitespace is refused")
    void paddedFaceValueIsRefused() {
        VoucherSecret secret = canonicalVoucher();
        rewriteTag(secret, VoucherTags.FACE_VALUE, " 1000 ");

        assertFalse(VoucherSignatureService.verify(secret), "whitespace must not be normalised away");
    }

    @Test
    @DisplayName("a plus-signed face value is refused")
    void signedFaceValueIsRefused() {
        VoucherSecret secret = canonicalVoucher();
        rewriteTag(secret, VoucherTags.FACE_VALUE, "+1000");

        assertFalse(VoucherSignatureService.verify(secret), "+1000 and 1000 are two forms");
    }

    /**
     * Every numeric tag, not just the face value. {@code expires_at} decides whether a coupon
     * is still live, and {@code issuance_ratio} scales what it is worth, so an ambiguous
     * reading of either is worth money too.
     */
    @Test
    @DisplayName("the same rule applies to expires_at")
    void expiryIsCheckedToo() {
        VoucherSecret secret = VoucherSecret.builder()
                .voucherId(UUID.randomUUID())
                .issuerId("b1787b2b98c0a4f9e1d3c5a7b9e1d3c5a7b9e1d3c5a7b9e1d3c5a7b9e1d3c5a7")
                .unit("EUR")
                .faceValue(1000L)
                .faceDecimals(2)
                .expiresAt(2000000000L)
                .build();
        signAs(secret);
        assertTrue(VoucherSignatureService.verify(secret), "control: it verifies as issued");

        rewriteTag(secret, VoucherTags.EXPIRES_AT, "2000000000.0");
        assertFalse(VoucherSignatureService.verify(secret),
                "an expiry with two readings is an expiry a holder can choose");
    }

    @Test
    @DisplayName("the same rule applies to face_decimals")
    void decimalsAreCheckedToo() {
        VoucherSecret secret = canonicalVoucher();
        rewriteTag(secret, VoucherTags.FACE_DECIMALS, "2.0");

        assertFalse(VoucherSignatureService.verify(secret),
                "decimals decide whether 1000 is EUR 10.00 or EUR 1000");
    }

    /**
     * A genuinely fractional ratio must still work. The rule is "the wire form must be the
     * canonical form", not "integers only", and refusing real fractional ratios would break
     * every voucher that has one.
     */
    @Test
    @DisplayName("a fractional issuance ratio still verifies in its canonical form")
    void fractionalRatioStillVerifies() {
        VoucherSecret secret = VoucherSecret.builder()
                .voucherId(UUID.randomUUID())
                .issuerId("b1787b2b98c0a4f9e1d3c5a7b9e1d3c5a7b9e1d3c5a7b9e1d3c5a7b9e1d3c5a7")
                .unit("EUR")
                .faceValue(1000L)
                .faceDecimals(2)
                .issuanceRatio(0.5)
                .build();
        signAs(secret);

        assertTrue(VoucherSignatureService.verify(secret),
                "0.5 is its own canonical form and must keep verifying");
    }

    /**
     * Non-numeric tags are untouched by this rule. They are already compared byte for byte,
     * and applying a numeric canonicalisation to them would be meaningless.
     */
    @Test
    @DisplayName("a rewritten non-numeric tag still fails, as it always did")
    void nonNumericTagsUnaffected() {
        VoucherSecret secret = canonicalVoucher();
        rewriteTag(secret, VoucherTags.UNIT, "GBP");

        assertFalse(VoucherSignatureService.verify(secret),
                "changing the currency has always broken the signature");
    }
}
