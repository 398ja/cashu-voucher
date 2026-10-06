package xyz.tcheeric.cashu.voucher.domain;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherTags;
import xyz.tcheeric.cashu.common.util.SecretUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What, outside the issuing service, authorised an issuance.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A voucher carries exactly one signature and it belongs to the issuing service. The stall
 * named in {@code issuer} signs nothing, so a coupon asserts <em>"the service says stall X
 * issued this"</em> and carries no evidence that stall X did. Whoever holds the service key can
 * mint any face value in any stall's name and the result is byte-for-byte indistinguishable
 * from a genuine coupon.
 *
 * <p>The obvious remedy — have the stall countersign — authenticates the <em>debtor</em> and
 * leaves the <em>amount</em> to whoever holds the signing key. That is the wrong half. The harm
 * is not that a coupon names the wrong stall, it is that an unbacked claim exists against a
 * stall that was never paid. So a warrant binds a digest that <b>covers the face value</b> to
 * evidence from outside the issuing service.
 *
 * <h2>A warrant attests a SALE, not a voucher</h2>
 *
 * <p>This is the design decision worth understanding before reading the digest.
 *
 * <p>One Sell action can mint several coupons: a requested quantity, and each of those
 * auto-splitting again when a single coupon would be too large to receive. The split is a
 * <em>server-side</em> decision based on token size, so the device cannot predict how many
 * coupons there will be, and none of their {@code voucher_id}s exist at the moment the merchant
 * signs. A per-voucher digest therefore cannot be produced by the device at all without a
 * second round trip over a count it does not know.
 *
 * <p>So the digest covers the <b>sale total</b>, and every coupon minted from that sale carries
 * the same warrant. Verification is a <b>ceiling test</b>: this coupon's face value must not
 * exceed the warranted total.
 *
 * <p><b>What that costs, stated plainly.</b> A verifier can check that a sale of this size was
 * authorised by the stall. It cannot check, from the warrant alone, that <em>this particular
 * coupon</em> was part of that sale rather than an extra one minted alongside it. Relating a
 * coupon to its sale relies on the server's split arithmetic, which is inside the trust
 * boundary this scheme exists to reach outside of. The amount is still bound, and the amount is
 * what the attack turns on, so this is a real but bounded weakening rather than a hole.
 *
 * <h2>The ceiling is also why splits keep working</h2>
 *
 * <p>A coupon that is later split keeps the signed bytes it was issued with, so it keeps its
 * warrant, and its face value only ever goes <em>down</em>. A ceiling test stays true across
 * every split without anyone re-signing anything — which matters because the stall is not
 * present at split time and could not re-sign if it were asked to.
 *
 * @see VoucherTags#ISSUANCE_WARRANT
 */
public final class IssuanceWarrant {

    private static final Logger logger = LoggerFactory.getLogger(IssuanceWarrant.class);

    /**
     * Separates fields in the digest preimage.
     *
     * <p>US (unit separator, 0x1f), which cannot appear in any field value here: ids and keys
     * are hex, amounts are decimal, units are alphabetic.
     *
     * <p><b>A separator is load-bearing, not tidiness.</b> Concatenating
     * {@code faceValue=25, faceDecimals=00} and {@code faceValue=2500, faceDecimals=0} produces
     * identical bytes, so one warrant would verify against a coupon worth a hundred times more.
     * That is a forgery, not a theoretical concern, and it is why this constant exists rather
     * than a bare {@code +}.
     */
    private static final char FIELD_SEPARATOR = '\u001f';

    /** BIP-340 signatures are 64 bytes, hex-encoded to 128 characters. */
    private static final int SIGNATURE_HEX_LENGTH = 128;

    /** x-only public keys are 32 bytes, hex-encoded to 64 characters. */
    private static final int PUBKEY_HEX_LENGTH = 64;

    private IssuanceWarrant() {
    }

    /**
     * The shapes a warrant can take.
     *
     * <p>One field, a closed set of forms, so every verifier has one code path and an unknown
     * shape is <em>refused</em> rather than guessed at.
     */
    public enum Form {

