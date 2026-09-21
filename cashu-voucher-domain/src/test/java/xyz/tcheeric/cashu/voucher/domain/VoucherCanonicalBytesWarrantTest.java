package xyz.tcheeric.cashu.voucher.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherTags;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The warrant must be INSIDE the bytes the issuer signs.
 *
 * <p>This is the property the whole scheme rests on and it is one line away from being false.
 * {@code VoucherCanonicalBytes} walks the tags generically and skips those in
 * {@code isAddedAfterSigning}. If the warrant were ever added to that exclusion list — or if a
 * caller set it after the signature tags in a hand-built secret — the signature would no longer
 * cover it, and anyone handling the token could strip the warrant or swap in another stall's.
 *
 * <p>A stripped warrant is worse than no warrant, because it reads as a legacy voucher.
 */
class VoucherCanonicalBytesWarrantTest {

    private static final String ISSUER =
            "b1787b2b98a5244a70d934b393e0179e7ebba0c72579b4a0b238eda3911caa02";

    private static final String WARRANT_JSON =
            "{\"form\":\"merchant\",\"sale_total\":2500,\"nonce\":\"abc123\",\"sig\":\"ff\"}";

    @Test
    @DisplayName("a warrant changes the signed bytes, so the signature covers it")
    void warrantIsInsideTheSignedBytes() {
        String withWarrant = canonical(secret(WARRANT_JSON));
        String without = canonical(secret(null));

        assertNotEquals(without, withWarrant,
                "if these match, the signature does not cover the warrant and anyone "
                        + "handling the token can strip it");
        assertTrue(withWarrant.contains(VoucherTags.ISSUANCE_WARRANT),
                "the warrant tag must appear in the canonical bytes verbatim");
    }

    @Test
    @DisplayName("changing ONLY the warrant changes the signed bytes")
    void warrantContentIsCovered() {
        // Presence is not enough. If two different warrants produced the same bytes, one could
        // be swapped for another - a genuine warrant from a small sale moved onto a large one.
        String a = canonical(secret(WARRANT_JSON));
        String b = canonical(secret(
                "{\"form\":\"merchant\",\"sale_total\":5000000,\"nonce\":\"abc123\",\"sig\":\"ff\"}"));

        assertNotEquals(a, b, "the warrant's CONTENT must be covered, not merely its presence");
    }

    @Test
    @DisplayName("a warrant survives a round trip through the secret")
    void warrantRoundTrips() {
        VoucherSecret secret = secret(WARRANT_JSON);
        assertEquals(WARRANT_JSON, secret.getIssuanceWarrant());
    }

    @Test
    @DisplayName("absent stays absent, and is distinguishable from a signed `none`")
    void absentIsNotNone() {
        // Three states, not two. A reader that treats absent and `none` alike lets a stripped
        // warrant read as a pre-warrant voucher.
        assertNull(secret(null).getIssuanceWarrant(), "absent must read as null");

        String signedNone = "{\"form\":\"none\"}";
        assertEquals(signedNone, secret(signedNone).getIssuanceWarrant(),
                "a signed `none` is a positive statement and must survive as one");

        assertNotEquals(canonical(secret(null)), canonical(secret(signedNone)),
                "absent and signed-none must produce different signed bytes, or the "
                        + "distinction is unenforceable");
    }

    @Test
    @DisplayName("the warrant is NOT in the added-after-signing exclusion list")
    void warrantIsNotExcludedFromSigning() {
        // Guards the specific regression: someone adding ISSUANCE_WARRANT beside ISSUER_SIG in
        // isAddedAfterSigning, reasoning that both are "signature-ish". The signature tags are
        // excluded because they cannot cover themselves. The warrant is a different thing: it
        // is covered BY the signature, and must be.
        String bytes = canonical(secret(WARRANT_JSON));

        assertTrue(bytes.contains(VoucherTags.ISSUANCE_WARRANT));
        assertFalse(bytes.contains(VoucherTags.ISSUER_SIG),
                "the signature tag itself is correctly excluded");
        assertFalse(bytes.contains(VoucherTags.ISSUER_PUBKEY),
                "the pubkey tag is correctly excluded");
    }

    // ------------------------------------------------------------------ helpers

    private static VoucherSecret secret(String warrantJson) {
        return VoucherSecret.builder()
                .voucherId(UUID.fromString("6f39585b-7826-4493-84e5-2a7e907c0820"))
                .issuerId(ISSUER)
                .unit("EUR")
                .faceValue(2500L)
                .faceDecimals(2)
                .issuanceRatio(1.0)
                .nonce("0f0f0f0f")
                .issuanceWarrant(warrantJson)
                .issuerSignature("aa".repeat(64))
                .issuerPublicKey("bb".repeat(32))
                .build();
    }

    private static String canonical(VoucherSecret secret) {
        return new String(VoucherCanonicalBytes.of(secret), StandardCharsets.UTF_8);
    }
}
