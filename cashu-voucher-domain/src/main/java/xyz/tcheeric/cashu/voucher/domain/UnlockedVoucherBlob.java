package xyz.tcheeric.cashu.voucher.domain;

import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.util.VoucherSerializationUtils;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the voucher inside an UNLOCKED voucher secret's {@code data} blob.
 *
 * <h2>Why this exists, and why here</h2>
 *
 * <p>The two voucher kinds keep their fields in different places. A
 * {@code P2PK_VOUCHER} carries them as NUT-10 tags, so any verifier can read them
 * straight off the secret. A plain {@code VOUCHER} carries them as CBOR inside
 * {@code data}, with an EMPTY tag array on the wire.
 *
 * <p>That asymmetry had a consequence nobody intended. Every check in
 * {@code VoucherMetadata} reads tags, so for an unlocked voucher they all found
 * nothing and passed: no signature check, no expiry check, no issuer binding. A
 * verifier could not see the fields even though they were right there in the blob.
 *
 * <p>It lives in {@code cashu-voucher-domain} because reading a voucher is not
 * anybody's private business. The same decode already existed inside
 * imani-gateway-customer's {@code SignedVoucherCodec}, where the mint cannot reach
 * it, which is precisely why the mint could not check what it was accepting.
 *
 * <h2>What it does not do</h2>
 *
 * <p>No opinions. It returns the voucher the blob describes, or nothing when the
 * blob cannot be read. Deciding whether that voucher is acceptable belongs to the
 * caller, so a malformed blob and an expired voucher stay distinguishable.
 *
 * <p>cashu-mint#525.
 */
public final class UnlockedVoucherBlob {

    private static final Logger logger = LoggerFactory.getLogger(UnlockedVoucherBlob.class);

    private UnlockedVoucherBlob() {
    }

    /**
     * The voucher described by an unlocked voucher secret's blob, or {@code null}.
     *
     * <p>Returns {@code null} rather than throwing, because "this is not a readable
     * unlocked voucher" is an ordinary answer on a path that also sees locked
     * vouchers and plain bearer secrets. A caller that requires one refuses on null.
     *
     * @param secret a secret of kind {@code VOUCHER}
     * @return the voucher the blob describes, with its signature tags set, or null
     */
    public static VoucherSecret read(@NonNull WellKnownSecret secret) {
        if (secret.getKind() != WellKnownSecret.Kind.VOUCHER) {
            return null;
        }
        byte[] data = secret.getData();
        if (data == null || data.length == 0) {
            return null;
        }

        try {
            // `data` is ALREADY the CBOR bytes. The secret carries it as hex on the wire
            // and `SecretUtil` hex-decodes it while parsing, so decoding again here
            // produced null on every real voucher and would have left the hole exactly
            // as it was. Caught by the captured-token test rather than by reading.
            return fromBlob(VoucherSerializationUtils.fromCbor(data));
        } catch (RuntimeException unreadable) {
            // Deliberately quiet about the contents: this runs on untrusted input and a
            // blob that will not decode is not evidence of anything worth dumping.
            logger.debug("unlocked voucher blob could not be read: {}", unreadable.getMessage());
            return null;
        }
    }

    /**
     * Whether this secret is an unlocked voucher whose blob names an issuer signature.
     *
     * <p>Separate from {@link #read} so a caller can tell "unsigned" from "unreadable"
     * without inspecting the result, since those two deserve different refusals.
     */
    public static boolean carriesSignature(@NonNull WellKnownSecret secret) {
        VoucherSecret voucher = read(secret);
        return voucher != null
                && voucher.getIssuerSignature() != null
                && voucher.getIssuerPublicKey() != null;
    }

    private static VoucherSecret fromBlob(Map<String, Object> blob) {
        String voucherId = string(blob, "voucherId");
        String issuerId = string(blob, "issuerId");
        String unit = string(blob, "unit");
        String signature = string(blob, "issuerSignature");
        String issuerPublicKey = string(blob, "issuerPublicKey");
        if (voucherId == null || issuerId == null || unit == null) {
            return null;
        }

        String strategy = string(blob, "backingStrategy");
        return VoucherSecret.builder()
                .voucherId(UUID.fromString(voucherId))
                .issuerId(issuerId)
                .unit(unit)
                .faceValue(longValue(blob, "faceValue", 0L))
                .expiresAt(optionalLong(blob, "expiresAt"))
                .memo(string(blob, "memo"))
                .backingStrategy(strategy == null || strategy.isBlank()
                        ? BackingStrategy.MINIMAL.name()
                        : BackingStrategy.valueOf(strategy.toUpperCase(Locale.ROOT)).name())
                .issuanceRatio(doubleValue(blob, "issuanceRatio", 1.0d))
                .faceDecimals((int) longValue(blob, "faceDecimals", 0L))
                .merchantMetadata(string(blob, "merchantMetadata"))
                // Kept verbatim, because an ABSENT warrant is a weaker claim than one
                // whose form is `none`, and collapsing the two is how a stripped warrant
                // reads as a legacy coupon.
                .issuanceWarrant(string(blob, "issuanceWarrant"))
                .nonce(string(blob, "nonce"))
                .issuerSignature(signature)
                .issuerPublicKey(issuerPublicKey)
                .build();
    }

    private static String string(Map<String, Object> blob, String key) {
        Object value = blob.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static long longValue(Map<String, Object> blob, String key, long fallback) {
        Object value = blob.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static Long optionalLong(Map<String, Object> blob, String key) {
        Object value = blob.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static double doubleValue(Map<String, Object> blob, String key, double fallback) {
        Object value = blob.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

}
