package xyz.tcheeric.cashu.voucher.domain;

import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The issuance warrant, under the attack it exists to refuse.
 *
 * <p>These are not shape tests. Each one runs the forgery from
 * {@code imani-wallet/docs/handoff-issuance-countersignature.md} against the scheme and asserts
 * it is refused, or establishes a property the scheme's soundness rests on.
 */
class IssuanceWarrantTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The victim stall, whose key sits on its own device and never on a server. */
    private static final byte[] STALL_PRIVATE_KEY = privateKey();

    private static final String STALL_PUBKEY = pubkeyOf(STALL_PRIVATE_KEY);

    /** An attacker who holds the ISSUING SERVICE key but not the stall's. */
    private static final byte[] ATTACKER_PRIVATE_KEY = privateKey();

    private static final String SALE_NONCE = "b7f3a1c2d4e5f60718293a4b5c6d7e8f";

    // A plausible counter sale: EUR 25.00.
    private static final long SALE_TOTAL = 2500L;
    private static final int DECIMALS = 2;
    private static final String UNIT = "EUR";

    @Nested
    @DisplayName("the attack")
    class TheAttack {

        @Test
        @DisplayName("a genuine warrant verifies offline against the stall's own key")
        void genuineWarrantVerifies() {
            String signature = signAsStall(
                    IssuanceWarrant.saleDigest(STALL_PUBKEY, SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE));

            assertTrue(IssuanceWarrant.verifyMerchant(
                    STALL_PUBKEY, SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE, signature, SALE_TOTAL),
                    "the stall signed this sale; a verifier holding only the coupon and the "
                            + "stall's pubkey must be able to confirm that without a network call");
        }

        @Test
        @DisplayName("a compromised service cannot forge a warrant in the stall's name")
        void attackerSignedWarrantIsRefused() {
            // The whole finding: whoever holds the ISSUING SERVICE key can mint a coupon naming
            // any stall. What they cannot do is produce a warrant for it, because the warrant
            // is signed by a key that never leaves the merchant's device.
            byte[] digest =
                    IssuanceWarrant.saleDigest(STALL_PUBKEY, SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE);
            String forged = sign(digest, ATTACKER_PRIVATE_KEY);

            assertFalse(IssuanceWarrant.verifyMerchant(
                    STALL_PUBKEY, SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE, forged, SALE_TOTAL),
                    "a warrant signed by anyone but the named stall must be refused, or the "
                            + "service key alone still mints unbacked claims");
        }

        @Test
        @DisplayName("a warrant REPLAYED onto an inflated coupon is refused")
        void replayOntoInflatedCouponIsRefused() {
            // This is the sharp case and the reason the ceiling test exists.
            //
            // The attacker does not forge a signature. They take a GENUINE warrant from a real
            // EUR 25.00 sale - one they may have simply observed - and attach it to a coupon
            // they minted for EUR 50,000.00. The signature verifies perfectly, because it is
            // real. Only the ceiling refuses this.
            String genuineSignature = signAsStall(
                    IssuanceWarrant.saleDigest(STALL_PUBKEY, SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE));

            long inflatedCoupon = 5_000_000L; // EUR 50,000.00

            assertFalse(IssuanceWarrant.verifyMerchant(
                    STALL_PUBKEY, SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE,
                    genuineSignature, inflatedCoupon),
                    "a real signature over a EUR 25.00 sale says NOTHING about a EUR 50,000.00 "
                            + "coupon carrying it; refusing this is the entire point");
        }

        @Test
        @DisplayName("inflating ONLY the claimed sale total breaks the signature")
        void inflatingTheSaleTotalBreaksTheSignature() {
            // The other half of the previous test. If the attacker instead inflates the
            // WARRANT's claimed total so the ceiling passes, the digest moves and the genuine
            // signature no longer verifies over it. Both doors have to be shut.
            String genuineSignature = signAsStall(
                    IssuanceWarrant.saleDigest(STALL_PUBKEY, SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE));

            long inflatedTotal = 5_000_000L;

            assertFalse(IssuanceWarrant.verifyMerchant(
                    STALL_PUBKEY, inflatedTotal, DECIMALS, UNIT, SALE_NONCE,
                    genuineSignature, inflatedTotal),
                    "the digest covers the amount, so raising the claimed total must invalidate "
                            + "a signature made over the real one");
        }

        @Test
        @DisplayName("a warrant cannot be moved to a different stall")
        void warrantIsNotTransferableBetweenStalls() {
            String genuineSignature = signAsStall(
                    IssuanceWarrant.saleDigest(STALL_PUBKEY, SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE));

            String otherStall = pubkeyOf(privateKey());

            assertFalse(IssuanceWarrant.verifyMerchant(
                    otherStall, SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE,
                    genuineSignature, SALE_TOTAL),
                    "issuerId is inside the digest precisely so one stall's warrant cannot "
                            + "vouch for another's coupons");
        }
    }

    @Nested
    @DisplayName("the digest")
    class TheDigest {

        @Test
        @DisplayName("changes when ONLY the sale total changes")
        void digestCoversTheAmount() {
            // If this ever fails, the scheme has degenerated into a plain countersignature:
            // it would authenticate the debtor and leave the amount free.
            assertNotEquals(
                    Hex.toHexString(IssuanceWarrant.saleDigest(
                            STALL_PUBKEY, 2500L, DECIMALS, UNIT, SALE_NONCE)),
                    Hex.toHexString(IssuanceWarrant.saleDigest(
                            STALL_PUBKEY, 2501L, DECIMALS, UNIT, SALE_NONCE)));
        }

        @Test
        @DisplayName("distinguishes amounts that would collide without a field separator")
        void separatorPreventsAmbiguity() {
            // THE reason FIELD_SEPARATOR exists. Concatenated, both of these are "2500":
            //
            //   faceValue=25   decimals=00   ->  "25" + "00"
            //   faceValue=2500 decimals=0    ->  "2500" + "0"
            //
            // A scheme that collides here lets one warrant verify against a coupon worth a
            // hundred times more, which is a forgery rather than a curiosity.
            byte[] a = IssuanceWarrant.saleDigest(STALL_PUBKEY, 25L, 0, UNIT, SALE_NONCE);
            byte[] b = IssuanceWarrant.saleDigest(STALL_PUBKEY, 2L, 50, UNIT, SALE_NONCE);

            assertNotEquals(Hex.toHexString(a), Hex.toHexString(b),
                    "field values must not be able to forge a boundary between fields");
        }

        @Test
        @DisplayName("distinguishes the same number in different currencies")
        void digestCoversTheUnit() {
            assertNotEquals(
                    Hex.toHexString(IssuanceWarrant.saleDigest(
                            STALL_PUBKEY, SALE_TOTAL, DECIMALS, "EUR", SALE_NONCE)),
                    Hex.toHexString(IssuanceWarrant.saleDigest(
                            STALL_PUBKEY, SALE_TOTAL, DECIMALS, "XAF", SALE_NONCE)),
                    "2500 is not a number, it is EUR 25.00 or XAF 2500");
        }

        @Test
        @DisplayName("distinguishes two identical sales via the nonce")
        void nonceSeparatesIdenticalSales() {
            // Without this, a merchant who sells EUR 25.00 twice produces one digest twice, and
            // the warrant from the first sale authorises coupons from the second - letting a
            // compromised service mint a free second batch off one real authorisation.
            assertNotEquals(
                    Hex.toHexString(IssuanceWarrant.saleDigest(
                            STALL_PUBKEY, SALE_TOTAL, DECIMALS, UNIT, "aaaa")),
                    Hex.toHexString(IssuanceWarrant.saleDigest(
                            STALL_PUBKEY, SALE_TOTAL, DECIMALS, UNIT, "bbbb")));
        }

        @Test
        @DisplayName("is deterministic")
        void digestIsStable() {
            // The device signs it and a verifier recomputes it, possibly in another language.
            // Any instability here is a coupon that verifies on one build and not another.
            assertArrayEquals(
                    IssuanceWarrant.saleDigest(STALL_PUBKEY, SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE),
                    IssuanceWarrant.saleDigest(STALL_PUBKEY, SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE));
        }

        @Test
        @DisplayName("refuses a negative sale total rather than hashing it")
        void negativeTotalIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> IssuanceWarrant.saleDigest(
                    STALL_PUBKEY, -1L, DECIMALS, UNIT, SALE_NONCE));
        }
    }

    @Nested
    @DisplayName("the ceiling, which is what makes a SALE warrant usable per coupon")
    class TheCeiling {

        @Test
        @DisplayName("covers every part of an auto-split sale")
        void coversSplitParts() {
            // One Sell action mints several coupons. The device could not know how many, so the
            // warrant covers the TOTAL and each part is checked against it. This is the property
            // that makes a sale-level warrant workable at all.
            long[] parts = {834L, 833L, 833L}; // EUR 25.00 split three ways
            long sum = 0;
            for (long part : parts) {
                assertTrue(IssuanceWarrant.coversFaceValue(SALE_TOTAL, part));
                sum += part;
            }
            assertEquals(SALE_TOTAL, sum, "the split must preserve the total it was warranted for");
        }

        @Test
        @DisplayName("keeps covering a coupon as it is spent down")
        void survivesRepeatedSplits() {
            // A coupon carries its issued bytes forever, so it keeps this warrant while its
            // face value only ever falls. A ceiling stays true across every split with nobody
            // re-signing - which matters because the stall is not present at split time and
            // could not re-sign if asked.
            long held = SALE_TOTAL;
            for (long spend : new long[]{400L, 600L, 900L}) {
                held -= spend;
                assertTrue(IssuanceWarrant.coversFaceValue(SALE_TOTAL, held),
                        "spending down must never invalidate the warrant");
            }
            assertEquals(600L, held);
        }

        @Test
        @DisplayName("refuses a coupon larger than the sale it claims to come from")
        void refusesOversizedCoupon() {
            assertFalse(IssuanceWarrant.coversFaceValue(SALE_TOTAL, SALE_TOTAL + 1));
        }

        @Test
        @DisplayName("refuses negative values rather than clamping them")
        void refusesNegatives() {
            // A negative face value is not a small coupon, it is a coupon whose encoding we do
            // not understand. Clamping would turn a parse failure into an acceptance.
            assertFalse(IssuanceWarrant.coversFaceValue(SALE_TOTAL, -1L));
            assertFalse(IssuanceWarrant.coversFaceValue(-1L, 1L));
        }
    }

    @Nested
    @DisplayName("forms")
    class Forms {

        @Test
        @DisplayName("round-trip through their wire tokens")
        void formsRoundTrip() {
            for (IssuanceWarrant.Form form : IssuanceWarrant.Form.values()) {
                assertEquals(form, IssuanceWarrant.Form.fromWire(form.wire()));
            }
        }

        @Test
        @DisplayName("an unknown form is REFUSED, never defaulted")
        void unknownFormThrows() {
            // Defaulting an unrecognised form to NONE would let a future or hostile shape read
            // as "the issuer signed that nothing authorised this", which is a claim nobody made.
            assertThrows(IllegalArgumentException.class,
                    () -> IssuanceWarrant.Form.fromWire("merchant-v2"));
            assertThrows(IllegalArgumentException.class,
                    () -> IssuanceWarrant.Form.fromWire(""));
            assertThrows(IllegalArgumentException.class,
                    () -> IssuanceWarrant.Form.fromWire(null));
        }

        @Test
        @DisplayName("NONE is a signed statement, and it is not the same as absent")
        void noneIsDistinctFromAbsent() {
            // The enum cannot express "absent" at all, and that is deliberate: absence is the
            // lack of a tag, not a value of one. Readers must keep them apart, because
            // conflating them lets a stripped warrant read as a legacy voucher.
            assertEquals("none", IssuanceWarrant.Form.NONE.wire());
            assertThrows(IllegalArgumentException.class,
                    () -> IssuanceWarrant.Form.fromWire(null));
        }
    }

    @Nested
    @DisplayName("malformed input is refused rather than thrown at a redeeming merchant")
    class Malformed {

        @Test
        @DisplayName("a signature of the wrong length is refused")
        void shortSignatureRefused() {
            assertFalse(IssuanceWarrant.verifyMerchant(
                    STALL_PUBKEY, SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE, "abcd", SALE_TOTAL));
        }

        @Test
        @DisplayName("a pubkey of the wrong length is refused")
        void shortPubkeyRefused() {
            String signature = signAsStall(
                    IssuanceWarrant.saleDigest(STALL_PUBKEY, SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE));

            assertFalse(IssuanceWarrant.verifyMerchant(
                    "abcd", SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE, signature, SALE_TOTAL));
        }

        @Test
        @DisplayName("non-hex input is refused rather than propagating")
        void nonHexRefused() {
            String notHex = "z".repeat(128);
            assertFalse(IssuanceWarrant.verifyMerchant(
                    STALL_PUBKEY, SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE, notHex, SALE_TOTAL));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String signAsStall(byte[] digest) {
        return sign(digest, STALL_PRIVATE_KEY);
    }

    private static String sign(byte[] digest, byte[] privateKey) {
        try {
            byte[] auxRand = new byte[32];
            RANDOM.nextBytes(auxRand);
            return Hex.toHexString(Schnorr.sign(digest, privateKey, auxRand));
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign test digest", e);
        }
    }

    private static byte[] privateKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        // Keep it comfortably inside the curve order; the top byte dominates the risk.
        key[0] = 0x01;
        return key;
    }

    private static String pubkeyOf(byte[] privateKey) {
        try {
            return Hex.toHexString(Schnorr.genPubKey(privateKey));
        } catch (Exception e) {
            throw new IllegalStateException("failed to derive test pubkey", e);
        }
    }
}
