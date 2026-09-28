package xyz.tcheeric.cashu.voucher.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.SecretUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the voucher out of an unlocked voucher's blob.
 *
 * <h2>Why a captured secret</h2>
 *
 * <p>The whole point of this class is that a real unlocked voucher hides its fields
 * somewhere no tag-based reader can see them. A hand-built fixture would be built
 * from my belief about that layout, and my belief is exactly what needs checking.
 *
 * <p>So the fixture is a secret captured from a real token
 * ({@code control-unlocked.token} in imani-wallet). The first test asserts the thing
 * that made this bug possible: its tag array is EMPTY, so every check in
 * {@code VoucherMetadata} finds nothing and passes.
 *
 * <p>cashu-mint#525.
 */
class UnlockedVoucherBlobTest {

    private static WellKnownSecret capturedUnlockedVoucher() {
        try (InputStream in = UnlockedVoucherBlobTest.class
                .getResourceAsStream("/captured-unlocked-voucher-secret.json")) {
            assertNotNull(in, "the captured unlocked voucher fixture must be on the classpath");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return (WellKnownSecret) SecretUtil.toSecret(json);
        } catch (IOException cannotRead) {
            throw new IllegalStateException(cannotRead);
        }
    }

    /**
     * The bug itself, stated as a test. Every existing check reads tags, and there are
     * none, which is why an unlocked voucher was accepted with nothing verified.
     */
    @Test
    @DisplayName("a real unlocked voucher carries no tags at all, which is the bug")
    void unlockedVoucherHasNoTags() {
        WellKnownSecret secret = capturedUnlockedVoucher();

        assertEquals(WellKnownSecret.Kind.VOUCHER, secret.getKind());
        assertTrue(secret.getTags() == null || secret.getTags().isEmpty(),
                "if this ever gains tags, the reason this class exists has changed");

        // So the tag-based readers see nothing, and every guard built on them passes.
        assertNull(VoucherMetadata.issuerSignature(secret));
        assertNull(VoucherMetadata.issuerPublicKey(secret));
        assertFalse(VoucherMetadata.isSigned(secret),
                "which is how an unlocked voucher skipped the signature check entirely");
    }

    /** And the fields were there the whole time, inside the blob. */
    @Test
    @DisplayName("reads the voucher out of the blob")
    void readsTheBlob() {
        VoucherSecret voucher = UnlockedVoucherBlob.read(capturedUnlockedVoucher());

        assertNotNull(voucher, "a real captured voucher must be readable");
        assertNotNull(voucher.getVoucherId());
        assertNotNull(voucher.getIssuerId());
        assertNotNull(voucher.getUnit());
        assertNotNull(voucher.getIssuerSignature(), "the signature the mint could not see");
        assertNotNull(voucher.getIssuerPublicKey(), "the key the mint could not see");
    }

    @Test
    @DisplayName("reports that a real unlocked voucher does carry a signature")
    void reportsSignaturePresence() {
        assertTrue(UnlockedVoucherBlob.carriesSignature(capturedUnlockedVoucher()),
                "so a mint can tell an unsigned voucher from a signed one");
    }

    /**
     * The decoded voucher must VERIFY, which is the claim that matters. A decode that
     * produced plausible-looking fields in the wrong shape would satisfy every
     * assertion above and still fail every signature.
     */
    @Test
    @DisplayName("the decoded voucher verifies against its own signature")
    void decodedVoucherVerifies() {
        VoucherSecret voucher = UnlockedVoucherBlob.read(capturedUnlockedVoucher());

        assertNotNull(voucher);
        assertTrue(VoucherSignatureService.verify(voucher),
                "the decode must reproduce the bytes the issuer signed, not merely similar ones");
    }

    @Test
    @DisplayName("declines a locked voucher, which keeps its fields in tags")
    void declinesLockedVouchers() {
        WellKnownSecret locked = (WellKnownSecret) SecretUtil.toSecret(
                "[\"P2PK_VOUCHER\",{\"nonce\":\"00\",\"data\":\"02" + "ab".repeat(32)
                        + "\",\"tags\":[[\"voucher_id\",\"x\"]]}]");

        assertNull(UnlockedVoucherBlob.read(locked),
                "a locked voucher needs no blob decode, its fields are already readable");
    }

    /**
     * Unreadable is not the same as absent, and both must be survivable. This runs on
     * untrusted input, so a blob of rubbish must produce null rather than an exception
     * that becomes a 500 on a public endpoint.
     *
     * <p>Note what is NOT tested here: data that is not hex at all. {@code SecretUtil}
     * refuses that while parsing, before this class ever sees it, so a test for it would
     * be asserting somebody else's behaviour. Found by writing that test and watching it
     * throw from the parser.
     */
    @Test
    @DisplayName("returns nothing for a blob that is not readable")
    void survivesRubbish() {
        WellKnownSecret hexButNotCbor = (WellKnownSecret) SecretUtil.toSecret(
                "[\"VOUCHER\",{\"nonce\":\"00\",\"data\":\"deadbeef\",\"tags\":[]}]");

        assertNull(UnlockedVoucherBlob.read(hexButNotCbor),
                "valid hex that is not CBOR must read as nothing, not throw");
        assertFalse(UnlockedVoucherBlob.carriesSignature(hexButNotCbor),
                "and it certainly does not carry a signature");
    }
}
