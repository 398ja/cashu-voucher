package xyz.tcheeric.cashu.voucher.domain;

import lombok.NonNull;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherTags;

import java.nio.charset.StandardCharsets;

/**
 * Renders the exact bytes an issuer signature commits to.
 *
 * <p>This is the single definition of the voucher signing preimage. It is deliberately its own
 * class rather than a detail of {@link VoucherSignatureService}: the bytes are a wire contract
 * shared with every verifier, including the offline TypeScript wallet, so anything that needs
 * to reproduce them must be able to call the one implementation instead of copying it.
 *
 * <p>The form is {@code [kind, "data_hex", "nonce", [[tag, value...], ...]]}, matching
 * {@code WellKnownSecretSerializer}, with {@code issuer_sig} and {@code issuer_pubkey} omitted
 * because they are only added after signing.
 *
 * <h2>The kind is read from the secret</h2>
 *
 * <p>It used to be the literal {@code "VOUCHER"}. Two kinds now carry voucher metadata —
 * {@code VOUCHER} and {@code P2PK_VOUCHER}, the latter being a voucher that is also
 * P2PK-locked — and the signature has to commit to which one it is. A fixed string would let a
 * signature made for an unlocked voucher verify against a locked one carrying the same
 * metadata, and vice versa, so the kind would not be covered by what the issuer signed.
 *
 * <p>This does not change the bytes for a {@code VOUCHER} secret: the value read is the same
 * string that was previously hardcoded, so every signature made under the old code still
 * verifies.
 *
 * <h2>Why the tag key decides what is numeric</h2>
 *
 * <p>NUT-10 carries every tag value as a string, so the runtime type of a value says nothing
 * about how it was written when a signature was made. An earlier version keyed off
 * {@code value instanceof Number}; when cashu-lib began modelling tag values as {@code String},
 * every numeric tag silently changed from {@code 1000} to {@code "1000"} and every voucher
 * signature ever issued would have stopped verifying. The voucher tag schema is fixed and
 * known, so the key is the durable record of which values are numbers.
 *
 * @see VoucherSignatureService
 */
public final class VoucherCanonicalBytes {

    private VoucherCanonicalBytes() {
    }

    /**
     * Renders the canonical signing bytes for a voucher secret.
     *
     * @param secret the voucher secret to render; either a {@link VoucherSecret} or a
     *               {@code P2PKVoucherSecret}
     * @return the bytes that are hashed and signed
     */
    public static byte[] of(@NonNull WellKnownSecret secret) {
        return of(secret, NumericTagForm.CURRENT);
    }

    /**
     * How numeric tag values are rendered.
     *
     * <p>{@link #TRUNCATED_TO_LONG} reproduces a historical defect in which every numeric tag
     * was put through {@code longValue()}, so a ratio of {@code 0.056} signed as {@code 0}.
     * Vouchers issued that way are live and cannot be re-signed, because the issuer's key is
     * not available here, so they must keep verifying until they expire. Nothing signs this
     * way any more.
     */
    public enum NumericTagForm {
        CURRENT,
        TRUNCATED_TO_LONG
    }

    /**
     * Renders the canonical signing bytes, choosing how numeric values are written.
     *
     * @param secret      the voucher secret to render
     * @param numericForm the rendering of numeric tag values
     * @return the bytes that are hashed and signed
     * @throws IllegalArgumentException if the secret's kind carries no voucher metadata
     */
    public static byte[] of(@NonNull WellKnownSecret secret, @NonNull NumericTagForm numericForm) {
        requireVoucherCarryingKind(secret);

        StringBuilder sb = new StringBuilder();
        sb.append("[\"").append(secret.getKind().name()).append("\",\"");
        sb.append(Hex.toHexString(secret.getData()));
        sb.append("\",\"");
        sb.append(secret.getNonce() != null ? secret.getNonce() : "");
        sb.append("\",[");

        boolean first = true;
        for (var tag : secret.getTags()) {
            if (isAddedAfterSigning(tag.getKey())) {
                continue;
            }
            if (!first) {
                sb.append(",");
            }
            first = false;
            appendTag(sb, tag, numericForm);
        }
        sb.append("]]");

        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Refuses a kind that carries no voucher metadata.
     *
     * <p>Signing an arbitrary secret with an issuer key would produce a signature that means
     * nothing but looks like an issuer's attestation. Failing here keeps the mistake at the
     * call site rather than turning it into a credential.
     */
    private static void requireVoucherCarryingKind(WellKnownSecret secret) {
        WellKnownSecret.Kind kind = secret.getKind();
        if (kind != WellKnownSecret.Kind.VOUCHER && kind != WellKnownSecret.Kind.P2PK_VOUCHER) {
            throw new IllegalArgumentException(
                    "canonical voucher bytes require a VOUCHER or P2PK_VOUCHER secret, got " + kind);
        }
    }

    private static void appendTag(StringBuilder sb, WellKnownSecret.Tag tag, NumericTagForm numericForm) {
        sb.append("[\"").append(escapeJson(tag.getKey())).append("\"");
        for (var value : tag.getValues()) {
            sb.append(",");
            String raw = String.valueOf(value);
            if (isNumericTag(tag.getKey())) {
                appendNumber(sb, parseNumber(raw), numericForm);
            } else {
                sb.append("\"").append(escapeJson(raw)).append("\"");
            }
        }
        sb.append("]");
    }

    /**
     * Tags carrying the signature itself, which cannot be part of what the signature covers.
     */
    private static boolean isAddedAfterSigning(String key) {
        return VoucherTags.ISSUER_SIG.equals(key) || VoucherTags.ISSUER_PUBKEY.equals(key);
    }

    /**
     * Tags whose values are written as bare JSON numbers rather than quoted strings.
     */
    private static boolean isNumericTag(String key) {
        return VoucherTags.FACE_VALUE.equals(key)
                || VoucherTags.EXPIRES_AT.equals(key)
                || VoucherTags.FACE_DECIMALS.equals(key)
                || VoucherTags.ISSUANCE_RATIO.equals(key);
    }

    /**
     * Reads a numeric tag value, preferring the integral form so whole numbers do not acquire
     * a decimal point they never had when signed.
     */
    private static Number parseNumber(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException notIntegral) {
            return Double.valueOf(raw);
        }
    }

    private static void appendNumber(StringBuilder sb, Number value, NumericTagForm numericForm) {
        if (numericForm == NumericTagForm.TRUNCATED_TO_LONG) {
            sb.append(value.longValue());
            return;
        }
        double d = value.doubleValue();
        // Integral doubles serialise as longs, exactly as WellKnownSecretSerializer does, so
        // 1.0 and 1 produce identical bytes rather than two valid forms. NaN/Infinity fall
        // through to longValue() rather than emitting the literals "NaN"/"Infinity", which are
        // not JSON and would not round-trip.
        if (Double.isFinite(d) && d != Math.floor(d)) {
            sb.append(d);
            return;
        }
        sb.append(value.longValue());
    }

    /**
     * Escapes special JSON characters in a string per RFC 8259.
     *
     * <p>All control characters (U+0000 through U+001F) must be escaped. Common ones use
     * shorthand notation, others use backslash-u hex notation.
     */
    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
