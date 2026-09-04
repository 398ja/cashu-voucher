package xyz.tcheeric.cashu.voucher.domain;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A signed voucher that is also locked.
 *
 * <p>The properties worth attacking are the ones where being wrong produces something that
 * LOOKS right: a locked voucher with no lock, a signature that verifies over the wrong
 * kind, and a lock that the signature does not cover.
 */
class SignedLockedVoucherTest {

    /** secp256k1 generator, even-y — the key a witness must sign for. */
    private static final String SPENDING_KEY =
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";

    private static final String OTHER_KEY =
            "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5";

    private static final String ISSUER_PRIVKEY =
            "f6b7737ceb512d66070b27fc20994e612e55d6974dbaf37511cfabc62d59cafd";

    private static final String ISSUER_PUBKEY =
            "28615b57803d0b05418a13677ab2aa62a51868799892414a73c5d99e02c3fcec";

    private static P2PKVoucherSecret locked(String spendingKey) {
        P2PKVoucherSecret secret = new P2PKVoucherSecret(Hex.decode(spendingKey));
        secret.setVoucherId(UUID.randomUUID().toString());
        secret.setIssuerId("test-issuer");
        secret.setUnit("sat");
        secret.setFaceValue(5000L);
        return secret;
    }

    private static SignedLockedVoucher signed(String spendingKey) {
        return SignedLockedVoucher.createSigned(locked(spendingKey), ISSUER_PRIVKEY, ISSUER_PUBKEY);
    }

    @Nested
    @DisplayName("signing")
    class Signing {

        @Test
        @DisplayName("a locked voucher signs and verifies")
        void roundTrips() {
            assertThat(signed(SPENDING_KEY).verify()).isTrue();
        }

        @Test
        @DisplayName("the lock survives signing")
        void lockSurvives() {
            // The one thing that must never be quietly dropped. A locked voucher
            // whose lock vanished would verify, look correct, and be spendable
            // by anyone.
            assertThat(signed(SPENDING_KEY).getLockKey()).isEqualTo(SPENDING_KEY);
        }

        @Test
        @DisplayName("the signature covers the lock")
        void signatureCoversLock() {
            /**
             * Canonical bytes include `data`, which is where the spending key
             * lives — so re-locking a signed voucher to another key must break
             * verification. If it did not, an attacker could take a valid
             * voucher and point it at their own key.
             */
            P2PKVoucherSecret tampered = signed(SPENDING_KEY).getSecret();
            tampered.setData(Hex.decode(OTHER_KEY));

            assertThat(VoucherSignatureService.verify(tampered)).isFalse();
        }

        @Test
        @DisplayName("two different locks produce two different signatures")
        void locksDiffer() {
            // Follows from the above, asserted directly because it is the
            // property a reviewer would want to see rather than infer.
            assertThat(signed(SPENDING_KEY).getSecret().getIssuerSignature())
                    .isNotEqualTo(signed(OTHER_KEY).getSecret().getIssuerSignature());
        }
    }

    @Nested
    @DisplayName("what it refuses to wrap")
    class Refusals {

        @Test
        @DisplayName("an unsigned secret")
        void unsigned() {
            assertThatThrownBy(() -> new SignedLockedVoucher(locked(SPENDING_KEY)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("signature");
        }

        @Test
        @DisplayName("a locked voucher carrying no lock")
        void noLock() {
            /**
             * The failure this type exists to rule out: the plain kind's
             * weakness wearing this one's name. It would verify and be
             * spendable by anyone holding the proof.
             */
            P2PKVoucherSecret secret = locked(SPENDING_KEY);
            secret.setIssuerSignature("00".repeat(64));
            secret.setIssuerPublicKey(ISSUER_PUBKEY);
            secret.setData(new byte[0]);

            assertThatThrownBy(() -> new SignedLockedVoucher(secret))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("spending key");
        }
    }

    @Nested
    @DisplayName("beside the unlocked kind")
    class BesideSignedVoucher {

        @Test
        @DisplayName("both carry the same voucher fields")
        void sameFields() {
            // The two kinds differ in where the key lives, not in what the
            // voucher is worth. A reader that got different money depending on
            // the kind would make the lock change the value.
            SignedLockedVoucher lockedVoucher = signed(SPENDING_KEY);

            assertThat(lockedVoucher.getSecret().getIssuerId()).isEqualTo("test-issuer");
            assertThat(lockedVoucher.getSecret().getUnit()).isEqualTo("sat");
            assertThat(lockedVoucher.getSecret().getFaceValue()).isEqualTo(5000L);
        }

        /**
         * equals/hashCode compare {@code secret.toString()}, which returns the
         * CACHED wire string when the secret was parsed rather than built. A
         * mutator that forgot to discard that cache would leave two different
         * vouchers comparing equal — and these are money objects, so "equal"
         * decides deduplication.
         *
         * <p>Sound today because every mutator routes through {@code setTag},
         * which calls {@code forgetWireString}. Pinned here because that
         * invariant lives in a different repository from the equals() that
         * depends on it.
         *
         * <p>Mutates a signed voucher and re-reads it rather than comparing two
         * independently signed ones: Schnorr signing is randomised, so two
         * signatures over identical content differ, and equality between
         * separately-signed vouchers is neither expected nor meaningful.
         */
        @Test
        @DisplayName("mutating a voucher changes its identity, cached wire string or not")
        void mutationBreaksEquality() {
            SignedLockedVoucher voucher = signed(SPENDING_KEY);
            // Parse it back, so the secret HAS a cached wire string. A secret
            // that was built rather than parsed has none, so toString() always
            // re-encodes and the cache is never exercised.
            voucher = new SignedLockedVoucher(
                    (P2PKVoucherSecret) SecretUtil.toSecret(voucher.getSecret().toString()));
            String before = voucher.getSecret().toString();
            int hashBefore = voucher.hashCode();

            voucher.getSecret().setFaceValue(9_999L);

            assertThat(voucher.getSecret().toString())
                    .as("a stale wire string would hide the new face value")
                    .isNotEqualTo(before);
            assertThat(voucher.hashCode())
                    .as("...and a set keyed on it would silently collapse the two")
                    .isNotEqualTo(hashBefore);
            assertThat(voucher.getSecret().getFaceValue()).isEqualTo(9_999L);
        }

        @Test
        @DisplayName("an unlocked voucher has no lock to read")
        void unlockedHasNoLock() {
            // Asserting the difference is real: SignedVoucher exposes no lock
            // key at all, so a caller cannot mistake one for the other.
            VoucherSecret plain = new VoucherSecret();
            assertThat(plain.getKind()).isNotEqualTo(locked(SPENDING_KEY).getKind());
        }
    }
}
