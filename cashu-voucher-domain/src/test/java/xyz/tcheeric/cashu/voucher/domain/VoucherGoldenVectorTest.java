package xyz.tcheeric.cashu.voucher.domain;

import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Produces a deterministic, byte-for-byte golden vector for offline-wallet (JS) verification
 * of Imani VOUCHER token issuer signatures.
 *
 * <p>This is a throwaway/fixture-generation test: it hardcodes a fixed voucherId, nonce,
 * tag set, and issuer key pair, signs the resulting {@link VoucherSecret} via the authoritative
 * {@link VoucherSignatureService}, and prints every intermediate value needed to replicate the
 * signing/verification pipeline in TypeScript ({@code packages/offline-wallet}):
 *
 * <ul>
 *   <li>the full NUT-10 secret string (as it appears in a token)</li>
 *   <li>the canonical bytes (hex) that get sha256'd and signed, taken from
 *       {@link VoucherCanonicalBytes} — the same implementation the signer uses</li>
 *   <li>the sha256 of those canonical bytes</li>
 *   <li>the BIP-340 Schnorr signature</li>
 *   <li>the issuer's x-only public key</li>
 *   <li>the issuer's private key</li>
 * </ul>
 *
 * <p>It also produces a TAMPERED secret string (face_value changed post-signing, signature
 * left untouched) which MUST fail verification — this is the negative fixture.
 *
 * <p>Not wired into any CI gate; values are captured by hand into
 * {@code imani-apps/packages/offline-wallet/tests/fixtures/voucher-golden.json}.
 */
class VoucherGoldenVectorTest {

    // Fixed 32-byte issuer private key (hex). Arbitrary but < secp256k1 curve order.
    private static final String ISSUER_PRIVATE_KEY_HEX =
            "f6b7737ceb512d66070b27fc20994e612e55d6974dbaf37511cfabc62d59cafd";

    // Fixed voucher id (UUID) and nonce so the vector is 100% reproducible.
    private static final UUID FIXED_VOUCHER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String FIXED_NONCE = "0000000000000000000000000000000000000000000000000000000000aa";

    // Fixed tag set.
    private static final String ISSUER_ID = "test-issuer";
    private static final String UNIT = "sat";
    private static final long FACE_VALUE = 5000L;
    private static final long TAMPERED_FACE_VALUE = 9999L;
    private static final long EXPIRES_AT = 1893456000L;
    private static final String MEMO = "golden";
    private static final int FACE_DECIMALS = 2;

    private static VoucherSecret buildSecret(long faceValue) {
        return VoucherSecret.builder()
                .voucherId(FIXED_VOUCHER_ID)
                .nonce(FIXED_NONCE)
                .issuerId(ISSUER_ID)
                .unit(UNIT)
                .faceValue(faceValue)
                .expiresAt(EXPIRES_AT)
                .memo(MEMO)
                .faceDecimals(FACE_DECIMALS)
                .build();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void printGoldenVector() {
        // Derive the issuer's x-only pubkey exactly the way the mint does (nostr.crypto.schnorr.Schnorr).
        byte[] issuerPrivateKeyBytes = Hex.decode(ISSUER_PRIVATE_KEY_HEX);
        byte[] issuerPublicKeyBytes = Schnorr.genPubKey(issuerPrivateKeyBytes);
        String issuerPublicKeyHex = Hex.toHexString(issuerPublicKeyBytes);

        // Build + sign.
        VoucherSecret secret = buildSecret(FACE_VALUE);
        byte[] signature = VoucherSignatureService.sign(secret, ISSUER_PRIVATE_KEY_HEX);
        secret.setIssuerSignature(Hex.toHexString(signature));
        secret.setIssuerPublicKey(issuerPublicKeyHex);

        // Sanity: the authoritative verifier must accept this vector.
        assertThat(VoucherSignatureService.verify(secret)).isTrue();

        // Cross-check the canonical bytes against the real signature via the Schnorr
        // primitive directly. These are the same bytes the signer used — this test prints
        // the vector, it does not re-derive it, so the printed vector cannot drift from
        // what VoucherSignatureService actually signs.
        byte[] canonicalBytes = VoucherCanonicalBytes.of(secret);
        byte[] sha256Bytes = sha256(canonicalBytes);
        assertThat(Schnorr.verify(sha256Bytes, issuerPublicKeyBytes, signature)).isTrue();

        String secretString = secret.toString();

        // Tampered variant: same everything, face_value changed post-signing, signature left
        // untouched (reused from the honest secret). MUST fail verification.
        VoucherSecret tampered = buildSecret(TAMPERED_FACE_VALUE);
        tampered.setIssuerSignature(secret.getIssuerSignature());
        tampered.setIssuerPublicKey(secret.getIssuerPublicKey());
        assertThat(VoucherSignatureService.verify(tampered)).isFalse();
        String tamperedSecretString = tampered.toString();

        System.out.println("=== VOUCHER GOLDEN VECTOR ===");
        System.out.println("secretString=" + secretString);
        System.out.println("canonicalBytesHex=" + Hex.toHexString(canonicalBytes));
        System.out.println("sha256Hex=" + Hex.toHexString(sha256Bytes));
        System.out.println("issuerSigHex=" + secret.getIssuerSignature());
        System.out.println("issuerPubkeyHex=" + issuerPublicKeyHex);
        System.out.println("issuerPrivkeyHex=" + ISSUER_PRIVATE_KEY_HEX);
        System.out.println("tamperedSecretString=" + tamperedSecretString);
        System.out.println("=== END VOUCHER GOLDEN VECTOR ===");
    }
}
