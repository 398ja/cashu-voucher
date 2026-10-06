package xyz.tcheeric.cashu.voucher.domain;

import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code terminal} warrant: a till's own key signs the sale, and the warrant carries the
 * credential the stall issued to that key.
 *
 * <p>Each refusal below is a way someone could try to sell coupons in a stall's name without
 * the stall having handed them a till that may sell. The one happy-path test exists so that
 * every refusal is a refusal of ONE changed thing, not of a fixture that never worked.
 */
class IssuanceWarrantTerminalTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The stall that owns the till. It signs nothing here; the gateway minted the credential. */
    private static final String STALL = pubkeyOf(privateKey());

    /** Some other stall, for the "credential from the wrong shop" case. */
    private static final String OTHER_STALL = pubkeyOf(privateKey());

    /** The issuing service, which signs every voucher it mints, credentials included. */
    private static final byte[] SERVICE_KEY = privateKey();

    /** The service keys a verifier trusts to mint credentials. */
    private static final Set<String> TRUSTED = Set.of(pubkeyOf(SERVICE_KEY));

    /** The till's own key, K. It never leaves the device. */
    private static final byte[] TILL_KEY = privateKey();
    private static final String TILL_PUBKEY = pubkeyOf(TILL_KEY);

    /** Someone who has a copy of the credential but not the till's key. */
    private static final byte[] THIEF_KEY = privateKey();

    private static final String NONCE = "9a8b7c6d5e4f30211203f4e5d6c7b8a9";
    private static final long SALE_TOTAL = 2500L;
    private static final int DECIMALS = 2;
    private static final String UNIT = "EUR";

    @Test
    @DisplayName("a sell-and-redeem till's signature over the sale verifies")
    void genuineTerminalWarrantVerifies() {
        // The baseline every refusal below departs from by one thing.
        P2PKVoucherSecret credential = credential(STALL, "issue-and-redeem", TILL_PUBKEY, true);

        assertTrue(verify(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL)));
    }

    @Test
    @DisplayName("a credential issued for one stall cannot warrant a sale for another")
    void wrongIssuerIsRefused() {
        // The till belongs to OTHER_STALL. It signs a perfectly good sale, but names STALL as
        // the shop on the hook. A till must only ever sell for the stall that issued it.
        P2PKVoucherSecret credential =
                credential(OTHER_STALL, "issue-and-redeem", TILL_PUBKEY, true);

        assertFalse(verify(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL)));
    }

    @Test
    @DisplayName("a credential whose service signature does not check out is refused")
    void forgedCredentialSignatureIsRefused() {
        // A genuine redeem-only credential, upgraded to sell-and-redeem after the service
        // signed it. The signature no longer covers what the credential now says.
        P2PKVoucherSecret credential = credential(STALL, "redeem-only", TILL_PUBKEY, true);
        credential.setMerchantMetadata(metadata(STALL, "issue-and-redeem", TILL_PUBKEY));

        assertFalse(verify(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL)));
    }

    @Test
    @DisplayName("a credential signed by a key the verifier does not trust is refused")
    void credentialFromUntrustedSignerIsRefused() {
        // Someone writes a credential for the stall, names their own key as the till, and signs
        // it with their own "service" key. The signature checks out against the key written
        // inside it, which is why that key alone proves nothing.
        P2PKVoucherSecret credential = locked(STALL, TILL_PUBKEY,
                metadata(STALL, "issue-and-redeem", TILL_PUBKEY));
        SignedLockedVoucher.createSigned(
                credential, Hex.toHexString(THIEF_KEY), pubkeyOf(THIEF_KEY));

        assertFalse(verify(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL)));
    }

    @Test
    @DisplayName("an unsigned credential is refused")
    void unsignedCredentialIsRefused() {
        // No service signature at all: anyone could have written this.
        P2PKVoucherSecret credential = credential(STALL, "issue-and-redeem", TILL_PUBKEY, false);

        assertFalse(verify(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL)));
    }

    @Test
    @DisplayName("a redeem-only till cannot warrant a sale")
    void redeemOnlyRoleIsRefused() {
        // The owner gave this device permission to take coupons, not to sell them. Its key
        // signing a sale must not turn it into a till that may sell.
        P2PKVoucherSecret credential = credential(STALL, "redeem-only", TILL_PUBKEY, true);

        assertFalse(verify(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL)));
    }

    @Test
    @DisplayName("a sale signed by a key other than the credential's lock key is refused")
    void signatureByAnotherKeyIsRefused() {
        // The credential is public enough to be copied. What makes it authority is that only
        // the till holds K. A thief signing with their own key gets nothing.
        P2PKVoucherSecret credential = credential(STALL, "issue-and-redeem", TILL_PUBKEY, true);
        String thiefSignature = sign(digest(SALE_TOTAL), THIEF_KEY);

        assertFalse(verify(STALL, credential, SALE_TOTAL, thiefSignature));
    }

    @Test
    @DisplayName("a warrant whose sale total was raised after signing is refused")
    void tamperedSaleTotalIsRefused() {
        // The till signed EUR 25.00. Someone raises the warrant to EUR 5,000.00 so it covers a
        // much bigger coupon. The signature is over the old total, so it must fail.
        P2PKVoucherSecret credential = credential(STALL, "issue-and-redeem", TILL_PUBKEY, true);
        String signedFor2500 = tillSignature(SALE_TOTAL);

        assertFalse(IssuanceWarrant.verifyTerminal(sale(STALL, credential, 500_000L, signedFor2500)
                .couponFaceMinor(500_000L).build()).isValid());
    }

    @Test
    @DisplayName("a coupon bigger than the warranted sale is refused")
    void couponAboveCeilingIsRefused() {
        // Same ceiling as the merchant form: the warrant covers EUR 25.00, not this coupon.
        P2PKVoucherSecret credential = credential(STALL, "issue-and-redeem", TILL_PUBKEY, true);

        assertRefused(IssuanceWarrant.TerminalRefusal.COUPON_ABOVE_SALE,
                sale(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL))
                        .couponFaceMinor(SALE_TOTAL + 1).build());
    }

    @Test
    @DisplayName("an ordinary coupon cannot stand in for a terminal credential")
    void nonTerminalMetadataIsRefused() {
        // A genuine, service-signed coupon whose metadata is not a terminal credential, even
        // though it carries the right-looking keys. Without "terminal": true it grants nothing.
        P2PKVoucherSecret credential = signed(locked(STALL, TILL_PUBKEY,
                "{\"stall_pubkey\":\"" + STALL + "\",\"role\":\"issue-and-redeem\","
                        + "\"lock_key\":\"" + TILL_PUBKEY + "\"}"));

        assertFalse(verify(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL)));
    }

    @Test
    @DisplayName("\"terminal\": \"true\" as a string is not a terminal")
    void terminalAsStringIsRefused() {
        // Mirrors the wallet and gateway: terminal must be the boolean true, not merely truthy.
        P2PKVoucherSecret credential = signed(locked(STALL, TILL_PUBKEY,
                metadata(STALL, "issue-and-redeem", TILL_PUBKEY)
                        .replace("\"terminal\":true", "\"terminal\":\"true\"")));

        assertFalse(verify(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL)));
    }

    @Test
    @DisplayName("a credential whose mint lock is not its lock_key is refused")
    void lockMismatchIsRefused() {
        // The metadata names the till's key, but the coupon is actually locked to the thief.
        // Whoever holds the lock could spend (burn or keep) the credential, so the two must agree.
        P2PKVoucherSecret credential = signed(locked(STALL, pubkeyOf(THIEF_KEY),
                metadata(STALL, "issue-and-redeem", TILL_PUBKEY)));

        assertFalse(verify(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL)));
    }

    @Test
    @DisplayName("the terminal form has its own wire name, and delegated is unchanged")
    void wireNames() {
        assertEquals(IssuanceWarrant.Form.TERMINAL, IssuanceWarrant.Form.fromWire("terminal"));
        assertEquals(IssuanceWarrant.Form.DELEGATED, IssuanceWarrant.Form.fromWire("delegated"));
    }

    @Test
    @DisplayName("an empty trust list refuses everything rather than trusting everyone")
    void emptyTrustListRefuses() {
        // "No keys configured" must never mean "allow all". A verifier that forgot to configure
        // its trusted service keys must refuse a perfectly genuine credential.
        assertRefused(IssuanceWarrant.TerminalRefusal.UNTRUSTED_SERVICE_KEY,
                genuineSale().trustedServiceKeys(List.of()).build());
    }

    @Test
    @DisplayName("a missing trust list is a programming error, not an open door")
    void nullTrustListThrows() {
        // The trust list is mandatory: leaving it out fails loudly at construction.
        assertThrows(NullPointerException.class,
                () -> genuineSale().trustedServiceKeys(null).build());
    }

    @Test
    @DisplayName("a credential naming another stall is refused even when its metadata names ours")
    void wrongIssuerIdWithRightStallPubkeyIsRefused() {
        // Only issuer_id is wrong: the metadata still claims our stall. The issuer_id check
        // alone must catch it.
        P2PKVoucherSecret credential = signed(locked(OTHER_STALL, TILL_PUBKEY,
                metadata(STALL, "issue-and-redeem", TILL_PUBKEY)));

        assertRefused(IssuanceWarrant.TerminalRefusal.WRONG_STALL,
                sale(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL)).build());
    }

    @Test
    @DisplayName("a credential whose metadata names another stall is refused even when issuer_id is ours")
    void rightIssuerIdWithWrongStallPubkeyIsRefused() {
        // Only stall_pubkey is wrong: issuer_id is our stall. The stall_pubkey check alone
        // must catch it.
        P2PKVoucherSecret credential = signed(locked(STALL, TILL_PUBKEY,
                metadata(OTHER_STALL, "issue-and-redeem", TILL_PUBKEY)));

        assertRefused(IssuanceWarrant.TerminalRefusal.NOT_A_SELLING_TERMINAL,
                sale(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL)).build());
    }

    @Test
    @DisplayName("an expired credential cannot warrant a new sale when the portal asks as of now")
    void expiredCredentialIsRefusedAsOfNow() {
        // The till's year is up. At issuance the portal passes "now", so it must refuse.
        P2PKVoucherSecret credential = expiringCredential(1_000L);

        assertRefused(IssuanceWarrant.TerminalRefusal.CREDENTIAL_EXPIRED,
                sale(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL))
                        .expiryCheck(IssuanceWarrant.ExpiryCheck.at(2_000L)).build());
    }

    @Test
    @DisplayName("an offline verifier that passes no instant does not check expiry")
    void expiredCredentialStillVerifiesOffline() {
        // A coupon sold while the till was valid stays valid after the till expires, so a
        // wallet verifying an issued coupon passes null and the check is skipped.
        P2PKVoucherSecret credential = expiringCredential(1_000L);

        assertTrue(IssuanceWarrant.verifyTerminal(
                sale(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL)).build()).isValid());
    }

    @Test
    @DisplayName("a credential still inside its validity passes the as-of check")
    void unexpiredCredentialPassesAsOfCheck() {
        // Same as above, but asked before the expiry instant: nothing to refuse.
        P2PKVoucherSecret credential = expiringCredential(1_000L);

        assertTrue(IssuanceWarrant.verifyTerminal(
                sale(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL))
                        .expiryCheck(IssuanceWarrant.ExpiryCheck.at(999L)).build()).isValid());
    }

    @Test
    @DisplayName("a nonce carrying the digest separator is refused")
    void separatorInNonceIsRefused() {
        // A nonce of "N<US>REF" would make the terminal digest equal a delegated digest, so a
        // delegate's signature could be replayed as a till's. No control characters allowed.
        assertRefused(IssuanceWarrant.TerminalRefusal.MALFORMED_SALE,
                genuineSale().saleNonce(NONCE + "\u001fREF").build());
    }

    @Test
    @DisplayName("a unit carrying a control character is refused")
    void controlCharacterInUnitIsRefused() {
        // Same reasoning for the unit: it is a digest field, so it must not hold a separator.
        assertRefused(IssuanceWarrant.TerminalRefusal.MALFORMED_SALE,
                genuineSale().unit("EUR\u001f").build());
    }

    @Test
    @DisplayName("an uppercase stall id is refused rather than hashed differently")
    void uppercaseIssuerIdIsRefused() {
        // The wallet signs lowercase hex. An uppercase id would hash to different bytes, so
        // it is refused up front instead of failing confusingly at the signature.
        assertRefused(IssuanceWarrant.TerminalRefusal.MALFORMED_SALE,
                genuineSale().issuerId(STALL.toUpperCase(Locale.ROOT)).build());
    }

    @Test
    @DisplayName("a credential whose lock_key is the stall's own key is refused")
    void lockKeyEqualToStallIsRefused() {
        // Such a credential would let any merchant warrant (signed by the stall) pass as a
        // terminal one, so the two forms would no longer be separated.
        byte[] stallKey = privateKey();
        String stall = pubkeyOf(stallKey);
        P2PKVoucherSecret credential = signed(locked(stall, stall,
                metadata(stall, "issue-and-redeem", stall)));
        String signature = sign(IssuanceWarrant.saleDigest(stall, SALE_TOTAL, DECIMALS, UNIT, NONCE),
                stallKey);

        assertRefused(IssuanceWarrant.TerminalRefusal.LOCK_IS_STALL,
                sale(stall, credential, SALE_TOTAL, signature).build());
    }

    @Test
    @DisplayName("a sale total above 2^53-1 is refused, since the wallet cannot hash it exactly")
    void saleTotalAboveSafeIntegerIsRefused() {
        // JavaScript loses precision above 2^53-1, so Java and the wallet would disagree.
        assertRefused(IssuanceWarrant.TerminalRefusal.MALFORMED_SALE,
                genuineSale().saleTotalMinor(1L << 53).couponFaceMinor(1L).build());
    }

    @Test
    @DisplayName("decimals outside 0..18 are refused")
    void faceDecimalsOutOfRangeIsRefused() {
        // The portal allows 0..18. Anything else is not a currency we understand.
        assertRefused(IssuanceWarrant.TerminalRefusal.MALFORMED_SALE,
                genuineSale().faceDecimals(19).build());
        assertRefused(IssuanceWarrant.TerminalRefusal.MALFORMED_SALE,
                genuineSale().faceDecimals(-1).build());
    }

    @Test
    @DisplayName("a negative sale total or coupon is refused")
    void negativeAmountsAreRefused() {
        // A negative amount is not a small one, it is an encoding we do not understand.
        assertRefused(IssuanceWarrant.TerminalRefusal.MALFORMED_SALE,
                genuineSale().saleTotalMinor(-1L).build());
        assertRefused(IssuanceWarrant.TerminalRefusal.MALFORMED_SALE,
                genuineSale().couponFaceMinor(-1L).build());
    }

    @Test
    @DisplayName("metadata with trailing garbage is refused, as the wallet's JSON.parse would")
    void trailingGarbageInMetadataIsRefused() {
        // Java and the wallet must read the same credential the same way.
        P2PKVoucherSecret credential = signed(locked(STALL, TILL_PUBKEY,
                metadata(STALL, "issue-and-redeem", TILL_PUBKEY) + "xyz"));

        assertRefused(IssuanceWarrant.TerminalRefusal.NOT_A_SELLING_TERMINAL,
                sale(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL)).build());
    }

    @Test
    @DisplayName("rewriting expires_at to a fraction cannot strip an expired credential's expiry")
    void fractionalExpiryRewriteIsRefused() {
        // Review N1. The till operator holds the credential. Rewriting "1000" to "1000.5" made
        // the legacy truncated form hash the same bytes, so the service signature still
        // verified, while getExpiresAt() could no longer parse it and returned null, so the
        // expired till kept selling. Terminal credentials postdate the canonical form, so
        // the signature must verify strictly and this rewrite must fail it.
        String wire = expiringCredential(1_000L).toString();
        String tampered = wire.replace("[\"expires_at\",\"1000\"]", "[\"expires_at\",\"1000.5\"]");
        assertFalse(tampered.equals(wire), "fixture must actually rewrite the tag");

        assertRefused(IssuanceWarrant.TerminalRefusal.BAD_CREDENTIAL_SIGNATURE,
                sale(STALL, IssuanceWarrant.parseCredential(tampered), SALE_TOTAL,
                        tillSignature(SALE_TOTAL)).expiryCheck(IssuanceWarrant.ExpiryCheck.at(2_000L)).build());
    }

    @Test
    @DisplayName("a credential that only the legacy truncated form verifies is refused")
    void legacyFormCredentialIsRefused() {
        // Review N1, the direct check. A terminal credential has no legacy form, so a
        // signature that verifies only over the truncated bytes (here a ratio of 0.5 signed as
        // 0) must be refused even though plain VoucherSignatureService.verify accepts it.
        P2PKVoucherSecret secret = locked(STALL, TILL_PUBKEY,
                metadata(STALL, "issue-and-redeem", TILL_PUBKEY));
        secret.setTag("issuance_ratio", List.of("0.5"));
        byte[] legacyDigest = sha256(VoucherCanonicalBytes.of(
                secret, VoucherCanonicalBytes.NumericTagForm.TRUNCATED_TO_LONG));
        secret.setIssuerSignature(sign(legacyDigest, SERVICE_KEY));
        secret.setIssuerPublicKey(pubkeyOf(SERVICE_KEY));
        assertTrue(VoucherSignatureService.verify(secret), "fixture: the legacy window accepts it");

        assertRefused(IssuanceWarrant.TerminalRefusal.BAD_CREDENTIAL_SIGNATURE,
                sale(STALL, secret, SALE_TOTAL, tillSignature(SALE_TOTAL)).build());
    }

    private static byte[] sha256(byte[] input) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(input);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a credential whose expires_at is signed but not an integer is refused")
    void nonIntegerExpiryIsRefusedEvenWhenSigned() {
        // Belt and braces for N1: even a credential the service really signed with a
        // fractional expires_at must not be read as "no expiry". An expiry we cannot read
        // is a malformed credential, not an immortal one.
        P2PKVoucherSecret secret = locked(STALL, TILL_PUBKEY,
                metadata(STALL, "issue-and-redeem", TILL_PUBKEY));
        secret.setTag("expires_at", List.of("1000.5"));
        P2PKVoucherSecret credential = signed(secret);

        assertRefused(IssuanceWarrant.TerminalRefusal.MALFORMED_CREDENTIAL,
                sale(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL))
                        .expiryCheck(IssuanceWarrant.ExpiryCheck.at(500L)).build());
    }

    @Test
    @DisplayName("a sale that does not choose an expiry check does not build")
    void omittedExpiryCheckDoesNotBuild() {
        // Review N2. Before, leaving the as-of instant out meant null, which meant "skip", so
        // a caller that forgot it let expired tills sell. Now there is no default: the caller
        // must say ExpiryCheck.at(now) or ExpiryCheck.skipOffline().
        assertThrows(NullPointerException.class, () -> IssuanceWarrant.TerminalSale.builder()
                .issuerId(STALL)
                .credential(credential(STALL, "issue-and-redeem", TILL_PUBKEY, true))
                .trustedServiceKeys(TRUSTED).saleTotalMinor(SALE_TOTAL).faceDecimals(DECIMALS)
                .unit(UNIT).saleNonce(NONCE).signatureHex(tillSignature(SALE_TOTAL))
                .couponFaceMinor(SALE_TOTAL).build());
    }

    @Test
    @DisplayName("a credential with no expires_at is refused")
    void credentialWithoutExpiryIsRefused() {
        // Review N2. Spec section 2.7 says credentials last 365 days. One with no expiry at all
        // would sell forever, so it is malformed, not immortal.
        P2PKVoucherSecret credential = signed(locked(STALL, TILL_PUBKEY,
                metadata(STALL, "issue-and-redeem", TILL_PUBKEY), null));

        assertRefused(IssuanceWarrant.TerminalRefusal.MALFORMED_CREDENTIAL,
                sale(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL))
                        .expiryCheck(IssuanceWarrant.ExpiryCheck.at(500L)).build());
    }

    @Test
    @DisplayName("at the exact expiry second the credential is still valid, as at the mint")
    void expiryBoundaryMatchesMint() {
        // Review N6. cashu-mint's VoucherSpendingCondition refuses only when now > expires_at,
        // so expires_at itself is the last valid second. The portal must not be one second
        // stricter than the mint that will later honour the coupon.
        P2PKVoucherSecret credential = expiringCredential(1_000L);

        assertTrue(IssuanceWarrant.verifyTerminal(
                sale(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL))
                        .expiryCheck(IssuanceWarrant.ExpiryCheck.at(1_000L)).build()).isValid());
        assertRefused(IssuanceWarrant.TerminalRefusal.CREDENTIAL_EXPIRED,
                sale(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL))
                        .expiryCheck(IssuanceWarrant.ExpiryCheck.at(1_001L)).build());
    }

    @Test
    @DisplayName("a lone surrogate in the nonce or unit is refused")
    void loneSurrogateIsRefused() {
        // Review N3. Java's UTF-8 encoder turns an unpaired surrogate into '?', so "n1\uD800"
        // shared a signature with "n1?": two nonces, one digest, a replay past a nonce ledger.
        // TypeScript emits U+FFFD instead, so the two sides disagreed too. Refuse it outright.
        assertRefused(IssuanceWarrant.TerminalRefusal.MALFORMED_SALE, genuineSale()
                .saleNonce("n1\uD800").signatureHex(sign(
                        IssuanceWarrant.saleDigest(STALL, SALE_TOTAL, DECIMALS, UNIT, "n1?"),
                        TILL_KEY)).build());
        assertRefused(IssuanceWarrant.TerminalRefusal.MALFORMED_SALE,
                genuineSale().unit("EU\uDC00").build());
    }

    @Test
    @DisplayName("a correctly paired surrogate (an emoji) is still accepted")
    void pairedSurrogateIsAccepted() {
        // The N3 check must refuse only MALFORMED UTF-16, not every non-BMP character.
        String nonce = "n1\uD83D\uDE00";
        assertTrue(IssuanceWarrant.verifyTerminal(genuineSale().saleNonce(nonce).signatureHex(sign(
                IssuanceWarrant.saleDigest(STALL, SALE_TOTAL, DECIMALS, UNIT, nonce), TILL_KEY))
                .build()).isValid());
    }

    @Test
    @DisplayName("parseCredential refuses any wire form other than the canonical one")
    void parseCredentialRefusesNonCanonicalWireForms() {
        // Review N4. Padding, an extra array element and a duplicated key all parsed to the
        // same credential, so one credential had many wire forms. Only the exact form the
        // gateway serialises is accepted now.
        String wire = credential(STALL, "issue-and-redeem", TILL_PUBKEY, true).toString();
        assertThrows(IllegalArgumentException.class,
                () -> IssuanceWarrant.parseCredential("  " + wire + "\n"));
        assertThrows(IllegalArgumentException.class,
                () -> IssuanceWarrant.parseCredential(wire.replaceFirst("]$", ",1]")));
        assertThrows(IllegalArgumentException.class, () -> IssuanceWarrant.parseCredential(
                wire.replaceFirst("\"data\":", "\"data\":\"02" + STALL + "\",\"data\":")));
    }

    @Test
    @DisplayName("TerminalVerdict cannot be constructed from outside, only by verifyTerminal")
    void terminalVerdictHasNoPublicConstructor() {
        // Review N5. Lombok's @Value made new TerminalVerdict(null) public, and null means
        // valid, so any caller could mint a "valid" verdict without verifying anything.
        for (var constructor : IssuanceWarrant.TerminalVerdict.class.getDeclaredConstructors()) {
            assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()),
                    "constructor must be private: " + constructor);
        }
    }

    @Test
    @DisplayName("metadata with a duplicated key is refused rather than last-one-wins")
    void duplicateKeyInMetadataIsRefused() {
        // Two "role" keys leave the meaning up to whichever parser reads it. Refuse instead.
        P2PKVoucherSecret credential = signed(locked(STALL, TILL_PUBKEY,
                metadata(STALL, "issue-and-redeem", TILL_PUBKEY)
                        .replace("{", "{\"role\":\"redeem-only\",")));

        assertRefused(IssuanceWarrant.TerminalRefusal.NOT_A_SELLING_TERMINAL,
                sale(STALL, credential, SALE_TOTAL, tillSignature(SALE_TOTAL)).build());
    }

    @Test
    @DisplayName("a credential round-trips through its wire form")
    void parseCredentialReadsWireSecret() {
        // The portal receives the credential as JSON. Parsing it must give back a credential
        // that still verifies, signature and all.
        P2PKVoucherSecret credential = credential(STALL, "issue-and-redeem", TILL_PUBKEY, true);
        P2PKVoucherSecret parsed = IssuanceWarrant.parseCredential(credential.toString());

        assertTrue(IssuanceWarrant.verifyTerminal(
                sale(STALL, parsed, SALE_TOTAL, tillSignature(SALE_TOTAL)).build()).isValid());
    }

    /**
     * A terminal credential exactly as cashu-lib 0.30.6 serialises it, frozen as text. It has
     * the till lock, a stall co-key, n_sigs, sigflag, an emoji in the till name and a real
     * service signature (fixed keys 0x01.., 0x02.., 0x03.. for service, stall and till).
     */
    private static final String GOLDEN_CREDENTIAL_WIRE = "[\"P2PK_VOUCHER\",{"
            + "\"nonce\":\"9a8b7c6d5e4f30211203f4e5d6c7b8a9\","
            + "\"data\":\"02531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe337\","
            + "\"tags\":[[\"n_sigs\",\"1\"],[\"sigflag\",\"SIG_ALL\"],"
            + "[\"pubkeys\",\"024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766\"],"
            + "[\"voucher_id\",\"6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f\"],"
            + "[\"issuer\",\"4d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766\"],"
            + "[\"unit\",\"sat\"],[\"face_value\",\"1\"],[\"expires_at\",\"4000000000\"],"
            + "[\"merchant_metadata\",\"{\\\"terminal\\\":true,"
            + "\\\"stall_pubkey\\\":\\\"4d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766\\\","
            + "\\\"role\\\":\\\"issue-and-redeem\\\","
            + "\\\"lock_key\\\":\\\"531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe337\\\","
            + "\\\"name\\\":\\\"Pizza till \uD83C\uDF55\\\"}\"],"
            + "[\"issuer_sig\",\"e83f6f38db9679b773c9d6c6fc01cecbcecd6411c43d35569854094c44ef06cf"
            + "85da8aad04444bcdea87132097e535fc9a45dc48faee439b101c4efef0f62b82\"],"
            + "[\"issuer_pubkey\",\"1b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f\"]]}]";

    @Test
    @DisplayName("a frozen credential wire string still parses byte-exact and still verifies")
    void parseCredentialAcceptsGoldenWireString() {
        // Review r3 M1. parseCredential only accepts what this build's cashu-lib serialiser
        // would write, and credentials live for a year. If a cashu-lib upgrade changes that
        // output (say n_sigs as a number, or the emoji escaped), every credential already in
        // a till would be refused in production. This literal was written by today's
        // serialiser, so such a change fails here in CI instead.
        P2PKVoucherSecret parsed = IssuanceWarrant.parseCredential(GOLDEN_CREDENTIAL_WIRE);

        assertEquals(GOLDEN_CREDENTIAL_WIRE, parsed.toString());
        assertTrue(VoucherSignatureService.verifyStrict(parsed),
                "the frozen service signature must still verify over the parsed credential");
    }

    @Test
    @DisplayName("parseCredential refuses a secret that is not a P2PK_VOUCHER")
    void parseCredentialRefusesOtherKinds() {
        // A plain P2PK secret is not a credential, whatever it carries.
        assertThrows(IllegalArgumentException.class, () -> IssuanceWarrant.parseCredential(
                "[\"P2PK\",{\"nonce\":\"00\",\"data\":\"02" + TILL_PUBKEY + "\",\"tags\":[]}]"));
        assertThrows(IllegalArgumentException.class, () -> IssuanceWarrant.parseCredential("nope"));
    }

    // ------------------------------------------------------------------ helpers

    private static P2PKVoucherSecret expiringCredential(long expiresAt) {
        P2PKVoucherSecret secret = locked(STALL, TILL_PUBKEY,
                metadata(STALL, "issue-and-redeem", TILL_PUBKEY));
        secret.setExpiresAt(expiresAt);
        return signed(secret);
    }

    private static boolean verify(
            String issuerId, P2PKVoucherSecret credential, long saleTotal, String signature) {
        return IssuanceWarrant.verifyTerminal(sale(issuerId, credential, saleTotal, signature).build())
                .isValid();
    }

    /** The genuine sale, as a builder, so a test can change exactly one field. */
    private static IssuanceWarrant.TerminalSale.TerminalSaleBuilder sale(
            String issuerId, P2PKVoucherSecret credential, long saleTotal, String signature) {
        return IssuanceWarrant.TerminalSale.builder()
                .issuerId(issuerId)
                .credential(credential)
                .trustedServiceKeys(TRUSTED)
                .saleTotalMinor(saleTotal)
                .faceDecimals(DECIMALS)
                .unit(UNIT)
                .saleNonce(NONCE)
                .signatureHex(signature)
                .couponFaceMinor(saleTotal)
                .expiryCheck(IssuanceWarrant.ExpiryCheck.skipOffline());
    }

    private static IssuanceWarrant.TerminalSale.TerminalSaleBuilder genuineSale() {
        return sale(STALL, credential(STALL, "issue-and-redeem", TILL_PUBKEY, true),
                SALE_TOTAL, tillSignature(SALE_TOTAL));
    }

    private static void assertRefused(
            IssuanceWarrant.TerminalRefusal expected, IssuanceWarrant.TerminalSale sale) {
        assertEquals(expected, IssuanceWarrant.verifyTerminal(sale).getRefusal());
    }

    private static byte[] digest(long saleTotal) {
        return IssuanceWarrant.saleDigest(STALL, saleTotal, DECIMALS, UNIT, NONCE);
    }

    private static String tillSignature(long saleTotal) {
        return sign(digest(saleTotal), TILL_KEY);
    }

    private static String metadata(String stall, String role, String lockKey) {
        return "{\"terminal\":true,\"stall_pubkey\":\"" + stall + "\",\"role\":\"" + role
                + "\",\"lock_key\":\"" + lockKey + "\",\"name\":\"Front till\"}";
    }

    /** A credential as the gateway mints it: issuer = stall, locked to the till, signed. */
    private static P2PKVoucherSecret credential(
            String stall, String role, String lockKey, boolean sign) {
        P2PKVoucherSecret secret = locked(stall, lockKey, metadata(stall, role, lockKey));
        return sign ? signed(secret) : secret;
    }

    /** A year-ish validity far in the future: every genuine credential carries an expiry. */
    private static final long FAR_EXPIRY = 4_000_000_000L;

    private static P2PKVoucherSecret locked(String issuerId, String xOnlyLockKey, String metadata) {
        return locked(issuerId, xOnlyLockKey, metadata, FAR_EXPIRY);
    }

    private static P2PKVoucherSecret locked(
            String issuerId, String xOnlyLockKey, String metadata, Long expiresAt) {
        P2PKVoucherSecret secret = new P2PKVoucherSecret(Hex.decode("02" + xOnlyLockKey));
        secret.setVoucherId(UUID.randomUUID().toString());
        secret.setIssuerId(issuerId);
        secret.setUnit("sat");
        secret.setFaceValue(1L);
        secret.setMerchantMetadata(metadata);
        if (expiresAt != null) {
            secret.setExpiresAt(expiresAt);
        }
        return secret;
    }

    private static P2PKVoucherSecret signed(P2PKVoucherSecret secret) {
        return SignedLockedVoucher.createSigned(
                secret, Hex.toHexString(SERVICE_KEY), pubkeyOf(SERVICE_KEY)).getSecret();
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
