package xyz.tcheeric.cashu.voucher.app.ports;

import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;

import java.util.List;

/**
 * Port for voucher backup operations (private user storage).
 *
 * <p>This port defines the interface for backing up and restoring vouchers to/from
 * private user storage. In the Nostr implementation, this uses NIP-17 (private
 * direct messages) with NIP-44 (versioned encryption) for secure, private backup.
 *
 * <h3>Hexagonal Architecture</h3>
 * <p>This is a <b>port</b> (interface) in hexagonal architecture terminology.
 * The actual implementation (adapter) is provided by the infrastructure layer
 * (e.g., NostrVoucherBackupRepository in cashu-voucher-nostr module).
 *
 * <h3>Design Principles</h3>
 * <ul>
 *   <li><b>Infrastructure-agnostic</b>: No dependencies on Nostr or any specific storage</li>
 *   <li><b>Pluggable</b>: Can be implemented with Nostr, IPFS, encrypted cloud storage, etc.</li>
 *   <li><b>Privacy-first</b>: Backups are private to the user (encrypted with user's key)</li>
 *   <li><b>Testable</b>: Easy to mock for unit testing application services</li>
 * </ul>
 *
 * <h3>Why Backup is Needed</h3>
 * <p>Unlike deterministic secrets (NUT-13), vouchers are <b>non-deterministic</b>.
 * If a user loses their wallet, they cannot regenerate voucher secrets from a seed
 * phrase. Therefore, vouchers MUST be backed up to recoverable storage.
 *
 * <h3>Security Considerations</h3>
 * <ul>
 *   <li>Backups are encrypted with the user's Nostr private key (or equivalent)</li>
 *   <li>Only the user can decrypt their own voucher backups</li>
 *   <li>The backup port does not handle encryption - that's the adapter's responsibility</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>
 * // Backing up vouchers
 * List&lt;SignedVoucher&gt; vouchers = wallet.getVouchers();
 * backupPort.backup(vouchers, userNostrPrivateKey);
 *
 * // Restoring vouchers after wallet loss
 * List&lt;SignedVoucher&gt; restored = backupPort.restore(userNostrPrivateKey);
 * wallet.addVouchers(restored);
 * </pre>
 *
 * @see VoucherLedgerPort
 * @see xyz.tcheeric.cashu.voucher.domain.SignedVoucher
 */
public interface VoucherBackupPort {

    /**
     * Backs up a list of vouchers to private user storage.
     *
     * <p>This method encrypts and stores the vouchers in a way that only the
     * user can retrieve them using their private key. The backup is typically
     * incremental - calling this multiple times may append to or replace
     * previous backups depending on the implementation.
     *
     * <p>Backup format considerations:
     * <ul>
     *   <li>Vouchers should be serialized in a stable format</li>
     *   <li>Backup should include metadata (timestamp, version)</li>
     *   <li>Encryption should use strong, modern algorithms (e.g., NIP-44)</li>
     * </ul>
     *
     * <h4>Implementation Notes</h4>
     * <p>Nostr implementation (NIP-17 + NIP-44):
     * <ul>
     *   <li>Creates encrypted DM to self with voucher data</li>
     *   <li>Uses NIP-44 versioned encryption</li>
     *   <li>Tags with "cashu-voucher-backup" for easy retrieval</li>
     * </ul>
     *
     * @param vouchers the list of vouchers to backup (must not be null, can be empty)
     * @param userPrivateKey the user's private key for encryption (format depends on implementation)
     * @throws IllegalArgumentException if parameters are invalid
     * @throws RuntimeException if backup fails (network, storage, encryption, etc.)
     */
    void backup(List<SignedVoucher> vouchers, String userPrivateKey);

    /**
     * Restores vouchers from private user storage.
     *
     * <p>This method retrieves and decrypts all voucher backups associated with
     * the user's private key. If multiple backups exist, they are merged and
     * deduplicated (by voucher ID, with newest timestamp winning).
     *
     * <p>The returned list includes all vouchers that have ever been backed up,
     * regardless of their current status. The caller is responsible for:
     * <ul>
     *   <li>Checking voucher status against the public ledger</li>
     *   <li>Filtering out redeemed/expired vouchers if desired</li>
     *   <li>Handling conflicts with existing wallet state</li>
     * </ul>
     *
     * <h4>Implementation Notes</h4>
     * <p>Nostr implementation:
     * <ul>
     *   <li>Queries all NIP-17 DMs tagged "cashu-voucher-backup"</li>
     *   <li>Decrypts each using NIP-44</li>
     *   <li>Merges and deduplicates by voucher ID</li>
     * </ul>
     *
     * @param userPrivateKey the user's private key for decryption (format depends on implementation)
     * @return list of restored vouchers (never null, but may be empty if no backups found)
     * @throws IllegalArgumentException if userPrivateKey is invalid
     * @throws RuntimeException if restore fails (network, storage, decryption, etc.)
     */
    List<SignedVoucher> restore(String userPrivateKey);

    /**
     * Checks if backups exist for the given user.
     *
     * <p>This is a convenience method to check if any voucher backups are
     * available without actually downloading and decrypting them.
     *
     * <p>Default implementation calls {@link #restore(String)} and checks if
     * the result is non-empty. Implementations may override with a more
     * efficient check.
     *
     * @param userPrivateKey the user's private key (format depends on implementation)
     * @return true if at least one backup exists, false otherwise
     * @throws IllegalArgumentException if userPrivateKey is invalid
     * @throws RuntimeException if check fails (network, storage, etc.)
     */
    default boolean hasBackups(String userPrivateKey) {
        return !restore(userPrivateKey).isEmpty();
    }

    /**
     * Deletes all backups for the given user.
     *
     * <p>This is an optional operation. Some implementations may not support
     * deletion (e.g., immutable storage like Nostr relays). Default implementation
     * throws UnsupportedOperationException.
     *
     * <p><b>Warning:</b> This operation is destructive and cannot be undone.
     * Use with caution.
     *
     * @param userPrivateKey the user's private key (format depends on implementation)
     * @throws UnsupportedOperationException if implementation doesn't support deletion
     * @throws IllegalArgumentException if userPrivateKey is invalid
     * @throws RuntimeException if deletion fails (network, storage, etc.)
     */
    default void deleteBackups(String userPrivateKey) {
        throw new UnsupportedOperationException(
                "This implementation does not support backup deletion");
    }
}
