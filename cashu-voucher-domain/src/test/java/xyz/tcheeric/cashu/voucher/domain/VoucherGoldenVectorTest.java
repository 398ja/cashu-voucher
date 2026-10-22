package xyz.tcheeric.cashu.voucher.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherTags;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
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

    // The original golden vector: prints the fixture values for the offline wallet and checks
    // that the authoritative verifier accepts the honest secret and refuses the tampered one.
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

    private static final String RATIO_VECTORS = "/whole-issuance-ratio-vectors.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    // cashu-voucher#59. For ratios 2, 50 and 700 the shared vector file pins the exact bytes an
    // issuer signs and a deterministic signature over them. Both the canonical wire "N" and the
    // legacy "N.0" that cashu-lib <= 0.30.6 wrote must render to those same bytes and verify;
    // the TypeScript verifier replays the same file (imani-wallet wholeIssuanceRatio.test.ts).
    @Test
    void wholeRatioLockedVectorsVerifyInBothLiveSpellings() throws Exception {
        JsonNode doc = loadRatioVectors();
        for (JsonNode v : doc.get("vectors")) {
            for (String wire : List.of(v.get("canonical_wire").textValue(), v.get("legacy_wire").textValue())) {
                // Arrange
                P2PKVoucherSecret secret = lockedFromTemplate(doc, wire, v.get("issuer_sig").textValue());

                // Act
                byte[] canonical = VoucherCanonicalBytes.of(secret);

                // Assert
                assertThat(new String(canonical, StandardCharsets.UTF_8)).as("bytes for wire %s", wire)
                        .isEqualTo(v.get("canonical_bytes_utf8").textValue());
                assertThat(Hex.toHexString(sha256(canonical))).isEqualTo(v.get("sha256").textValue());
                assertThat(VoucherSignatureService.verify(secret)).as("verify wire %s", wire).isTrue();
            }
        }
    }

    // The legacy window is exactly one spelling wide. Every other way of writing N is refused even
    // though it hashes to the same bytes, which is the #48 rule this fix must not loosen.
    @Test
    void wholeRatioVectorsRefuseEveryOtherSpelling() throws Exception {
        JsonNode doc = loadRatioVectors();
        for (JsonNode v : doc.get("vectors")) {
            for (JsonNode wire : v.get("refused_wires")) {
                // Arrange
                P2PKVoucherSecret secret = lockedFromTemplate(doc, wire.textValue(), v.get("issuer_sig").textValue());

                // Act + Assert
                assertThat(VoucherSignatureService.verify(secret)).as("wire '%s'", wire.textValue()).isFalse();
            }
        }
    }

    // The unlocked kind, rebuilt from the CBOR blob by UnlockedVoucherBlob (the mint's path), has
    // the same bytes contract. Its pinned signature must verify whatever spelling the lib wrote.
    @Test
    void wholeRatioUnlockedVectorsVerify() throws Exception {
        JsonNode doc = loadRatioVectors();
        JsonNode t = doc.get("unlocked_template");
        for (JsonNode v : doc.get("vectors")) {
            for (String wire : List.of(v.get("canonical_wire").textValue(), v.get("legacy_wire").textValue())) {
                // Arrange
                VoucherSecret secret = VoucherSecret.builder()
                        .voucherId(UUID.fromString(t.get("voucher_id").textValue()))
                        .nonce(t.get("nonce").textValue())
                        .issuerId(t.get("issuer").textValue())
                        .unit(t.get("unit").textValue())
                        .faceValue(t.get("face_value").longValue())
                        .expiresAt(t.get("expires_at").longValue())
                        .memo(t.get("memo").textValue())
                        .faceDecimals(t.get("face_decimals").intValue())
                        .backingStrategy(t.get("backing_strategy").textValue())
                        .build();
                secret.setTag(VoucherTags.ISSUANCE_RATIO, List.of(wire));
                secret.setIssuerSignature(v.get("unlocked").get("issuer_sig").textValue());
                secret.setIssuerPublicKey(doc.get("issuer").get("pub").textValue());

                // Act
                byte[] canonical = VoucherCanonicalBytes.of(secret);

                // Assert
                assertThat(new String(canonical, StandardCharsets.UTF_8))
                        .isEqualTo(v.get("unlocked").get("canonical_bytes_utf8").textValue());
                assertThat(VoucherSignatureService.verify(secret)).as("unlocked wire %s", wire).isTrue();
            }
        }
    }

    private static P2PKVoucherSecret lockedFromTemplate(JsonNode doc, String ratioWire, String signature) {
        JsonNode t = doc.get("locked_template");
        P2PKVoucherSecret secret = new P2PKVoucherSecret(Hex.decode(t.get("data").textValue()));
        secret.setNonce(t.get("nonce").textValue());
        for (JsonNode tag : t.get("tags_before_ratio")) {
            secret.setTag(tag.get(0).textValue(), List.of(tag.get(1).textValue()));
        }
        secret.setTag(VoucherTags.ISSUANCE_RATIO, List.of(ratioWire));
        secret.setTag(VoucherTags.ISSUER_SIG, List.of(signature));
        secret.setTag(VoucherTags.ISSUER_PUBKEY, List.of(doc.get("issuer").get("pub").textValue()));
        assertThat(secret.getKind()).isEqualTo(WellKnownSecret.Kind.P2PK_VOUCHER);
        return secret;
    }

    private static JsonNode loadRatioVectors() throws Exception {
        try (InputStream in = VoucherGoldenVectorTest.class.getResourceAsStream(RATIO_VECTORS)) {
            if (in == null) {
                throw new IllegalStateException(RATIO_VECTORS + " is not on the test classpath");
            }
            return JSON.readTree(in);
        }
    }
}
