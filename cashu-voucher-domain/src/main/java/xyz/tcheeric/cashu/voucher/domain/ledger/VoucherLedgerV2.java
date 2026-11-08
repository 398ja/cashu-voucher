package xyz.tcheeric.cashu.voucher.domain.ledger;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.SignedLockedVoucher;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.UnlockedVoucherBlob;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Voucher ledger v2: encrypted, pseudonymous voucher status events
 * ({@code docs/explanation/voucher-ledger-v2.md}).
 *
 * <p>Writing: {@link #seal} turns a {@link LedgerEntry} into an unsigned NIP-01 event template
 * plus the per-voucher event key that must sign it. Reading: {@link #open} verifies one event's
 * plaintext and {@link #resolve} folds every event at a locator into one status. Neither touches
 * a relay, and neither checks the outer NIP-01 id and signature: the transport does that first.
 */
public final class VoucherLedgerV2 {

    /** Addressable application-data kind (NIP-01). */
    public static final int KIND = 30078;
    /** Envelope buckets, in UTF-8 bytes. */
    static final int[] BUCKETS = {1024, 2048, 4096, 8192, 16384, 32768, 65535};
    static final String STATEMENT_DOMAIN = "imani-voucher-ledger-v2";
    static final long DAY = 86_400L;
    static final long GRACE_DAYS = 30L;

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern HEX128 = Pattern.compile("[0-9a-f]{128}");
    private static final Pattern EVEN_HEX = Pattern.compile("(?:[0-9a-f]{2})+");
    private static final Pattern ZEROS = Pattern.compile("0*");
    private static final List<String> ENVELOPE_KEYS = List.of("body", "sig", "signer", "pad");
    private static final Set<String> BODY_KEYS = Set.of("v", "ref", "status", "seq", "voucher", "doc");
    private static final Set<String> VOUCHER_KEYS = Set.of("voucherId", "issuerId", "issuerPublicKey",
            "unit", "faceValue", "faceDecimals", "expiresAt", "memo", "backingStrategy", "issuanceRatio",
            "merchantMetadata", "issuanceWarrant");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private VoucherLedgerV2() {
    }

    // ===================================================================== writing

    /**
     * A sealed event: the template to sign, and the per-voucher key that signs it. The caller
     * should {@link #wipe()} the key after signing.
     */
    public record Sealed(LedgerEvent template, byte[] eventSecretKey) {
        public Sealed {
            eventSecretKey = eventSecretKey.clone();
        }

        @Override
        public byte[] eventSecretKey() {
            return eventSecretKey.clone();
        }

        public void wipe() {
            Arrays.fill(eventSecretKey, (byte) 0);
        }

        /** Never prints the event key. */
        @Override
        public String toString() {
            return "Sealed[pubkey=" + template.pubkey() + "]";
        }
    }

    /** {@link #seal(LedgerEntry, byte[], byte[], byte[], byte[])} with fresh random nonce and aux. */
    public static Sealed seal(LedgerEntry entry, byte[] signerSecretKey, byte[] eventKeyRoot) {
        byte[] nonce = new byte[32];
        byte[] aux = new byte[32];
        RANDOM.nextBytes(nonce);
        RANDOM.nextBytes(aux);
        return seal(entry, signerSecretKey, eventKeyRoot, nonce, aux);
    }

    /**
     * Seals an entry.
     *
     * @param entry the entry; a terminal credential is refused
     * @param signerSecretKey the inner signer: the platform key for {@code ISSUED}, the stall key
     *                        ({@code issuerId}) for {@code REDEEMED} and {@code REVOKED}
     * @param eventKeyRoot {@link VoucherLedgerRef#ledgerRoot} of the publisher's secret key
     * @param nip44Nonce 32 bytes, fresh per event (fixed only in test vectors)
     * @param auxRand 32 bytes of BIP-340 auxiliary randomness
     * @throws LedgerRefusedException for {@code TERMINAL_CREDENTIAL}, {@code UNAUTHORISED_SIGNER}
     *                                or {@code TOO_LARGE}
     */
    public static Sealed seal(LedgerEntry entry, byte[] signerSecretKey, byte[] eventKeyRoot,
                              byte[] nip44Nonce, byte[] auxRand) {
        VoucherFields voucher = entry.voucher();
        if (voucher.isTerminalCredential()) {
            throw new LedgerRefusedException(LedgerRefusal.TERMINAL_CREDENTIAL,
                    "terminal credentials are never published");
        }
        String signer = Hex.toHexString(publicKey(signerSecretKey));
        if (entry.status() != VoucherStatus.ISSUED && !signer.equals(voucher.issuerId())) {
            throw new LedgerRefusedException(LedgerRefusal.UNAUTHORISED_SIGNER,
                    entry.status() + " must be signed by the stall (issuerId)");
        }
        byte[] ref = voucher.ref();
        String d = VoucherLedgerRef.locator(ref);
        String body = writeBody(entry, ref);
        String envelope = writeEnvelope(body, d, signerSecretKey, auxRand);
        byte[] k = VoucherLedgerRef.key(ref);
        byte[] skV = VoucherLedgerRef.eventKey(eventKeyRoot, ref);
        try {
            String content = Nip44V2.encrypt(envelope, k, nip44Nonce);
            List<List<String>> tags = new ArrayList<>();
            tags.add(List.of("d", d));
            if (voucher.expiresAt() != null) {
                tags.add(List.of("expiration", Long.toString(expirationTag(voucher.expiresAt()))));
            }
            LedgerEvent template = new LedgerEvent(Hex.toHexString(publicKey(skV)), KIND, tags, content);
            return new Sealed(template, skV);
        } finally {
            Arrays.fill(k, (byte) 0);
            Arrays.fill(skV, (byte) 0);
        }
    }

    /** {@code E = ceil(expiresAt / 86400) * 86400 + 30 * 86400}. */
    public static long expirationTag(long expiresAt) {
        return Math.ceilDiv(expiresAt, DAY) * DAY + GRACE_DAYS * DAY;
    }

    /** The body JSON of an entry, under the spec's writing rule. */
    static String writeBody(LedgerEntry entry, byte[] ref) {
        VoucherFields v = entry.voucher();
        LedgerJson w = new LedgerJson().begin()
                .num("v", 2)
                .str("ref", Hex.toHexString(ref))
                .str("status", entry.status().name())
                .num("seq", entry.seq())
                .key("voucher").raw(new LedgerJson().begin()
                        .str("voucherId", v.voucherId())
                        .str("issuerId", v.issuerId())
                        .optStr("issuerPublicKey", v.issuerPublicKey())
                        .optStr("unit", v.unit())
                        .optNum("faceValue", v.faceValue())
                        .optNum("faceDecimals", v.faceDecimals())
                        .optNum("expiresAt", v.expiresAt())
                        .optStr("memo", v.memo())
                        .optStr("backingStrategy", v.backingStrategy())
                        .optStr("issuanceRatio", v.issuanceRatio())
                        .optStr("merchantMetadata", v.merchantMetadata())
                        .optStr("issuanceWarrant", v.issuanceWarrant())
                        .end().toString());
        entry.docBytes().ifPresent(doc -> w.str("doc", Hex.toHexString(doc)));
        return w.end().toString();
    }

    /** Signs {@code body} and pads the envelope to its bucket. */
    static String writeEnvelope(String body, String d, byte[] signerSecretKey, byte[] auxRand) {
        String signer = Hex.toHexString(publicKey(signerSecretKey));
        String sig;
        try {
            sig = Hex.toHexString(Schnorr.sign(statement(d, body), signerSecretKey, auxRand));
        } catch (Exception e) {
            throw new IllegalArgumentException("could not sign ledger body: " + e.getMessage(), e);
        }
        String head = "{\"body\":" + LedgerJson.quote(body) + ",\"sig\":\"" + sig
                + "\",\"signer\":\"" + signer + "\",\"pad\":\"";
        String tail = "\"}";
        int unpadded = LedgerJson.utf8Length(head) + tail.length();
        for (int bucket : BUCKETS) {
            if (unpadded <= bucket) {
                return head + "0".repeat(bucket - unpadded) + tail;
            }
        }
        throw new LedgerRefusedException(LedgerRefusal.TOO_LARGE,
                "envelope of " + unpadded + " bytes does not fit 65535");
    }

    /**
     * {@code SHA256("imani-voucher-ledger-v2" || US || d || US || hex(SHA256(body)))}.
     */
    static byte[] statement(String d, String body) {
        String bodyHash = Hex.toHexString(VoucherLedgerRef.sha256(body.getBytes(StandardCharsets.UTF_8)));
        String s = STATEMENT_DOMAIN + '\u001f' + d + '\u001f' + bodyHash;
        return VoucherLedgerRef.sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    // ===================================================================== entry builders

    /** An entry for an unlocked voucher; {@code doc} is its signed CBOR blob, or {@code null}. */
    public static LedgerEntry entry(VoucherStatus status, SignedVoucher voucher, byte[] doc) {
        return new LedgerEntry(status, VoucherFields.of(voucher), doc);
    }

    /** An entry for a P2PK-locked voucher. Locked vouchers carry no document. */
    public static LedgerEntry entry(VoucherStatus status, SignedLockedVoucher voucher) {
        return new LedgerEntry(status, VoucherFields.of(voucher), null);
    }

    /** An entry for a P2PK-locked voucher secret. Locked vouchers carry no document. */
    public static LedgerEntry entry(VoucherStatus status, P2PKVoucherSecret secret) {
        return new LedgerEntry(status, VoucherFields.of(secret), null);
    }

    /**
     * True when the voucher must be treated as a terminal credential, which is never published.
     *
     * <p>Fails closed. Absent or empty metadata is an ordinary voucher. Anything else that is not a
     * strictly parseable JSON object (invalid JSON, a duplicate key, trailing data, an array) is
     * treated as a terminal credential, because answering "not a credential" for metadata we cannot
     * read would publish a till's credential the day its metadata is malformed. {@code terminal}
     * counts when it is the boolean {@code true} or the string {@code "true"} (any case), so a
     * credential written with a stringly-typed flag is not published either.
     */
    public static boolean isTerminalCredential(String merchantMetadataJson) {
        if (merchantMetadataJson == null || merchantMetadataJson.isEmpty()) {
            return false;
        }
        JsonNode node;
        try {
            node = JSON.readTree(merchantMetadataJson);
        } catch (Exception unreadable) {
            return true;
        }
        if (node == null || !node.isObject()) {
            return true;
        }
        JsonNode terminal = node.get("terminal");
        if (terminal == null || terminal.isNull()) {
            return false;
        }
        if (terminal.isBoolean()) {
            return terminal.booleanValue();
        }
        return terminal.isTextual() && "true".equalsIgnoreCase(terminal.textValue().trim());
    }

    // ===================================================================== reading

    /**
     * One accepted event.
     *
     * @param entry the verified entry; its {@code doc} is {@code null} when absent or dropped
     * @param signer x-only hex of the inner signer
     * @param docDropped true when a {@code doc} was present but did not verify
     */
    public record Opened(LedgerEntry entry, String signer, boolean docDropped) {
        public int seq() {
            return entry.seq();
        }

        public VoucherStatus status() {
            return entry.status();
        }

        /** Status only: the entry's fields and document stay out of logs. */
        @Override
        public String toString() {
            return "Opened[status=" + entry.status() + ", docDropped=" + docDropped + "]";
        }
    }

    /** The folded status of a voucher. */
    public record Resolved(VoucherStatus status, VoucherFields voucher, byte[] doc, List<Opened> accepted) {
        public Resolved {
            doc = doc == null ? null : doc.clone();
            accepted = List.copyOf(accepted);
        }

        @Override
        public byte[] doc() {
            return doc == null ? null : doc.clone();
        }

        /** Status and counts only: the voucher's fields and document stay out of logs. */
        @Override
        public String toString() {
            return "Resolved[status=" + status + ", accepted=" + accepted.size()
                    + ", doc=" + (doc == null ? "none" : "present") + "]";
        }
    }

    /** {@link #read} that answers empty instead of throwing. */
    public static Optional<Opened> open(LedgerEvent event, byte[] ref, Collection<String> trustedIssuerKeys) {
        try {
            return Optional.of(read(event, ref, trustedIssuerKeys));
        } catch (LedgerRefusedException refused) {
            return Optional.empty();
        }
    }

    /**
     * Verifies one event's plaintext against {@code ref}, following the Reading section.
     *
     * @param event an event whose own NIP-01 id and signature the caller already verified
     * @param ref the voucher's ref
     * @param trustedIssuerKeys x-only hex keys trusted to sign {@code ISSUED} (the platform key)
     * @throws LedgerRefusedException naming the first check that failed
     */
    public static Opened read(LedgerEvent event, byte[] ref, Collection<String> trustedIssuerKeys) {
        String d = VoucherLedgerRef.locator(ref);
        requireAddress(event, d);
        byte[] k = VoucherLedgerRef.key(ref);
        String plaintext;
        try {
            plaintext = Nip44V2.decrypt(event.content(), k);
        } catch (IllegalArgumentException e) {
            throw new LedgerRefusedException(LedgerRefusal.UNDECRYPTABLE, e.getMessage(), e);
        } finally {
            Arrays.fill(k, (byte) 0);
        }

        JsonNode envelope = parseObject(plaintext, LedgerRefusal.MALFORMED_ENVELOPE);
        requireExactKeys(envelope, ENVELOPE_KEYS);
        String body = text(envelope, "body", LedgerRefusal.MALFORMED_ENVELOPE);
        String sig = text(envelope, "sig", LedgerRefusal.MALFORMED_ENVELOPE);
        String signer = text(envelope, "signer", LedgerRefusal.MALFORMED_ENVELOPE);
        String pad = text(envelope, "pad", LedgerRefusal.MALFORMED_ENVELOPE);
        if (!HEX128.matcher(sig).matches() || !HEX64.matcher(signer).matches()
                || !ZEROS.matcher(pad).matches()) {
            throw new LedgerRefusedException(LedgerRefusal.MALFORMED_ENVELOPE, "sig, signer or pad malformed");
        }
        boolean verified;
        try {
            verified = Schnorr.verify(statement(d, body), Hex.decode(signer), Hex.decode(sig));
        } catch (Exception e) {
            verified = false;
        }
        if (!verified) {
            throw new LedgerRefusedException(LedgerRefusal.BAD_SIGNATURE, "inner signature does not verify");
        }

        JsonNode b = parseObject(body, LedgerRefusal.MALFORMED_BODY);
        requireKnownKeys(b, BODY_KEYS);
        JsonNode v = b.get("v");
        if (v == null || !v.isIntegralNumber()) {
            throw new LedgerRefusedException(LedgerRefusal.MALFORMED_BODY, "v must be an integer");
        }
        if (!v.canConvertToInt() || v.intValue() != 2) {
            throw new LedgerRefusedException(LedgerRefusal.WRONG_VERSION, "v is not 2");
        }
        String bodyRef = text(b, "ref", LedgerRefusal.MALFORMED_BODY);
        String statusName = text(b, "status", LedgerRefusal.MALFORMED_BODY);
        JsonNode seqNode = b.get("seq");
        if (seqNode == null || !seqNode.isIntegralNumber()) {
            throw new LedgerRefusedException(LedgerRefusal.MALFORMED_BODY, "seq must be an integer");
        }
        JsonNode vo = b.get("voucher");
        if (vo == null || !vo.isObject()) {
            throw new LedgerRefusedException(LedgerRefusal.MALFORMED_BODY, "voucher must be an object");
        }
        requireKnownKeys(vo, VOUCHER_KEYS);
        VoucherFields fields = readVoucher(vo);
        JsonNode docNode = b.get("doc");
        if (docNode != null && !docNode.isTextual()) {
            throw new LedgerRefusedException(LedgerRefusal.MALFORMED_BODY, "doc must be a string");
        }

        if (!Hex.toHexString(ref).equals(bodyRef)
                || !Arrays.equals(ref, VoucherLedgerRef.ref(fields.issuerId(), fields.voucherId()))) {
            throw new LedgerRefusedException(LedgerRefusal.REF_MISMATCH, "event is for another voucher");
        }
        VoucherStatus status = switch (statusName) {
            case "ISSUED" -> VoucherStatus.ISSUED;
            case "REDEEMED" -> VoucherStatus.REDEEMED;
            case "REVOKED" -> VoucherStatus.REVOKED;
            default -> throw new LedgerRefusedException(LedgerRefusal.STATUS_REFUSED,
                    "status " + statusName + " is never published");
        };
        if (!seqNode.canConvertToInt() || seqNode.intValue() != LedgerEntry.seqOf(status)) {
            throw new LedgerRefusedException(LedgerRefusal.SEQ_MISMATCH, "seq does not match " + status);
        }
        boolean authorised = signer.equals(fields.issuerId())
                || (status == VoucherStatus.ISSUED && trusted(trustedIssuerKeys).contains(signer));
        if (!authorised) {
            throw new LedgerRefusedException(LedgerRefusal.UNAUTHORISED_SIGNER,
                    "signer may not publish " + status);
        }

        byte[] doc = null;
        boolean dropped = false;
        if (docNode != null) {
            doc = verifiedDoc(docNode.textValue(), fields);
            dropped = doc == null;
        }
        return new Opened(new LedgerEntry(status, fields, doc), signer, dropped);
    }

    /**
     * Folds every event fetched at a voucher's locator. Events that do not verify are ignored.
     * The status is the accepted event with the highest {@code seq}, ties going to
     * {@code REVOKED} over {@code REDEEMED} over {@code ISSUED}; the voucher fields and document
     * come from an accepted {@code ISSUED} event when there is one.
     *
     * <p>Anyone who knows {@code ref} can publish any number of events at its {@code d} under
     * throwaway outer keys. Each is refused here by the inner signature, but a reader that fetched
     * with a small relay {@code limit} could see only the junk and miss the real events, so readers
     * must not rely on one.
     */
    public static Optional<Resolved> resolve(List<LedgerEvent> events, byte[] ref,
                                             Collection<String> trustedIssuerKeys) {
        List<Opened> accepted = new ArrayList<>();
        for (LedgerEvent event : events) {
            open(event, ref, trustedIssuerKeys).ifPresent(accepted::add);
        }
        if (accepted.isEmpty()) {
            return Optional.empty();
        }
        Opened winner = accepted.get(0);
        for (Opened o : accepted) {
            if (o.seq() > winner.seq()
                    || (o.seq() == winner.seq() && rank(o.status()) > rank(winner.status()))) {
                winner = o;
            }
        }
        Opened source = accepted.stream().filter(o -> o.status() == VoucherStatus.ISSUED)
                .filter(o -> o.entry().doc() != null).findFirst()
                .or(() -> accepted.stream().filter(o -> o.status() == VoucherStatus.ISSUED).findFirst())
                .orElse(winner);
        byte[] doc = source.entry().doc();
        if (doc == null) {
            doc = accepted.stream().map(o -> o.entry().doc()).filter(x -> x != null).findFirst().orElse(null);
        }
        return Optional.of(new Resolved(winner.status(), source.entry().voucher(), doc, accepted));
    }

    private static int rank(VoucherStatus s) {
        return switch (s) {
            case REVOKED -> 3;
            case REDEEMED -> 2;
            default -> 1;
        };
    }

    private static Set<String> trusted(Collection<String> keys) {
        return keys == null ? Set.of() : keys.stream().filter(x -> x != null)
                .map(x -> x.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private static void requireAddress(LedgerEvent event, String d) {
        if (event == null || event.kind() != KIND) {
            throw new LedgerRefusedException(LedgerRefusal.NOT_THIS_ADDRESS, "not kind 30078");
        }
        List<List<String>> dTags = event.tags().stream()
                .filter(t -> !t.isEmpty() && "d".equals(t.get(0))).toList();
        if (dTags.size() != 1 || dTags.get(0).size() < 2 || !d.equals(dTags.get(0).get(1))) {
            throw new LedgerRefusedException(LedgerRefusal.NOT_THIS_ADDRESS, "not exactly one d tag equal to d");
        }
        if (event.content() == null) {
            throw new LedgerRefusedException(LedgerRefusal.UNDECRYPTABLE, "no content");
        }
    }

    private static JsonNode parseObject(String json, LedgerRefusal malformed) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (JsonParseException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Duplicate field")) {
                throw new LedgerRefusedException(LedgerRefusal.DUPLICATE_KEY, "duplicate key", e);
            }
            throw new LedgerRefusedException(malformed, "not JSON", e);
        } catch (Exception e) {
            throw new LedgerRefusedException(malformed, "not JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new LedgerRefusedException(malformed, "not a JSON object");
        }
        return node;
    }

    private static void requireExactKeys(JsonNode node, List<String> keys) {
        requireKnownKeys(node, Set.copyOf(keys));
        for (String key : keys) {
            if (!node.has(key)) {
                throw new LedgerRefusedException(LedgerRefusal.MALFORMED_ENVELOPE, "missing " + key);
            }
        }
    }

    private static void requireKnownKeys(JsonNode node, Set<String> allowed) {
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!allowed.contains(name)) {
                throw new LedgerRefusedException(LedgerRefusal.UNKNOWN_KEY, "unknown key " + name);
            }
        }
    }

    private static String text(JsonNode node, String key, LedgerRefusal malformed) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual()) {
            throw new LedgerRefusedException(malformed, key + " must be a string");
        }
        return value.textValue();
    }

    private static String optText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw new LedgerRefusedException(LedgerRefusal.MALFORMED_BODY, "voucher." + key + " must be a string");
        }
        return value.textValue();
    }

    private static Long optLong(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new LedgerRefusedException(LedgerRefusal.MALFORMED_BODY, "voucher." + key + " must be an int64");
        }
        return value.longValue();
    }

    private static VoucherFields readVoucher(JsonNode vo) {
        String voucherId = text(vo, "voucherId", LedgerRefusal.MALFORMED_BODY);
        String issuerId = text(vo, "issuerId", LedgerRefusal.MALFORMED_BODY);
        VoucherFields fields;
        try {
            fields = new VoucherFields(voucherId, issuerId, optText(vo, "issuerPublicKey"),
                    optText(vo, "unit"), optLong(vo, "faceValue"), optLong(vo, "faceDecimals"),
                    optLong(vo, "expiresAt"), optText(vo, "memo"), optText(vo, "backingStrategy"),
                    optText(vo, "issuanceRatio"), optText(vo, "merchantMetadata"),
                    optText(vo, "issuanceWarrant"));
        } catch (IllegalArgumentException e) {
            throw new LedgerRefusedException(LedgerRefusal.MALFORMED_BODY, e.getMessage(), e);
        }
        if (!fields.voucherId().equals(voucherId) || !fields.issuerId().equals(issuerId)) {
            throw new LedgerRefusedException(LedgerRefusal.MALFORMED_BODY,
                    "voucherId and issuerId must be written in their normalised (lowercase) form");
        }
        return fields;
    }

    /**
     * The document bytes when {@code hex} decodes to a signed voucher blob for this voucher whose
     * issuer signature verifies; {@code null} otherwise.
     */
    private static byte[] verifiedDoc(String hex, VoucherFields fields) {
        if (!EVEN_HEX.matcher(hex).matches()) {
            return null;
        }
        byte[] bytes = Hex.decode(hex);
        try {
            VoucherSecret carrier = new VoucherSecret();
            carrier.setData(bytes);
            VoucherSecret voucher = UnlockedVoucherBlob.read(carrier);
            if (voucher == null || voucher.getVoucherId() == null
                    || !fields.voucherId().equals(voucher.getVoucherId().toString())
                    || voucher.getIssuerId() == null
                    || !fields.issuerId().equals(voucher.getIssuerId().toLowerCase(Locale.ROOT))
                    || !VoucherSignatureService.verify(voucher)) {
                return null;
            }
            return bytes;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static byte[] publicKey(byte[] secretKey) {
        try {
            return Schnorr.genPubKey(secretKey);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid secret key", e);
        }
    }
}
