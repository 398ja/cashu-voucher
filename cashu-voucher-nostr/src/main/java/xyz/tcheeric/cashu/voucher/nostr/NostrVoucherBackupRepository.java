package xyz.tcheeric.cashu.voucher.nostr;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import nostr.event.impl.GenericEvent;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherBackupPort;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.nostr.events.VoucherBackupPayload;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Nostr implementation of the VoucherBackupPort for private voucher backups.
 *
 * <p>This adapter implements voucher backup and restore using Nostr's NIP-17
 * (private direct messages) with NIP-44 (versioned encryption) to create secure,
 * private, recoverable voucher backups.
 *
 * <h3>Hexagonal Architecture</h3>
 * <p>This is an <b>adapter</b> that implements the {@link VoucherBackupPort} defined in
 * the application layer. It translates domain backup operations into Nostr protocol operations.
 *
 * <h3>NIP-17 + NIP-44 Backup Model</h3>
 * <p>Voucher backups are stored as encrypted direct messages to self:
 * <ul>
 *   <li><b>Event Kind</b>: 4 (encrypted direct message)</li>
 *   <li><b>Encryption</b>: NIP-44 versioned encryption (XChaCha20-Poly1305)</li>
 *   <li><b>Recipient</b>: User's own public key (self-addressed)</li>
 *   <li><b>Content</b>: Encrypted JSON array of SignedVoucher objects</li>
 *   <li><b>Tags</b>: "p" (recipient pubkey), "backup" (identification tag)</li>
 * </ul>
 *
 * <h3>Key Features</h3>
 * <ul>
 *   <li><b>Privacy</b>: Only user can decrypt their own backups</li>
 *   <li><b>Redundancy</b>: Multi-relay storage for availability</li>
 *   <li><b>Recovery</b>: Restore vouchers from any device with user's private key</li>
 *   <li><b>Deduplication</b>: Automatic merging of multiple backups by voucher ID</li>
 *   <li><b>Incremental</b>: Each backup creates a new event (append-only)</li>
 * </ul>
 *
 * <h3>Why This Matters</h3>
 * <p>Unlike deterministic secrets (NUT-13), vouchers are <b>non-deterministic</b>.
 * If a user loses their wallet, they cannot regenerate voucher secrets from a seed
 * phrase. This backup mechanism ensures vouchers can always be recovered.
 *
 * <h3>Usage Example</h3>
 * <pre>
 * // Initialize repository
 * NostrClientAdapter client = new NostrClientAdapter(relays, 5000, 3);
 * NostrVoucherBackupRepository backup = new NostrVoucherBackupRepository(client);
 *
 * try {
 *     client.connect();
 *
 *     // Backup vouchers
 *     List&lt;SignedVoucher&gt; vouchers = wallet.getVouchers();
 *     backup.backup(vouchers, userNostrPrivateKey);
 *
 *     // Restore vouchers (e.g., after device loss)
 *     List&lt;SignedVoucher&gt; restored = backup.restore(userNostrPrivateKey);
 *     wallet.addVouchers(restored);
 *
 * } finally {
 *     client.disconnect();
 * }
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is thread-safe as it delegates to the thread-safe {@link NostrClientAdapter}.
 *
 * <h3>Error Handling</h3>
 * <p>All methods may throw {@link VoucherNostrException} for:
 * <ul>
 *   <li>Network failures (relay unreachable)</li>
 *   <li>Encryption/decryption errors (invalid key, corrupted data)</li>
 *   <li>Serialization errors (invalid voucher data)</li>
 *   <li>Publishing failures (all relays reject event)</li>
 *   <li>Query timeouts (no response from relays)</li>
 * </ul>
 *
 * @see VoucherBackupPort
 * @see VoucherBackupPayload
 * @see NostrClientAdapter
 * @see <a href="https://github.com/nostr-protocol/nips/blob/master/17.md">NIP-17</a>
 * @see <a href="https://github.com/nostr-protocol/nips/blob/master/44.md">NIP-44</a>
 */
@Slf4j
public class NostrVoucherBackupRepository implements VoucherBackupPort {

    private final NostrClientAdapter nostrClient;
    private final long publishTimeoutMs;
    private final long queryTimeoutMs;

    /**
     * Creates a NostrVoucherBackupRepository with default timeouts.
     *
     * @param nostrClient the Nostr client adapter (must not be null)
     * @throws IllegalArgumentException if nostrClient is null
     */
    public NostrVoucherBackupRepository(@NonNull NostrClientAdapter nostrClient) {
        this(nostrClient, 5000L, 5000L);
    }

