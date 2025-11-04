package xyz.tcheeric.cashu.voucher.nostr;

/**
 * Exception thrown when voucher-specific Nostr operations fail.
 *
 * <p>This exception wraps various failure modes in voucher Nostr operations including:
 * <ul>
 *   <li>Voucher serialization/deserialization errors</li>
 *   <li>Event mapping failures</li>
 *   <li>Encryption/decryption errors</li>
 *   <li>Relay communication failures</li>
 * </ul>
 */
public class VoucherNostrException extends RuntimeException {

    /**
     * Constructs a VoucherNostrException with the specified detail message.
     *
     * @param message the detail message
     */
    public VoucherNostrException(String message) {
        super(message);
    }

    /**
     * Constructs a VoucherNostrException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause
     */
    public VoucherNostrException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a VoucherNostrException with the specified cause.
     *
     * @param cause the cause
     */
    public VoucherNostrException(Throwable cause) {
        super(cause);
    }
}
