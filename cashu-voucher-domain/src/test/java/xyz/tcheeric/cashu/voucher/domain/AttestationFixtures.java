package xyz.tcheeric.cashu.voucher.domain;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;
import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;

import java.util.Set;
import java.util.UUID;

/**
 * Fixed keys and builders for owner-attestation tests and golden vectors.
 *
 * <p>Every key is a fixed scalar and every attestation is signed with all-zero auxiliary
 * randomness, so an attestation built here is the same bytes on every run. The wallet's
 * TypeScript mirror reads the same keys from {@code owner-attestation-vectors.json}.
 */
final class AttestationFixtures {

    static final AttestationFixtures INSTANCE = new AttestationFixtures();

    /** The issuing service, which signs every credential it mints. */
    final byte[] serviceKey = key("01");
    /** The stall that owns the till. Signs merchant warrants and attestations. */
    final byte[] stallKey = key("02");
    /** The till's own key, K. */
    final byte[] tillKey = key("03");
    /** Another stall, for "wrong shop" cases. */
    final byte[] otherStallKey = key("04");
    /** Someone with a copy of the credential but not K. */
    final byte[] thiefKey = key("05");

    final String service = pubkeyOf(serviceKey);
    final String stall = pubkeyOf(stallKey);
    final String till = pubkeyOf(tillKey);
    final String otherStall = pubkeyOf(otherStallKey);
    final String thief = pubkeyOf(thiefKey);

    /** The owner's 32-byte attestation nonce. */
    final String attestationNonce = "a1".repeat(32);

    /** T, the attestation's expiry, and the genuine credential's. */
    static final long T = 4_000_000_000L;

    static final String SALE_NONCE = "9a8b7c6d5e4f30211203f4e5d6c7b8a9";
    static final long SALE_TOTAL = 2500L;
    static final int DECIMALS = 2;
    static final String UNIT = "EUR";

    private AttestationFixtures() {
    }

    /** What the stall signs, plus the signature, as the wallet would build it. */
    @Value
    @Builder(toBuilder = true, access = AccessLevel.PRIVATE)
    static class Attestation {
        String v;
        String stallPubkey;
        String lockKey;
        String role;
        String expiresAt;
        String nonce;
        byte[] signingKey;
        /** A signature to use instead of the computed one, for tamper tests. */
        String sigOverride;

        String getSig() {
            if (sigOverride != null) {
                return sigOverride;
            }
            return sign(IssuanceWarrant.attestationDigest(v, stallPubkey, lockKey, role,
                    expiresAt, nonce), signingKey);
        }

        /** Signed for another role (still by the same key). */
        Attestation role(String newRole) {
            return toBuilder().role(newRole).build();
        }

        /** Signed by another key over the same fields. */
        Attestation signedBy(byte[] key) {
            return toBuilder().signingKey(key).build();
        }

        /** Carries this signature, whatever it is over. */
        Attestation sig(String sig) {
            return toBuilder().sigOverride(sig).build();
        }

        /** The {@code owner_attestation} value, in the spec's key order. */
        String json() {
            return "{\"v\":\"" + v + "\",\"expires_at\":\"" + expiresAt + "\",\"nonce\":\""
                    + nonce + "\",\"sig\":\"" + getSig() + "\"}";
        }
    }

    /** The genuine attestation: our stall lets our till sell until T. */
    Attestation attestation() {
        return Attestation.builder().v("1").stallPubkey(stall).lockKey(till)
                .role("issue-and-redeem").expiresAt(Long.toString(T)).nonce(attestationNonce)
                .signingKey(stallKey).build();
    }

    /** Terminal metadata as gateway-customer writes it, with the given attestation JSON (or none). */
    String metadataRaw(String role, String lockKey, String attestationJson) {
        return "{\"terminal\":true,\"stall_pubkey\":\"" + stall + "\",\"role\":\"" + role
                + "\",\"lock_key\":\"" + lockKey + "\",\"name\":\"Front till\","
                + "\"idle_lock_minutes\":10"
                + (attestationJson == null ? "" : ",\"owner_attestation\":" + attestationJson)
                + "}";
    }

