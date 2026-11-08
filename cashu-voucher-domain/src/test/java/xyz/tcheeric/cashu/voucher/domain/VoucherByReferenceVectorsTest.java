package xyz.tcheeric.cashu.voucher.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static xyz.tcheeric.cashu.voucher.domain.ByReferenceFixtures.docs;
import static xyz.tcheeric.cashu.voucher.domain.ByReferenceFixtures.document;
import static xyz.tcheeric.cashu.voucher.domain.ByReferenceFixtures.parse;
import static xyz.tcheeric.cashu.voucher.domain.ByReferenceFixtures.sign;
import static xyz.tcheeric.cashu.voucher.domain.ByReferenceFixtures.v1;
import static xyz.tcheeric.cashu.voucher.domain.ByReferenceFixtures.v2;
import static xyz.tcheeric.cashu.voucher.domain.ByReferenceFixtures.wire;

/**
 * Golden vectors for vouchers by reference (cashu-voucher#64), shared with the TypeScript
 * wallet. The file is replayed here, and must equal what {@link #generate()} produces, so it
 * cannot drift from the code. Regenerate with
 * {@code mvn -pl cashu-voucher-domain test -Dtest=VoucherByReferenceVectorsTest -Dcv.writeByReferenceVectors=$PWD/cashu-voucher-domain/src/test/resources/voucher-by-reference-vectors.json}.
 */
class VoucherByReferenceVectorsTest {

    private static final String RESOURCE = "/voucher-by-reference-vectors.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final byte[] ISSUER_KEY = AttestationFixtures.key("07");
    private static final byte[] OTHER_KEY = AttestationFixtures.key("08");
    private static final long FAR = 4_000_000_000L;
    private static final long PAST = 1_000_000_000L;

    private static VoucherSecret.Builder base(String id, String nonce) {
        return VoucherSecret.builder()
                .voucherId(UUID.fromString(id))
                .nonce(nonce)
                .issuerId("corner-cafe")
                .unit("EUR")
                .faceDecimals(2)
                .issuanceRatio(1.0)
                .backingStrategy(BackingStrategy.PROPORTIONAL.name());
    }

    private static String proofNonce(int i) {
        return String.format("%064x", 0xab00 + i);
    }

    /** The full vector document, deterministically. */
    static Map<String, Object> generate() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("description", "cashu-voucher vouchers by reference golden vectors (cashu-voucher#64, "
                + "docs/explanation/voucher-by-reference.md). Replayed by VoucherByReferenceVectorsTest; "
                + "mirrored by the wallet.");
        root.put("version", 1);
        root.put("max_document_bytes", VoucherDocuments.MAX_DOCUMENT_BYTES);
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("issuer_priv", Hex.toHexString(ISSUER_KEY));
        keys.put("issuer_pub", AttestationFixtures.pubkeyOf(ISSUER_KEY));
        root.put("keys", keys);

        List<Map<String, Object>> vouchers = new ArrayList<>();
        vouchers.add(vector("plain", sign(base("11111111-2222-4333-8444-555555555555", "00".repeat(32))
                .faceValue(700L).expiresAt(FAR).memo("7 EUR coupon").build(), ISSUER_KEY), 1, true, true));
        vouchers.add(vector("non-ascii-memo", sign(base("22222222-3333-4444-8555-666666666666", "01".repeat(32))
                .faceValue(500L).expiresAt(FAR).memo("Caf\u00e9 \u2615 5\u20ac, d\u00e9j\u00e0 vu \u65e5\u672c").build(),
                ISSUER_KEY), 2, true, true));
        vouchers.add(vector("warrant", sign(base("33333333-4444-4555-8666-777777777777", "02".repeat(32))
                .faceValue(2500L).expiresAt(FAR).memo("warranted")
                .issuanceWarrant("{\"form\":\"merchant\",\"sale_total\":2500,\"nonce\":\"abc123\",\"sig\":\"ff\"}")
                .build(), ISSUER_KEY), 3, true, true));
        vouchers.add(vector("no-expiry-ratio-2", sign(base("44444444-5555-4666-8777-888888888888", "03".repeat(32))
                .faceValue(1000L).issuanceRatio(2.0).merchantMetadata("{\"shop\":\"stall 9\"}").build(),
                ISSUER_KEY), 4, true, true));
        vouchers.add(vector("expired", sign(base("55555555-6666-4777-8888-999999999999", "04".repeat(32))
                .faceValue(300L).expiresAt(PAST).build(), ISSUER_KEY), 5, true, false));
        VoucherSecret tampered = sign(base("66666666-7777-4888-8999-aaaaaaaaaaaa", "05".repeat(32))
                .faceValue(100L).expiresAt(FAR).build(), OTHER_KEY);
        tampered.setFaceValue(10_000L);
        vouchers.add(vector("tampered-face-value", tampered, 6, false, false));
        root.put("vouchers", vouchers);

