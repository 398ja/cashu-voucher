package xyz.tcheeric.cashu.voucher.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.time.Instant;

/**
 * DTO for storing voucher metadata in wallet state.
 *
 * <p>This DTO extends the basic SignedVoucher with additional metadata useful
 * for wallet management, such as when the voucher was added, last backup time, etc.
 *
 * <h3>Usage Example</h3>
 * <pre>
 * // Convert from SignedVoucher to StoredVoucher
 * StoredVoucher stored = StoredVoucher.from(signedVoucher);
 *
 * // Store in wallet
 * walletState.addVoucher(stored);
 *
 * // Check if backup needed
 * if (stored.needsBackup()) {
 *     backupService.backup(stored.getVoucher());
 *     stored.markBackedUp();
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoredVoucher {

    /**
     * The signed voucher.
     */
    private SignedVoucher voucher;

    /**
     * When this voucher was added to the wallet (Unix epoch seconds).
     */
    private Long addedAt;

    /**
     * When this voucher was last backed up to Nostr (Unix epoch seconds).
     * Null if never backed up.
     */
    private Long lastBackupAt;

    /**
     * Cached status from the last ledger query.
     * Null if status has never been queried.
     */
    private VoucherStatus cachedStatus;

    /**
     * When the cached status was last updated (Unix epoch seconds).
     * Null if status has never been queried.
     */
    private Long statusUpdatedAt;

    /**
     * Optional label/note added by the user.
     */
    private String userLabel;

    /**
     * Creates a StoredVoucher from a SignedVoucher with current timestamp.
     */
    public static StoredVoucher from(SignedVoucher voucher) {
        return StoredVoucher.builder()
                .voucher(voucher)
                .addedAt(Instant.now().getEpochSecond())
                .build();
    }

    /**
     * Creates a StoredVoucher with a user label.
     */
    public static StoredVoucher from(SignedVoucher voucher, String label) {
        return StoredVoucher.builder()
                .voucher(voucher)
                .addedAt(Instant.now().getEpochSecond())
                .userLabel(label)
                .build();
    }

    /**
     * Marks the voucher as backed up (sets lastBackupAt to now).
     */
    public void markBackedUp() {
        this.lastBackupAt = Instant.now().getEpochSecond();
    }

    /**
     * Updates the cached status and timestamp.
     */
    public void updateStatus(VoucherStatus status) {
        this.cachedStatus = status;
        this.statusUpdatedAt = Instant.now().getEpochSecond();
    }

    /**
     * Checks if the voucher needs backup.
     * Returns true if never backed up, or if added more recently than last backup.
     */
    public boolean needsBackup() {
        if (lastBackupAt == null) {
            return true; // Never backed up
        }
        if (addedAt != null && addedAt > lastBackupAt) {
            return true; // Modified since last backup
        }
        return false;
    }

    /**
     * Checks if the cached status is stale (older than threshold).
     *
     * @param thresholdSeconds how many seconds before status is considered stale
     * @return true if status should be refreshed
     */
    public boolean isStatusStale(long thresholdSeconds) {
        if (statusUpdatedAt == null) {
            return true; // Never queried
        }
        long age = Instant.now().getEpochSecond() - statusUpdatedAt;
        return age > thresholdSeconds;
    }

    /**
     * Convenience accessor for voucher ID.
     */
    public String getVoucherId() {
        return voucher != null ? voucher.getSecret().getVoucherId() : null;
    }

    /**
     * Convenience accessor for face value.
     */
    public Long getAmount() {
        return voucher != null ? voucher.getSecret().getFaceValue() : null;
    }

    /**
     * Convenience accessor for unit.
     */
    public String getUnit() {
        return voucher != null ? voucher.getSecret().getUnit() : null;
    }

    /**
     * Convenience accessor for expiry.
     */
    public Long getExpiresAt() {
        return voucher != null ? voucher.getSecret().getExpiresAt() : null;
    }

    /**
     * Checks if the voucher is expired.
     */
    public boolean isExpired() {
        return voucher != null && voucher.isExpired();
    }
}
