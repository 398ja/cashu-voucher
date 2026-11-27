package xyz.tcheeric.cashu.voucher.domain.util;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;
import org.bouncycastle.math.ec.FixedPointUtil;
import org.bouncycastle.math.ec.WNafUtil;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * BIP-340 Schnorr over secp256k1, directly on BouncyCastle's specialised curve arithmetic.
 *
 * <p>Internal to the voucher domain. It produces exactly the bytes {@code nostr.crypto.schnorr.Schnorr}
 * produces (same nonce derivation, same post-sign self check), and accepts exactly the signatures
 * it accepts; {@code Bip340DifferentialTest} holds it to that. It exists because the library
 * round-trips every point through {@code BigInteger} affine coordinates and lifts public keys with
 * {@code BigInteger.modPow}, which is most of what a verify costs, and because callers that already
 * know the public key (or verify twice under one key) could not avoid re-deriving it.
 */
public final class Bip340 {

    private static final X9ECParameters PARAMS = CustomNamedCurves.getByName("secp256k1");
    private static final ECCurve CURVE = PARAMS.getCurve();
    private static final ECPoint G = PARAMS.getG();
    private static final BigInteger N = PARAMS.getN();
    private static final BigInteger P = CURVE.getField().getCharacteristic();
    private static final FixedPointCombMultiplier COMB = new FixedPointCombMultiplier();

    private static final byte[] TAG_AUX = tagHash("BIP0340/aux");
    private static final byte[] TAG_NONCE = tagHash("BIP0340/nonce");
    private static final byte[] TAG_CHALLENGE = tagHash("BIP0340/challenge");

    static {
        FixedPointUtil.precompute(G);
    }

    private Bip340() {
    }

    /** A parsed x-only public key; lift once, verify many times. */
    public static final class PublicKey {
        private final byte[] x;
        private final ECPoint point;

        private PublicKey(byte[] x, ECPoint point) {
            this.x = x;
            this.point = point;
        }

        /** The 32-byte x-only encoding. */
        public byte[] bytes() {
            return x.clone();
        }
    }

    /**
     * Lifts a 32-byte x-only key to the even-y point, or {@code null} if it is not on the curve.
     *
     * @throws IllegalArgumentException if the key is not 32 bytes
     */
    public static PublicKey liftX(byte[] x) {
        require(x != null && x.length == 32, "The public key must be a 32-byte array.");
        KeyBytes cacheKey = new KeyBytes(x);
        PublicKey cached = LIFTED.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        PublicKey lifted = lift(x);
        if (lifted != null) {
            if (LIFTED.size() >= LIFTED_CAPACITY) {
                LIFTED.clear();
            }
            LIFTED.put(new KeyBytes(x.clone()), lifted);
        }
        return lifted;
    }

    /**
     * Lifted keys by x-only bytes. A verifier sees few distinct keys (issuers, the platform), and
     * BouncyCastle caches its multiplication tables on the point object, so keeping the point
     * keeps the tables. Bounded; cleared wholesale when full, which is cheap and never wrong.
     */
    private static final int LIFTED_CAPACITY = 1024;
    private static final java.util.concurrent.ConcurrentHashMap<KeyBytes, PublicKey> LIFTED =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record KeyBytes(byte[] b) {
        @Override
        public boolean equals(Object o) {
            return o instanceof KeyBytes k && Arrays.equals(b, k.b);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(b);
        }
    }

    private static PublicKey lift(byte[] x) {
        if (new BigInteger(1, x).compareTo(P) >= 0) {
            return null;
        }
        byte[] enc = new byte[33];
        enc[0] = 0x02;
        System.arraycopy(x, 0, enc, 1, 32);
        ECPoint point;
        try {
            point = CURVE.decodePoint(enc);
        } catch (IllegalArgumentException notOnCurve) {
            return null;
        }
        // Cached keys are reused, so give them the wider wNAF window BC reserves for basepoints.
        WNafUtil.configureBasepoint(point);
        return new PublicKey(x.clone(), point);
    }

    /** {@code bytes(d*G)}, the x-only public key of a secret key. */
    public static byte[] publicKey(byte[] secretKey) {
        return xBytes(COMB.multiply(G, secretScalar(secretKey)).normalize());
    }

    /**
     * Verifies a BIP-340 signature.
     *
     * @throws IllegalArgumentException on wrong lengths, as the library throws
     */
    public static boolean verify(byte[] msg, byte[] publicKey, byte[] sig) {
        require(msg != null && msg.length == 32, "The message must be a 32-byte array.");
        require(publicKey != null && publicKey.length == 32, "The public key must be a 32-byte array.");
        require(sig != null && sig.length == 64, "The signature must be a 64-byte array.");
        PublicKey pk = liftX(publicKey);
        return pk != null && verify(msg, pk, sig);
    }

