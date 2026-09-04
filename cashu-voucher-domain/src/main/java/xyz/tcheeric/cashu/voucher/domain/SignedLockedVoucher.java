package xyz.tcheeric.cashu.voucher.domain;

import lombok.NonNull;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;

import java.util.Objects;

/**
 * A signed voucher that is also P2PK-locked.
 *
 * <p>The {@code P2PK_VOUCHER} counterpart to {@link SignedVoucher}. Same job — carry a
 * signed voucher and let a holder verify it — over the composite kind whose lock the mint
 * actually enforces.
 *
 * <h2>Why a separate type rather than widening SignedVoucher</h2>
 *
 * <p>{@link SignedVoucher} holds a {@link xyz.tcheeric.cashu.common.nut18.VoucherSecret},
 * and {@code P2PKVoucherSecret} is its SIBLING rather than a subclass — both extend
 * {@code WellKnownSecret}. That split is deliberate upstream: a mint must be able to test
 * for the locked kind first, so a broader "is a voucher" branch cannot swallow it and skip
 * the witness check.
 *
 * <p>Widening {@code SignedVoucher}'s field to the common supertype would reach 37 call
 * sites that read it as a {@code VoucherSecret}, and would let a caller construct one
 * around a secret carrying no voucher metadata at all. Two narrow types, each certain of
 * what it holds, cost one class and remove a whole category of mistake.
 *
 * <h2>The lock is not advisory</h2>
 *
 * <p>A plain {@code VOUCHER} that merely NAMES a holder is dispatched by the mint to its
 * voucher condition, which checks the issuer signature and expiry and never looks for a
 * witness — so a thief holding the proof can spend it, and every client-side check is the
 * only thing in the way. Under this kind the mint runs both conditions and the refusal
 * comes from the mint.
 *
 * @see SignedVoucher
 * @see P2PKVoucherSecret
 */
public final class SignedLockedVoucher {

    private final P2PKVoucherSecret secret;

    /**
     * Wraps a secret that already carries its signature and public key.
     *
     * @param secret a signed {@code P2PK_VOUCHER} secret
     * @throws IllegalArgumentException if the secret is unsigned, or carries no lock
     */
    public SignedLockedVoucher(@NonNull P2PKVoucherSecret secret) {
        if (!secret.isSigned()) {
            throw new IllegalArgumentException(
                    "P2PKVoucherSecret must have signature and public key set");
        }
        if (secret.getData() == null || secret.getData().length == 0) {
            // A locked voucher with no lock is the one thing this type exists to
            // rule out. It would verify, look correct, and be spendable by
            // anyone — the failure mode of the plain kind, wearing this one's
            // name.
            throw new IllegalArgumentException("P2PKVoucherSecret must carry a spending key");
        }
        this.secret = secret;
    }

    /**
     * Signs a locked voucher and wraps the result.
     *
     * <p>Goes through {@link VoucherSignatureService#sign} rather than {@code createSigned},
     * because that method takes a {@code VoucherSecret} and this kind is not one. The
     * signing itself is already generic: {@code sign} accepts any {@code WellKnownSecret},
     * and {@code VoucherCanonicalBytes} renders {@code VOUCHER} and {@code P2PK_VOUCHER}
     * through the same path. So the bytes signed here are produced by the same code that
     * signs an unlocked voucher, which is what keeps the two verifiable by one verifier.
     */
    public static SignedLockedVoucher createSigned(
            @NonNull P2PKVoucherSecret secret,
            @NonNull String issuerPrivateKeyHex,
            @NonNull String issuerPublicKeyHex
    ) {
        byte[] signature = VoucherSignatureService.sign(secret, issuerPrivateKeyHex);
        secret.setIssuerSignature(Hex.toHexString(signature));
        secret.setIssuerPublicKey(issuerPublicKeyHex);
        return new SignedLockedVoucher(secret);
    }

    public P2PKVoucherSecret getSecret() {
        return secret;
    }

    /** The key a spender must produce a witness signature for, hex-encoded. */
    public String getLockKey() {
        return Hex.toHexString(secret.getData());
    }

    /** Whether the issuer's signature checks out over this voucher's canonical bytes. */
    public boolean verify() {
        return VoucherSignatureService.verify(secret);
    }

    public boolean isExpired() {
        return secret.isExpired();
    }

    /**
     * Signed, unexpired, and locked.
     *
     * <p>Deliberately the same three-part reading as {@link SignedVoucher#isValid()} plus
     * the lock, so a caller cannot get a "valid" locked voucher that is not actually
     * locked.
     */
    public boolean isValid() {
        return verify() && !isExpired();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof SignedLockedVoucher that
                && Objects.equals(secret.toString(), that.secret.toString());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(secret.toString());
    }

    @Override
    public String toString() {
        return "SignedLockedVoucher[voucherId=" + secret.getVoucherId()
                + ", issuerId=" + secret.getIssuerId()
                + ", lockKey=" + getLockKey()
                + "]";
    }
}
