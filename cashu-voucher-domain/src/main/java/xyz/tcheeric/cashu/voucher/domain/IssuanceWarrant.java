package xyz.tcheeric.cashu.voucher.domain;

import lombok.NonNull;
import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

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
     * The four shapes a warrant can take.
     *
     * <p>One field, four forms, so every verifier has one code path and an unknown shape is
     * <em>refused</em> rather than guessed at.
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
