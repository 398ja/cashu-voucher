package xyz.tcheeric.cashu.voucher.app;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.voucher.app.dto.StoredVoucher;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for managing voucher backup operations.
 *
 * <p>This service provides higher-level backup management beyond the basic
 * backup/restore operations, including:
 * <ul>
 *   <li>Intelligent backup scheduling (only backup changed vouchers)</li>
 *   <li>Conflict resolution during restore</li>
 *   <li>Backup verification and integrity checks</li>
 *   <li>Merge logic for combining local and remote vouchers</li>
 * </ul>
 *
 * <h3>Backup Strategy</h3>
 * <p>Vouchers are non-deterministic (unlike NUT-13 secrets), so they MUST be
 * backed up to recoverable storage. This service implements an incremental
 * backup strategy to minimize bandwidth and storage usage.
 *
 * <h3>Usage Example</h3>
 * <pre>
 * VoucherBackupService service = new VoucherBackupService(voucherService);
 *
 * // Backup vouchers that need backing up
 * List&lt;StoredVoucher&gt; vouchers = wallet.getVouchers();
 * int backedUp = service.backupIfNeeded(vouchers, userPrivateKey);
 *
 * // Restore and merge with local vouchers
 * List&lt;StoredVoucher&gt; local = wallet.getVouchers();
 * List&lt;StoredVoucher&gt; merged = service.restoreAndMerge(local, userPrivateKey);
 * </pre>
 *
 * @see VoucherService
 * @see StoredVoucher
 */
@Slf4j
public class VoucherBackupService {

    private final VoucherService voucherService;

    /**
     * Constructs a VoucherBackupService.
     *
     * @param voucherService the underlying voucher service (must not be null)
     */
    public VoucherBackupService(@NonNull VoucherService voucherService) {
        this.voucherService = voucherService;
        log.info("VoucherBackupService initialized");
    }

    /**
     * Backs up vouchers that need backing up (incremental backup).
     *
     * <p>This method filters the vouchers to only backup those that:
     * <ul>
     *   <li>Have never been backed up (lastBackupAt is null)</li>
     *   <li>Have been modified since last backup (addedAt &gt; lastBackupAt)</li>
     * </ul>
     *
     * @param vouchers the list of stored vouchers to check (must not be null)
     * @param userPrivateKey the user's private key for encryption (must not be null or blank)
     * @return the number of vouchers backed up
     * @throws IllegalArgumentException if parameters are invalid
     * @throws RuntimeException if backup fails
     */
    public int backupIfNeeded(@NonNull List<StoredVoucher> vouchers, @NonNull String userPrivateKey) {
        if (userPrivateKey.isBlank()) {
            throw new IllegalArgumentException("User private key cannot be blank");
        }

        log.debug("Checking {} voucher(s) for backup", vouchers.size());

        // Filter vouchers that need backup
        List<StoredVoucher> needsBackup = vouchers.stream()
                .filter(StoredVoucher::needsBackup)
                .collect(Collectors.toList());

        if (needsBackup.isEmpty()) {
            log.debug("No vouchers need backup");
            return 0;
        }

        log.info("Backing up {} of {} voucher(s)", needsBackup.size(), vouchers.size());

        // Extract SignedVoucher objects
        List<SignedVoucher> toBackup = needsBackup.stream()
                .map(StoredVoucher::getVoucher)
                .collect(Collectors.toList());

        // Perform backup
        voucherService.backup(toBackup, userPrivateKey);

        // Mark vouchers as backed up
        long backupTime = Instant.now().getEpochSecond();
        needsBackup.forEach(v -> v.setLastBackupAt(backupTime));

        log.info("Successfully backed up {} voucher(s)", needsBackup.size());
        return needsBackup.size();
    }

    /**
     * Backs up all vouchers regardless of their backup status.
     *
     * @param vouchers the list of stored vouchers to backup (must not be null)
     * @param userPrivateKey the user's private key for encryption (must not be null or blank)
     * @throws IllegalArgumentException if parameters are invalid
     * @throws RuntimeException if backup fails
     */
    public void backupAll(@NonNull List<StoredVoucher> vouchers, @NonNull String userPrivateKey) {
        if (userPrivateKey.isBlank()) {
            throw new IllegalArgumentException("User private key cannot be blank");
        }

        log.info("Backing up all {} voucher(s)", vouchers.size());

        List<SignedVoucher> toBackup = vouchers.stream()
                .map(StoredVoucher::getVoucher)
                .collect(Collectors.toList());

        voucherService.backup(toBackup, userPrivateKey);

        // Mark all as backed up
        long backupTime = Instant.now().getEpochSecond();
        vouchers.forEach(v -> v.setLastBackupAt(backupTime));

        log.info("Successfully backed up all {} voucher(s)", vouchers.size());
    }