        /**
         * The stall itself signed the sale digest. Verifiable offline against {@code issuer}.
         *
         * <p>This is prevention: a compromised issuing service cannot produce one, because it
         * does not hold the stall's key.
         */
        MERCHANT("merchant"),

        /**
         * A payment processor moved money into the stall's own account.
         *
         * <p><b>A claim, not a proof, at verification time.</b> The issuing service is the thing
         * that reads the processor, so a compromised service can assert a charge that does not
         * exist. What changes is that the assertion is falsifiable by a party outside the trust
         * boundary: the stall, reading its own dashboard, resolves it to "no such charge". The
         * forgery survives the instant and does not survive reconciliation.
         *
         * <p>It is also economically self-defeating. Forging a large coupon this way requires
         * routing that much real money into the victim's own account.
         */
        PROCESSOR("processor"),

        /**
         * A terminal credential the stall issued, and can burn, signed the digest.
         *
         * <p>Prevention, and revocable. Verification is two steps: the signature against the
         * credential's lock key, then that the credential was minted by {@code issuer}. Doing
         * only the first accepts a credential the attacker minted for themselves.
         */
        DELEGATED("delegated"),

        /**
         * A terminal's own key signed the digest, and the warrant carries the terminal
         * credential that lets that key sell for the stall.
         *
         * <p>Prevention, and revocable by spending the credential. Verified offline by
         * {@link IssuanceWarrant#verifyTerminal}. Not {@link #DELEGATED}, which stays the
         * card-purchase form.
         */
        TERMINAL("terminal"),

        /**
         * Nothing outside the issuing service authorised this, and the issuer has SIGNED that.
         *
         * <p>Not the same as an absent tag. An absent tag means the voucher predates warrants
         * and the issuer said nothing; {@code NONE} is a positive statement. Conflating them
         * lets a compromised service strip a warrant and have it read as legacy.
         */
        NONE("none");

        private final String wire;

        Form(String wire) {
            this.wire = wire;
        }

        /** The lowercase token that appears on the wire. */
        public String wire() {
            return wire;
        }

        /**
         * Parses a wire token, or throws.
         *
         * <p>Throws rather than returning null or a default: an unrecognised form is a voucher
         * produced by something this build does not understand, and treating it as
         * {@code NONE} would silently downgrade it.
         */
        public static Form fromWire(String value) {
            if (value == null) {
                throw new IllegalArgumentException("issuance warrant form is missing");
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Form form : values()) {
                if (form.wire.equals(normalized)) {
                    return form;
                }
            }
            throw new IllegalArgumentException("unknown issuance warrant form: " + value);
        }
    }