        byte[] good = document(sign(base("11111111-2222-4333-8444-555555555555", "00".repeat(32))
                .faceValue(700L).expiresAt(FAR).memo("7 EUR coupon").build(), ISSUER_KEY));
        byte[] other = document(sign(base("22222222-3333-4444-8555-666666666666", "01".repeat(32))
                .faceValue(500L).expiresAt(FAR).build(), ISSUER_KEY));
        String goodKey = VoucherDocuments.key(good);
        byte[] oversized = new byte[VoucherDocuments.MAX_DOCUMENT_BYTES + 1];
        Arrays.fill(oversized, (byte) 0x5a);
        String n = proofNonce(99);

        List<Map<String, Object>> negatives = new ArrayList<>();
        negatives.add(negative("missing-document", v2(n, good), Map.of(), "missing", true));
        negatives.add(negative("wrong-document-under-key", v2(n, good), Map.of(goodKey, Hex.toHexString(other)),
                "mismatch", false));
        negatives.add(negative("other-document-only", v2(n, good), docsHex(other), "missing", true));
        negatives.add(negative("data-31-bytes", wire(n, Arrays.copyOf(Hex.decode(goodKey), 31)), docsHex(good),
                "not_by_reference", true));
        negatives.add(negative("data-33-bytes", wire(n, Arrays.copyOf(Hex.decode(goodKey), 33)), docsHex(good),
                "not_by_reference", true));
        Map<String, Object> big = negative("oversized-document", wire(n, Hex.decode(VoucherDocuments.key(oversized))),
                Map.of(), "mismatch", false);
        // 64 KiB of hex would dwarf the file, so the document is described: `length` bytes of `fill`.
        big.put("generated_document", Map.of("fill", "5a", "length", oversized.length));
        negatives.add(big);
        negatives.add(negative("tags-with-32-byte-data", "[\"VOUCHER\",{\"nonce\":\"" + n + "\",\"data\":\""
                + goodKey + "\",\"tags\":[[\"issuer\",\"corner-cafe\"]]}]", docsHex(good), "not_by_reference", true));
        negatives.add(negative("uppercase-key", v2(n, good), Map.of(goodKey.toUpperCase(), Hex.toHexString(good)),
                "missing", false));
        root.put("negatives", negatives);
        return root;
    }

    private static Map<String, Object> vector(String name, VoucherSecret v, int i,
                                              boolean signatureValid, boolean valid) {
        byte[] doc = document(v);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("proof_nonce", proofNonce(i));
        m.put("v1_secret", v1(proofNonce(i), doc));
        m.put("document_hex", Hex.toHexString(doc));
        m.put("document_key", VoucherDocuments.key(doc));
        m.put("v2_secret", v2(proofNonce(i), doc));
        m.put("voucher_id", v.getVoucherId().toString());
        m.put("fingerprint", VoucherFingerprint.compute(v));
        m.put("signature_valid", signatureValid);
        m.put("valid", valid);
        return m;
    }

    private static Map<String, Object> negative(String name, String secret, Map<String, String> documents,
                                                String resolution, boolean documentsAcceptable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("secret", secret);
        m.put("documents", documents);
        m.put("resolution", resolution);
        m.put("documents_acceptable", documentsAcceptable);
        m.put("verifiable", false);
        return m;
    }

    private static Map<String, String> docsHex(byte[] doc) {
        return Map.of(VoucherDocuments.key(doc), Hex.toHexString(doc));
    }

    private static String render(Object root) throws Exception {
        return JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(root) + "\n";
    }

    private static String fileContents() throws Exception {
        try (InputStream in = VoucherByReferenceVectorsTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as("vector file on classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void fileMatchesGenerator() throws Exception {
        assertThat(JSON.readTree(fileContents())).isEqualTo(JSON.readTree(render(generate())));
    }

    @Test
    void replaysVouchers() throws Exception {
        JsonNode root = JSON.readTree(fileContents());
        assertThat(root.get("vouchers")).hasSizeGreaterThanOrEqualTo(6);
        for (JsonNode v : root.get("vouchers")) {
            String name = v.get("name").asText();
            byte[] doc = Hex.decode(v.get("document_hex").asText());
            assertThat(VoucherDocuments.key(doc)).as(name).isEqualTo(v.get("document_key").asText());

            WellKnownSecret v1 = parse(v.get("v1_secret").asText());
            WellKnownSecret v2 = parse(v.get("v2_secret").asText());
            assertThat(v1.getData()).as(name).isEqualTo(doc);
            assertThat(VoucherDocuments.isByReference(v1)).as(name).isFalse();
            assertThat(VoucherDocuments.isByReference(v2)).as(name).isTrue();
            assertThat(UnlockedVoucherBlob.read(v2)).as(name + " legacy read fails closed").isNull();

            Map<String, byte[]> documents = docs(doc);
            WellKnownSecret resolved = VoucherDocuments.resolve(v2, documents).orElseThrow();
            assertThat(resolved.toString()).as(name + " resolves to the v1 secret")
                    .isEqualTo(v1.toString());

            VoucherSecret a = UnlockedVoucherBlob.read(v1);
            VoucherSecret b = UnlockedVoucherBlob.read(v2, documents);
            assertThat(b.toString()).as(name).isEqualTo(a.toString());
            assertThat(VoucherSignatureService.verify(a)).as(name).isEqualTo(v.get("signature_valid").asBoolean());
            assertThat(VoucherSignatureService.verify(b)).as(name).isEqualTo(v.get("signature_valid").asBoolean());
            assertThat(a.getVoucherId().toString()).isEqualTo(v.get("voucher_id").asText());

            assertThat(VoucherFingerprint.compute(v1, null)).as(name).isEqualTo(v.get("fingerprint").asText());
            assertThat(VoucherFingerprint.compute(v2, documents)).as(name).isEqualTo(v.get("fingerprint").asText());

            boolean valid = v.get("valid").asBoolean();
            assertThat(VoucherValidator.validateProofSecret(v1, null).isValid()).as(name).isEqualTo(valid);
            assertThat(VoucherValidator.validateProofSecret(v2, documents).isValid()).as(name).isEqualTo(valid);
            assertThat(VoucherValidator.validateProofSecret(v2, Map.of()).isValid()).as(name).isFalse();
        }
    }

    @Test
    void replaysNegatives() throws Exception {
        JsonNode root = JSON.readTree(fileContents());
        for (JsonNode c : root.get("negatives")) {
            String name = c.get("name").asText();
            WellKnownSecret secret = parse(c.get("secret").asText());
            Map<String, byte[]> documents = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = c.get("documents").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                documents.put(e.getKey(), Hex.decode(e.getValue().asText()));
            }
            if (c.has("generated_document")) {
                byte[] g = new byte[c.get("generated_document").get("length").asInt()];
                Arrays.fill(g, Hex.decode(c.get("generated_document").get("fill").asText())[0]);
                documents.put(VoucherDocuments.key(g), g);
            }
            VoucherDocuments.Resolution r = VoucherDocuments.resolveForVerification(secret, documents);
            String expected = c.get("resolution").asText();
            Class<?> type = switch (expected) {
                case "missing" -> VoucherDocuments.Resolution.Missing.class;
                case "mismatch" -> VoucherDocuments.Resolution.Mismatch.class;
                case "not_by_reference" -> VoucherDocuments.Resolution.NotByReference.class;
                default -> throw new IllegalStateException(expected);
            };
            assertThat(r).as(name).isInstanceOf(type);
            assertThat(VoucherDocuments.validate(documents, 1).isEmpty()).as(name)
                    .isEqualTo(c.get("documents_acceptable").asBoolean());
            assertThat(UnlockedVoucherBlob.read(secret, documents)).as(name).isNull();
            assertThat(UnlockedVoucherBlob.read(secret)).as(name).isNull();
            assertThat(VoucherValidator.validateProofSecret(secret, documents).isValid()).as(name).isFalse();
            assertThat(VoucherFingerprint.compute(secret, documents)).as(name).isNull();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "cv.writeByReferenceVectors", matches = ".+")
    void writeVectors() throws Exception {
        Files.writeString(Path.of(System.getProperty("cv.writeByReferenceVectors")), render(generate()));
    }
}
