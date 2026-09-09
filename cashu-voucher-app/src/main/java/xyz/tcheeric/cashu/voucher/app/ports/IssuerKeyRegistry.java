package xyz.tcheeric.cashu.voucher.app.ports;

import java.util.Optional;

/**
 * Resolves the public key an issuer is known to sign with.
 *
 * <h2>Why verification needs this</h2>
 *
 * <p>A voucher carries an {@code issuer} id and an {@code issuer_pubkey} tag, and verification
 * used to check the signature against that embedded key (audit H-14). A signature that verifies
 * under a key the signer chose proves only that whoever built the voucher held the matching
 * private key, which the attacker does: generate a keypair, put the merchant's issuer id in the
 * voucher, sign with your own key, and offline verification passes. The voucher was, in effect,
 * self-certifying.
 *
 * <p>A signature is only evidence when it verifies under a key you already trust. This port is
 * where that key comes from. Implementations range from a configured map for a single merchant
 * to a lookup against a merchant directory.
 */
public interface IssuerKeyRegistry {

    /**
     * The public key registered for an issuer.
     *
     * @param issuerId the issuer identifier claimed by a voucher
     * @return the hex-encoded public key, or empty when the issuer is unknown
     */
    Optional<String> publicKeyFor(String issuerId);
}