    String metadata(String role, String lockKey, Attestation attestation) {
        return metadataRaw(role, lockKey, attestation == null ? null : attestation.json());
    }

    /** A signed selling credential for our till, expiring at T, with this attestation. */
    P2PKVoucherSecret credential(Attestation attestation) {
        return credential("issue-and-redeem", till, attestation);
    }

    P2PKVoucherSecret credential(String role, String lockKey, Attestation attestation) {
        return credential(role, lockKey, attestation, T);
    }

    P2PKVoucherSecret credential(String role, String lockKey, Attestation attestation, Long expiresAt) {
        return signed(unsignedCredential(role, lockKey, attestation, expiresAt));
    }

    P2PKVoucherSecret unsignedCredential(
            String role, String lockKey, Attestation attestation, Long expiresAt) {
        return locked(lockKey, metadata(role, lockKey, attestation), expiresAt);
    }

    /** A signed selling credential whose {@code owner_attestation} is this raw JSON value. */
    P2PKVoucherSecret credentialRaw(String attestationJson) {
        return signed(locked(till, metadataRaw("issue-and-redeem", till, attestationJson), T));
    }

    /** A signed credential locked to our till carrying exactly this metadata (or none). */
    P2PKVoucherSecret credentialMetadata(String metadata) {
        return signed(locked(till, metadata, T));
    }

    /** The same metadata and expiry, but locked to (and re-signed for) another key. */
    P2PKVoucherSecret lockedTo(String lockKey, P2PKVoucherSecret credential) {
        return signed(locked(lockKey, credential.getMerchantMetadata(), credential.getExpiresAt()));
    }

    /** The genuine sale for this credential, as a builder, so a test can change one field. */
    IssuanceWarrant.TerminalSale.TerminalSaleBuilder sale(P2PKVoucherSecret credential) {
        return IssuanceWarrant.TerminalSale.builder()
                .issuerId(stall)
                .credential(credential)
                .trustedServiceKeys(Set.of(service))
                .saleTotalMinor(SALE_TOTAL)
                .faceDecimals(DECIMALS)
                .unit(UNIT)
                .saleNonce(SALE_NONCE)
                .signatureHex(tillSaleSignature())
                .couponFaceMinor(SALE_TOTAL)
                .expiryCheck(IssuanceWarrant.ExpiryCheck.skipOffline());
    }

    String tillSaleSignature() {
        return sign(IssuanceWarrant.saleDigest(stall, SALE_TOTAL, DECIMALS, UNIT, SALE_NONCE), tillKey);
    }

    private P2PKVoucherSecret locked(String lockKey, String metadata, Long expiresAt) {
        P2PKVoucherSecret secret = new P2PKVoucherSecret(Hex.decode("02" + lockKey));
        secret.setVoucherId(UUID.nameUUIDFromBytes((lockKey + metadata + expiresAt).getBytes(
                java.nio.charset.StandardCharsets.UTF_8)).toString());
        secret.setIssuerId(stall);
        secret.setUnit("sat");
        secret.setFaceValue(1L);
        if (metadata != null) {
            secret.setMerchantMetadata(metadata);
        }
        if (expiresAt != null) {
            secret.setExpiresAt(expiresAt);
        }
        return secret;
    }

    P2PKVoucherSecret signed(P2PKVoucherSecret secret) {
        return SignedLockedVoucher.createSigned(secret, Hex.toHexString(serviceKey), service)
                .getSecret();
    }

    /** BIP-340 with all-zero auxiliary randomness: deterministic, for reproducible vectors. */
    static String sign(byte[] digest, byte[] privateKey) {
        try {
            return Hex.toHexString(Schnorr.sign(digest, privateKey, new byte[32]));
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign test digest", e);
        }
    }

    static byte[] key(String byteHex) {
        return Hex.decode(byteHex.repeat(32));
    }

    static String pubkeyOf(byte[] privateKey) {
        try {
            return Hex.toHexString(Schnorr.genPubKey(privateKey));
        } catch (Exception e) {
            throw new IllegalStateException("failed to derive test pubkey", e);
        }
    }
}
