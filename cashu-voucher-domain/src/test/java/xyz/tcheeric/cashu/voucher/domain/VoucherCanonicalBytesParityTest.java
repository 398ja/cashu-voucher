package xyz.tcheeric.cashu.voucher.domain;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the canonical signing bytes for a {@code P2PK_VOUCHER} against a fixed vector.
 *
 * <p>The same vector is asserted by the TypeScript renderer in the NAP repo
 * ({@code packages/nap-voucher/test/secret.test.ts}). Two implementations render the bytes an
 * issuer signs — this one, and the one a NAP server uses to verify — and a disagreement between
 * them is not a failed test but a signature that verifies over different content than the issuer
 * meant. Inspection cannot catch that, so both sides pin the same bytes instead.
 *
 * <p>The secret exercises every case that has differed between the two renderers: a numeric tag
 * (written bare) beside a string tag that looks numeric, the P2PK tags a {@link P2PKVoucherSecret}
 * carries implicitly, the {@code issuer_sig}/{@code issuer_pubkey} exclusion, non-ASCII, and the
 * JSON escapes.
 *
 * <p>If this test fails, the wire format changed. That is a mint-visible change and every live
 * voucher's signature depends on it, so update the vector only alongside the mint — never to make
 * the build green.
 */
class VoucherCanonicalBytesParityTest {

    private static final String EXPECTED_CANONICAL_HEX =
            "5b225032504b5f564f5543484552222c2230326161616161616161616161616161616161616161616161"
                    + "616161616161616161616161616161616161616161616161616161616161616161616161616161616122"
                    + "2c2230313233343536373839616263646566222c5b5b226e5f73696773222c2231225d2c5b2273696766"
                    + "6c6167222c225349475f494e50555453225d2c5b22766f75636865725f6964222c22762d313233225d2c"
                    + "5b22697373756572222c22696d616e69225d2c5b22756e6974222c22736174225d2c5b22666163655f76"
                    + "616c7565222c313030305d2c5b22657870697265735f6174222c323030303030303030305d2c5b226d65"
                    + "726368616e745f6d65746164617461222c22636166c3a9205c2271756f7465645c225c6e6c696e655c74"
                    + "7365705c5c6261636b225d5d5d";

    @Test
    void rendersTheVectorTheTypeScriptVerifierExpects() {
        P2PKVoucherSecret secret = new P2PKVoucherSecret(Hex.decode("02" + "a".repeat(64)));
        secret.setNonce("0123456789abcdef");
        secret.setVoucherId("v-123");
        secret.setIssuerId("imani");
        secret.setUnit("sat");
        secret.setFaceValue(1000L);
        secret.setExpiresAt(2000000000L);
        secret.setMerchantMetadata("caf\u00e9 \"quoted\"\nline\tsep\\back");
        // Added after signing, so present here precisely to prove they are excluded.
        secret.setIssuerPublicKey("deadbeef");
        secret.setIssuerSignature("ffff");

        assertEquals(EXPECTED_CANONICAL_HEX, Hex.toHexString(VoucherCanonicalBytes.of(secret)));
    }
}
