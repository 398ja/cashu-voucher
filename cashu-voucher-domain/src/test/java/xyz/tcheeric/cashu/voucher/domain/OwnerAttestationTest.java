package xyz.tcheeric.cashu.voucher.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.AttestationFixtures.Attestation;
import xyz.tcheeric.cashu.voucher.domain.IssuanceWarrant.TerminalRefusal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The owner attestation (imani-wallet#160 decision 5, spec section 4.6): the stall's own key
 * says "this till's key K may act as this role until T", inside the credential the service
 * signs. Without it, the service key alone could mint a selling till for any stall.
 *
 * <p>Every test departs from one genuine, attested credential by exactly one change, so each
 * refusal is a refusal of that change and not of a fixture that never worked.
 */
class OwnerAttestationTest {

    private static final AttestationFixtures F = AttestationFixtures.INSTANCE;

    // ------------------------------------------------------------------ the digest

    @Test
    @DisplayName("the digest is sha256 of the domain and the six fields joined by U+001F")
    void digestIsTheSpecifiedPreimage() {
        // Spec 4.6.2, written out by hand so the implementation cannot quietly drift from it.
        String preimage = String.join("\u001f", "imani-terminal-attestation", "1", F.stall,
                F.till, "issue-and-redeem", "4000000000", F.attestationNonce);

        assertArrayEquals(sha256(preimage.getBytes(StandardCharsets.UTF_8)),
                IssuanceWarrant.attestationDigest("1", F.stall, F.till, "issue-and-redeem",
                        "4000000000", F.attestationNonce));
    }

    @Test
    @DisplayName("each of the six fields changes the digest")
    void everyFieldIsCovered() {
        // A field the digest ignores is a field the service can change without the stall's
        // signature noticing. Change each one alone and the digest must move.
        byte[] base = IssuanceWarrant.attestationDigest("1", F.stall, F.till, "issue-and-redeem",
                "4000000000", F.attestationNonce);
        List<byte[]> changed = List.of(
                IssuanceWarrant.attestationDigest("2", F.stall, F.till, "issue-and-redeem",
                        "4000000000", F.attestationNonce),
                IssuanceWarrant.attestationDigest("1", F.otherStall, F.till, "issue-and-redeem",
                        "4000000000", F.attestationNonce),
                IssuanceWarrant.attestationDigest("1", F.stall, F.thief, "issue-and-redeem",
                        "4000000000", F.attestationNonce),
                IssuanceWarrant.attestationDigest("1", F.stall, F.till, "redeem-only",
                        "4000000000", F.attestationNonce),
                IssuanceWarrant.attestationDigest("1", F.stall, F.till, "issue-and-redeem",
                        "4000000001", F.attestationNonce),
                IssuanceWarrant.attestationDigest("1", F.stall, F.till, "issue-and-redeem",
                        "4000000000", "00".repeat(32)));
        for (byte[] digest : changed) {
            assertFalse(java.util.Arrays.equals(base, digest));
        }
    }

    @Test
    @DisplayName("a field carrying the separator or another control character is refused")
    void controlCharacterInAFieldThrows() {
        // A separator inside a field would let two different field lists share one preimage.
        assertThrows(IllegalArgumentException.class, () -> IssuanceWarrant.attestationDigest(
                "1", F.stall, F.till, "issue\u001fand-redeem", "4000000000", F.attestationNonce));
        assertThrows(IllegalArgumentException.class, () -> IssuanceWarrant.attestationDigest(
                "1", F.stall, F.till, "issue-and-redeem", "4000000000\n", F.attestationNonce));
    }

    // ------------------------------------------------------------------ domain separation

    @Test
    @DisplayName("an attestation preimage can never equal a merchant sale preimage")
    void preimagesCannotCollide() {
        // A sale preimage starts with the stall's 64 lowercase hex characters. The attestation
        // preimage starts with "imani-terminal-attestation", which is not hex. So the two byte
        // strings differ in the first character, whatever the other fields are.
        String salePrefix = F.stall;
        assertTrue(salePrefix.matches("^[0-9a-f]{64}$"));
        assertFalse("imani-terminal-attestation".matches("^[0-9a-f].*"));
    }

    @Test
    @DisplayName("the stall's signature over a sale cannot be used as its attestation")
    void saleSignatureIsNotAnAttestation() {
        // Direction 1. A stall signs merchant warrants every day, and those are public in its
        // coupons. If one of those signatures could pass as an attestation, anyone holding a
        // merchant coupon could forge stall consent for a till.
        String saleSig = AttestationFixtures.sign(IssuanceWarrant.saleDigest(F.stall, 2500L, 2, "EUR",
                F.attestationNonce), F.stallKey);
        P2PKVoucherSecret credential = F.credential(F.attestation().sig(saleSig));

        assertRefusal(TerminalRefusal.BAD_OWNER_ATTESTATION,
                IssuanceWarrant.verifyOwnerAttestation(credential, F.stall));
    }

    @Test
    @DisplayName("the stall's attestation signature cannot be used as a merchant warrant")
    void attestationSignatureIsNotASaleWarrant() {
        // Direction 2. The attestation travels in every terminal coupon. Read as a merchant
        // warrant over the same-looking fields, it must not verify.
        String attestationSig = AttestationFixtures.sign(IssuanceWarrant.attestationDigest("1", F.stall, F.till,
                "issue-and-redeem", "4000000000", F.attestationNonce), F.stallKey);

        assertFalse(IssuanceWarrant.verifyMerchant(F.stall, 1L, 0, "1", F.attestationNonce,
                attestationSig, 1L));
        assertFalse(IssuanceWarrant.verifyMerchant(F.stall, 4_000_000_000L, 0, "issue-and-redeem",
                F.attestationNonce, attestationSig, 1L));
    }

    // ------------------------------------------------------------------ verifyOwnerAttestation

    @Test
    @DisplayName("a credential the stall attested verifies against the stall")
    void genuineAttestationVerifies() {
        // The baseline every refusal below departs from by one change.
        assertTrue(IssuanceWarrant.verifyOwnerAttestation(F.credential(F.attestation()), F.stall)
                .isValid());
    }

    @Test
    @DisplayName("a redeem-only credential's attestation also verifies: the role is the stall's choice")
    void redeemOnlyAttestationVerifies() {
        // gateway-customer validates an attestation whenever one is present, whatever the
        // role, so this check must not demand a selling role.
        Attestation a = F.attestation().role("redeem-only");
        assertTrue(IssuanceWarrant.verifyOwnerAttestation(F.credential("redeem-only", F.till, a),
                F.stall).isValid());
    }

    @Test
    @DisplayName("a credential with no attestation is refused as missing")
    void absentAttestationIsMissing() {
        // Every credential minted before 0.17.0 looks like this. Refuse and renew, no grace.
        assertRefusal(TerminalRefusal.OWNER_ATTESTATION_MISSING,
                IssuanceWarrant.verifyOwnerAttestation(F.credential(null), F.stall));
    }

    @Test
    @DisplayName("an attestation that is not exactly four canonical strings is refused as missing")
    void malformedAttestationIsMissing() {
        // One shape only, so Java and the wallet read every attestation the same way.
        // Each variant changes one thing about a genuinely signed attestation.
        Attestation a = F.attestation();
        String sig = a.getSig();
        String nonce = a.getNonce();
        String[] variants = {
                "\"not an object\"",
                "null",
                "[]",
                "{}",
                a.json().replace("}", ",\"extra\":\"x\"}"),
                a.json().replace(",\"nonce\":\"" + nonce + "\"", ""),
                a.json().replace("\"v\":\"1\"", "\"v\":1"),
                a.json().replace("\"v\":\"1\"", "\"v\":\"2\""),
                a.json().replace("\"expires_at\":\"4000000000\"", "\"expires_at\":4000000000"),
                a.json().replace("\"expires_at\":\"4000000000\"", "\"expires_at\":\"04000000000\""),
                a.json().replace("\"expires_at\":\"4000000000\"", "\"expires_at\":\"4000000000.0\""),
                a.json().replace("\"expires_at\":\"4000000000\"", "\"expires_at\":\"9999999999999999999\""),
                a.json().replace(nonce, nonce.toUpperCase(Locale.ROOT)),
                a.json().replace(nonce, nonce.substring(2)),
                a.json().replace(sig, sig.toUpperCase(Locale.ROOT)),
                a.json().replace(sig, sig.substring(2)),
                a.json().replace("\"" + sig + "\"", "null"),
        };
        for (String raw : variants) {
            assertFalse(raw.equals(a.json()), "fixture must change: " + raw);
            assertRefusal(TerminalRefusal.OWNER_ATTESTATION_MISSING,
                    IssuanceWarrant.verifyOwnerAttestation(F.credentialRaw(raw), F.stall), raw);
        }
    }

    @Test
    @DisplayName("an attestation signed by a key other than the stall's is refused")
    void attestationByAnotherKeyIsRefused() {
        // The service, or the till itself, signs "the stall consents". It does not.
        assertRefusal(TerminalRefusal.BAD_OWNER_ATTESTATION, IssuanceWarrant.verifyOwnerAttestation(
                F.credential(F.attestation().signedBy(F.serviceKey)), F.stall));
        assertRefusal(TerminalRefusal.BAD_OWNER_ATTESTATION, IssuanceWarrant.verifyOwnerAttestation(
                F.credential(F.attestation().signedBy(F.tillKey)), F.stall));
    }

    @Test
    @DisplayName("an attestation for a redeem-only till cannot be reused on a selling credential")
    void roleUpgradeIsRefused() {
        // The stall attested redeem-only. The service mints issue-and-redeem with that
        // attestation. The digest is rebuilt from the credential's own role, so it fails.
        Attestation signedForRedeemOnly = F.attestation().role("redeem-only");
        P2PKVoucherSecret credential = F.credential("issue-and-redeem", F.till, signedForRedeemOnly);

        assertRefusal(TerminalRefusal.BAD_OWNER_ATTESTATION,
                IssuanceWarrant.verifyOwnerAttestation(credential, F.stall));
    }

    @Test
    @DisplayName("an attestation for one till key cannot be moved onto another key's credential")
    void transplantToAnotherKeyIsRefused() {
        // The attestation is public. Copying it onto a credential for the thief's K must fail.
        P2PKVoucherSecret credential = F.credential("issue-and-redeem", F.thief, F.attestation());

        assertRefusal(TerminalRefusal.BAD_OWNER_ATTESTATION,
                IssuanceWarrant.verifyOwnerAttestation(credential, F.stall));
    }

    @Test
    @DisplayName("an attestation whose expires_at was raised after signing is refused")
    void raisedAttestationExpiryIsRefused() {
        // Raising T would let the service extend the till. The signature covers T.
        Attestation a = F.attestation();
        String raised = a.json().replace("\"4000000000\"", "\"4000000001\"");

        assertRefusal(TerminalRefusal.BAD_OWNER_ATTESTATION,
                IssuanceWarrant.verifyOwnerAttestation(F.credentialRaw(raised), F.stall));
    }

    @Test
    @DisplayName("a credential that outlives the attestation is refused")
    void credentialOutlivingTheAttestationIsRefused() {
        // The stall signed "until T". The service wrote T + 1 second as the credential's expiry.
        P2PKVoucherSecret credential = F.credential("issue-and-redeem", F.till, F.attestation(),
                4_000_000_001L);

        assertRefusal(TerminalRefusal.ATTESTATION_EXCEEDED,
                IssuanceWarrant.verifyOwnerAttestation(credential, F.stall));
    }

    @Test
    @DisplayName("a credential expiring exactly at T, or before it, is within the attestation")
    void credentialAtOrBeforeTVerifies() {
        // T is the last second the stall allowed, the same boundary as ExpiryCheck.
        assertTrue(IssuanceWarrant.verifyOwnerAttestation(
                F.credential("issue-and-redeem", F.till, F.attestation(), 4_000_000_000L), F.stall)
                .isValid());
        assertTrue(IssuanceWarrant.verifyOwnerAttestation(
                F.credential("issue-and-redeem", F.till, F.attestation(), 3_999_999_999L), F.stall)
                .isValid());
    }

    @Test
    @DisplayName("an attestation checked against a different stall is refused as the wrong stall")
    void checkedAgainstAnotherStallIsRefused() {
        // issuerId is the coupon's stall. A credential for our stall is no authority elsewhere.
        assertRefusal(TerminalRefusal.WRONG_STALL, IssuanceWarrant.verifyOwnerAttestation(
                F.credential(F.attestation()), F.otherStall));
    }

    @Test
    @DisplayName("an uppercase issuerId is refused rather than compared loosely")
    void uppercaseIssuerIdIsRefused() {
        // One stall, one spelling, as everywhere else in the terminal checks.
        assertRefusal(TerminalRefusal.WRONG_STALL, IssuanceWarrant.verifyOwnerAttestation(
                F.credential(F.attestation()), F.stall.toUpperCase(Locale.ROOT)));
    }

    @Test
    @DisplayName("a credential whose issuer tag is another stall is refused even if the metadata is ours")
    void issuerTagForAnotherStallIsRefused() {
        // The metadata and the attestation both name our stall, but the credential itself was
        // issued for another. The stall is read from both places and they must agree.
        P2PKVoucherSecret credential = F.credential(F.attestation());
        credential.setIssuerId(F.otherStall);

        assertRefusal(TerminalRefusal.WRONG_STALL,
                IssuanceWarrant.verifyOwnerAttestation(credential, F.stall));
    }

    @Test
    @DisplayName("metadata that is not a terminal is refused as a malformed credential")
    void unreadableMetadataIsMalformed() {
        // No terminal, no attestation to check: the caller handed us something else.
        P2PKVoucherSecret notTerminal = F.credentialMetadata(
                F.metadata("issue-and-redeem", F.till, F.attestation())
                        .replace("\"terminal\":true", "\"terminal\":false"));
        P2PKVoucherSecret upperStall = F.credentialMetadata(
                F.metadata("issue-and-redeem", F.till, F.attestation())
                        .replace("\"stall_pubkey\":\"" + F.stall, "\"stall_pubkey\":\""
                                + F.stall.toUpperCase(Locale.ROOT)));
        P2PKVoucherSecret upperLock = F.credentialMetadata(
                F.metadata("issue-and-redeem", F.till, F.attestation())
                        .replace("\"lock_key\":\"" + F.till, "\"lock_key\":\""
                                + F.till.toUpperCase(Locale.ROOT)));
        P2PKVoucherSecret controlRole = F.credentialMetadata(
                F.metadata("issue-and-redeem", F.till, F.attestation())
                        .replace("\"role\":\"issue-and-redeem\"", "\"role\":\"issue\\u001fand\""));
        P2PKVoucherSecret duplicateInside = F.credentialMetadata(
                F.metadata("issue-and-redeem", F.till, F.attestation())
                        .replace("{\"v\":\"1\"", "{\"v\":\"1\",\"v\":\"1\""));
        P2PKVoucherSecret noMetadata = F.credentialMetadata(null);
        P2PKVoucherSecret noExpiry = F.credential("issue-and-redeem", F.till, F.attestation(), null);

        for (P2PKVoucherSecret credential : List.of(notTerminal, upperStall, upperLock, controlRole,
                duplicateInside, noMetadata, noExpiry)) {
            assertRefusal(TerminalRefusal.MALFORMED_CREDENTIAL,
                    IssuanceWarrant.verifyOwnerAttestation(credential, F.stall),
                    String.valueOf(credential.getMerchantMetadata()));
        }
    }

    @Test
    @DisplayName("verifyOwnerAttestation does not need a service signature: the gateway checks before it mints")
    void unsignedCredentialIsCheckedOnTheAttestationAlone() {
        // gateway-customer calls this pre-mint, before it has signed anything.
        P2PKVoucherSecret unsigned = F.unsignedCredential("issue-and-redeem", F.till,
                F.attestation(), 4_000_000_000L);

        assertTrue(IssuanceWarrant.verifyOwnerAttestation(unsigned, F.stall).isValid());
    }

    // ------------------------------------------------------------------ verifyTerminal

    @Test
    @DisplayName("an attested selling till's sale verifies")
    void attestedTerminalSaleVerifies() {
        // The 0.17.0 happy path: the service signed the credential, the stall attested it,
        // the till signed the sale.
        assertNull(IssuanceWarrant.verifyTerminal(F.sale(F.credential(F.attestation())).build())
                .getRefusal());
    }

    @Test
    @DisplayName("an unattested credential can no longer warrant a sale")
    void unattestedTerminalSaleIsRefused() {
        // The 0.16.x shape: the service's word alone. Refused, no grace period.
        assertRefusal(TerminalRefusal.OWNER_ATTESTATION_MISSING,
                IssuanceWarrant.verifyTerminal(F.sale(F.credential(null)).build()));
    }

    @Test
    @DisplayName("a terminal sale with an attestation by the wrong key is refused")
    void badAttestationRefusesTheSale() {
        // The forgery decision 5 closes: the service mints a selling till under its own K and
        // signs "the stall consents" itself.
        assertRefusal(TerminalRefusal.BAD_OWNER_ATTESTATION, IssuanceWarrant.verifyTerminal(
                F.sale(F.credential(F.attestation().signedBy(F.serviceKey))).build()));
    }

    @Test
    @DisplayName("a terminal sale on a credential outliving its attestation is refused")
    void exceededAttestationRefusesTheSale() {
        // Even offline, where expiry itself is skipped, the service may not extend T.
        assertRefusal(TerminalRefusal.ATTESTATION_EXCEEDED, IssuanceWarrant.verifyTerminal(
                F.sale(F.credential("issue-and-redeem", F.till, F.attestation(), 4_000_000_001L))
                        .build()));
    }

    @Test
    @DisplayName("the attestation is checked after the lock and before the sale signature")
    void attestationIsCheckedBetweenLockAndSaleSignature() {
        // Spec 4.6.3 fixes the order so Java and the wallet report the same reason.
        // A lock mismatch with no attestation reports the lock; a bad sale signature with no
        // attestation reports the attestation.
        P2PKVoucherSecret wrongLock = F.lockedTo(F.thief, F.credential(null));
        assertRefusal(TerminalRefusal.LOCK_MISMATCH,
                IssuanceWarrant.verifyTerminal(F.sale(wrongLock).build()));
        assertRefusal(TerminalRefusal.OWNER_ATTESTATION_MISSING, IssuanceWarrant.verifyTerminal(
                F.sale(F.credential(null)).signatureHex("00".repeat(64)).build()));
        assertRefusal(TerminalRefusal.BAD_SALE_SIGNATURE, IssuanceWarrant.verifyTerminal(
                F.sale(F.credential(F.attestation())).signatureHex("00".repeat(64)).build()));
    }

    @Test
    @DisplayName("a terminal credential whose stall_pubkey differs in case from issuerId is refused")
    void stallPubkeyMustMatchExactly() {
        // The digest is built from stall_pubkey as written, so it must be the stall's one
        // spelling, not one that matches it ignoring case.
        P2PKVoucherSecret credential = F.credentialMetadata(
                F.metadata("issue-and-redeem", F.till, F.attestation())
                        .replace("\"stall_pubkey\":\"" + F.stall, "\"stall_pubkey\":\""
                                + F.stall.toUpperCase(Locale.ROOT)));

        assertRefusal(TerminalRefusal.NOT_A_SELLING_TERMINAL,
                IssuanceWarrant.verifyTerminal(F.sale(credential).build()));
    }

    @Test
    @DisplayName("the service signature is still required on an attested credential")
    void attestationDoesNotReplaceTheServiceSignature() {
        // The stall's consent alone is not enough: revocation and the lock rest on the service.
        // Unsigned (no issuer_pubkey row at all), signed by an untrusted key, and signed but
        // tampered after signing are all refused, attestation or not.
        P2PKVoucherSecret unsigned = F.unsignedCredential("issue-and-redeem", F.till,
                F.attestation(), 4_000_000_000L);
        assertRefusal(TerminalRefusal.UNTRUSTED_SERVICE_KEY,
                IssuanceWarrant.verifyTerminal(F.sale(unsigned).build()));
        assertRefusal(TerminalRefusal.UNTRUSTED_SERVICE_KEY, IssuanceWarrant.verifyTerminal(
                F.sale(F.credential(F.attestation())).trustedServiceKeys(Set.of(F.stall)).build()));
        P2PKVoucherSecret tampered = F.credential(F.attestation());
        tampered.setFaceValue(2L);
        assertRefusal(TerminalRefusal.BAD_CREDENTIAL_SIGNATURE,
                IssuanceWarrant.verifyTerminal(F.sale(tampered).build()));
    }

    // ------------------------------------------------------------------ helpers

    private static void assertRefusal(TerminalRefusal expected, IssuanceWarrant.TerminalVerdict verdict) {
        assertEquals(expected, verdict.getRefusal());
    }

    private static void assertRefusal(
            TerminalRefusal expected, IssuanceWarrant.TerminalVerdict verdict, String context) {
        assertEquals(expected, verdict.getRefusal(), context);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
