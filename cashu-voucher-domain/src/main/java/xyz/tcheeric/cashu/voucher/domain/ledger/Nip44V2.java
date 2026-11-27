package xyz.tcheeric.cashu.voucher.domain.ledger;

import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * NIP-44 version 2 encryption over a caller-supplied conversation key.
 *
 * <p>Implemented on BouncyCastle low-level primitives ({@link ChaCha7539Engine}) and
 * the JDK's mandatory HmacSHA256, so it needs no JCA provider registration. The
 * ECDH step that derives a conversation key from two Nostr keys is deliberately absent: the
 * voucher ledger supplies its own conversation key (see {@code voucher-ledger-v2.md}).
 *
 * <p>Replays the official paulmillr/nip44 vectors in {@code nip44.vectors.json}.
 */
public final class Nip44V2 {

    /** Smallest plaintext NIP-44 accepts, in UTF-8 bytes. */
    public static final int MIN_PLAINTEXT_SIZE = 1;
    /** Largest plaintext NIP-44 accepts, in UTF-8 bytes. */
    public static final int MAX_PLAINTEXT_SIZE = 65535;

    private static final byte VERSION = 2;

    private Nip44V2() {
    }

    /** The three per-message keys NIP-44 derives from the conversation key and nonce. */
    record MessageKeys(byte[] chachaKey, byte[] chachaNonce, byte[] hmacKey) {
        void wipe() {
            Arrays.fill(chachaKey, (byte) 0);
            Arrays.fill(chachaNonce, (byte) 0);
            Arrays.fill(hmacKey, (byte) 0);
        }
    }

    static MessageKeys messageKeys(byte[] conversationKey, byte[] nonce) {
        require(conversationKey != null && conversationKey.length == 32, "conversation key must be 32 bytes");
        require(nonce != null && nonce.length == 32, "nonce must be 32 bytes");
        // HKDF-Expand(PRK = conversationKey, info = nonce, L = 76): T(1) || T(2) || T(3)[..12],
        // T(i) = HMAC(PRK, T(i-1) || info || i).
        byte[] okm = new byte[76];
        Mac mac = hmacSha256(conversationKey);
        byte[] t = new byte[0];
        for (int i = 1, off = 0; off < okm.length; i++) {
            mac.update(t);
            mac.update(nonce);
            mac.update((byte) i);
            t = mac.doFinal();
            int n = Math.min(t.length, okm.length - off);
            System.arraycopy(t, 0, okm, off, n);
            off += n;
        }
        Arrays.fill(t, (byte) 0);
        MessageKeys keys = new MessageKeys(
                Arrays.copyOfRange(okm, 0, 32),
                Arrays.copyOfRange(okm, 32, 44),
                Arrays.copyOfRange(okm, 44, 76));
        Arrays.fill(okm, (byte) 0);
        return keys;
    }

    /** NIP-44 padded length of an unpadded plaintext length. */
    public static int calcPaddedLen(int unpaddedLen) {
        require(unpaddedLen > 0, "unpadded length must be positive");
        if (unpaddedLen <= 32) {
            return 32;
        }
        int nextPower = 1 << (32 - Integer.numberOfLeadingZeros(unpaddedLen - 1));
        int chunk = nextPower <= 256 ? 32 : nextPower / 8;
        return chunk * ((unpaddedLen - 1) / chunk + 1);
    }

    /**
     * Encrypts {@code plaintext}.
     *
     * @param plaintext 1..65535 UTF-8 bytes
     * @param conversationKey 32 bytes
     * @param nonce 32 random bytes; never reuse with the same key
     * @return base64 payload
     */
    public static String encrypt(String plaintext, byte[] conversationKey, byte[] nonce) {
        require(plaintext != null, "plaintext must not be null");
        byte[] message = plaintext.getBytes(StandardCharsets.UTF_8);
        require(message.length >= MIN_PLAINTEXT_SIZE && message.length <= MAX_PLAINTEXT_SIZE,
                "invalid plaintext length: " + message.length);
        MessageKeys keys = messageKeys(conversationKey, nonce);
        try {
            byte[] padded = new byte[2 + calcPaddedLen(message.length)];
            padded[0] = (byte) (message.length >>> 8);
            padded[1] = (byte) message.length;
            System.arraycopy(message, 0, padded, 2, message.length);
            byte[] ciphertext = chacha(keys, padded);
            Arrays.fill(padded, (byte) 0);
            byte[] mac = hmac(keys.hmacKey(), nonce, ciphertext);
            byte[] out = new byte[1 + 32 + ciphertext.length + 32];
            out[0] = VERSION;
            System.arraycopy(nonce, 0, out, 1, 32);
            System.arraycopy(ciphertext, 0, out, 33, ciphertext.length);
            System.arraycopy(mac, 0, out, 33 + ciphertext.length, 32);
            return Base64.getEncoder().encodeToString(out);
        } finally {
            keys.wipe();
        }
    }

    /**
     * Decrypts a NIP-44 v2 payload.
     *
     * @throws IllegalArgumentException for any malformed, unauthenticated or badly padded payload
     */
    public static String decrypt(String payload, byte[] conversationKey) {
        require(payload != null, "payload must not be null");
        int plen = payload.length();
        require(plen != 0 && payload.charAt(0) != '#', "unknown encryption version");
        require(plen >= 132 && plen <= 87472, "invalid payload size: " + plen);
        byte[] data;
        try {
            data = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException badBase64) {
            throw new IllegalArgumentException("invalid base64", badBase64);
        }
        require(data.length >= 99 && data.length <= 65603, "invalid data size: " + data.length);
        require(data[0] == VERSION, "unknown encryption version " + data[0]);
        byte[] nonce = Arrays.copyOfRange(data, 1, 33);
        byte[] ciphertext = Arrays.copyOfRange(data, 33, data.length - 32);
        byte[] mac = Arrays.copyOfRange(data, data.length - 32, data.length);
        MessageKeys keys = messageKeys(conversationKey, nonce);
        try {
            byte[] expected = hmac(keys.hmacKey(), nonce, ciphertext);
            require(MessageDigest.isEqual(expected, mac), "invalid MAC");
            byte[] padded = chacha(keys, ciphertext);
            try {
                int len = ((padded[0] & 0xff) << 8) | (padded[1] & 0xff);
                require(len >= MIN_PLAINTEXT_SIZE && padded.length == 2 + calcPaddedLen(len),
                        "invalid padding");
                for (int i = 2 + len; i < padded.length; i++) {
                    require(padded[i] == 0, "invalid padding");
                }
                return new String(padded, 2, len, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(padded, (byte) 0);
            }
        } finally {
            keys.wipe();
        }
    }

    private static byte[] chacha(MessageKeys keys, byte[] input) {
        ChaCha7539Engine engine = new ChaCha7539Engine();
        engine.init(true, new ParametersWithIV(new KeyParameter(keys.chachaKey()), keys.chachaNonce()));
        byte[] out = new byte[input.length];
        engine.processBytes(input, 0, input.length, out, 0);
        return out;
    }

    private static byte[] hmac(byte[] key, byte[] nonce, byte[] ciphertext) {
        Mac mac = hmacSha256(key);
        mac.update(nonce);
        mac.update(ciphertext);
        return mac.doFinal();
    }

    /**
     * JDK HmacSHA256, which every Java platform must provide, so no provider registration is
     * needed. It is used over BouncyCastle's {@code HMac} because the JDK's SHA-256 is
     * intrinsified (SHA-NI) where the CPU has it; BC's pure-Java digest was most of the cost.
     */
    private static Mac hmacSha256(byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
