package xyz.tcheeric.cashu.voucher.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.AttestationFixtures.Attestation;
import xyz.tcheeric.cashu.voucher.domain.IssuanceWarrant.TerminalRefusal;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden vectors for the owner attestation, shared with the wallet's TypeScript mirror
 * ({@code verifyTerminalWarrant}, imani-wallet#160 step 5b-5).
 *
 * <p>{@code owner-attestation-vectors.json} is the contract. This class replays every vector
 * in it against this build, so the file and the Java verifier cannot drift apart, and the
 * wallet runs the same file against its mirror. A vector names the refusal each verifier must
 * report, by its enum name.
 *
 * <p>Regenerate (only when the contract changes on purpose) with
 * {@code mvn -pl cashu-voucher-domain test -Dtest=OwnerAttestationVectorsTest
 * -Dcv.writeAttestationVectors=src/test/resources/owner-attestation-vectors.json}.
 * Service signatures use fresh randomness, so a regenerated file differs in those bytes.
 */
class OwnerAttestationVectorsTest {

    private static final String RESOURCE = "/owner-attestation-vectors.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("every digest vector in the shared file hashes to the recorded preimage and digest")
    void digestVectorsMatch() throws Exception {
        // The wallet builds the same preimage from the same strings. If Java's bytes moved,
        // every attestation the wallet signs would stop verifying here.
        JsonNode vectors = load().get("digest_vectors");
        assertTrue(vectors.size() > 0);
        for (JsonNode v : vectors) {
            String preimage = String.join("\u001f", IssuanceWarrant.ATTESTATION_DOMAIN,
                    v.get("v").textValue(), v.get("stall_pubkey").textValue(),
                    v.get("lock_key").textValue(), v.get("role").textValue(),
                    v.get("expires_at").textValue(), v.get("nonce").textValue());
            assertEquals(v.get("preimage_hex").textValue(),
                    Hex.toHexString(preimage.getBytes(StandardCharsets.UTF_8)), name(v));
            assertEquals(v.get("digest_hex").textValue(), Hex.toHexString(
                    IssuanceWarrant.attestationDigest(v.get("v").textValue(),
                            v.get("stall_pubkey").textValue(), v.get("lock_key").textValue(),
                            v.get("role").textValue(), v.get("expires_at").textValue(),
                            v.get("nonce").textValue())), name(v));
        }
    }

    @Test
    @DisplayName("every credential vector gets exactly the recorded verdict from both verifiers")
    void credentialVectorsMatch() throws Exception {
        // One credential, one expected answer from verifyOwnerAttestation and one from
        // verifyTerminal. The wallet asserts the same answers from its mirror.
        JsonNode vectors = load().get("credential_vectors");
        assertTrue(vectors.size() > 0);
        for (JsonNode v : vectors) {
            P2PKVoucherSecret credential = IssuanceWarrant.parseCredential(
                    v.get("credential_wire").textValue());
            String issuerId = v.get("issuer_id").textValue();

            assertEquals(refusal(v.get("owner_attestation_refusal")),
                    IssuanceWarrant.verifyOwnerAttestation(credential, issuerId).getRefusal(),
                    "verifyOwnerAttestation: " + name(v));

            JsonNode t = v.get("terminal");
            List<String> trusted = new ArrayList<>();
            t.get("trusted_service_keys").forEach(k -> trusted.add(k.textValue()));
            JsonNode expiry = t.get("expiry_check");
            IssuanceWarrant.TerminalSale sale = IssuanceWarrant.TerminalSale.builder()
                    .issuerId(issuerId)
                    .credential(IssuanceWarrant.parseCredential(v.get("credential_wire").textValue()))
                    .trustedServiceKeys(trusted)
                    .saleTotalMinor(t.get("sale_total_minor").longValue())
                    .faceDecimals(t.get("face_decimals").intValue())
                    .unit(t.get("unit").textValue())
                    .saleNonce(t.get("sale_nonce").textValue())
                    .signatureHex(t.get("signature_hex").textValue())
                    .couponFaceMinor(t.get("coupon_face_minor").longValue())
                    .expiryCheck(expiry.isTextual()
                            ? IssuanceWarrant.ExpiryCheck.skipOffline()
                            : IssuanceWarrant.ExpiryCheck.at(expiry.longValue()))
                    .build();
            assertEquals(refusal(t.get("refusal")),
                    IssuanceWarrant.verifyTerminal(sale).getRefusal(),
                    "verifyTerminal: " + name(v));
        }
    }

    @Test
    @DisplayName("the file covers every new refusal and the boundary cases the wallet must mirror")
    void vectorsCoverEveryNewRefusal() throws Exception {
        // A vector file without a BAD_OWNER_ATTESTATION case would let the wallet's mirror
        // skip the signature check and still pass.
        List<String> seen = new ArrayList<>();
        for (JsonNode v : load().get("credential_vectors")) {
            seen.add(v.get("owner_attestation_refusal").asText());
            seen.add(v.get("terminal").get("refusal").asText());
        }
        for (TerminalRefusal required : List.of(TerminalRefusal.OWNER_ATTESTATION_MISSING,
                TerminalRefusal.BAD_OWNER_ATTESTATION, TerminalRefusal.ATTESTATION_EXCEEDED,
                TerminalRefusal.WRONG_STALL, TerminalRefusal.MALFORMED_CREDENTIAL,
                TerminalRefusal.NOT_A_SELLING_TERMINAL, TerminalRefusal.BAD_SALE_SIGNATURE,
                TerminalRefusal.CREDENTIAL_EXPIRED)) {
            assertTrue(seen.contains(required.name()), "no vector for " + required);
        }
        assertTrue(seen.contains("null"), "no valid vector");
        List<String> names = new ArrayList<>();
        load().get("credential_vectors").forEach(v -> names.add(name(v)));
        for (String required : List.of("issuer-tag-other", "role-number")) {
            assertTrue(names.contains(required), "no vector " + required);
        }
    }

    // ------------------------------------------------------------------ generator

    @Test
    @EnabledIfSystemProperty(named = "cv.writeAttestationVectors", matches = ".+")
    @DisplayName("writes the vector file (opt-in)")
    void writeVectors() throws Exception {
        AttestationFixtures f = AttestationFixtures.INSTANCE;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("description", "cashu-voucher 0.17.0 owner attestation golden vectors "
                + "(imani-wallet#160 spec section 4.6). Replayed by OwnerAttestationVectorsTest; "
                + "mirrored by the wallet's verifyTerminalWarrant tests.");
        root.put("version", 1);
        root.put("domain", IssuanceWarrant.ATTESTATION_DOMAIN);
        root.put("separator", "\u001f");
        root.put("digest", "sha256(utf8(join(separator, domain, v, stall_pubkey, lock_key, role, "
                + "expires_at, nonce)))");
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("service", keyPair(f.serviceKey));
        keys.put("stall", keyPair(f.stallKey));
        keys.put("till", keyPair(f.tillKey));
        keys.put("other_stall", keyPair(f.otherStallKey));
        keys.put("thief", keyPair(f.thiefKey));
        root.put("keys", keys);

        List<Object> digests = new ArrayList<>();
        digests.add(digestVector("issue-and-redeem", "1", f.stall, f.till, "issue-and-redeem",
                "4000000000", f.attestationNonce));
        digests.add(digestVector("redeem-only", "1", f.stall, f.till, "redeem-only",
                "1823212800", "00".repeat(32)));
        digests.add(digestVector("expires-at-zero", "1", f.stall, f.till, "issue-and-redeem",
                "0", "ff".repeat(32)));
        root.put("digest_vectors", digests);

        Attestation good = f.attestation();
        String goodSig = good.getSig();
        String nonce = good.getNonce();
        List<Object> creds = new ArrayList<>();
        creds.add(cred("valid", f.stall, f.credential(good), null, null, f.tillSaleSignature(), "skip-offline"));
        creds.add(cred("valid-as-of-expiry-second", f.stall, f.credential(good), null, null,
                f.tillSaleSignature(), AttestationFixtures.T));
        creds.add(cred("expired-as-of", f.stall, f.credential(good), null,
                TerminalRefusal.CREDENTIAL_EXPIRED, f.tillSaleSignature(), AttestationFixtures.T + 1));
        creds.add(cred("valid-credential-expires-before-t", f.stall,
                f.credential("issue-and-redeem", f.till, good, AttestationFixtures.T - 1), null, null,
                f.tillSaleSignature(), "skip-offline"));
        creds.add(cred("redeem-only-attested", f.stall,
                f.credential("redeem-only", f.till, good.role("redeem-only")), null,
                TerminalRefusal.NOT_A_SELLING_TERMINAL, f.tillSaleSignature(), "skip-offline"));
        creds.add(cred("missing", f.stall, f.credential(null),
                TerminalRefusal.OWNER_ATTESTATION_MISSING, TerminalRefusal.OWNER_ATTESTATION_MISSING,
                f.tillSaleSignature(), "skip-offline"));
        Map<String, String> malformed = new LinkedHashMap<>();
        malformed.put("malformed-not-object", "\"x\"");
        malformed.put("malformed-null", "null");
        malformed.put("malformed-empty", "{}");
        malformed.put("malformed-extra-key", good.json().replace("}", ",\"extra\":\"x\"}"));
        malformed.put("malformed-missing-nonce", good.json().replace(",\"nonce\":\"" + nonce + "\"", ""));
        malformed.put("malformed-v-number", good.json().replace("\"v\":\"1\"", "\"v\":1"));
        malformed.put("malformed-v-2", good.json().replace("\"v\":\"1\"", "\"v\":\"2\""));
        malformed.put("malformed-expires-at-number",
                good.json().replace("\"expires_at\":\"4000000000\"", "\"expires_at\":4000000000"));
        malformed.put("malformed-expires-at-leading-zero",
                good.json().replace("\"expires_at\":\"4000000000\"", "\"expires_at\":\"04000000000\""));
        malformed.put("malformed-expires-at-above-int64",
                good.json().replace("\"expires_at\":\"4000000000\"",
                        "\"expires_at\":\"9223372036854775808\""));
        malformed.put("malformed-nonce-uppercase",
                good.json().replace(nonce, nonce.toUpperCase(Locale.ROOT)));
        malformed.put("malformed-sig-uppercase",
                good.json().replace(goodSig, goodSig.toUpperCase(Locale.ROOT)));
        malformed.put("malformed-sig-short", good.json().replace(goodSig, goodSig.substring(2)));
        for (Map.Entry<String, String> m : malformed.entrySet()) {
            creds.add(cred(m.getKey(), f.stall, f.credentialRaw(m.getValue()),
                    TerminalRefusal.OWNER_ATTESTATION_MISSING, TerminalRefusal.OWNER_ATTESTATION_MISSING,
                    f.tillSaleSignature(), "skip-offline"));
        }
        creds.add(cred("bad-signed-by-service", f.stall, f.credential(good.signedBy(f.serviceKey)),
                TerminalRefusal.BAD_OWNER_ATTESTATION, TerminalRefusal.BAD_OWNER_ATTESTATION,
                f.tillSaleSignature(), "skip-offline"));
        creds.add(cred("bad-merchant-sale-signature-as-attestation", f.stall,
                f.credential(good.sig(AttestationFixtures.sign(IssuanceWarrant.saleDigest(
                        f.stall, 2500L, 2, "EUR", nonce), f.stallKey))),
                TerminalRefusal.BAD_OWNER_ATTESTATION, TerminalRefusal.BAD_OWNER_ATTESTATION,
                f.tillSaleSignature(), "skip-offline"));
        creds.add(cred("bad-role-upgraded", f.stall,
                f.credential("issue-and-redeem", f.till, good.role("redeem-only")),
                TerminalRefusal.BAD_OWNER_ATTESTATION, TerminalRefusal.BAD_OWNER_ATTESTATION,
                f.tillSaleSignature(), "skip-offline"));
        creds.add(cred("bad-transplanted-to-thief-key", f.stall,
                f.credential("issue-and-redeem", f.thief, good),
                TerminalRefusal.BAD_OWNER_ATTESTATION, TerminalRefusal.BAD_OWNER_ATTESTATION,
                AttestationFixtures.sign(IssuanceWarrant.saleDigest(f.stall,
                        AttestationFixtures.SALE_TOTAL, AttestationFixtures.DECIMALS,
                        AttestationFixtures.UNIT, AttestationFixtures.SALE_NONCE), f.thiefKey),
                "skip-offline"));
        creds.add(cred("bad-attestation-expiry-raised", f.stall,
                f.credentialRaw(good.json().replace("\"4000000000\"", "\"4000000001\"")),
                TerminalRefusal.BAD_OWNER_ATTESTATION, TerminalRefusal.BAD_OWNER_ATTESTATION,
                f.tillSaleSignature(), "skip-offline"));
        creds.add(cred("exceeded", f.stall,
                f.credential("issue-and-redeem", f.till, good, AttestationFixtures.T + 1),
                TerminalRefusal.ATTESTATION_EXCEEDED, TerminalRefusal.ATTESTATION_EXCEEDED,
                f.tillSaleSignature(), "skip-offline"));
        creds.add(cred("wrong-stall", f.otherStall, f.credential(good),
                TerminalRefusal.WRONG_STALL, TerminalRefusal.WRONG_STALL,
                f.tillSaleSignature(), "skip-offline"));
        // Only the signed issuer tag names another stall: the metadata, the attestation and
        // issuerId are all ours (review 5b L1; the wallet mirror once skipped this check).
        P2PKVoucherSecret issuerTagOther = f.unsignedCredential("issue-and-redeem", f.till, good,
                AttestationFixtures.T);
        issuerTagOther.setIssuerId(f.otherStall);
        creds.add(cred("issuer-tag-other", f.stall, f.signed(issuerTagOther),
                TerminalRefusal.WRONG_STALL, TerminalRefusal.WRONG_STALL,
                f.tillSaleSignature(), "skip-offline"));
        // A numeric role is malformed, not a crash (review 5b L1, mutant R4).
        creds.add(cred("role-number", f.stall, f.credentialMetadata(
                        f.metadata("issue-and-redeem", f.till, good)
                                .replace("\"role\":\"issue-and-redeem\"", "\"role\":1")),
                TerminalRefusal.MALFORMED_CREDENTIAL, TerminalRefusal.NOT_A_SELLING_TERMINAL,
                f.tillSaleSignature(), "skip-offline"));
        creds.add(cred("stall-pubkey-uppercase", f.stall, f.credentialMetadata(
                        f.metadata("issue-and-redeem", f.till, good).replace(
                                "\"stall_pubkey\":\"" + f.stall,
                                "\"stall_pubkey\":\"" + f.stall.toUpperCase(Locale.ROOT))),
                TerminalRefusal.MALFORMED_CREDENTIAL, TerminalRefusal.NOT_A_SELLING_TERMINAL,
                f.tillSaleSignature(), "skip-offline"));
        creds.add(cred("duplicate-key-in-attestation", f.stall, f.credentialMetadata(
                        f.metadata("issue-and-redeem", f.till, good)
                                .replace("{\"v\":\"1\"", "{\"v\":\"1\",\"v\":\"1\"")),
                TerminalRefusal.MALFORMED_CREDENTIAL, TerminalRefusal.NOT_A_SELLING_TERMINAL,
                f.tillSaleSignature(), "skip-offline"));
        creds.add(cred("bad-sale-signature", f.stall, f.credential(good), null,
                TerminalRefusal.BAD_SALE_SIGNATURE, "00".repeat(64), "skip-offline"));
        creds.add(cred("missing-and-bad-sale-signature", f.stall, f.credential(null),
                TerminalRefusal.OWNER_ATTESTATION_MISSING, TerminalRefusal.OWNER_ATTESTATION_MISSING,
                "00".repeat(64), "skip-offline"));
        root.put("credential_vectors", creds);

        Path out = Path.of(System.getProperty("cv.writeAttestationVectors"));
        Files.writeString(out, JSON.enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(root) + "\n");
    }

    private static Map<String, Object> keyPair(byte[] key) {
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("priv", Hex.toHexString(key));
        pair.put("pub", AttestationFixtures.pubkeyOf(key));
        return pair;
    }

    private static Map<String, Object> digestVector(String name, String v, String stall,
            String lock, String role, String expiresAt, String nonce) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("v", v);
        m.put("stall_pubkey", stall);
        m.put("lock_key", lock);
        m.put("role", role);
        m.put("expires_at", expiresAt);
        m.put("nonce", nonce);
        String preimage = String.join("\u001f", IssuanceWarrant.ATTESTATION_DOMAIN, v, stall, lock,
                role, expiresAt, nonce);
        m.put("preimage_hex", Hex.toHexString(preimage.getBytes(StandardCharsets.UTF_8)));
        m.put("digest_hex", Hex.toHexString(
                IssuanceWarrant.attestationDigest(v, stall, lock, role, expiresAt, nonce)));
        return m;
    }

    private static Map<String, Object> cred(String name, String issuerId,
            P2PKVoucherSecret credential, TerminalRefusal ownerRefusal,
            TerminalRefusal terminalRefusal, String saleSignature, Object expiryCheck) {
        AttestationFixtures f = AttestationFixtures.INSTANCE;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("issuer_id", issuerId);
        m.put("credential_wire", credential.toString());
        m.put("owner_attestation_refusal", ownerRefusal == null ? null : ownerRefusal.name());
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("sale_total_minor", AttestationFixtures.SALE_TOTAL);
        t.put("face_decimals", AttestationFixtures.DECIMALS);
        t.put("unit", AttestationFixtures.UNIT);
        t.put("sale_nonce", AttestationFixtures.SALE_NONCE);
        t.put("signature_hex", saleSignature);
        t.put("coupon_face_minor", AttestationFixtures.SALE_TOTAL);
        t.put("trusted_service_keys", List.of(f.service));
        t.put("expiry_check", expiryCheck);
        t.put("refusal", terminalRefusal == null ? null : terminalRefusal.name());
        m.put("terminal", t);
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private static JsonNode load() throws Exception {
        try (InputStream in = OwnerAttestationVectorsTest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the test classpath");
            }
            return JSON.readTree(in);
        }
    }

    private static TerminalRefusal refusal(JsonNode node) {
        return node == null || node.isNull() ? null : TerminalRefusal.valueOf(node.textValue());
    }

    private static String name(JsonNode vector) {
        return vector.get("name").textValue();
    }
}
