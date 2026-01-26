package xyz.tcheeric.cashu.voucher.app.ports;

import java.time.Instant;
import java.util.Optional;

/**
 * Port for voucher redemption tracking with duplicate detection.
 *
 * <p>This port defines the interface for tracking voucher redemptions using
 * cryptographic fingerprints. It provides defense-in-depth against double
 * redemption by maintaining a record of all redemption attempts.
 *
 * <h3>Hexagonal Architecture</h3>
 * <p>This is a <b>port</b> (interface) in hexagonal architecture terminology.
 * Implementations (adapters) can use various storage backends:
 * <ul>
 *   <li>Nostr: Store redemptions as events (NIP-33 or encrypted)</li>
 *   <li>SQL: Store in a redemptions table</li>
 *   <li>In-memory: For testing or caching</li>
 * </ul>
 *
 * <h3>Security Model</h3>
 * <p>The fingerprint-based tracking provides:
 * <ul>
 *   <li><b>Duplicate detection</b>: Same voucher produces same fingerprint</li>
 *   <li><b>Privacy</b>: Fingerprint doesn't reveal voucher details</li>
 *   <li><b>Atomic operations</b>: tryRecordRedemption is idempotent</li>
 * </ul>
 *
 * @see VoucherLedgerPort
 * @see xyz.tcheeric.cashu.voucher.domain.VoucherFingerprint
 */
public interface VoucherRedemptionPort {

    /**
     * Attempts to record a voucher redemption atomically.
     *
     * <p>This is an idempotent operation:
     * <ul>
     *   <li>If fingerprint not seen before: records redemption and returns true</li>
     *   <li>If fingerprint already recorded: returns false (no change)</li>
     * </ul>
     *
     * <p>This method MUST be atomic to prevent race conditions between
     * concurrent redemption attempts.
     *
     * @param fingerprint the voucher fingerprint (64-char hex, must not be null)
     * @param voucherId the voucher ID for reference (must not be null)
     * @param redeemedBy identifier of the redeeming party (must not be null)
     * @return true if redemption was recorded (first attempt), false if duplicate
     * @throws IllegalArgumentException if parameters are invalid
     * @throws RuntimeException if storage operation fails
     */
    boolean tryRecordRedemption(String fingerprint, String voucherId, String redeemedBy);

    /**
     * Checks if a voucher fingerprint has already been redeemed.
     *
     * @param fingerprint the voucher fingerprint to check (64-char hex)
     * @return true if a redemption record exists for this fingerprint
     * @throws IllegalArgumentException if fingerprint is null or invalid
     */
    boolean isRedeemed(String fingerprint);

    /**
     * Retrieves redemption details for a fingerprint.
     *
     * @param fingerprint the voucher fingerprint (64-char hex)
     * @return redemption record if found, empty otherwise
     * @throws IllegalArgumentException if fingerprint is null or invalid
     */
    Optional<RedemptionRecord> getRedemption(String fingerprint);

    /**
     * Redemption record containing details of when and by whom a voucher was redeemed.
     *
     * @param fingerprint the voucher fingerprint
     * @param voucherId the original voucher ID
     * @param redeemedBy identifier of the redeeming party
     * @param redeemedAt timestamp of redemption
     */
    record RedemptionRecord(
            String fingerprint,
            String voucherId,
            String redeemedBy,
            Instant redeemedAt
    ) {
        public RedemptionRecord {
            if (fingerprint == null || fingerprint.length() != 64) {
                throw new IllegalArgumentException("Fingerprint must be 64-char hex string");
            }
            if (voucherId == null || voucherId.isBlank()) {
                throw new IllegalArgumentException("Voucher ID must not be null or blank");
            }
            if (redeemedBy == null || redeemedBy.isBlank()) {
                throw new IllegalArgumentException("RedeemedBy must not be null or blank");
            }
            if (redeemedAt == null) {
                throw new IllegalArgumentException("RedeemedAt must not be null");
            }
        }
    }
}