    /**
     * Creates a NostrVoucherBackupRepository with custom timeouts.
     *
     * @param nostrClient the Nostr client adapter (must not be null)
     * @param publishTimeoutMs timeout for publishing backup events in milliseconds
     * @param queryTimeoutMs timeout for querying backup events in milliseconds
     * @throws IllegalArgumentException if parameters are invalid
     */
    public NostrVoucherBackupRepository(
            @NonNull NostrClientAdapter nostrClient,
            long publishTimeoutMs,
            long queryTimeoutMs
    ) {
        if (publishTimeoutMs <= 0) {
            throw new IllegalArgumentException("Publish timeout must be positive");
        }
        if (queryTimeoutMs <= 0) {
            throw new IllegalArgumentException("Query timeout must be positive");
        }

        this.nostrClient = nostrClient;
        this.publishTimeoutMs = publishTimeoutMs;
        this.queryTimeoutMs = queryTimeoutMs;

        log.info("NostrVoucherBackupRepository initialized: publishTimeout={}ms, queryTimeout={}ms",
                publishTimeoutMs, queryTimeoutMs);
    }

    /**
     * Backs up vouchers to Nostr using encrypted DMs to self.
     *
     * <p>This method:
     * <ol>
     *   <li>Validates the private key format</li>
     *   <li>Derives the public key from private key</li>
     *   <li>Creates a VoucherBackupPayload with all vouchers</li>
     *   <li>Encrypts payload with NIP-44 (XChaCha20-Poly1305)</li>
     *   <li>Creates kind 4 event (encrypted DM to self)</li>
     *   <li>Publishes to all connected relays</li>
     * </ol>
     *
     * <p>Each backup creates a new event. Multiple backups are merged during restore.
     * This is an append-only operation - old backups are not deleted.
     *
     * @param vouchers the list of vouchers to backup (must not be null, can be empty)
     * @param userPrivateKey the user's Nostr private key in hex format (must not be null or blank)
     * @throws IllegalArgumentException if parameters are invalid
     * @throws VoucherNostrException if backup fails (encryption, publishing, etc.)
     */
    @Override
    public void backup(@NonNull List<SignedVoucher> vouchers, @NonNull String userPrivateKey) {
        if (userPrivateKey.isBlank()) {
            throw new IllegalArgumentException("User private key cannot be blank");
        }

        log.info("Backing up {} voucher(s) to Nostr", vouchers.size());

        try {
            // Derive public key from private key
            String userPublicKey = derivePublicKey(userPrivateKey);

            // Create encrypted backup event
            GenericEvent backupEvent = VoucherBackupPayload.createBackupEvent(
                    vouchers,
                    userPrivateKey,
                    userPublicKey
            );

            // TODO: Sign event with user's private key
            // This will be implemented when we add key management
            // For now, the event is published unsigned (will fail on real relays)

            // Publish to relays
            boolean success = nostrClient.publishEvent(backupEvent, publishTimeoutMs);

            if (!success) {
                throw new VoucherNostrException(
                        "Failed to publish backup to any relay: " + vouchers.size() + " vouchers");
            }

            log.info("Successfully backed up {} voucher(s) to Nostr: eventId={}",
                    vouchers.size(), backupEvent.getId());

        } catch (VoucherNostrException e) {
            log.error("Failed to backup vouchers: count={}", vouchers.size(), e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error backing up vouchers: count={}", vouchers.size(), e);
            throw new VoucherNostrException("Failed to backup vouchers", e);
        }
    }

    /**
     * Restores vouchers from Nostr backup events.
     *
     * <p>This method:
     * <ol>
     *   <li>Validates the private key format</li>
     *   <li>Derives the public key from private key</li>
     *   <li>Queries all encrypted DM events to self with "backup" tag</li>
     *   <li>Decrypts each event using NIP-44</li>
     *   <li>Extracts vouchers from each backup</li>
     *   <li>Merges and deduplicates by voucher ID (newest timestamp wins)</li>
     *   <li>Returns the complete list of unique vouchers</li>
     * </ol>
     *
     * <p>If multiple backups exist for the same voucher ID, the one with the
     * latest backup timestamp is used. This handles the case where a voucher's
     * status may have changed between backups.
     *
     * @param userPrivateKey the user's Nostr private key in hex format (must not be null or blank)
     * @return list of restored vouchers (never null, may be empty if no backups found)
     * @throws IllegalArgumentException if userPrivateKey is invalid
     * @throws VoucherNostrException if restore fails (decryption, network, etc.)
     */
    @Override
    public List<SignedVoucher> restore(@NonNull String userPrivateKey) {
        if (userPrivateKey.isBlank()) {
            throw new IllegalArgumentException("User private key cannot be blank");
        }

        log.info("Restoring vouchers from Nostr backups");

        try {
            // Derive public key from private key
            String userPublicKey = derivePublicKey(userPrivateKey);

            // Generate subscription ID for backup query
            String subscriptionId = "voucher_backup_" + userPublicKey.substring(0, 8);

            // TODO: Set up proper filter for backup events query
            // Filter should include:
            // - kind: 4 (encrypted DM)
            // - authors: [userPublicKey]
            // - #p: [userPublicKey] (self-addressed)
            // - #backup: ["vouchers"]

            // Query all backup events
            List<GenericEvent> backupEvents = nostrClient.queryEvents(subscriptionId, queryTimeoutMs);

            log.debug("Found {} backup event(s) from relays", backupEvents.size());

            if (backupEvents.isEmpty()) {
                log.info("No backup events found");
                return Collections.emptyList();
            }

            // Decrypt and extract vouchers from each backup
            Map<String, VoucherWithTimestamp> voucherMap = new HashMap<>();

            for (GenericEvent event : backupEvents) {
                if (!VoucherBackupPayload.isValidBackupEvent(event)) {
                    log.warn("Skipping invalid backup event: eventId={}", event.getId());
                    continue;
                }

                try {
                    List<SignedVoucher> vouchers = VoucherBackupPayload.extractVouchers(
                            event,
                            userPrivateKey,
                            userPublicKey
                    );

                    long eventTimestamp = event.getCreatedAt();

                    // Merge into map (latest timestamp wins)
                    for (SignedVoucher voucher : vouchers) {
                        String voucherId = voucher.getSecret().getVoucherId() != null
                                ? voucher.getSecret().getVoucherId().toString() : null;

                        VoucherWithTimestamp existing = voucherMap.get(voucherId);
                        if (existing == null || eventTimestamp > existing.timestamp) {
                            voucherMap.put(voucherId, new VoucherWithTimestamp(voucher, eventTimestamp));
                            log.debug("Added/updated voucher: voucherId={}, timestamp={}",
                                    voucherId, eventTimestamp);
                        } else {
                            log.debug("Skipped older voucher: voucherId={}, timestamp={}",
                                    voucherId, eventTimestamp);
                        }
                    }

                } catch (VoucherNostrException e) {
                    log.error("Failed to decrypt backup event: eventId={}", event.getId(), e);
                    // Continue with other events - partial recovery is better than none
                }
            }

            // Extract vouchers from map
            List<SignedVoucher> restoredVouchers = voucherMap.values().stream()
                    .map(vwt -> vwt.voucher)
                    .collect(Collectors.toList());

            log.info("Restored {} unique voucher(s) from {} backup event(s)",
                    restoredVouchers.size(), backupEvents.size());

            return restoredVouchers;

        } catch (VoucherNostrException e) {
            log.error("Failed to restore vouchers", e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error restoring vouchers", e);
            throw new VoucherNostrException("Failed to restore vouchers", e);
        }
    }

    /**
     * Checks if backups exist for the given user.
     *
     * <p>This implementation queries Nostr relays for backup events without
     * decrypting them, providing a faster check than full restore.
     *
     * @param userPrivateKey the user's Nostr private key (must not be null or blank)
     * @return true if at least one backup event exists, false otherwise
     * @throws IllegalArgumentException if userPrivateKey is invalid
     * @throws VoucherNostrException if check fails (network, etc.)
     */
    @Override
    public boolean hasBackups(@NonNull String userPrivateKey) {
        if (userPrivateKey.isBlank()) {
            throw new IllegalArgumentException("User private key cannot be blank");
        }

        log.debug("Checking for existing backups");

        try {
            // Derive public key from private key
            String userPublicKey = derivePublicKey(userPrivateKey);

            // Generate subscription ID for backup query
            String subscriptionId = "voucher_backup_check_" + userPublicKey.substring(0, 8);

            // TODO: Set up proper filter for backup events query
            // Query with limit=1 for efficiency

            // Query backup events
            List<GenericEvent> backupEvents = nostrClient.queryEvents(subscriptionId, queryTimeoutMs);

            boolean hasBackups = !backupEvents.isEmpty();
            log.debug("Has backups: {}", hasBackups);

            return hasBackups;

        } catch (Exception e) {
            log.error("Error checking for backups", e);
            throw new VoucherNostrException("Failed to check for backups", e);
        }
    }

    /**
     * Derives the public key from a private key.
     *
     * <p>This is a placeholder implementation. In production, this would use
     * proper Nostr key derivation (secp256k1).
     *
     * @param privateKey the private key in hex format
     * @return the derived public key in hex format
     * @throws VoucherNostrException if key derivation fails
     */
    private String derivePublicKey(String privateKey) {
        // TODO: Implement proper secp256k1 public key derivation
        // For now, return a placeholder
        // This should use: PublicKey.fromPrivateKey(PrivateKey.fromHex(privateKey))

        log.debug("Deriving public key from private key (placeholder)");

        // Temporary placeholder - this will fail in real usage
        // Real implementation needs secp256k1 curve operations
        throw new VoucherNostrException(
                "Public key derivation not yet implemented. " +
                "Please provide both private and public keys explicitly.");
    }

    /**
     * Gets the underlying Nostr client adapter.
     *
     * @return the Nostr client adapter
     */
    public NostrClientAdapter getNostrClient() {
        return nostrClient;
    }

    /**
     * Helper class to track vouchers with their backup timestamps.
     */
    private static class VoucherWithTimestamp {
        final SignedVoucher voucher;
        final long timestamp;

        VoucherWithTimestamp(SignedVoucher voucher, long timestamp) {
            this.voucher = voucher;
            this.timestamp = timestamp;
        }
    }
}
