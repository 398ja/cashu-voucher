package xyz.tcheeric.cashu.voucher.domain;

import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;

import java.security.SecureRandom;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        assertFalse(IssuanceWarrant.verifyTerminal(
                STALL, credential, TRUSTED, 500_000L, DECIMALS, UNIT, NONCE, signedFor2500, 500_000L));
    }

    @Test
    @DisplayName("a coupon bigger than the warranted sale is refused")
    void couponAboveCeilingIsRefused() {
        // Same ceiling as the merchant form: the warrant covers EUR 25.00, not this coupon.
        P2PKVoucherSecret credential = credential(STALL, "issue-and-redeem", TILL_PUBKEY, true);

        assertFalse(IssuanceWarrant.verifyTerminal(STALL, credential, TRUSTED, SALE_TOTAL, DECIMALS, UNIT,
                NONCE, tillSignature(SALE_TOTAL), SALE_TOTAL + 1));
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

    // ------------------------------------------------------------------ helpers

    private static boolean verify(
            String issuerId, P2PKVoucherSecret credential, long saleTotal, String signature) {
        return IssuanceWarrant.verifyTerminal(
                issuerId, credential, TRUSTED, saleTotal, DECIMALS, UNIT, NONCE, signature, saleTotal);
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

    private static P2PKVoucherSecret locked(String issuerId, String xOnlyLockKey, String metadata) {
        P2PKVoucherSecret secret = new P2PKVoucherSecret(Hex.decode("02" + xOnlyLockKey));
        secret.setVoucherId(UUID.randomUUID().toString());
        secret.setIssuerId(issuerId);
        secret.setUnit("sat");
        secret.setFaceValue(1L);
        secret.setMerchantMetadata(metadata);
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
