package xyz.tcheeric.cashu.voucher.domain.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.UnlockedVoucherBlob;
import xyz.tcheeric.cashu.voucher.domain.VoucherCanonicalBytes;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;
import xyz.tcheeric.cashu.voucher.domain.util.VoucherSerializationUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden vectors of voucher ledger v2 ({@code voucher-ledger-v2-vectors.json}), the cross-language
 * contract. {@link #replaysEveryVector()} replays the committed file from its own inputs;
 * {@link #writeVectors()} regenerates it and only runs with
 * {@code -Dcv.writeLedgerV2Vectors=<path>}.
 */
class VoucherLedgerV2VectorsTest {

    static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    static final String PLATFORM_SK = "a1".repeat(32);
    static final String STALL_SK = "b2".repeat(32);
    static final String ATTACKER_SK = "c3".repeat(32);
    static final String VOUCHER_ID = "0b8e3f6a-1c2d-4e5f-8a9b-0c1d2e3f4a5b";
    static final String VOUCHER_ID_2 = "7f2c9d10-3b4a-4c5d-9e6f-a1b2c3d4e5f6";

    static String pub(String skHex) {
        try {
            return Hex.toHexString(Schnorr.genPubKey(Hex.decode(skHex)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] fixed(int b) {
        byte[] out = new byte[32];
        java.util.Arrays.fill(out, (byte) b);
        return out;
    }

    // ================================================================= generation

    static synchronized ObjectNode generate() throws Exception {
        craftCounter = 0x50;
        String platformPub = pub(PLATFORM_SK);
        String stallPub = pub(STALL_SK);
        byte[] platformRoot = VoucherLedgerRef.ledgerRoot(Hex.decode(PLATFORM_SK));
        byte[] stallRoot = VoucherLedgerRef.ledgerRoot(Hex.decode(STALL_SK));

        ObjectNode root = MAPPER.createObjectNode();
        root.put("description", "voucher ledger v2 golden vectors, see docs/explanation/voucher-ledger-v2.md. "
                + "Byte strings are lowercase hex. Regenerate with -Dcv.writeLedgerV2Vectors=<path>.");
        ObjectNode keys = root.putObject("keys");
        keys.put("platformSecretKey", PLATFORM_SK).put("platformPublicKey", platformPub)
                .put("platformLedgerRoot", Hex.toHexString(platformRoot));
        keys.put("stallSecretKey", STALL_SK).put("stallPublicKey", stallPub)
                .put("stallLedgerRoot", Hex.toHexString(stallRoot));
        keys.put("attackerSecretKey", ATTACKER_SK).put("attackerPublicKey", pub(ATTACKER_SK));

        // ---- derivations
        ArrayNode derivations = root.putArray("derivations");
        String[][] inputs = {
                {stallPub, VOUCHER_ID, "stall"},
                {stallPub.toUpperCase(), VOUCHER_ID.toUpperCase(), "stall"},
                {"merchant-42@imani", VOUCHER_ID_2, "platform"},
                {"!~", "00000000-0000-0000-0000-000000000000", "platform"},
        };
        for (String[] in : inputs) {
            byte[] ref = VoucherLedgerRef.ref(in[0], in[1]);
            byte[] r = in[2].equals("stall") ? stallRoot : platformRoot;
            byte[] sk = VoucherLedgerRef.eventKey(r, ref);
            derivations.addObject()
                    .put("issuerId", in[0]).put("voucherId", in[1])
                    .put("normalisedIssuerId", VoucherLedgerRef.normaliseIssuerId(in[0]))
                    .put("normalisedVoucherId", VoucherLedgerRef.normaliseVoucherId(in[1]))
                    .put("root", in[2])
                    .put("ref", Hex.toHexString(ref))
                    .put("key", Hex.toHexString(VoucherLedgerRef.key(ref)))
                    .put("locator", VoucherLedgerRef.locator(ref))
                    .put("eventKey", Hex.toHexString(sk))
                    .put("eventPubkey", pub(Hex.toHexString(sk)));
        }

        ArrayNode refused = root.putArray("refusedInputs");
        String[][] bad = {
                {"", VOUCHER_ID, "empty issuerId"},
                {"has space", VOUCHER_ID, "issuerId with a space"},
                {"caf\u00e9", VOUCHER_ID, "non-ASCII issuerId"},
                {"a".repeat(257), VOUCHER_ID, "issuerId over 256 characters"},
                {"tab\tid", VOUCHER_ID, "control character in issuerId"},
                {stallPub, "not-a-uuid", "voucherId not a UUID"},
                {stallPub, "0b8e3f6a1c2d4e5f8a9b0c1d2e3f4a5b", "voucherId without hyphens"},
                {stallPub, "{" + VOUCHER_ID + "}", "voucherId in braces"},
        };
        for (String[] in : bad) {
            refused.addObject().put("issuerId", in[0]).put("voucherId", in[1]).put("note", in[2]);
        }

        ArrayNode expirations = root.putArray("expirationTag");
        for (long t : new long[]{1790000000L, 1789948800L, 1L, 0L}) {
            expirations.addObject().put("expiresAt", t).put("expiration", VoucherLedgerV2.expirationTag(t));
        }

        // ---- sealed events
        byte[] doc = signedDoc(platformPub);
        String warrant = "{\"form\":\"none\",\"note\":\"" + "w".repeat(2000) + "\"}";
        ArrayNode sealed = root.putArray("sealed");
        sealed.add(sealCase("issued-platform-expiring", "platform", "platform",
                fields(stallPub, VOUCHER_ID, platformPub, 1790000000L, "Coffee \"large\" \\ caf\u00e9 \u2615 \ud83c\udf89 \u0001\u001f\n\t/\u007f",
                        null), VoucherStatus.ISSUED, null, 1, 2));
        sealed.add(sealCase("issued-platform-with-doc", "platform", "platform",
                fields(stallPub, VOUCHER_ID, platformPub, 1790000000L, "hi", null),
                VoucherStatus.ISSUED, doc, 3, 4));
        sealed.add(sealCase("redeemed-stall", "stall", "stall",
                fields(stallPub, VOUCHER_ID, platformPub, 1790000000L, "hi", null),
                VoucherStatus.REDEEMED, null, 5, 6));
        sealed.add(sealCase("issued-non-expiring-large-warrant", "platform", "platform",
                fields(stallPub, VOUCHER_ID_2, platformPub, null, null, warrant),
                VoucherStatus.ISSUED, null, 7, 8));
        sealed.add(sealCase("issued-non-hex-issuer", "platform", "platform",
                new VoucherFields(VOUCHER_ID_2, "merchant-42@imani", platformPub, "eur", 2500L, 2L,
                        null, null, "PROPORTIONAL", "2", "{\"name\":\"Bakery\"}", null),
                VoucherStatus.ISSUED, null, 9, 10));

        // ---- negative read cases
        ArrayNode negative = root.putArray("negativeReads");
        VoucherFields base = fields(stallPub, VOUCHER_ID, platformPub, 1790000000L, "hi", null);
        byte[] ref = base.ref();
        String d = VoucherLedgerRef.locator(ref);

        negative.add(crafted("redeemed-signed-by-platform", base,
                VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.REDEEMED, base, null), ref),
                PLATFORM_SK, "platform", "UNAUTHORISED_SIGNER"));
        negative.add(crafted("issued-signed-by-attacker", base,
                VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.ISSUED, base, null), ref),
                ATTACKER_SK, "platform", "UNAUTHORISED_SIGNER"));
        negative.add(crafted("seq-status-mismatch", base,
                VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.REDEEMED, base, null), ref)
                        .replace("\"seq\":2", "\"seq\":1"),
                STALL_SK, "stall", "SEQ_MISMATCH"));
        VoucherFields other = fields(stallPub, VOUCHER_ID_2, platformPub, 1790000000L, "hi", null);
        negative.add(crafted("body-for-another-voucher", base,
                VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.ISSUED, other, null), other.ref()),
                PLATFORM_SK, "platform", "REF_MISMATCH"));
        negative.add(crafted("body-ref-matches-but-voucher-ids-do-not", base,
                VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.ISSUED, other, null), ref),
                PLATFORM_SK, "platform", "REF_MISMATCH"));
        negative.add(crafted("unknown-body-key", base,
                VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.ISSUED, base, null), ref)
                        .replace("{\"v\":2,", "{\"v\":2,\"extra\":1,"),
                PLATFORM_SK, "platform", "UNKNOWN_KEY"));
        negative.add(crafted("unknown-voucher-key", base,
                VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.ISSUED, base, null), ref)
                        .replace("\"unit\":", "\"amount\":5,\"unit\":"),
                PLATFORM_SK, "platform", "UNKNOWN_KEY"));
        negative.add(crafted("duplicate-body-key", base,
                VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.ISSUED, base, null), ref)
                        .replace("\"status\":\"ISSUED\"", "\"status\":\"ISSUED\",\"status\":\"REDEEMED\""),
                PLATFORM_SK, "platform", "DUPLICATE_KEY"));
        negative.add(crafted("expired-status", base,
                VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.REDEEMED, base, null), ref)
                        .replace("\"REDEEMED\"", "\"EXPIRED\""),
                STALL_SK, "stall", "STATUS_REFUSED"));
        negative.add(crafted("wrong-version", base,
                VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.ISSUED, base, null), ref)
                        .replace("{\"v\":2,", "{\"v\":3,"),
                PLATFORM_SK, "platform", "WRONG_VERSION"));
        negative.add(crafted("faceValue-as-string", base,
                VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.ISSUED, base, null), ref)
                        .replace("\"faceValue\":1000", "\"faceValue\":\"1000\""),
                PLATFORM_SK, "platform", "MALFORMED_BODY"));
        negative.add(crafted("uppercase-issuer-in-body", base,
                VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.ISSUED, base, null), ref)
                        .replace(stallPub, stallPub.toUpperCase()),
                PLATFORM_SK, "platform", "MALFORMED_BODY"));

        // envelope-level cases
        String goodBody = VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.ISSUED, base, null), ref);
        String goodEnvelope = VoucherLedgerV2.writeEnvelope(goodBody, d, Hex.decode(PLATFORM_SK), fixed(0x21));
        negative.add(rawEnvelope("duplicate-envelope-key", base,
                goodEnvelope.replace(",\"pad\":\"", ",\"pad\":\"\",\"pad\":\""), "platform", "DUPLICATE_KEY"));
        negative.add(rawEnvelope("unknown-envelope-key", base,
                goodEnvelope.replace(",\"pad\":\"", ",\"x\":\"\",\"pad\":\""), "platform", "UNKNOWN_KEY"));
        negative.add(rawEnvelope("tampered-body", base,
                goodEnvelope.replace("\\\"memo\\\":\\\"hi\\\"", "\\\"memo\\\":\\\"ho\\\""), "platform", "BAD_SIGNATURE"));

        ObjectNode good = sealCase("tamper-source", "platform", "platform", base, VoucherStatus.ISSUED, null, 0x31, 0x32);
        String content = good.get("event").get("content").asText();
        byte[] raw = java.util.Base64.getDecoder().decode(content);
        raw[40] ^= 0x01;
        ObjectNode tampered = negative.addObject();
        tampered.put("name", "tampered-ciphertext");
        tampered.put("issuerId", base.issuerId()).put("voucherId", base.voucherId());
        tampered.putArray("trustedIssuerKeys").add(platformPub);
        ObjectNode te = good.get("event").deepCopy();
        te.put("content", java.util.Base64.getEncoder().encodeToString(raw));
        tampered.set("event", te);
        tampered.put("expected", "UNDECRYPTABLE");

        // forged: attacker derives an event key from ref alone and signs inner with own key
        byte[] refOnlyKey = VoucherLedgerRef.sha256(ref);
        ObjectNode forged = crafted("forged-at-same-d-key-from-ref", base,
                VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.REDEEMED, base, null), ref),
                ATTACKER_SK, "platform", "UNAUTHORISED_SIGNER");
        ((ObjectNode) forged.get("event")).put("pubkey", pub(Hex.toHexString(refOnlyKey)));
        forged.put("note", "outer pubkey is a key derived from ref alone; the outer author is never trusted");
        negative.add(forged);

        // doc verifies, but the body claims another faceValue: refused (M3)
        VoucherFields inflated = new VoucherFields(VOUCHER_ID, stallPub, platformPub, "sat", 99000L, 2L,
                1790000000L, "hi", "MINIMAL", "0.5", null, null);
        String inflatedBody = VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.ISSUED, base, doc), ref)
                .replace("\"faceValue\":1000", "\"faceValue\":99000");
        negative.add(crafted("doc-verifies-but-body-faceValue-differs", inflated, inflatedBody,
                STALL_SK, "stall", "DOC_MISMATCH"));

        // a stall-signed ISSUED with other fields and no doc, racing the platform one
        sealed.add(sealCase("issued-stall-other-fields", "stall", "stall", inflated, VoucherStatus.ISSUED,
                null, 11, 12));

        // a stall-signed ISSUED carrying a valid doc for the base fields (N4)
        sealed.add(sealCase("issued-stall-with-doc", "stall", "stall", base, VoucherStatus.ISSUED,
                doc, 13, 14));
        sealed.add(sealCase("issued-platform-no-doc", "platform", "platform", base, VoucherStatus.ISSUED,
                null, 15, 16));

        ObjectNode wrongD = sealCase("wrong-d", "platform", "platform", other, VoucherStatus.ISSUED, null, 0x41, 0x42);
        negative.addObject().put("name", "event-at-another-locator")
                .put("issuerId", base.issuerId()).put("voucherId", base.voucherId())
                .put("expected", "NOT_THIS_ADDRESS")
                .<ObjectNode>set("event", wrongD.get("event"))
                .putArray("trustedIssuerKeys").add(platformPub);

        // ---- resolve
        ArrayNode resolve = root.putArray("resolve");
        resolve.addObject().put("name", "issued-then-redeemed")
                .put("expectedStatus", "REDEEMED")
                .putArray("events").add("issued-platform-with-doc").add("redeemed-stall");
        resolve.addObject().put("name", "redeemed-beats-issued-whatever-the-order")
                .put("expectedStatus", "REDEEMED")
                .putArray("events").add("redeemed-stall").add("issued-platform-expiring");
        resolve.addObject().put("name", "forgery-ignored")
                .put("expectedStatus", "ISSUED")
                .putArray("events").add("issued-platform-expiring").add("negative:forged-at-same-d-key-from-ref");
        resolve.addObject().put("name", "nothing-accepted")
                .putNull("expectedStatus")
                .putArray("events").add("negative:redeemed-signed-by-platform");
        resolve.addObject().put("name", "platform-issued-beats-stall-issued-platform-first")
                .put("expectedStatus", "ISSUED").put("expectedFaceValue", 1000)
                .putArray("events").add("issued-platform-with-doc").add("issued-stall-other-fields");
        resolve.addObject().put("name", "platform-issued-beats-stall-issued-stall-first")
                .put("expectedStatus", "ISSUED").put("expectedFaceValue", 1000)
                .putArray("events").add("issued-stall-other-fields").add("issued-platform-with-doc");
        resolve.addObject().put("name", "mismatched-doc-event-ignored")
                .put("expectedStatus", "ISSUED").put("expectedFaceValue", 1000)
                .putArray("events").add("negative:doc-verifies-but-body-faceValue-differs").add("issued-platform-expiring");
        // N4: trusted ISSUED without doc (other memo) plus stall ISSUED with other fields and a doc.
        // The trusted event is the source; the stall doc signs other fields, so no doc is returned.
        resolve.addObject().put("name", "trusted-issued-without-doc-ignores-stall-doc-for-other-fields")
                .put("expectedStatus", "ISSUED").put("expectedFaceValue", 1000).put("expectedDoc", false)
                .putArray("events").add("issued-platform-expiring").add("issued-stall-with-doc");
        resolve.addObject().put("name", "trusted-issued-without-doc-ignores-stall-doc-for-other-fields-reversed")
                .put("expectedStatus", "ISSUED").put("expectedFaceValue", 1000).put("expectedDoc", false)
                .putArray("events").add("issued-stall-with-doc").add("issued-platform-expiring");
        // A doc from another event is kept when it signs exactly the source's fields.
        resolve.addObject().put("name", "trusted-issued-without-doc-takes-doc-from-same-fields")
                .put("expectedStatus", "ISSUED").put("expectedFaceValue", 1000).put("expectedDoc", true)
                .putArray("events").add("issued-stall-with-doc").add("issued-platform-no-doc");
        return root;
    }

    static VoucherFields fields(String issuer, String id, String platformPub, Long expiresAt, String memo, String warrant) {
        return new VoucherFields(id, issuer, platformPub, "sat", 1000L, 2L, expiresAt, memo,
                "MINIMAL", "0.5", null, warrant);
    }

    static ObjectNode entryJson(VoucherFields f) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("voucherId", f.voucherId()).put("issuerId", f.issuerId());
        if (f.issuerPublicKey() != null) n.put("issuerPublicKey", f.issuerPublicKey());
        if (f.unit() != null) n.put("unit", f.unit());
        if (f.faceValue() != null) n.put("faceValue", f.faceValue());
        if (f.faceDecimals() != null) n.put("faceDecimals", f.faceDecimals());
        if (f.expiresAt() != null) n.put("expiresAt", f.expiresAt());
        if (f.memo() != null) n.put("memo", f.memo());
        if (f.backingStrategy() != null) n.put("backingStrategy", f.backingStrategy());
        if (f.issuanceRatio() != null) n.put("issuanceRatio", f.issuanceRatio());
        if (f.merchantMetadata() != null) n.put("merchantMetadata", f.merchantMetadata());
        if (f.issuanceWarrant() != null) n.put("issuanceWarrant", f.issuanceWarrant());
        return n;
    }

    static VoucherFields fieldsFrom(JsonNode n) {
        return new VoucherFields(n.get("voucherId").asText(), n.get("issuerId").asText(),
                txt(n, "issuerPublicKey"), txt(n, "unit"), lng(n, "faceValue"), lng(n, "faceDecimals"),
                lng(n, "expiresAt"), txt(n, "memo"), txt(n, "backingStrategy"), txt(n, "issuanceRatio"),
                txt(n, "merchantMetadata"), txt(n, "issuanceWarrant"));
    }

    static String txt(JsonNode n, String k) {
        return n.has(k) ? n.get(k).asText() : null;
    }

    static Long lng(JsonNode n, String k) {
        return n.has(k) ? n.get(k).asLong() : null;
    }

    static String sk(String who) {
        return switch (who) {
            case "platform" -> PLATFORM_SK;
            case "stall" -> STALL_SK;
            case "attacker" -> ATTACKER_SK;
            default -> throw new IllegalArgumentException(who);
        };
    }

    static ObjectNode sealCase(String name, String signer, String root, VoucherFields f, VoucherStatus status,
                               byte[] doc, int nonceByte, int auxByte) {
        byte[] nonce = fixed(nonceByte);
        byte[] aux = fixed(auxByte);
        LedgerEntry entry = new LedgerEntry(status, f, doc);
        VoucherLedgerV2.Sealed s = VoucherLedgerV2.seal(entry, Hex.decode(sk(signer)),
                VoucherLedgerRef.ledgerRoot(Hex.decode(sk(root))), nonce, aux);
        ObjectNode n = MAPPER.createObjectNode();
        n.put("name", name);
        ObjectNode in = n.putObject("input");
        in.put("status", status.name()).put("signer", signer).put("root", root)
                .put("nip44Nonce", Hex.toHexString(nonce)).put("auxRand", Hex.toHexString(aux));
        in.set("voucher", entryJson(f));
        if (doc != null) in.put("doc", Hex.toHexString(doc));
        byte[] ref = f.ref();
        String body = VoucherLedgerV2.writeBody(entry, ref);
        String envelope = VoucherLedgerV2.writeEnvelope(body, VoucherLedgerRef.locator(ref), Hex.decode(sk(signer)), aux);
        n.put("body", body);
        n.put("envelope", envelope);
        n.put("envelopeBytes", LedgerJson.utf8Length(envelope));
        n.set("event", eventJson(s.template()));
        n.putArray("trustedIssuerKeys").add(pub(PLATFORM_SK));
        return n;
    }

    static ObjectNode eventJson(LedgerEvent e) {
        ObjectNode ev = MAPPER.createObjectNode();
        ev.put("pubkey", e.pubkey()).put("kind", e.kind());
        ArrayNode tags = ev.putArray("tags");
        for (List<String> t : e.tags()) {
            ArrayNode a = tags.addArray();
            t.forEach(a::add);
        }
        ev.put("content", e.content());
        return ev;
    }

    static LedgerEvent eventFrom(JsonNode ev) {
        List<List<String>> tags = new ArrayList<>();
        for (JsonNode t : ev.get("tags")) {
            List<String> tag = new ArrayList<>();
            t.forEach(x -> tag.add(x.asText()));
            tags.add(tag);
        }
        return new LedgerEvent(ev.get("pubkey").asText(), ev.get("kind").asInt(), tags, ev.get("content").asText());
    }

    private static int craftCounter = 0x50;

    static ObjectNode crafted(String name, VoucherFields at, String body, String signerSk, String root, String expected) {
        byte[] ref = at.ref();
        String d = VoucherLedgerRef.locator(ref);
        String envelope = VoucherLedgerV2.writeEnvelope(body, d, Hex.decode(signerSk), fixed(craftCounter++));
        return rawEnvelope(name, at, envelope, root, expected);
    }

    static ObjectNode rawEnvelope(String name, VoucherFields at, String envelope, String root, String expected) {
        byte[] ref = at.ref();
        String d = VoucherLedgerRef.locator(ref);
        byte[] skV = VoucherLedgerRef.eventKey(VoucherLedgerRef.ledgerRoot(Hex.decode(sk(root))), ref);
        String content = Nip44V2.encrypt(envelope, VoucherLedgerRef.key(ref), fixed(craftCounter++));
        LedgerEvent e = new LedgerEvent(pub(Hex.toHexString(skV)), VoucherLedgerV2.KIND,
                List.of(List.of("d", d)), content);
        ObjectNode n = MAPPER.createObjectNode();
        n.put("name", name).put("issuerId", at.issuerId()).put("voucherId", at.voucherId());
        n.putArray("trustedIssuerKeys").add(pub(PLATFORM_SK));
        n.put("envelope", envelope);
        n.set("event", eventJson(e));
        n.put("expected", expected);
        return n;
    }

    /** A deterministic signed unlocked-voucher CBOR blob, signed by the platform key. */
    static byte[] signedDoc(String platformPub) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("voucherId", VOUCHER_ID);
        m.put("issuerId", pub(STALL_SK));
        m.put("unit", "sat");
        m.put("faceValue", 1000L);
        m.put("expiresAt", 1790000000L);
        m.put("memo", "hi");
        m.put("backingStrategy", "MINIMAL");
        m.put("issuanceRatio", 0.5d);
        m.put("faceDecimals", 2L);
        m.put("nonce", "00".repeat(32));
        m.put("issuerPublicKey", platformPub);
        VoucherSecret carrier = new VoucherSecret();
        carrier.setData(VoucherSerializationUtils.toCbor(m));
        VoucherSecret unsigned = UnlockedVoucherBlob.read(carrier);
        byte[] digest = VoucherLedgerRef.sha256(VoucherCanonicalBytes.of(unsigned));
        m.put("issuerSignature", Hex.toHexString(Schnorr.sign(digest, Hex.decode(PLATFORM_SK), new byte[32])));
        return VoucherSerializationUtils.toCbor(m);
    }

    // ================================================================= replay

    static JsonNode committed() throws Exception {
        try (InputStream in = VoucherLedgerV2VectorsTest.class.getResourceAsStream("/voucher-ledger-v2-vectors.json")) {
            assertThat(in).as("vector file on the classpath").isNotNull();
            return MAPPER.readTree(in);
        }
    }

    @Test
    @DisplayName("the committed vector file is exactly what the code generates")
    void fileMatchesGenerator() throws Exception {
        assertThat(MAPPER.writeValueAsString(committed())).isEqualTo(MAPPER.writeValueAsString(generate()));
    }

    @Test
    @DisplayName("every vector replays from its own inputs")
    void replaysEveryVector() throws Exception {
        JsonNode v = committed();
        JsonNode keys = v.get("keys");
        assertThat(pub(keys.get("platformSecretKey").asText())).isEqualTo(keys.get("platformPublicKey").asText());
        assertThat(Hex.toHexString(VoucherLedgerRef.ledgerRoot(Hex.decode(keys.get("stallSecretKey").asText()))))
                .isEqualTo(keys.get("stallLedgerRoot").asText());
        assertThat(Hex.toHexString(VoucherLedgerRef.ledgerRoot(Hex.decode(keys.get("platformSecretKey").asText()))))
                .isEqualTo(keys.get("platformLedgerRoot").asText());

        for (JsonNode d : v.get("derivations")) {
            byte[] ref = VoucherLedgerRef.ref(d.get("issuerId").asText(), d.get("voucherId").asText());
            assertThat(Hex.toHexString(ref)).isEqualTo(d.get("ref").asText());
            assertThat(VoucherLedgerRef.ref(d.get("normalisedIssuerId").asText(),
                    d.get("normalisedVoucherId").asText())).isEqualTo(ref);
            assertThat(Hex.toHexString(VoucherLedgerRef.key(ref))).isEqualTo(d.get("key").asText());
            assertThat(VoucherLedgerRef.locator(ref)).isEqualTo(d.get("locator").asText());
            byte[] root = Hex.decode(keys.get(d.get("root").asText() + "LedgerRoot").asText());
            byte[] sk = VoucherLedgerRef.eventKey(root, ref);
            assertThat(Hex.toHexString(sk)).isEqualTo(d.get("eventKey").asText());
            assertThat(pub(Hex.toHexString(sk))).isEqualTo(d.get("eventPubkey").asText());
        }
        for (JsonNode r : v.get("refusedInputs")) {
            assertThatThrownBy(() -> VoucherLedgerRef.ref(r.get("issuerId").asText(), r.get("voucherId").asText()))
                    .as(r.get("note").asText()).isInstanceOf(IllegalArgumentException.class);
        }
        for (JsonNode e : v.get("expirationTag")) {
            assertThat(VoucherLedgerV2.expirationTag(e.get("expiresAt").asLong())).isEqualTo(e.get("expiration").asLong());
        }

        Map<String, LedgerEvent> byName = new LinkedHashMap<>();
        for (JsonNode s : v.get("sealed")) {
            JsonNode in = s.get("input");
            VoucherFields f = fieldsFrom(in.get("voucher"));
            byte[] doc = in.has("doc") ? Hex.decode(in.get("doc").asText()) : null;
            LedgerEntry entry = new LedgerEntry(VoucherStatus.valueOf(in.get("status").asText()), f, doc);
            byte[] signer = Hex.decode(keys.get(in.get("signer").asText() + "SecretKey").asText());
            byte[] root = Hex.decode(keys.get(in.get("root").asText() + "LedgerRoot").asText());
            VoucherLedgerV2.Sealed sealed = VoucherLedgerV2.seal(entry, signer, root,
                    Hex.decode(in.get("nip44Nonce").asText()), Hex.decode(in.get("auxRand").asText()));
            assertThat(VoucherLedgerV2.writeBody(entry, f.ref())).as(s.get("name").asText()).isEqualTo(s.get("body").asText());
            assertThat(eventJson(sealed.template())).as(s.get("name").asText()).isEqualTo(s.get("event"));
            String envelope = Nip44V2.decrypt(sealed.template().content(), VoucherLedgerRef.key(f.ref()));
            assertThat(envelope).isEqualTo(s.get("envelope").asText());
            assertThat(LedgerJson.utf8Length(envelope)).isEqualTo(s.get("envelopeBytes").asInt());

            LedgerEvent event = eventFrom(s.get("event"));
            VoucherLedgerV2.Opened opened = VoucherLedgerV2.read(event, f.ref(), trusted(s));
            assertThat(opened.entry()).as(s.get("name").asText()).isEqualTo(entry);
            assertThat(opened.docDropped()).isFalse();
            byName.put(s.get("name").asText(), event);
        }

        for (JsonNode n : v.get("negativeReads")) {
            LedgerEvent event = eventFrom(n.get("event"));
            byte[] ref = VoucherLedgerRef.ref(n.get("issuerId").asText(), n.get("voucherId").asText());
            assertThatThrownBy(() -> VoucherLedgerV2.read(event, ref, trusted(n)))
                    .as(n.get("name").asText())
                    .isInstanceOfSatisfying(LedgerRefusedException.class,
                            ex -> assertThat(ex.reason().name()).isEqualTo(n.get("expected").asText()));
            byName.put("negative:" + n.get("name").asText(), event);
        }

        byte[] ref = VoucherLedgerRef.ref(pub(STALL_SK), VOUCHER_ID);
        for (JsonNode r : v.get("resolve")) {
            List<LedgerEvent> events = new ArrayList<>();
            r.get("events").forEach(x -> events.add(byName.get(x.asText())));
            var resolved = VoucherLedgerV2.resolve(events, ref, List.of(pub(PLATFORM_SK)));
            if (r.get("expectedStatus").isNull()) {
                assertThat(resolved).as(r.get("name").asText()).isEmpty();
            } else {
                assertThat(resolved).as(r.get("name").asText()).isPresent();
                assertThat(resolved.get().status().name()).isEqualTo(r.get("expectedStatus").asText());
                if (r.has("expectedDoc")) {
                    assertThat(resolved.get().doc() != null).as(r.get("name").asText() + " doc")
                            .isEqualTo(r.get("expectedDoc").asBoolean());
                }
                if (r.has("expectedFaceValue")) {
                    assertThat(resolved.get().voucher().faceValue()).as(r.get("name").asText())
                            .isEqualTo(r.get("expectedFaceValue").asLong());
                }
            }
        }
    }

    static List<String> trusted(JsonNode n) {
        List<String> out = new ArrayList<>();
        for (Iterator<JsonNode> it = n.get("trustedIssuerKeys").elements(); it.hasNext(); ) {
            out.add(it.next().asText());
        }
        return out;
    }

    @Test
    @EnabledIfSystemProperty(named = "cv.writeLedgerV2Vectors", matches = ".+")
    @DisplayName("regenerates voucher-ledger-v2-vectors.json (opt-in)")
    void writeVectors() throws Exception {
        Path out = Path.of(System.getProperty("cv.writeLedgerV2Vectors"));
        Files.writeString(out, MAPPER.writeValueAsString(generate()) + "\n");
    }
}
