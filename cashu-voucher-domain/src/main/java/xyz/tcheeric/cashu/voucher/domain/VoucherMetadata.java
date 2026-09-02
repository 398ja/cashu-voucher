package xyz.tcheeric.cashu.voucher.domain;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherTags;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Reads voucher metadata from either kind that carries it.
 *
 * <p>Public because the mint needs it. {@code VoucherSpendingCondition} used to reach for
 * {@code instanceof VoucherSecret} and skip its checks when that failed, which meant a
 * {@code P2PK_VOUCHER} had its expiry and issuer signature silently ignored. Reading through
 * one accessor set removes the opportunity for that class of bug.
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
public final class VoucherMetadata {

    private VoucherMetadata() {
    }

    /** Whether this kind carries voucher metadata at all. */
    public static boolean isVoucherCarrying(@NonNull WellKnownSecret secret) {
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
    public static String voucherId(@NonNull WellKnownSecret secret) {
        if (secret.getKind() == WellKnownSecret.Kind.P2PK_VOUCHER) {
            return tagValue(secret, VoucherTags.VOUCHER_ID);
        }
        byte[] data = secret.getData();
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    /** The issuing merchant's identifier, or {@code null}. */
    public static String issuerId(@NonNull WellKnownSecret secret) {
        return tagValue(secret, VoucherTags.ISSUER);
    }

    /** The issuer's signature over the canonical bytes, or {@code null} when unsigned. */
    public static String issuerSignature(@NonNull WellKnownSecret secret) {
        return tagValue(secret, VoucherTags.ISSUER_SIG);
    }

    /** The issuer's public key, or {@code null} when unset. */
    public static String issuerPublicKey(@NonNull WellKnownSecret secret) {
        return tagValue(secret, VoucherTags.ISSUER_PUBKEY);
    }

    /** Expiry as a Unix timestamp in seconds, or {@code null} when the voucher does not expire. */
    public static Long expiresAt(@NonNull WellKnownSecret secret) {
        String raw = tagValue(secret, VoucherTags.EXPIRES_AT);
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            // A non-numeric expiry is malformed. Treating it as "no expiry" would make a
            // corrupt tag into an immortal voucher, so report it as already expired instead
            // and let the caller refuse.
            return 0L;
        }
    }

    /** Whether the expiry has passed. A voucher with no expiry never expires. */
    public static boolean isExpired(@NonNull WellKnownSecret secret) {
        Long expiresAt = expiresAt(secret);
        return expiresAt != null && System.currentTimeMillis() / 1000 > expiresAt;
    }

    /** Whether both issuer fields are present. Presence only; says nothing about validity. */
    public static boolean isSigned(@NonNull WellKnownSecret secret) {
        return issuerSignature(secret) != null && issuerPublicKey(secret) != null;
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