    /**
     * Restores vouchers from backup and merges with local vouchers.
     *
     * <p>Merge strategy:
     * <ul>
     *   <li>If voucher exists in both: keep local version (user may have updated status)</li>
     *   <li>If voucher only in backup: add to result (recovery scenario)</li>
     *   <li>If voucher only local: keep in result</li>
     * </ul>
     *
     * @param localVouchers the current local vouchers (must not be null, can be empty)
     * @param userPrivateKey the user's private key for decryption (must not be null or blank)
     * @return the merged list of vouchers
     * @throws IllegalArgumentException if parameters are invalid
     * @throws RuntimeException if restore fails
     */
    public List<StoredVoucher> restoreAndMerge(
            @NonNull List<StoredVoucher> localVouchers,
            @NonNull String userPrivateKey
    ) {
        if (userPrivateKey.isBlank()) {
            throw new IllegalArgumentException("User private key cannot be blank");
        }

        log.info("Restoring vouchers and merging with {} local voucher(s)", localVouchers.size());

        // Restore from backup
        List<SignedVoucher> restored = voucherService.restore(userPrivateKey);
        log.info("Restored {} voucher(s) from backup", restored.size());

        // Convert to StoredVoucher
        List<StoredVoucher> restoredStored = restored.stream()
                .map(v -> {
                    StoredVoucher stored = StoredVoucher.from(v);
                    stored.markBackedUp(); // Mark as backed up since we just restored it
                    return stored;
                })
                .collect(Collectors.toList());

        // Merge with local vouchers
        List<StoredVoucher> merged = mergeVouchers(localVouchers, restoredStored);
        log.info("Merge complete: {} total voucher(s)", merged.size());

        return merged;
    }

    /**
     * Restores vouchers from backup without merging.
     *
     * @param userPrivateKey the user's private key for decryption (must not be null or blank)
     * @return the list of restored vouchers as StoredVoucher objects
     * @throws IllegalArgumentException if userPrivateKey is invalid
     * @throws RuntimeException if restore fails
     */
    public List<StoredVoucher> restore(@NonNull String userPrivateKey) {
        if (userPrivateKey.isBlank()) {
            throw new IllegalArgumentException("User private key cannot be blank");
        }

        log.info("Restoring vouchers from backup");

        List<SignedVoucher> restored = voucherService.restore(userPrivateKey);
        log.info("Restored {} voucher(s)", restored.size());

        return restored.stream()
                .map(v -> {
                    StoredVoucher stored = StoredVoucher.from(v);
                    stored.markBackedUp();
                    return stored;
                })
                .collect(Collectors.toList());
    }

    /**
     * Merges local and restored vouchers.
     *
     * <p>Merge logic:
     * <ul>
     *   <li>Local vouchers take precedence (may have newer status)</li>
     *   <li>Restored vouchers fill in missing gaps</li>
     *   <li>Deduplication by voucher ID</li>
     * </ul>
     *
     * @param local the local vouchers
     * @param restored the restored vouchers
     * @return the merged list
     */
    private List<StoredVoucher> mergeVouchers(
            List<StoredVoucher> local,
            List<StoredVoucher> restored
    ) {
        log.debug("Merging {} local and {} restored vouchers", local.size(), restored.size());

        // Create map of local vouchers by ID for fast lookup
        Map<String, StoredVoucher> localMap = new HashMap<>();
        for (StoredVoucher v : local) {
            localMap.put(v.getVoucherId(), v);
        }

        // Add restored vouchers that don't exist locally
        int addedCount = 0;
        for (StoredVoucher restoredVoucher : restored) {
            String voucherId = restoredVoucher.getVoucherId();
            if (!localMap.containsKey(voucherId)) {
                localMap.put(voucherId, restoredVoucher);
                addedCount++;
                log.debug("Added restored voucher: voucherId={}", voucherId);
            } else {
                log.debug("Voucher already exists locally, keeping local version: voucherId={}", voucherId);
            }
        }

        log.debug("Merge added {} new voucher(s) from backup", addedCount);

        return new ArrayList<>(localMap.values());
    }

    /**
     * Verifies backup integrity by restoring and comparing.
     *
     * <p>This method restores vouchers from backup and checks if all expected
     * vouchers are present. Useful for backup verification.
     *
     * @param expectedVoucherIds the list of voucher IDs that should be in backup
     * @param userPrivateKey the user's private key
     * @return true if all expected vouchers are in backup, false otherwise
     */
    public boolean verifyBackup(
            @NonNull List<String> expectedVoucherIds,
            @NonNull String userPrivateKey
    ) {
        if (userPrivateKey.isBlank()) {
            throw new IllegalArgumentException("User private key cannot be blank");
        }

        log.info("Verifying backup integrity: {} expected voucher(s)", expectedVoucherIds.size());

        try {
            List<SignedVoucher> restored = voucherService.restore(userPrivateKey);

            // Create set of restored voucher IDs
            Map<String, SignedVoucher> restoredMap = restored.stream()
                    .collect(Collectors.toMap(
                            v -> v.getSecret().getVoucherId(),
                            v -> v
                    ));

            // Check if all expected vouchers are present
            boolean allPresent = true;
            for (String expectedId : expectedVoucherIds) {
                if (!restoredMap.containsKey(expectedId)) {
                    log.warn("Backup verification failed: missing voucher {}", expectedId);
                    allPresent = false;
                }
            }

            if (allPresent) {
                log.info("Backup verification successful: all {} voucher(s) present", expectedVoucherIds.size());
            } else {
                log.warn("Backup verification failed: some vouchers missing");
            }

            return allPresent;
        } catch (Exception e) {
            log.error("Backup verification failed with exception", e);
            return false;
        }
    }
}