    /**
     * The digest a warrant signs: one sale, by one stall, for one total.
     *
     * <p>Every component is here for a reason:
     *
     * <ul>
     *   <li>{@code issuerId} — which stall is on the hook. Without it a warrant is transferable
     *       between stalls.</li>
     *   <li>{@code saleTotalMinor} — <b>the field the attack turns on.</b> A warrant that does
     *       not cover the amount authenticates the debtor and leaves the sum to the attacker,
     *       which is the whole reason a plain countersignature was rejected.</li>
     *   <li>{@code faceDecimals} and {@code unit} — 2500 is not a number, it is EUR 25.00 or
     *       XAF 2500. Omitting these makes amounts comparable across currencies that are not
     *       comparable.</li>
     *   <li>{@code saleNonce} — a device-chosen nonce making two identical sales distinct.
     *       Without it a merchant selling EUR 25.00 twice produces one digest twice, and a
     *       warrant from the first sale verifies against coupons from the second.</li>
     * </ul>
     *
     * <p>Note what is <em>absent</em>: {@code voucher_id}. See the class comment.
     *
     * @param issuerId       the stall's Nostr pubkey, hex
     * @param saleTotalMinor the total being sold, in minor units, which every coupon from this
     *                       sale is bounded by
     * @param faceDecimals   decimal places for the unit
     * @param unit           the currency
     * @param saleNonce      a per-sale nonce chosen by the signing device
     * @return the 32-byte digest to sign
     */
    public static byte[] saleDigest(
            @NonNull String issuerId,
            long saleTotalMinor,
            int faceDecimals,
            @NonNull String unit,
            @NonNull String saleNonce
    ) {
        if (saleTotalMinor < 0) {
            throw new IllegalArgumentException("sale total must not be negative: " + saleTotalMinor);
        }
        String preimage = String.join(
                String.valueOf(FIELD_SEPARATOR),
                issuerId,
                Long.toString(saleTotalMinor),
                Integer.toString(faceDecimals),
                unit,
                saleNonce
        );
        return sha256(preimage.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Verifies a {@code merchant} warrant offline, against the stall's own key.
     *
     * <p>Two checks, and the second is the one people forget. The signature must verify, AND
     * the coupon's face value must not exceed the warranted sale total. A signature that
     * verifies over a EUR 25.00 sale says nothing about a EUR 5,000.00 coupon that happens to
     * carry it.
     *
     * @param issuerId          the stall's pubkey, hex, from the voucher's {@code issuer} tag
     * @param saleTotalMinor    the total the warrant claims to authorise
     * @param faceDecimals      decimal places
     * @param unit              the currency
     * @param saleNonce         the per-sale nonce carried in the warrant
     * @param signatureHex      the stall's BIP-340 signature over the digest
     * @param couponFaceMinor   THIS coupon's face value, which must fit inside the sale
     * @return true only if the signature verifies and the coupon fits under the ceiling
     */
    public static boolean verifyMerchant(
            @NonNull String issuerId,
            long saleTotalMinor,
            int faceDecimals,
            @NonNull String unit,
            @NonNull String saleNonce,
            @NonNull String signatureHex,
            long couponFaceMinor
    ) {
        if (!coversFaceValue(saleTotalMinor, couponFaceMinor)) {
            logger.warn("issuance warrant refused: coupon face {} exceeds warranted sale total {}",
                    couponFaceMinor, saleTotalMinor);
            return false;
        }
        return signatureValid(
                issuerId,
                saleDigest(issuerId, saleTotalMinor, faceDecimals, unit, saleNonce),
                signatureHex);
    }

    /**
     * Why a {@code terminal} warrant was refused.
     *
     * <p>A reason rather than a boolean, so the portal can say which check failed instead of
     * having only a WARN line to go on.
     */
    public enum TerminalRefusal {
        /** {@code issuerId}, {@code unit}, {@code saleNonce} or an amount is out of shape. */
        MALFORMED_SALE,
        /** The coupon's face value exceeds the warranted sale total. */
        COUPON_ABOVE_SALE,
        /** The credential was not signed by a key in {@code trustedServiceKeys}. */
        UNTRUSTED_SERVICE_KEY,
        /** The credential's service signature does not verify. */
        BAD_CREDENTIAL_SIGNATURE,
        /** The credential's {@code expires_at} is missing or not a canonical integer. */
        MALFORMED_CREDENTIAL,
        /** The credential's {@code issuer_id} is not the stall. */
        WRONG_STALL,
        /** The credential had expired at the {@link ExpiryCheck#at(long)} instant. */
        CREDENTIAL_EXPIRED,
        /** The metadata is not a terminal of this stall with role {@code issue-and-redeem}. */
        NOT_A_SELLING_TERMINAL,
        /** {@code lock_key} is the stall's own key, which would turn merchant warrants into terminal ones. */
        LOCK_IS_STALL,
        /** The credential's P2PK lock is not its {@code lock_key}. */
        LOCK_MISMATCH,
        /** The sale signature is not {@code lock_key}'s over {@link #saleDigest}. */
        BAD_SALE_SIGNATURE
    }

    /**
     * Everything {@link #verifyTerminal(TerminalSale)} needs, named, so that the adjacent
     * amounts and strings cannot be swapped silently.
     */
    @Value
    @Builder
    public static class TerminalSale {
        /** The stall's pubkey, lowercase hex, from the voucher's {@code issuer} tag. */
        @NonNull String issuerId;
        /** The terminal credential's signed secret (never the proof's {@code C}). */
        @NonNull P2PKVoucherSecret credential;
        /**
         * The gateway-customer service key(s) that mint terminal credentials. Mandatory: an
         * empty collection refuses everything. Not every voucher-signing key belongs here.
         */
        @NonNull Collection<String> trustedServiceKeys;
        /** The total the warrant claims to authorise, 0..2^53-1. */
        long saleTotalMinor;
        /** Decimal places, 0..18. */
        int faceDecimals;
        /** The currency, without control characters. */
        @NonNull String unit;
        /** The per-sale nonce carried in the warrant, without control characters. */
        @NonNull String saleNonce;
        /** {@code K}'s BIP-340 signature over the sale digest. */
        @NonNull String signatureHex;
        /** THIS coupon's face value, which must fit inside the sale. */
        long couponFaceMinor;
        /**
         * How to treat the credential's expiry. Required, so leaving it out fails at
         * {@code build()} instead of silently skipping the check (review N2).
         */
        @NonNull ExpiryCheck expiryCheck;
    }

    /**
     * The caller's explicit choice about credential expiry.
     *
     * <p>The portal, warranting a NEW sale, uses {@link #at(long)} with now. An offline
     * verifier of an already-issued coupon uses {@link #skipOffline()}, because a coupon sold
     * before the till expired rightly stays valid. There is no default.
     *
     * <p><b>Boundary.</b> {@code expires_at} is the last valid second: refused only when
     * {@code epochSeconds > expires_at}, the same as cashu-mint's
     * {@code VoucherSpendingCondition} ({@code VoucherMetadata.isExpired}), so the portal and
     * the mint agree to the second.
     */
    public static final class ExpiryCheck {
        private static final ExpiryCheck SKIP = new ExpiryCheck(null);

        private final Long epochSeconds;

        private ExpiryCheck(Long epochSeconds) {
            this.epochSeconds = epochSeconds;
        }

        /** Refuse the credential if it had expired at {@code epochSeconds} (seconds, not ms). */
        public static ExpiryCheck at(long epochSeconds) {
            return new ExpiryCheck(epochSeconds);
        }

        /** Skip the expiry instant: for verifying an already-issued coupon offline. */
        public static ExpiryCheck skipOffline() {
            return SKIP;
        }

        boolean expiredAt(long expiresAt) {
            return epochSeconds != null && epochSeconds > expiresAt;
        }

        @Override
        public String toString() {
            return epochSeconds == null ? "ExpiryCheck.skipOffline()" : "ExpiryCheck.at(" + epochSeconds + ")";
        }
    }

    /** The outcome of {@link #verifyTerminal(TerminalSale)}: valid, or one refusal reason. */
    @Value
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class TerminalVerdict {
        private static final TerminalVerdict VALID = new TerminalVerdict(null);

        /** Why it was refused, or {@code null} when valid. */
        TerminalRefusal refusal;

        public boolean isValid() {
            return refusal == null;
        }

        static TerminalVerdict refused(TerminalRefusal reason) {
            logger.warn("terminal warrant refused: {}", reason);
            return new TerminalVerdict(reason);
        }
    }

    /**
     * Parses a terminal credential as received on the wire (a NUT-10 secret, JSON) and
     * checks it is a {@code P2PK_VOUCHER}.
     *
     * @throws IllegalArgumentException if it does not parse, is another kind of secret, or is
     *         not byte-for-byte the canonical serialisation (no padding, extra elements or
     *         duplicate keys)
     */
    public static P2PKVoucherSecret parseCredential(@NonNull String wireSecret) {
        Object secret;
        try {
            secret = SecretUtil.toSecret(wireSecret);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("terminal credential does not parse", e);
        }
        if (!(secret instanceof P2PKVoucherSecret credential)
                || credential.getKind() != WellKnownSecret.Kind.P2PK_VOUCHER) {
            throw new IllegalArgumentException("terminal credential is not a P2PK_VOUCHER");
        }
        // One credential, one wire form (review N4). The parser tolerates padding, extra
        // array elements and duplicate keys (last wins); refusing anything that does not
        // re-serialise to the exact input closes all of them at once. toString() echoes the
        // remembered wire string, so a setter is touched first to make it serialise afresh.
        credential.setNonce(credential.getNonce());
        if (!credential.toString().equals(wireSecret)) {
            throw new IllegalArgumentException("terminal credential is not in canonical wire form");
        }
        return credential;
    }

    /**
     * Verifies a {@code terminal} warrant: a till's key {@code K} signed the sale, and the
     * warrant carries the credential the stall's issuing service minted to {@code K}.
     *
     * <p>Every check below is needed, and each closes a different forgery:
     *
     * <ol>
     *   <li><b>The inputs are well-formed.</b> {@code issuerId} is lowercase HEX64, and
     *       {@code unit} and {@code saleNonce} carry no control characters (in particular no
     *       US, the digest's separator, so a nonce cannot smuggle in the extra field of the
     *       {@code delegated} digest). {@code saleTotalMinor} is 0..2^53-1, so the Java and
     *       TypeScript digests agree, and {@code faceDecimals} is 0..18.</li>
     *   <li><b>The coupon fits under the sale total</b>, the same ceiling as
     *       {@link #verifyMerchant}.</li>
     *   <li><b>The credential is signed, by a service key the caller trusts.</b> Without the
     *       allow-list, anyone can mint themselves a credential with their own key.</li>
     *   <li><b>{@code issuer_id == issuerId}.</b> A till sells only for the stall that issued
     *       it.</li>
     *   <li><b>Not expired per {@link TerminalSale#getExpiryCheck()}</b>.</li>
     *   <li><b>The metadata is a terminal with {@code role == issue-and-redeem}</b>,
     *       {@code terminal} the boolean {@code true}, {@code stall_pubkey == issuerId}, and
     *       {@code lock_key != issuerId}.</li>
     *   <li><b>The mint lock is {@code lock_key}.</b></li>
     *   <li><b>{@code signatureHex} is a BIP-340 signature by {@code lock_key} over
     *       {@link #saleDigest}.</b></li>
     * </ol>
     *
     * <p><b>Not checked: liveness.</b> A revoked credential still verifies, because revocation
     * is a NUT-07 spend only the gateway sees; the portal checks it at issuance.
     *
     * <p><b>Expiry is an explicit choice.</b> The credential must carry a canonical integer
     * {@code expires_at}. The portal passes {@link ExpiryCheck#at(long)} with now, so an
     * expired credential cannot warrant a new sale. An offline verifier of an already-issued
     * coupon passes {@link ExpiryCheck#skipOffline()}, because a coupon sold before expiry
     * rightly stays valid. The boundary matches the mint: valid through {@code expires_at}.
     *
     * <p><b>Not checked: the denomination.</b> The coupon's own unit and decimals are not
     * inputs, so the caller must check they equal {@code unit} and {@code faceDecimals}.
     */
    public static TerminalVerdict verifyTerminal(@NonNull TerminalSale sale) {
        String issuerId = sale.getIssuerId();
        P2PKVoucherSecret credential = sale.getCredential();
        Collection<String> trustedServiceKeys = sale.getTrustedServiceKeys();
        long saleTotalMinor = sale.getSaleTotalMinor();
        long couponFaceMinor = sale.getCouponFaceMinor();

        if (!LOWER_HEX64.matcher(issuerId).matches()
                || hasControlCharacter(sale.getUnit()) || hasControlCharacter(sale.getSaleNonce())
                || saleTotalMinor < 0 || saleTotalMinor > MAX_SAFE_INTEGER || couponFaceMinor < 0
                || sale.getFaceDecimals() < 0 || sale.getFaceDecimals() > MAX_FACE_DECIMALS) {
            return TerminalVerdict.refused(TerminalRefusal.MALFORMED_SALE);
        }

        if (!coversFaceValue(saleTotalMinor, couponFaceMinor)) {
            logger.warn("terminal warrant: coupon face {} exceeds warranted sale total {}",
                    couponFaceMinor, saleTotalMinor);
            return TerminalVerdict.refused(TerminalRefusal.COUPON_ABOVE_SALE);
        }

        String servicePubkey = credential.getIssuerPublicKey();
        if (servicePubkey == null || !containsIgnoreCase(trustedServiceKeys, servicePubkey)) {
            return TerminalVerdict.refused(TerminalRefusal.UNTRUSTED_SERVICE_KEY);
        }
        // Strict: terminal credentials were all minted after the canonical form existed, so
        // none was signed in the legacy truncated form. Offering the legacy window here would
        // let a holder rewrite expires_at "1000" to "1000.5" and keep a valid signature
        // (review N1).
        if (!VoucherSignatureService.verifyStrict(credential)) {
            return TerminalVerdict.refused(TerminalRefusal.BAD_CREDENTIAL_SIGNATURE);
        }
        // Read the raw tag, not getExpiresAt(): the getter returns null for a value it cannot
        // parse, and null means "never expires". An expiry we cannot read is malformed.
        WellKnownSecret.Tag expiryTag = credential.getTag(VoucherTags.EXPIRES_AT);
        Long expiresAt = canonicalEpochSeconds(expiryTag);
        // Absent is refused too: credentials last 365 days (spec section 2.7), and one with no
        // expiry would sell forever (review N2).
        if (expiresAt == null) {
            return TerminalVerdict.refused(TerminalRefusal.MALFORMED_CREDENTIAL);
        }

        if (!issuerId.equalsIgnoreCase(credential.getIssuerId())) {
            return TerminalVerdict.refused(TerminalRefusal.WRONG_STALL);
        }

        if (sale.getExpiryCheck().expiredAt(expiresAt)) {
            return TerminalVerdict.refused(TerminalRefusal.CREDENTIAL_EXPIRED);
        }

        String lockKey = terminalLockKey(credential.getMerchantMetadata(), issuerId);
        if (lockKey == null) {
            return TerminalVerdict.refused(TerminalRefusal.NOT_A_SELLING_TERMINAL);
        }
        if (lockKey.equalsIgnoreCase(issuerId)) {
            return TerminalVerdict.refused(TerminalRefusal.LOCK_IS_STALL);
        }

        byte[] lock = credential.getData();
        if (lock == null || lock.length != 33
                || !Hex.toHexString(lock, 1, 32).equalsIgnoreCase(lockKey)) {
            return TerminalVerdict.refused(TerminalRefusal.LOCK_MISMATCH);
        }

        if (!signatureValid(
                lockKey.toLowerCase(Locale.ROOT),
                saleDigest(issuerId, saleTotalMinor, sale.getFaceDecimals(), sale.getUnit(),
                        sale.getSaleNonce()),
                sale.getSignatureHex())) {
            return TerminalVerdict.refused(TerminalRefusal.BAD_SALE_SIGNATURE);
        }
        return TerminalVerdict.VALID;
    }

    private static final Pattern CANONICAL_INTEGER = Pattern.compile("^(0|[1-9][0-9]{0,18})$");

    /** The tag's single value as epoch seconds, or {@code null} if absent or not canonical. */
    private static Long canonicalEpochSeconds(WellKnownSecret.Tag tag) {
        if (tag == null || tag.getValues() == null || tag.getValues().size() != 1) {
            return null;
        }
        String raw = String.valueOf(tag.getValues().get(0));
        if (!CANONICAL_INTEGER.matcher(raw).matches()) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /** 2^53-1: the largest integer the wallet's {@code String(number)} renders exactly. */
    private static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

    /** The most decimal places the portal accepts. */
    private static final int MAX_FACE_DECIMALS = 18;

    private static final Pattern LOWER_HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    /**
     * C0 controls (US among them), DEL, C1 controls (U+0080..U+009F), and malformed UTF-16.
     *
     * <p>A lone surrogate is refused because Java's UTF-8 encoder writes it as {@code ?}, so
     * {@code "n1\uD800"} and {@code "n1?"} would share one digest and one signature, and
     * TypeScript's {@code TextEncoder} writes U+FFFD instead (review N3). Paired surrogates,
     * which encode cleanly, are fine.
     */
    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || (c >= 0x7f && c <= 0x9f)) {
                return true;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** The role a terminal needs to sell. */
    private static final String ISSUE_AND_REDEEM = "issue-and-redeem";

    /** Strict like the wallet's {@code JSON.parse}: no trailing garbage, no duplicate keys. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /**
     * The {@code lock_key} of a selling terminal's metadata, or {@code null} with a log line
     * saying why it is not one.
     */
    private static String terminalLockKey(String metadataJson, String issuerId) {
        if (metadataJson == null) {
            logger.warn("terminal warrant refused: credential carries no metadata");
            return null;
        }
        JsonNode metadata;
        try {
            metadata = JSON.readTree(metadataJson);
        } catch (Exception e) {
            logger.warn("terminal warrant refused: credential metadata is not JSON");
            return null;
        }
        if (metadata == null || !metadata.isObject()) {
            logger.warn("terminal warrant refused: credential metadata is not an object");
            return null;
        }
        // The boolean true, not truthy: a coupon carrying "terminal": "yes" is not authority.
        JsonNode terminal = metadata.get("terminal");
        if (terminal == null || !terminal.isBoolean() || !terminal.booleanValue()) {
            logger.warn("terminal warrant refused: credential metadata is not a terminal");
            return null;
        }
        JsonNode stall = metadata.get("stall_pubkey");
        if (stall == null || !stall.isTextual() || !issuerId.equalsIgnoreCase(stall.textValue())) {
            logger.warn("terminal warrant refused: credential names a different stall");
            return null;
        }
        JsonNode role = metadata.get("role");
        if (role == null || !role.isTextual() || !ISSUE_AND_REDEEM.equals(role.textValue())) {
            logger.warn("terminal warrant refused: terminal role may not sell");
            return null;
        }
        JsonNode lockKey = metadata.get("lock_key");
        if (lockKey == null || !lockKey.isTextual()
                || !HEX64.matcher(lockKey.textValue()).matches()) {
            logger.warn("terminal warrant refused: credential lock_key is malformed");
            return null;
        }
        return lockKey.textValue();
    }

    private static final Pattern HEX64 = Pattern.compile("^[0-9a-fA-F]{64}$");

    private static boolean containsIgnoreCase(Collection<String> values, String wanted) {
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a warranted sale total covers a coupon's face value.
     *
     * <p><b>This is a ceiling, so the test is {@code <=} and the direction matters.</b> A coupon
     * worth less than the sale is the ordinary case: sales split into several coupons, and
     * coupons shrink further when partly spent. A coupon worth MORE than the sale it claims to
     * come from is the forgery this whole scheme exists to refuse.
     *
     * <p>Negative values are refused rather than clamped. A negative face value is not a small
     * coupon, it is a coupon whose encoding we do not understand.
     */
    public static boolean coversFaceValue(long saleTotalMinor, long couponFaceMinor) {
        if (saleTotalMinor < 0 || couponFaceMinor < 0) {
            return false;
        }
        return couponFaceMinor <= saleTotalMinor;
    }

    /**
     * BIP-340 Schnorr verification over a digest, matching {@link VoucherSignatureService}.
     *
     * <p>Returns false rather than throwing on malformed input. Every caller is deciding
     * whether to trust a coupon, and an exception there would be handled as a refusal anyway
     * or, worse, propagate and take down a redemption.
     */
    private static boolean signatureValid(String pubkeyHex, byte[] digest, String signatureHex) {
        if (pubkeyHex == null || pubkeyHex.length() != PUBKEY_HEX_LENGTH) {
            logger.warn("issuance warrant refused: issuer pubkey is not {} hex chars",
                    PUBKEY_HEX_LENGTH);
            return false;
        }
        if (signatureHex == null || signatureHex.length() != SIGNATURE_HEX_LENGTH) {
            logger.warn("issuance warrant refused: signature is not {} hex chars",
                    SIGNATURE_HEX_LENGTH);
            return false;
        }
        try {
            return Schnorr.verify(digest, Hex.decode(pubkeyHex), Hex.decode(signatureHex));
        } catch (Exception e) {
            logger.warn("issuance warrant signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and unavailable", e);
        }
    }
}
