package xyz.tcheeric.cashu.voucher.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.VoucherCanonicalBytes.NumericTagForm;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Service for secp256k1/Schnorr signature generation and verification of voucher secrets.
 *
 * <p>This service provides cryptographic operations for vouchers using the same
 * signature scheme as Nostr (BIP-340 Schnorr signatures over secp256k1):
 * <ul>
 *   <li>Signing voucher secrets with issuer private keys (secp256k1/Schnorr)</li>
 *   <li>Verifying signatures with issuer public keys (secp256k1 x-only)</li>
 *   <li>Creating complete {@link SignedVoucher} instances</li>
 * </ul>
 *
 * <h3>Cryptographic Details</h3>
 * <p>Uses BIP-340 Schnorr signatures over the NUT-10 serialized representation of the voucher secret.
 * The canonical bytes for signing are obtained by serializing the VoucherSecret without the
 * signature tag, then hashing with SHA-256.
 *
 * <h3>Key Format</h3>
 * <p>Keys are expected as hex-encoded strings (matching Nostr format):
 * <ul>
 *   <li>Private key: 64 hex characters (32 bytes) - secp256k1 scalar</li>
 *   <li>Public key: 64 hex characters (32 bytes) - x-only secp256k1 point</li>
 * </ul>
 *
 * <h3>Signature Format</h3>
 * <p>Signatures are 64 bytes (BIP-340 Schnorr format).
 *
 * <h3>Thread Safety</h3>
 * <p>All methods are stateless and thread-safe.
 *
 * @see VoucherSecret
 * @see SignedVoucher
 * @see <a href="https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki">BIP-340 Schnorr Signatures</a>
 */
public final class VoucherSignatureService {

    private static final Logger logger = LoggerFactory.getLogger(VoucherSignatureService.class);

    /**
     * Expected length for secp256k1 private keys (32 bytes = 64 hex chars).
     */
    private static final int SECP256K1_PRIVATE_KEY_LENGTH = 32;

    /**
     * Expected length for secp256k1 x-only public keys (32 bytes = 64 hex chars).
     */
    private static final int SECP256K1_PUBLIC_KEY_LENGTH = 32;