    /** Verifies against an already lifted key. */
    public static boolean verify(byte[] msg, PublicKey pk, byte[] sig) {
        require(msg != null && msg.length == 32, "The message must be a 32-byte array.");
        require(sig != null && sig.length == 64, "The signature must be a 64-byte array.");
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(sig, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(sig, 32, 64));
        if (r.compareTo(P) >= 0 || s.compareTo(N) >= 0) {
            return false;
        }
        BigInteger e = challenge(sig, 0, pk.x, msg);
        ECPoint rPoint = ECAlgorithms.sumOfTwoMultiplies(G, s, pk.point, N.subtract(e)).normalize();
        if (rPoint.isInfinity() || rPoint.getAffineYCoord().testBitZero()) {
            return false;
        }
        return rPoint.getAffineXCoord().toBigInteger().equals(r);
    }

    /** Signs {@code msg}, then verifies the result as the library does before returning it. */
    public static byte[] sign(byte[] msg, byte[] secretKey, byte[] auxRand) {
        return sign(msg, KeyPair.of(secretKey), auxRand);
    }

    /** A secret key with its public point derived once, for callers that need both. */
    public static final class KeyPair {
        private final BigInteger d0;
        private final ECPoint point;
        private final byte[] x;

        private KeyPair(BigInteger d0, ECPoint point) {
            this.d0 = d0;
            this.point = point;
            this.x = xBytes(point);
        }

        /** Derives {@code d*G} once. */
        public static KeyPair of(byte[] secretKey) {
            BigInteger d0 = secretScalar(secretKey);
            return new KeyPair(d0, COMB.multiply(G, d0).normalize());
        }

        /** The 32-byte x-only public key. */
        public byte[] publicKey() {
            return x.clone();
        }
    }

    /** Signs with a prepared key pair; same bytes and self check as {@link #sign(byte[], byte[], byte[])}. */
    public static byte[] sign(byte[] msg, KeyPair kp, byte[] auxRand) {
        require(msg != null && msg.length == 32, "The message must be a 32-byte array.");
        require(auxRand != null && auxRand.length == 32, "aux must be 32 bytes");
        boolean oddY = kp.point.getAffineYCoord().testBitZero();
        byte[] sig = signWith(msg, oddY ? N.subtract(kp.d0) : kp.d0, kp.x, auxRand);
        // Through the lifted-key cache, so a signer's repeated self checks reuse its tables.
        PublicKey self = liftX(kp.x);
        if (self == null || !verify(msg, self, sig)) {
            throw new IllegalStateException("Could not create a valid signature.");
        }
        return sig;
    }

    private static byte[] signWith(byte[] msg, BigInteger d, byte[] px, byte[] auxRand) {
        byte[] t = xor(bytes32(d), taggedHash(TAG_AUX, auxRand));
        BigInteger k0 = new BigInteger(1, taggedHash(TAG_NONCE, t, px, msg)).mod(N);
        if (k0.signum() == 0) {
            throw new IllegalStateException("Failure. This happens only with negligible probability.");
        }
        ECPoint rPoint = COMB.multiply(G, k0).normalize();
        BigInteger k = rPoint.getAffineYCoord().testBitZero() ? N.subtract(k0) : k0;
        byte[] rx = xBytes(rPoint);
        BigInteger e = challenge(rx, 0, px, msg);
        byte[] sig = new byte[64];
        System.arraycopy(rx, 0, sig, 0, 32);
        System.arraycopy(bytes32(k.add(e.multiply(d)).mod(N)), 0, sig, 32, 32);
        return sig;
    }

    private static BigInteger secretScalar(byte[] secretKey) {
        require(secretKey != null && secretKey.length == 32, "The secret key must be a 32-byte array.");
        BigInteger d = new BigInteger(1, secretKey);
        require(d.signum() > 0 && d.compareTo(N) < 0, "The secret key must be an integer in the range 1..n-1.");
        return d;
    }

    private static BigInteger challenge(byte[] rx, int off, byte[] px, byte[] msg) {
        MessageDigest md = sha256();
        md.update(TAG_CHALLENGE);
        md.update(TAG_CHALLENGE);
        md.update(rx, off, 32);
        md.update(px);
        md.update(msg);
        return new BigInteger(1, md.digest()).mod(N);
    }

    private static byte[] taggedHash(byte[] tag, byte[]... parts) {
        MessageDigest md = sha256();
        md.update(tag);
        md.update(tag);
        for (byte[] p : parts) {
            md.update(p);
        }
        return md.digest();
    }

    private static byte[] tagHash(String tag) {
        return sha256().digest(tag.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] xBytes(ECPoint normalized) {
        return bytes32(normalized.getAffineXCoord().toBigInteger());
    }

    private static byte[] bytes32(BigInteger v) {
        byte[] raw = v.toByteArray();
        if (raw.length == 32) {
            return raw;
        }
        byte[] out = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
