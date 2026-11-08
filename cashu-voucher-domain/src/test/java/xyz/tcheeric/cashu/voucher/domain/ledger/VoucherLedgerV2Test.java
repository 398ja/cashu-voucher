package xyz.tcheeric.cashu.voucher.domain.ledger;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static xyz.tcheeric.cashu.voucher.domain.ledger.VoucherLedgerV2VectorsTest.ATTACKER_SK;
import static xyz.tcheeric.cashu.voucher.domain.ledger.VoucherLedgerV2VectorsTest.PLATFORM_SK;
import static xyz.tcheeric.cashu.voucher.domain.ledger.VoucherLedgerV2VectorsTest.STALL_SK;
import static xyz.tcheeric.cashu.voucher.domain.ledger.VoucherLedgerV2VectorsTest.VOUCHER_ID;
import static xyz.tcheeric.cashu.voucher.domain.ledger.VoucherLedgerV2VectorsTest.pub;

/** Unit tests for every refusal the v2 spec names, on both the write and read side. */
class VoucherLedgerV2Test {

    static final String STALL = pub(STALL_SK);
    static final String PLATFORM = pub(PLATFORM_SK);
    static final byte[] PLATFORM_ROOT = VoucherLedgerRef.ledgerRoot(Hex.decode(PLATFORM_SK));
    static final byte[] STALL_ROOT = VoucherLedgerRef.ledgerRoot(Hex.decode(STALL_SK));

    static VoucherFields fields(String metadata) {
        return new VoucherFields(VOUCHER_ID, STALL, PLATFORM, "sat", 1000L, 2L, 1790000000L, "hi",
                "MINIMAL", "0.5", metadata, null);
    }

    static LedgerEvent issued(VoucherFields f) {
        return VoucherLedgerV2.seal(new LedgerEntry(VoucherStatus.ISSUED, f, null),
                Hex.decode(PLATFORM_SK), PLATFORM_ROOT).template();
    }

    static LedgerRefusal refusal(Runnable r) {
        try {
            r.run();
        } catch (LedgerRefusedException e) {
            return e.reason();
        }
        throw new AssertionError("expected a refusal");
    }

    // ---------------------------------------------------------------- derivation input checks

