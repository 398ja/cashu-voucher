package xyz.tcheeric.cashu.voucher.domain.util;

import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds {@link Bip340} to the library it replaces: identical signatures, identical verdicts,
 * on valid signatures and on every malformed shape we can think of.
 */
class Bip340DifferentialTest {

    private static final BigInteger P = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    private static final BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    private final Random rnd = new Random(42);

    private byte[] bytes(int n) {
        byte[] b = new byte[n];
        rnd.nextBytes(b);
        return b;
    }

    private static boolean lib(byte[] m, byte[] pk, byte[] sig) {
        try {
            return Schnorr.verify(m, pk, sig);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] be32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        int n = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - n, out, 32 - n, n);
        return out;
    }

    @Test
    void bip340OfficialVector0() throws Exception {
        byte[] sk = Hex.decode("0000000000000000000000000000000000000000000000000000000000000003");
        byte[] sig = Bip340.sign(new byte[32], sk, new byte[32]);
        assertThat(Hex.toHexString(Bip340.publicKey(sk)).toUpperCase())
                .isEqualTo("F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9");
        assertThat(Hex.toHexString(sig).toUpperCase()).isEqualTo(
                "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA8215"
                        + "25F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0");
    }

    @Test
    void signaturesAndPublicKeysAreByteIdentical() throws Exception {
        for (int i = 0; i < 300; i++) {
            byte[] sk = bytes(32);
            byte[] msg = bytes(32);
            byte[] aux = i % 3 == 0 ? new byte[32] : bytes(32);
            assertThat(Bip340.publicKey(sk)).isEqualTo(Schnorr.genPubKey(sk));
            assertThat(Bip340.sign(msg, sk, aux)).isEqualTo(Schnorr.sign(msg, sk, aux));
        }
    }

    @Test
    void verdictsAgreeOnValidAndCorruptedInputs() throws Exception {
        for (int i = 0; i < 300; i++) {
            byte[] sk = bytes(32);
            byte[] msg = bytes(32);
            byte[] pk = Schnorr.genPubKey(sk);
            byte[] sig = Schnorr.sign(msg, sk, bytes(32));
            agree(msg, pk, sig);

            byte[] flipped = sig.clone();
            flipped[rnd.nextInt(64)] ^= (byte) (1 << rnd.nextInt(8));
            agree(msg, pk, flipped);

            byte[] otherMsg = msg.clone();
            otherMsg[rnd.nextInt(32)] ^= 1;
            agree(otherMsg, pk, sig);

            byte[] otherPk = pk.clone();
            otherPk[rnd.nextInt(32)] ^= 1; // often not on the curve
            agree(msg, otherPk, sig);

            agree(msg, bytes(32), bytes(64));
        }
    }

    @Test
    void verdictsAgreeOnOutOfRangeComponents() throws Exception {
        byte[] sk = bytes(32);
        byte[] msg = bytes(32);
        byte[] pk = Schnorr.genPubKey(sk);
        byte[] sig = Schnorr.sign(msg, sk, bytes(32));

        byte[] rIsP = sig.clone();
        System.arraycopy(be32(P), 0, rIsP, 0, 32);
        agree(msg, pk, rIsP);

        byte[] sIsN = sig.clone();
        System.arraycopy(be32(N), 0, sIsN, 32, 32);
        agree(msg, pk, sIsN);

        // s + n would be the same scalar mod n; it must be refused, not reduced.
        byte[] sPlusN = sig.clone();
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(sig, 32, 64)).add(N);
        if (s.bitLength() <= 256) {
            System.arraycopy(be32(s), 0, sPlusN, 32, 32);
            agree(msg, pk, sPlusN);
        }

        agree(msg, be32(P), sig);
        agree(msg, be32(P.add(BigInteger.ONE)), sig);
        agree(msg, new byte[32], sig);
        byte[] ff = new byte[32];
        Arrays.fill(ff, (byte) 0xff);
        agree(msg, ff, sig);
        agree(msg, pk, new byte[64]);
    }

    private static void agree(byte[] msg, byte[] pk, byte[] sig) {
        boolean ours;
        try {
            ours = Bip340.verify(msg, pk, sig);
        } catch (IllegalArgumentException e) {
            ours = false;
        }
        assertThat(ours).as("verify(%s, %s, %s)", Hex.toHexString(msg), Hex.toHexString(pk), Hex.toHexString(sig))
                .isEqualTo(lib(msg, pk, sig));
    }
}
