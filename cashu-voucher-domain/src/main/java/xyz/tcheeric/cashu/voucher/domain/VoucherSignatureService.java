package xyz.tcheeric.cashu.voucher.domain;

import lombok.NonNull;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for ED25519 signature generation and verification of voucher secrets.
 *
 * <p>This service provides cryptographic operations for vouchers:
 * <ul>
 *   <li>Signing voucher secrets with issuer private keys (ED25519)</li>
 *   <li>Verifying signatures with issuer public keys (ED25519)</li>
 *   <li>Creating complete {@link SignedVoucher} instances</li>
 * </ul>
 *
 * <h3>Cryptographic Details</h3>
 * <p>Uses ED25519 signatures over the canonical CBOR representation of the voucher secret.
 * The canonical bytes are obtained via {@link VoucherSecret#toCanonicalBytes()}, which ensures
 * deterministic serialization.
 *
 * <h3>Key Format</h3>
 * <p>Keys are expected as hex-encoded strings:
 * <ul>
 *   <li>Private key: 64 hex characters (32 bytes)</li>
 *   <li>Public key: 64 hex characters (32 bytes)</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>All methods are stateless and thread-safe.
 *
 * @see VoucherSecret
 * @see SignedVoucher
 * @see <a href="https://en.wikipedia.org/wiki/EdDSA#Ed25519">ED25519 Specification</a>
 */
public final class VoucherSignatureService {

    private static final Logger logger = LoggerFactory.getLogger(VoucherSignatureService.class);

    /**
     * Expected length for ED25519 private keys (32 bytes = 64 hex chars).
     */
    private static final int ED25519_PRIVATE_KEY_LENGTH = 32;

    /**
     * Expected length for ED25519 public keys (32 bytes = 64 hex chars).
     */
    private static final int ED25519_PUBLIC_KEY_LENGTH = 32;

    /**
     * Expected length for ED25519 signatures (64 bytes).
     */
    private static final int ED25519_SIGNATURE_LENGTH = 64;

    private VoucherSignatureService() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Signs a voucher secret with an issuer's private key.
     *
     * <p>The signature is generated over the canonical CBOR bytes of the voucher secret
     * using ED25519. The resulting signature is 64 bytes.
     *
     * @param secret the voucher secret to sign (must not be null)
     * @param issuerPrivateKeyHex the issuer's private key as hex string (64 chars, must not be null)
     * @return the ED25519 signature (64 bytes)
     * @throws IllegalArgumentException if the private key format is invalid
     */
    public static byte[] sign(
            @NonNull VoucherSecret secret,
            @NonNull String issuerPrivateKeyHex
    ) {
        try {
            byte[] privateKeyBytes = Hex.decode(issuerPrivateKeyHex);

            if (privateKeyBytes.length != ED25519_PRIVATE_KEY_LENGTH) {
                throw new IllegalArgumentException(
                        "Invalid private key length: expected " + ED25519_PRIVATE_KEY_LENGTH +
                                " bytes, got " + privateKeyBytes.length);
            }

            Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(privateKeyBytes, 0);
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(true, privateKey);

            byte[] message = secret.toCanonicalBytes();
            signer.update(message, 0, message.length);

            byte[] signature = signer.generateSignature();

            if (logger.isDebugEnabled()) {
                logger.debug("Signed voucher {} (issuerId={}) with signature length={}",
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
     * Verifies a voucher signature using the issuer's public key.
     *
     * <p>Verifies that the signature is valid for the voucher secret's canonical bytes
     * using ED25519 signature verification.
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
            if (signature.length != ED25519_SIGNATURE_LENGTH) {
                logger.warn("Invalid signature length for voucher {}: expected {} bytes, got {}",
                        secret.getVoucherId(), ED25519_SIGNATURE_LENGTH, signature.length);
                return false;
            }

            byte[] publicKeyBytes = Hex.decode(issuerPublicKeyHex);

            if (publicKeyBytes.length != ED25519_PUBLIC_KEY_LENGTH) {
                logger.warn("Invalid public key length: expected {} bytes, got {}",
                        ED25519_PUBLIC_KEY_LENGTH, publicKeyBytes.length);
                return false;
            }

            Ed25519PublicKeyParameters publicKey = new Ed25519PublicKeyParameters(publicKeyBytes, 0);
            Ed25519Signer verifier = new Ed25519Signer();
            verifier.init(false, publicKey);

            byte[] message = secret.toCanonicalBytes();
            verifier.update(message, 0, message.length);

            boolean valid = verifier.verifySignature(signature);

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
     * Creates a signed voucher by signing the secret and wrapping it.
     *
     * <p>This is a convenience method that combines signing and voucher creation:
     * <ol>
     *   <li>Signs the voucher secret with the private key</li>
     *   <li>Creates a {@link SignedVoucher} with the signature and public key</li>
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
        return new SignedVoucher(secret, signature, issuerPublicKeyHex);
    }

    /**
     * Validates that a hex-encoded key string has the expected length.
     *
     * @param keyHex the key hex string
     * @param expectedBytes the expected byte length
     * @param keyType description of the key type for error messages
     * @throws IllegalArgumentException if the length is invalid
     */
    private static void validateKeyLength(String keyHex, int expectedBytes, String keyType) {
        byte[] keyBytes = Hex.decode(keyHex);
        if (keyBytes.length != expectedBytes) {
            throw new IllegalArgumentException(
                    "Invalid " + keyType + " length: expected " + expectedBytes +
                            " bytes, got " + keyBytes.length);
        }
    }
}