    @Test
    void refusesBadIds() {
        assertThatThrownBy(() -> VoucherLedgerRef.ref(null, VOUCHER_ID)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VoucherLedgerRef.ref(STALL, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VoucherLedgerRef.ref("a\u007f", VOUCHER_ID)).isInstanceOf(IllegalArgumentException.class);
        assertThat(VoucherLedgerRef.ref("a".repeat(256), VOUCHER_ID)).hasSize(32);
        assertThatThrownBy(() -> VoucherLedgerRef.key(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VoucherLedgerRef.locator(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VoucherLedgerRef.eventKey(new byte[31], new byte[32])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VoucherLedgerRef.ledgerRoot(new byte[33])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an event key outside [1, n-1] is refused")
    void scalarRange() {
        assertThatThrownBy(() -> VoucherLedgerRef.requireScalar(new byte[32])).isInstanceOf(IllegalArgumentException.class);
        byte[] n = Hex.decode("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141");
        assertThatThrownBy(() -> VoucherLedgerRef.requireScalar(n)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Arrays.equals(n, new byte[32])).as("refused scalar is zeroed").isTrue();
        byte[] nMinus1 = Hex.decode("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364140");
        VoucherLedgerRef.requireScalar(nMinus1);
    }

    @Test
    void returnsCopies() {
        byte[] ref = VoucherLedgerRef.ref(STALL, VOUCHER_ID);
        byte[] k1 = VoucherLedgerRef.key(ref);
        k1[0] ^= 1;
        assertThat(VoucherLedgerRef.key(ref)).isNotEqualTo(k1);
    }

    // ---------------------------------------------------------------- JSON writing rule

    @Test
    @DisplayName("writing rule escapes exactly what JSON.stringify escapes")
    void writingRule() {
        assertThat(LedgerJson.quote("\"\\\b\t\n\f\r\u0000\u001f/\u007f\u00e9\ud83c\udf89"))
                .isEqualTo("\"\\\"\\\\\\b\\t\\n\\f\\r\\u0000\\u001f/\u007f\u00e9\ud83c\udf89\"");
        assertThatThrownBy(() -> LedgerJson.quote("a\ud800")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LedgerJson.quote("\udc00a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LedgerJson.quote("\ud800a")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- write-side refusals

    @Test
    void refusesExpiredEntry() {
        assertThat(refusal(() -> new LedgerEntry(VoucherStatus.EXPIRED, fields(null), null)))
                .isEqualTo(LedgerRefusal.STATUS_REFUSED);
    }

    @Test
    void refusesTerminalCredential() {
        VoucherFields terminal = fields("{\"terminal\":true,\"role\":\"redeem-only\"}");
        assertThat(refusal(() -> issued(terminal))).isEqualTo(LedgerRefusal.TERMINAL_CREDENTIAL);
    }

    @Test
    @DisplayName("terminal detection: absent metadata is an ordinary voucher")
    void terminalDetection() {
        assertThat(VoucherLedgerV2.isTerminalCredential("{\"terminal\":true}")).isTrue();
        assertThat(VoucherLedgerV2.isTerminalCredential("{\"terminal\":false}")).isFalse();
        assertThat(VoucherLedgerV2.isTerminalCredential("{\"campaign\":\"x\"}")).isFalse();
        assertThat(VoucherLedgerV2.isTerminalCredential("{\"terminal\":null}")).isFalse();
        assertThat(VoucherLedgerV2.isTerminalCredential("")).isFalse();
        assertThat(VoucherLedgerV2.isTerminalCredential(null)).isFalse();
    }

    /**
     * Metadata we cannot read must never be published as an ordinary voucher: it might be a till's
     * credential. Each of these used to answer "not a credential", so the credential was published.
     */
    @Test
    @DisplayName("terminal detection fails closed on metadata it cannot read")
    void terminalDetectionFailsClosed() {
        assertThat(VoucherLedgerV2.isTerminalCredential("{\"terminal\":true,\"terminal\":true}"))
                .as("duplicate key").isTrue();
        assertThat(VoucherLedgerV2.isTerminalCredential("{\"terminal\":true} trailing"))
                .as("trailing data").isTrue();
        assertThat(VoucherLedgerV2.isTerminalCredential("not json")).as("not JSON").isTrue();
        assertThat(VoucherLedgerV2.isTerminalCredential("[true]")).as("an array").isTrue();
        assertThat(VoucherLedgerV2.isTerminalCredential("\"terminal\"")).as("a bare string").isTrue();
        assertThat(VoucherLedgerV2.isTerminalCredential("{\"terminal\":\"true\"}")).as("string true").isTrue();
        assertThat(VoucherLedgerV2.isTerminalCredential("{\"terminal\":\" TRUE \"}")).as("string TRUE").isTrue();
    }

    @Test
    @DisplayName("sealing refuses a voucher whose metadata cannot be read")
    void sealRefusesUnreadableMetadata() {
        assertThat(refusal(() -> issued(fields("{\"terminal\":true,\"terminal\":true}"))))
                .isEqualTo(LedgerRefusal.TERMINAL_CREDENTIAL);
    }

    /**
     * Logging a voucher, an opened event or a resolved status must not leak the memo, the merchant
     * metadata, the warrant (a terminal warrant carries the till's credential secret) or the ids
     * (the voucher id is the key to the ledger entry).
     */
    @Test
    @DisplayName("toString never prints memo, metadata, warrant or ids")
    void toStringLeaksNothing() {
        String memo = "memo-secret-7d1";
        String metadata = "{\"note\":\"metadata-secret-4a2\"}";
        String warrant = "warrant-secret-9c3";
        VoucherFields f = new VoucherFields(VOUCHER_ID, STALL, PLATFORM, "sat", 1000L, 2L, 1790000000L,
                memo, "MINIMAL", "0.5", metadata, warrant);
        LedgerEvent event = VoucherLedgerV2.seal(new LedgerEntry(VoucherStatus.ISSUED, f, null),
                Hex.decode(PLATFORM_SK), PLATFORM_ROOT).template();
        byte[] ref = f.ref();
        VoucherLedgerV2.Opened opened = VoucherLedgerV2.open(event, ref, List.of(PLATFORM)).orElseThrow();
        VoucherLedgerV2.Resolved resolved = VoucherLedgerV2.resolve(List.of(event), ref, List.of(PLATFORM))
                .orElseThrow();

        for (String printed : List.of(f.toString(), opened.toString(), resolved.toString(),
                new LedgerEntry(VoucherStatus.ISSUED, f, null).toString())) {
            assertThat(printed).doesNotContain(memo, "metadata-secret-4a2", warrant, VOUCHER_ID, STALL);
        }
    }

    @Test
    void refusesRedeemedByNonStall() {
        assertThat(refusal(() -> VoucherLedgerV2.seal(new LedgerEntry(VoucherStatus.REDEEMED, fields(null), null),
                Hex.decode(PLATFORM_SK), PLATFORM_ROOT))).isEqualTo(LedgerRefusal.UNAUTHORISED_SIGNER);
    }

    @Test
    void refusesTooLarge() {
        VoucherFields huge = new VoucherFields(VOUCHER_ID, STALL, PLATFORM, "sat", 1L, 0L, null,
                "m".repeat(70_000), null, null, null, null);
        assertThat(refusal(() -> issued(huge))).isEqualTo(LedgerRefusal.TOO_LARGE);
    }

    @Test
    @DisplayName("every envelope is exactly one bucket long")
    void padsToBucket() {
        for (int size : new int[]{0, 500, 1500, 3000, 6000, 12000, 30000, 60000}) {
            VoucherFields f = new VoucherFields(VOUCHER_ID, STALL, PLATFORM, null, null, null, null,
                    size == 0 ? null : "\u00e9".repeat(size / 2), null, null, null, null);
            LedgerEvent e = issued(f);
            String env = Nip44V2.decrypt(e.content(), VoucherLedgerRef.key(f.ref()));
            int len = LedgerJson.utf8Length(env);
            assertThat(Arrays.stream(VoucherLedgerV2.BUCKETS).anyMatch(b -> b == len)).as("size " + size).isTrue();
        }
    }

    @Test
    void noExpiryNoTag() {
        VoucherFields f = new VoucherFields(VOUCHER_ID, STALL, null, null, null, null, null, null, null, null, null, null);
        assertThat(issued(f).tags()).containsExactly(List.of("d", VoucherLedgerRef.locator(f.ref())));
    }

    // ---------------------------------------------------------------- read-side refusals

    @Test
    void roundTripAndTrust() {
        VoucherFields f = fields(null);
        LedgerEvent e = issued(f);
        assertThat(VoucherLedgerV2.open(e, f.ref(), List.of(PLATFORM))).isPresent();
        assertThat(VoucherLedgerV2.open(e, f.ref(), List.of(PLATFORM.toUpperCase()))).isPresent();
        assertThat(refusal(() -> VoucherLedgerV2.read(e, f.ref(), List.of()))).isEqualTo(LedgerRefusal.UNAUTHORISED_SIGNER);
        assertThat(refusal(() -> VoucherLedgerV2.read(e, f.ref(), null))).isEqualTo(LedgerRefusal.UNAUTHORISED_SIGNER);
    }

    @Test
    @DisplayName("an ISSUED event signed by the stall itself is accepted without trust")
    void stallMayIssue() {
        VoucherFields f = fields(null);
        LedgerEvent e = VoucherLedgerV2.seal(new LedgerEntry(VoucherStatus.ISSUED, f, null),
                Hex.decode(STALL_SK), STALL_ROOT).template();
        assertThat(VoucherLedgerV2.open(e, f.ref(), List.of())).isPresent();
    }

    @Test
    void refusesWrongAddress() {
        VoucherFields f = fields(null);
        LedgerEvent e = issued(f);
        LedgerEvent wrongKind = new LedgerEvent(e.pubkey(), 1, e.tags(), e.content());
        LedgerEvent twoD = new LedgerEvent(e.pubkey(), e.kind(),
                List.of(e.tags().get(0), e.tags().get(0)), e.content());
        LedgerEvent noD = new LedgerEvent(e.pubkey(), e.kind(), List.of(), e.content());
        LedgerEvent noContent = new LedgerEvent(e.pubkey(), e.kind(), e.tags(), null);
        assertThat(refusal(() -> VoucherLedgerV2.read(wrongKind, f.ref(), List.of(PLATFORM)))).isEqualTo(LedgerRefusal.NOT_THIS_ADDRESS);
        assertThat(refusal(() -> VoucherLedgerV2.read(twoD, f.ref(), List.of(PLATFORM)))).isEqualTo(LedgerRefusal.NOT_THIS_ADDRESS);
        assertThat(refusal(() -> VoucherLedgerV2.read(noD, f.ref(), List.of(PLATFORM)))).isEqualTo(LedgerRefusal.NOT_THIS_ADDRESS);
        assertThat(refusal(() -> VoucherLedgerV2.read(null, f.ref(), List.of(PLATFORM)))).isEqualTo(LedgerRefusal.NOT_THIS_ADDRESS);
        assertThat(refusal(() -> VoucherLedgerV2.read(noContent, f.ref(), List.of(PLATFORM)))).isEqualTo(LedgerRefusal.UNDECRYPTABLE);
    }

    /** Encrypts an arbitrary envelope at the voucher's address. */
    static LedgerEvent withEnvelope(VoucherFields f, String envelope) {
        byte[] ref = f.ref();
        return new LedgerEvent(PLATFORM, VoucherLedgerV2.KIND, List.of(List.of("d", VoucherLedgerRef.locator(ref))),
                Nip44V2.encrypt(envelope, VoucherLedgerRef.key(ref), new byte[32]));
    }

    static String signedEnvelope(VoucherFields f, String body) {
        return VoucherLedgerV2.writeEnvelope(body, VoucherLedgerRef.locator(f.ref()), Hex.decode(PLATFORM_SK), new byte[32]);
    }

    static String body(VoucherFields f) {
        return VoucherLedgerV2.writeBody(new LedgerEntry(VoucherStatus.ISSUED, f, null), f.ref());
    }

    @Test
    void refusesMalformedEnvelopes() {
        VoucherFields f = fields(null);
        String good = signedEnvelope(f, body(f));
        for (String bad : new String[]{"[]", "not json", "{\"body\":\"x\"}",
                good.replace("\"signer\":\"", "\"signer\":\"A"),
                good.replace("\"pad\":\"0", "\"pad\":\"1"),
                good.replace("\"sig\":\"", "\"sig\":\"00"),
                "{\"body\":1,\"sig\":\"\",\"signer\":\"\",\"pad\":\"\"}",
                good + " x"}) {
            assertThat(refusal(() -> VoucherLedgerV2.read(withEnvelope(f, bad), f.ref(), List.of(PLATFORM))))
                    .as(bad.length() > 40 ? bad.substring(0, 40) : bad).isEqualTo(LedgerRefusal.MALFORMED_ENVELOPE);
        }
    }

    @Test
    void refusesMalformedBodies() {
        VoucherFields f = fields(null);
        String b = body(f);
        String[] bad = {
                "[]",
                b.replace("\"v\":2", "\"v\":\"2\""),
                b.replace("\"seq\":1", "\"seq\":\"1\""),
                b.replace("\"status\":\"ISSUED\"", "\"status\":1"),
                b.replace("\"voucher\":{", "\"voucher\":[{").replace("}}", "}]}"),
                b.replace("\"memo\":\"hi\"", "\"memo\":5"),
                b.replace("\"faceValue\":1000", "\"faceValue\":1.5"),
                b.replace("\"faceValue\":1000", "\"faceValue\":99999999999999999999"),
                b.replace(VOUCHER_ID, VOUCHER_ID.toUpperCase()),
                b.replace("}}", "},\"doc\":5}"),
                b.replace("\"voucherId\":\"" + VOUCHER_ID + "\",", ""),
        };
        for (String x : bad) {
            assertThat(refusal(() -> VoucherLedgerV2.read(withEnvelope(f, signedEnvelope(f, x)), f.ref(), List.of(PLATFORM))))
                    .as(x).isIn(LedgerRefusal.MALFORMED_BODY, LedgerRefusal.REF_MISMATCH);
        }
        assertThat(refusal(() -> VoucherLedgerV2.read(withEnvelope(f, signedEnvelope(f, b.replace("\"v\":2", "\"v\":20000000000"))),
                f.ref(), List.of(PLATFORM)))).isEqualTo(LedgerRefusal.WRONG_VERSION);
        assertThat(refusal(() -> VoucherLedgerV2.read(withEnvelope(f, signedEnvelope(f, b.replace("\"status\":\"ISSUED\"", "\"status\":\"PENDING\""))),
                f.ref(), List.of(PLATFORM)))).isEqualTo(LedgerRefusal.STATUS_REFUSED);
    }

    @Test
    @DisplayName("a doc that does not verify is dropped, the event is kept")
    void badDocDropped() {
        VoucherFields f = fields(null);
        String b = body(f).replace("}}", "},\"doc\":\"abcd\"}");
        VoucherLedgerV2.Opened o = VoucherLedgerV2.read(withEnvelope(f, signedEnvelope(f, b)), f.ref(), List.of(PLATFORM));
        assertThat(o.docDropped()).isTrue();
        assertThat(o.entry().doc()).isNull();
        String odd = body(f).replace("}}", "},\"doc\":\"abc\"}");
        assertThat(VoucherLedgerV2.read(withEnvelope(f, signedEnvelope(f, odd)), f.ref(), List.of(PLATFORM)).docDropped()).isTrue();
    }

    @Test
    @DisplayName("a doc for another voucher is dropped")
    void docForOtherVoucherDropped() throws Exception {
        VoucherFields f = new VoucherFields(UUID.randomUUID().toString(), STALL, PLATFORM, "sat", 1000L, 2L,
                1790000000L, "hi", "MINIMAL", "0.5", null, null);
        byte[] doc = VoucherLedgerV2VectorsTest.signedDoc(PLATFORM);
        LedgerEvent e = VoucherLedgerV2.seal(new LedgerEntry(VoucherStatus.ISSUED, f, doc),
                Hex.decode(PLATFORM_SK), PLATFORM_ROOT).template();
        assertThat(VoucherLedgerV2.read(e, f.ref(), List.of(PLATFORM)).docDropped()).isTrue();
    }

    @Test
    void resolvePicksHighestSeqAndTies() {
        VoucherFields f = fields(null);
        LedgerEvent iss = issued(f);
        LedgerEvent red = VoucherLedgerV2.seal(new LedgerEntry(VoucherStatus.REDEEMED, f, null),
                Hex.decode(STALL_SK), STALL_ROOT).template();
        LedgerEvent rev = VoucherLedgerV2.seal(new LedgerEntry(VoucherStatus.REVOKED, f, null),
                Hex.decode(STALL_SK), STALL_ROOT).template();
        LedgerEvent forged = VoucherLedgerV2.seal(new LedgerEntry(VoucherStatus.ISSUED, f, null),
                Hex.decode(ATTACKER_SK), PLATFORM_ROOT).template();
        var r = VoucherLedgerV2.resolve(List.of(rev, iss, red, forged), f.ref(), List.of(PLATFORM)).orElseThrow();
        assertThat(r.status()).isEqualTo(VoucherStatus.REVOKED);
        assertThat(r.accepted()).hasSize(3);
        assertThat(r.voucher()).isEqualTo(f);
        assertThat(VoucherLedgerV2.resolve(List.of(red, rev), f.ref(), List.of()).orElseThrow().status())
                .isEqualTo(VoucherStatus.REVOKED);
        assertThat(VoucherLedgerV2.resolve(List.of(forged), f.ref(), List.of(PLATFORM))).isEmpty();
    }

    @Test
    void resolveTakesDocFromIssued() throws Exception {
        VoucherFields f = fields(null);
        byte[] doc = VoucherLedgerV2VectorsTest.signedDoc(PLATFORM);
        LedgerEvent iss = VoucherLedgerV2.seal(new LedgerEntry(VoucherStatus.ISSUED, f, doc),
                Hex.decode(PLATFORM_SK), PLATFORM_ROOT).template();
        LedgerEvent red = VoucherLedgerV2.seal(new LedgerEntry(VoucherStatus.REDEEMED, f, null),
                Hex.decode(STALL_SK), STALL_ROOT).template();
        var r = VoucherLedgerV2.resolve(List.of(red, iss), f.ref(), List.of(PLATFORM)).orElseThrow();
        assertThat(r.status()).isEqualTo(VoucherStatus.REDEEMED);
        assertThat(r.doc()).isEqualTo(doc);
    }

    // ---------------------------------------------------------------- builders

    @Test
    void buildsFromUnlockedVoucher() {
        VoucherSecret s = VoucherSecret.builder().voucherId(UUID.fromString(VOUCHER_ID)).issuerId(STALL.toUpperCase())
                .unit("sat").faceValue(1000L).expiresAt(null).memo("").backingStrategy("MINIMAL")
                .issuanceRatio(2.0).faceDecimals(0).issuerSignature("00".repeat(64)).issuerPublicKey(PLATFORM)
                .issuanceWarrant("{\"form\":\"none\"}").build();
        LedgerEntry e = VoucherLedgerV2.entry(VoucherStatus.ISSUED, new SignedVoucher(s), new byte[]{1, 2});
        assertThat(e.voucher().issuerId()).isEqualTo(STALL);
        assertThat(e.voucher().issuanceRatio()).isEqualTo("2");
        assertThat(e.voucher().memo()).isNull();
        assertThat(e.voucher().issuanceWarrant()).isEqualTo("{\"form\":\"none\"}");
        assertThat(e.doc()).containsExactly(1, 2);
        assertThat(e.docBytes()).isPresent();
        assertThat(e.withoutDoc().doc()).isNull();
        assertThat(e.toString()).contains("2 bytes");
        assertThat(e).isEqualTo(new LedgerEntry(VoucherStatus.ISSUED, e.voucher(), new byte[]{1, 2}));
        assertThat(e.hashCode()).isEqualTo(new LedgerEntry(VoucherStatus.ISSUED, e.voucher(), new byte[]{1, 2}).hashCode());
    }

    @Test
    void buildsFromLockedVoucher() {
        P2PKVoucherSecret s = new P2PKVoucherSecret(Hex.decode("02" + STALL));
        s.setVoucherId(VOUCHER_ID);
        s.setIssuerId(STALL);
        s.setUnit("sat");
        s.setFaceValue(500);
        s.setIssuanceRatio(0.5);
        s.setBackingStrategy("MINIMAL");
        LedgerEntry e = VoucherLedgerV2.entry(VoucherStatus.REDEEMED, s);
        assertThat(e.seq()).isEqualTo(2);
        assertThat(e.voucher().faceValue()).isEqualTo(500L);
        assertThat(e.voucher().issuanceRatio()).isEqualTo("0.5");
        assertThat(e.doc()).isNull();
        LedgerEvent sealed = VoucherLedgerV2.seal(e, Hex.decode(STALL_SK), STALL_ROOT).template();
        assertThat(VoucherLedgerV2.open(sealed, e.voucher().ref(), List.of())).isPresent();
    }

    @Test
    void sealedWipe() {
        VoucherLedgerV2.Sealed s = VoucherLedgerV2.seal(new LedgerEntry(VoucherStatus.ISSUED, fields(null), null),
                Hex.decode(PLATFORM_SK), PLATFORM_ROOT);
        assertThat(pub(Hex.toHexString(s.eventSecretKey()))).isEqualTo(s.template().pubkey());
        s.wipe();
        assertThat(s.eventSecretKey()).isEqualTo(new byte[32]);
    }
}