    /**
     * Expected length for BIP-340 Schnorr signatures (64 bytes).
     */
    private static final int SCHNORR_SIGNATURE_LENGTH = 64;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private VoucherSignatureService() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Signs a voucher secret with an issuer's private key using Schnorr signatures.
     *
     * <p>The signature is generated over the canonical representation of the voucher secret
     * (NUT-10 format without signature tag) using BIP-340 Schnorr signatures.
     * The resulting signature is 64 bytes.
     *
     * @param secret the voucher secret to sign (must not be null)
     * @param issuerPrivateKeyHex the issuer's private key as hex string (64 chars, must not be null)
     * @return the Schnorr signature (64 bytes)
     * @throws IllegalArgumentException if the private key format is invalid
     */
    public static byte[] sign(
            @NonNull VoucherSecret secret,
            @NonNull String issuerPrivateKeyHex
    ) {
        try {
            byte[] privateKeyBytes = Hex.decode(issuerPrivateKeyHex);

            if (privateKeyBytes.length != SECP256K1_PRIVATE_KEY_LENGTH) {
                throw new IllegalArgumentException(
                        "Invalid private key length: expected " + SECP256K1_PRIVATE_KEY_LENGTH +
                                " bytes, got " + privateKeyBytes.length);
            }

            // Get canonical bytes for signing (NUT-10 format without signature)
            byte[] canonicalBytes = getCanonicalBytesForSigning(secret);
            byte[] messageHash = sha256(canonicalBytes);

            // Generate 32 bytes of auxiliary randomness for BIP-340 signing
            byte[] auxRand = new byte[32];
            SECURE_RANDOM.nextBytes(auxRand);

            byte[] signature = Schnorr.sign(messageHash, privateKeyBytes, auxRand);

            if (logger.isDebugEnabled()) {
                logger.debug("Signed voucher {} (issuerId={}) with Schnorr signature length={}",
                        secret.getVoucherId(),
                        secret.getIssuerId(),
                        signature.length);
            }

            return signature;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to sign voucher: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies a voucher secret's signature using the signature and public key from its tags.
     *
     * @param secret the voucher secret with signature and public key tags set
     * @return true if the signature is valid, false otherwise
     */
    public static boolean verify(@NonNull VoucherSecret secret) {
        String signatureHex = secret.getIssuerSignature();
        String publicKeyHex = secret.getIssuerPublicKey();

        if (signatureHex == null || publicKeyHex == null) {
            logger.warn("Cannot verify unsigned voucher {}", secret.getVoucherId());
            return false;
        }

        try {
            byte[] signature = Hex.decode(signatureHex);
            return verify(secret, signature, publicKeyHex);
        } catch (Exception e) {
            logger.warn("Signature verification failed for voucher {}: {}",
                    secret.getVoucherId(), e.getMessage());
            return false;
        }
    }

    /**
     * Verifies a voucher signature using the issuer's public key.
     *
     * <p>Verifies that the signature is valid for the voucher secret's canonical bytes
     * using BIP-340 Schnorr signature verification.
     *
     * @param secret the voucher secret (must not be null)
     * @param signature the signature to verify (must not be null, 64 bytes)
     * @param issuerPublicKeyHex the issuer's public key as hex string (64 chars, must not be null)
     * @return true if the signature is valid, false otherwise
     */
    public static boolean verify(
            @NonNull VoucherSecret secret,
            @NonNull byte[] signature,
            @NonNull String issuerPublicKeyHex
    ) {
        try {
            if (signature.length != SCHNORR_SIGNATURE_LENGTH) {
                logger.warn("Invalid signature length for voucher {}: expected {} bytes, got {}",
                        secret.getVoucherId(), SCHNORR_SIGNATURE_LENGTH, signature.length);
                return false;
            }

            byte[] publicKeyBytes = Hex.decode(issuerPublicKeyHex);

            if (publicKeyBytes.length != SECP256K1_PUBLIC_KEY_LENGTH) {
                logger.warn("Invalid public key length: expected {} bytes, got {}",
                        SECP256K1_PUBLIC_KEY_LENGTH, publicKeyBytes.length);
                return false;
            }

            // Get canonical bytes for verification (NUT-10 format without signature)
            byte[] canonicalBytes = getCanonicalBytesForSigning(secret);
            byte[] messageHash = sha256(canonicalBytes);

            boolean valid = Schnorr.verify(messageHash, publicKeyBytes, signature);

            // Compatibility window. Every voucher issued before the fractional-tag fix
            // was signed over bytes where any Double was truncated by longValue(), so a
            // ratio of 0.056 signed as 0. Those vouchers are live and cannot be re-signed
            // — the issuer's key is not here — so they must still verify until they
            // expire. Only reached when the current form has already failed, so a voucher
            // signed the new way never pays for this.
            if (!valid) {
                byte[] legacyHash = sha256(
                        VoucherCanonicalBytes.of(secret, NumericTagForm.TRUNCATED_TO_LONG));
                valid = Schnorr.verify(legacyHash, publicKeyBytes, signature);
                if (valid) {
                    logger.info("voucher_signature_legacy_canonical voucher_id={} issuer_id={} "
                                    + "reason=pre_fractional_tag_fix",
                            secret.getVoucherId(), secret.getIssuerId());
                }
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Verified voucher {} (issuerId={}): {}",
                        secret.getVoucherId(),
                        secret.getIssuerId(),
                        valid ? "VALID" : "INVALID");
            }

            return valid;

        } catch (Exception e) {
            logger.warn("Signature verification failed for voucher {}: {}",
                    secret.getVoucherId(), e.getMessage());
            return false;
        }
    }

    /**
     * Creates a signed voucher by signing the secret and setting the signature/pubkey tags.
     *
     * <p>This is a convenience method that:
     * <ol>
     *   <li>Signs the voucher secret with the private key using Schnorr</li>
     *   <li>Sets the signature and public key tags on the secret</li>
     *   <li>Creates a {@link SignedVoucher} wrapping the secret</li>
     * </ol>
     *
     * @param secret the voucher secret to sign (must not be null)
     * @param issuerPrivateKeyHex the issuer's private key as hex string (must not be null)
     * @param issuerPublicKeyHex the issuer's public key as hex string (must not be null)
     * @return a new SignedVoucher instance
     * @throws IllegalArgumentException if key formats are invalid
     */
    public static SignedVoucher createSigned(
            @NonNull VoucherSecret secret,
            @NonNull String issuerPrivateKeyHex,
            @NonNull String issuerPublicKeyHex
    ) {
        byte[] signature = sign(secret, issuerPrivateKeyHex);
        secret.setIssuerSignature(Hex.toHexString(signature));
        secret.setIssuerPublicKey(issuerPublicKeyHex);
        return new SignedVoucher(secret);
    }

    private static byte[] getCanonicalBytesForSigning(VoucherSecret secret) {
        return VoucherCanonicalBytes.of(secret);
    }

    /**
     * Computes SHA-256 hash of input bytes.
     *
     * @param input bytes to hash
     * @return 32-byte SHA-256 hash
     */
    private static byte[] sha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
