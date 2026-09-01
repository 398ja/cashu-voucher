package xyz.tcheeric.cashu.voucher.domain;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherTags;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reads voucher metadata from either kind that carries it.
 *
 * <p>Two NUT-10 kinds hold voucher metadata: {@code VOUCHER}, and {@code P2PK_VOUCHER} for a
 * voucher that is also P2PK-locked. They share the {@link VoucherTags} vocabulary, so almost
 * everything is read identically — but they do not share a Java supertype that declares the
 * accessors, and they disagree about one field.
 *
 * <p>That field is the voucher id. A {@code VOUCHER} secret keeps it in {@code data}; a
 * {@code P2PK_VOUCHER} cannot, because {@code data} is where NUT-11 puts the spending key and
 * where a mint looks for the lock, so it moves to a tag. This class is the one place that
 * knows the difference, which keeps every caller from having to.
 *
 * <p>Reading rather than casting also means signing and verification stay single
 * implementations. Overloading them per kind would have duplicated the cryptography, which is
 * the last thing worth duplicating.
 */
final class VoucherMetadata {

    /** Where a {@code P2PK_VOUCHER} keeps its voucher id, since {@code data} holds the lock. */
    private static final String VOUCHER_ID_TAG = "voucher_id";

    private VoucherMetadata() {
    }

    /** Whether this kind carries voucher metadata at all. */
    static boolean isVoucherCarrying(@NonNull WellKnownSecret secret) {
        WellKnownSecret.Kind kind = secret.getKind();
        return kind == WellKnownSecret.Kind.VOUCHER
                || kind == WellKnownSecret.Kind.P2PK_VOUCHER;
    }

    /**
     * The voucher id, from wherever this kind keeps it, or {@code null}.
     *
     * <p>Used for log correlation, so it never throws: a secret too malformed to name is still
     * worth a log line saying so.
     */
    static String voucherId(@NonNull WellKnownSecret secret) {
        if (secret.getKind() == WellKnownSecret.Kind.P2PK_VOUCHER) {
            return tagValue(secret, VOUCHER_ID_TAG);
        }
        byte[] data = secret.getData();
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    /** The issuing merchant's identifier, or {@code null}. */
    static String issuerId(@NonNull WellKnownSecret secret) {
        return tagValue(secret, VoucherTags.ISSUER);
    }

    /** The issuer's signature over the canonical bytes, or {@code null} when unsigned. */
    static String issuerSignature(@NonNull WellKnownSecret secret) {
        return tagValue(secret, VoucherTags.ISSUER_SIG);
    }

    /** The issuer's public key, or {@code null} when unset. */
    static String issuerPublicKey(@NonNull WellKnownSecret secret) {
        return tagValue(secret, VoucherTags.ISSUER_PUBKEY);
    }

    /**
     * First value of {@code key} as a string, or {@code null}.
     *
     * <p>Coerces rather than casts: NUT-10 tags hold strings, but a hand-built secret can hold
     * a boxed number, and a reader used for logging must not be the thing that throws.
     */
    private static String tagValue(WellKnownSecret secret, String key) {
        WellKnownSecret.Tag tag = secret.getTag(key);
        if (tag == null) {
            return null;
        }
        List<Object> values = tag.getValues();
        if (values == null || values.isEmpty()) {
            return null;
        }
        Object value = values.get(0);
        return value == null ? null : String.valueOf(value);
    }
}
