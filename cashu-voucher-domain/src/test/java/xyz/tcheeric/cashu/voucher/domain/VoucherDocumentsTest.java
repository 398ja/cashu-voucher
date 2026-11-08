package xyz.tcheeric.cashu.voucher.domain;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.SecretUtil;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static xyz.tcheeric.cashu.voucher.domain.ByReferenceFixtures.docs;
import static xyz.tcheeric.cashu.voucher.domain.ByReferenceFixtures.document;
import static xyz.tcheeric.cashu.voucher.domain.ByReferenceFixtures.parse;
import static xyz.tcheeric.cashu.voucher.domain.ByReferenceFixtures.v2;

/**
 * Vouchers by reference (cashu-voucher#64): v1 and v2 of one voucher must give identical
 * answers everywhere, v1 must behave exactly as before, and an unresolved v2 must fail
 * closed in every legacy reader.
 */
class VoucherDocumentsTest {

    private static WellKnownSecret captured() throws Exception {
        try (InputStream in = VoucherDocumentsTest.class.getResourceAsStream("/captured-unlocked-voucher-secret.json")) {
            return (WellKnownSecret) SecretUtil.toSecret(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
        }
    }

    private static WellKnownSecret v2Of(WellKnownSecret v1) {
        return parse(v2(v1.getNonce(), v1.getData()));
    }

    /** Every v1 fixture this module has: the captured real token, plus freshly signed ones. */
    private static List<WellKnownSecret> v1Fixtures() throws Exception {
        VoucherSecret fresh = VoucherSecret.builder()
                .voucherId(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
                .nonce("0000000000000000000000000000000000000000000000000000000000aa")
                .issuerId("test-issuer").unit("sat").faceValue(5000L).expiresAt(1893456000L)
                .memo("golden").faceDecimals(2)
                .backingStrategy(BackingStrategy.MINIMAL.name()).issuanceRatio(1.0).build();
        VoucherSignatureService.createSigned(fresh,
                "f6b7737ceb512d66070b27fc20994e612e55d6974dbaf37511cfabc62d59cafd",
                Hex.toHexString(nostr.crypto.schnorr.Schnorr.genPubKey(
                        Hex.decode("f6b7737ceb512d66070b27fc20994e612e55d6974dbaf37511cfabc62d59cafd"))));
        return List.of(captured(), parse(ByReferenceFixtures.v1("cd".repeat(32), document(fresh))));
    }

    @Test
    @DisplayName("key is lowercase hex SHA-256")
    void key() {
        assertThat(VoucherDocuments.key("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("a real v1 proof is never mistaken for a reference")
    void v1IsNotByReference() throws Exception {
        for (WellKnownSecret v1 : v1Fixtures()) {
            assertThat(VoucherDocuments.isByReference(v1)).isFalse();
            assertThat(VoucherDocuments.resolveForVerification(v1, null))
                    .isInstanceOf(VoucherDocuments.Resolution.NotByReference.class);
            assertThat(VoucherMetadata.isUnresolvedReference(v1)).isFalse();
        }
        assertThat(VoucherDocuments.isByReference(null)).isFalse();
        WellKnownSecret locked = (WellKnownSecret) SecretUtil.toSecret(
                "[\"P2PK_VOUCHER\",{\"nonce\":\"00\",\"data\":\"02" + "ab".repeat(32) + "\",\"tags\":[]}]");
        assertThat(VoucherDocuments.isByReference(locked)).as("locked kind is out of scope").isFalse();
    }

    @Test
    @DisplayName("v1 and v2 of every fixture give the same blob, verdict, fingerprint and metadata")
    void v1AndV2Agree() throws Exception {
        for (WellKnownSecret v1 : v1Fixtures()) {
            WellKnownSecret v2 = v2Of(v1);
            Map<String, byte[]> documents = docs(v1.getData());

            VoucherSecret a = UnlockedVoucherBlob.read(v1);
            VoucherSecret b = UnlockedVoucherBlob.read(v2, documents);
            assertThat(a).isNotNull();
            assertThat(b.toString()).isEqualTo(a.toString());
            assertThat(VoucherSignatureService.verify(b)).isEqualTo(VoucherSignatureService.verify(a)).isTrue();
            assertThat(VoucherSignatureService.verifyStrict(b)).isEqualTo(VoucherSignatureService.verifyStrict(a));
            assertThat(VoucherFingerprint.compute(v2, documents))
                    .isEqualTo(VoucherFingerprint.compute(v1, null))
                    .isEqualTo(VoucherFingerprint.compute(a));
            assertThat(UnlockedVoucherBlob.carriesSignature(VoucherDocuments.resolve(v2, documents).orElseThrow()))
                    .isTrue();

            WellKnownSecret resolved = VoucherDocuments.resolve(v2, documents).orElseThrow();
            assertThat(resolved.toString()).isEqualTo(v1.toString());
            assertThat(SecretUtil.toY(resolved)).isEqualTo(SecretUtil.toY(v1));
            for (WellKnownSecret[] pair : new WellKnownSecret[][]{{v1, resolved}, {a, b}}) {
                assertThat(VoucherMetadata.isVoucherCarrying(pair[1])).isEqualTo(VoucherMetadata.isVoucherCarrying(pair[0]));
                assertThat(VoucherMetadata.voucherId(pair[1])).isEqualTo(VoucherMetadata.voucherId(pair[0]));
                assertThat(VoucherMetadata.isSigned(pair[1])).isEqualTo(VoucherMetadata.isSigned(pair[0]));
                assertThat(VoucherMetadata.isExpired(pair[1])).isEqualTo(VoucherMetadata.isExpired(pair[0]));
                assertThat(VoucherMetadata.expiresAt(pair[1])).isEqualTo(VoucherMetadata.expiresAt(pair[0]));
                assertThat(VoucherMetadata.issuerId(pair[1])).isEqualTo(VoucherMetadata.issuerId(pair[0]));
            }
            assertThat(VoucherValidator.validateProofSecret(v2, documents).getErrors())
                    .isEqualTo(VoucherValidator.validateProofSecret(v1, null).getErrors());
        }
    }

    @Test
    @DisplayName("inline v1 reads exactly as before, documents or not")
    void inlineUnchanged() throws Exception {
        for (WellKnownSecret v1 : v1Fixtures()) {
            String before = UnlockedVoucherBlob.read(v1).toString();
            assertThat(UnlockedVoucherBlob.read(v1, null).toString()).isEqualTo(before);
            assertThat(UnlockedVoucherBlob.read(v1, Map.of()).toString()).isEqualTo(before);
            assertThat(UnlockedVoucherBlob.read(v1, docs("unrelated".getBytes())).toString()).isEqualTo(before);
            assertThat(VoucherValidator.validateProofSecret(v1, null).getErrors()).isEmpty();
        }
    }

    @Test
    @DisplayName("an unresolved reference fails closed in every legacy reader")
    void unresolvedFailsClosed() throws Exception {
        WellKnownSecret v2 = v2Of(captured());
        assertThat(UnlockedVoucherBlob.read(v2)).as("32 bytes of hash are not a blob").isNull();
        assertThat(UnlockedVoucherBlob.carriesSignature(v2)).isFalse();
        assertThat(UnlockedVoucherBlob.read(v2, null)).isNull();
        assertThat(VoucherMetadata.isVoucherCarrying(v2)).isTrue();
        assertThat(VoucherMetadata.isUnresolvedReference(v2)).isTrue();
        assertThat(VoucherMetadata.voucherId(v2)).as("a hash is not a voucher id").isNull();
        assertThat(VoucherMetadata.isSigned(v2)).isFalse();
        assertThat(VoucherSignatureService.verify(v2)).isFalse();
        assertThat(VoucherFingerprint.compute(v2, null)).isNull();
        assertThat(VoucherValidator.validateProofSecret(v2, null).getErrorMessage()).contains("missing");
    }

    @Test
    @DisplayName("resolution keeps the proof's own nonce and copies the document")
    void resolutionShape() throws Exception {
        WellKnownSecret v1 = captured();
        byte[] doc = v1.getData().clone();
        WellKnownSecret v2 = parse(v2("ef".repeat(32), doc));
        WellKnownSecret resolved = VoucherDocuments.resolve(v2, docs(doc)).orElseThrow();
        assertThat(resolved.getNonce()).isEqualTo("ef".repeat(32));
        assertThat(resolved.getKind()).isEqualTo(WellKnownSecret.Kind.VOUCHER);
        assertThat(resolved.getTags() == null || resolved.getTags().isEmpty()).isTrue();
        doc[0] ^= 1;
        assertThat(resolved.getData()[0]).isNotEqualTo(doc[0]);
    }

    @Test
    @DisplayName("validate refuses bad maps and accepts good ones")
    void validate() {
        byte[] d = "document".getBytes(StandardCharsets.UTF_8);
        assertThat(VoucherDocuments.validate(null, 0)).isEmpty();
        assertThat(VoucherDocuments.validate(docs(d), 1)).isEmpty();
        assertThat(VoucherDocuments.validate(docs(d), 0)).anyMatch(e -> e.contains("Too many"));
        assertThat(VoucherDocuments.validate(Map.of("ab", d), 1)).anyMatch(e -> e.contains("64 lowercase"));
        assertThat(VoucherDocuments.validate(Map.of(VoucherDocuments.key(d).toUpperCase(), d), 1)).isNotEmpty();
        assertThat(VoucherDocuments.validate(Map.of(VoucherDocuments.key(d), new byte[0]), 1))
                .anyMatch(e -> e.contains("empty"));
        Map<String, byte[]> nulls = new HashMap<>();
        nulls.put(VoucherDocuments.key(d), null);
        nulls.put(null, d);
        assertThat(VoucherDocuments.validate(nulls, 5)).hasSize(2);
        assertThat(VoucherDocuments.validate(Map.of(VoucherDocuments.key(d), "other".getBytes()), 1))
                .anyMatch(e -> e.contains("not the SHA-256"));
        byte[] big = new byte[VoucherDocuments.MAX_DOCUMENT_BYTES + 1];
        assertThat(VoucherDocuments.validate(docs(big), 1)).anyMatch(e -> e.contains("exceeds"));
        assertThat(VoucherDocuments.validate(docs(new byte[VoucherDocuments.MAX_DOCUMENT_BYTES]), 1)).isEmpty();
    }

    @Test
    @DisplayName("a valid-hash document that is not a voucher reads as nothing, never throws")
    void rubbishDocument() {
        byte[] rubbish = "not cbor at all".getBytes(StandardCharsets.UTF_8);
        WellKnownSecret v2 = parse(v2("00", rubbish));
        assertThat(VoucherDocuments.resolve(v2, docs(rubbish))).isPresent();
        assertThat(UnlockedVoucherBlob.read(v2, docs(rubbish))).isNull();
        assertThat(VoucherValidator.validateProofSecret(v2, docs(rubbish)).getErrorMessage()).contains("could not be read");
        assertThat(VoucherFingerprint.compute(v2, docs(rubbish))).isNull();
    }

    @Test
    @DisplayName("validateProofSecret refuses locked and unsigned vouchers")
    void proofSecretRefusals() throws Exception {
        WellKnownSecret locked = (WellKnownSecret) SecretUtil.toSecret(
                "[\"P2PK_VOUCHER\",{\"nonce\":\"00\",\"data\":\"02" + "ab".repeat(32) + "\",\"tags\":[]}]");
        assertThat(VoucherValidator.validateProofSecret(locked, null).getErrorMessage()).contains("Not an unlocked");
        VoucherSecret unsigned = VoucherSecret.builder().voucherId(UUID.randomUUID()).issuerId("i").unit("sat")
                .faceValue(1L).nonce("00").build();
        WellKnownSecret v2 = parse(v2("00", document(unsigned)));
        assertThat(VoucherValidator.validateProofSecret(v2, docs(document(unsigned))).getErrorMessage())
                .contains("unsigned");
        byte[] other = "some other document".getBytes(StandardCharsets.UTF_8);
        WellKnownSecret ref = v2Of(captured());
        assertThat(VoucherValidator.validateProofSecret(ref,
                Map.of(VoucherDocuments.key(captured().getData()), other)).getErrorMessage()).contains("does not match");
    }

    @Test
    @DisplayName("validate refuses documents adding up to more than 1 MiB, though each is within its own bound")
    void totalBound() {
        Map<String, byte[]> atLimit = new java.util.TreeMap<>();
        for (int i = 0; i < 16; i++) {
            byte[] doc = new byte[VoucherDocuments.MAX_DOCUMENT_BYTES];
            java.util.Arrays.fill(doc, (byte) (i + 1));
            atLimit.put(VoucherDocuments.key(doc), doc);
        }
        assertThat(VoucherDocuments.validate(atLimit, 1000)).isEmpty();

        Map<String, byte[]> overLimit = new java.util.TreeMap<>(atLimit);
        byte[] extra = {42};
        overLimit.put(VoucherDocuments.key(extra), extra);
        assertThat(VoucherDocuments.validate(overLimit, 1000)).anyMatch(e -> e.contains("in total"));
    }
}
