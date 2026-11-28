package xyz.tcheeric.cashu.voucher.domain.ledger;

import org.bouncycastle.util.encoders.Hex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Derivations of voucher ledger v2 ({@code docs/explanation/voucher-ledger-v2.md}).
 *
 * <p>Every method returns a fresh array the caller owns. Intermediate secret buffers are zeroed.
 */
public final class VoucherLedgerRef {

    static final String REF_PREFIX = "imani-voucher-ref-v1:";
    static final String KEY_LABEL = "imani-voucher-key-v1";
    static final String LOCATOR_LABEL = "imani-voucher-locator-v1";
    static final String EVENT_KEY_LABEL = "imani-voucher-ledger-v1";
    static final String ROOT_LABEL = "imani-ledger-key-v1:";

    /** secp256k1 group order. */
    static final BigInteger CURVE_N = new BigInteger(
            "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16);

    private static final Pattern UUID_CANONICAL = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private VoucherLedgerRef() {
    }

    /**
     * Normalises an issuer id: ASCII-lowercased, must be 1..256 characters in 0x21..0x7e.
     *
     * @throws IllegalArgumentException otherwise
     */
    public static String normaliseIssuerId(String issuerId) {
        if (issuerId == null || issuerId.isEmpty() || issuerId.length() > 256) {
            throw new IllegalArgumentException("issuerId must be 1..256 visible ASCII characters");
        }
        for (int i = 0; i < issuerId.length(); i++) {
            char c = issuerId.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                throw new IllegalArgumentException("issuerId must be 1..256 visible ASCII characters");
            }
        }
        return issuerId.toLowerCase(Locale.ROOT);
    }

    /**
     * Normalises a voucher id to the canonical lowercase UUID form (8-4-4-4-12 hex).
     *
     * @throws IllegalArgumentException when it is not a UUID in that layout
     */
    public static String normaliseVoucherId(String voucherId) {
        if (voucherId == null || !UUID_CANONICAL.matcher(voucherId).matches()) {
            throw new IllegalArgumentException("voucherId must be a UUID");
        }
        return UUID.fromString(voucherId).toString();
    }

    /** {@code ref = SHA256("imani-voucher-ref-v1:" + issuerId + ":" + voucherId)}, inputs normalised. */
    public static byte[] ref(String issuerId, String voucherId) {
        String input = REF_PREFIX + normaliseIssuerId(issuerId) + ":" + normaliseVoucherId(voucherId);
        return sha256(input.getBytes(StandardCharsets.US_ASCII));
    }

    /** {@code k = HMAC("imani-voucher-key-v1", ref)}, the NIP-44 conversation key. */
    public static byte[] key(byte[] ref) {
        requireRef(ref);
        return hmac(KEY_LABEL.getBytes(StandardCharsets.US_ASCII), ref);
    }

    /** {@code d = hex(SHA256("imani-voucher-locator-v1" || ref))}. */
    public static String locator(byte[] ref) {
        requireRef(ref);
        byte[] label = LOCATOR_LABEL.getBytes(StandardCharsets.US_ASCII);
        byte[] input = concat(label, ref);
        return Hex.toHexString(sha256(input));
    }

    /**
     * {@code sk_v = HMAC(root, "imani-voucher-ledger-v1" || ref)}, refused outside {@code [1, n-1]}.
     */
    public static byte[] eventKey(byte[] root, byte[] ref) {
        if (root == null || root.length != 32) {
            throw new IllegalArgumentException("ledger root must be 32 bytes");
        }
        requireRef(ref);
        byte[] msg = concat(EVENT_KEY_LABEL.getBytes(StandardCharsets.US_ASCII), ref);
        byte[] sk = hmac(root, msg);
        requireScalar(sk);
        return sk;
    }

    /** {@code root = SHA256("imani-ledger-key-v1:" || secretKey)}. */
    public static byte[] ledgerRoot(byte[] secretKey32) {
        if (secretKey32 == null || secretKey32.length != 32) {
            throw new IllegalArgumentException("secret key must be 32 bytes");
        }
        byte[] input = concat(ROOT_LABEL.getBytes(StandardCharsets.US_ASCII), secretKey32);
        try {
            return sha256(input);
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    static void requireScalar(byte[] sk) {
        BigInteger s = new BigInteger(1, sk);
        if (s.signum() == 0 || s.compareTo(CURVE_N) >= 0) {
            Arrays.fill(sk, (byte) 0);
            throw new IllegalArgumentException("derived event key is not a valid secp256k1 scalar");
        }
    }

    private static void requireRef(byte[] ref) {
        if (ref == null || ref.length != 32) {
            throw new IllegalArgumentException("ref must be 32 bytes");
        }
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static byte[] hmac(byte[] key, byte[] msg) {
        try {
            // JDK HmacSHA256: mandatory on every platform and SHA-NI accelerated, unlike BC's HMac.
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(msg);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
