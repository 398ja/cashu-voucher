package xyz.tcheeric.cashu.voucher.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.common.VoucherSecret;
import xyz.tcheeric.cashu.common.VoucherTags;
import xyz.tcheeric.cashu.common.WellKnownSecret;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Collectors;

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

    /**
     * Gets the canonical bytes for signing (excludes signature-related tags).
     *
     * <p>The canonical representation is the NUT-10 JSON serialization of the
     * VoucherSecret with all tags except {@code issuer_sig} and {@code issuer_pubkey}.
     * These tags are added after signing, so they must be excluded to ensure
     * deterministic signing and verification.
     *
     * <p>Format matches WellKnownSecretSerializer: ["KIND", "data_hex", "nonce", [[tags]]]
     * where numbers are serialized as numbers, not quoted strings.
     *
     * @param secret the voucher secret
     * @return canonical bytes for signing
     */
    private static byte[] getCanonicalBytesForSigning(VoucherSecret secret) {
        try {
            // Build canonical JSON array manually to match WellKnownSecretSerializer format
            // but exclude the issuer_sig tag
            StringBuilder sb = new StringBuilder();
            sb.append("[\"").append(WellKnownSecret.Kind.VOUCHER.name()).append("\",\"");
            sb.append(Hex.toHexString(secret.getData()));
            sb.append("\",\"");
            sb.append(secret.getNonce() != null ? secret.getNonce() : "");
            sb.append("\",[");

            boolean first = true;
            for (var tag : secret.getTags()) {
                // Skip signature and public key tags for signing
                // These are added after signing, so they must be excluded from canonical bytes
                if (VoucherTags.ISSUER_SIG.equals(tag.getKey()) ||
                    VoucherTags.ISSUER_PUBKEY.equals(tag.getKey())) {
                    continue;
                }
                if (!first) {
                    sb.append(",");
                }
                first = false;

                // Serialize tag as array: ["key", value1, value2, ...]
                sb.append("[\"").append(escapeJson(tag.getKey())).append("\"");
                for (var value : tag.getValues()) {
                    sb.append(",");
                    if (value instanceof Number) {
                        // Numbers written without quotes (matching WellKnownSecretSerializer)
                        sb.append(((Number) value).longValue());
                    } else {
                        // Strings written with quotes and proper escaping
                        sb.append("\"").append(escapeJson(String.valueOf(value))).append("\"");
                    }
                }
                sb.append("]");
            }
            sb.append("]]");

            return sb.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize voucher for signing", e);
        }
    }

    /**
     * Escapes special JSON characters in a string.
     *
     * @param input the string to escape
     * @return the escaped string
     */
    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
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
