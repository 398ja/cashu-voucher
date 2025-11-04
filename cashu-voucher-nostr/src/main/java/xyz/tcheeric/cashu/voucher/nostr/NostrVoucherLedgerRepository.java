package xyz.tcheeric.cashu.voucher.nostr;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import nostr.base.PublicKey;
import nostr.event.impl.GenericEvent;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;
import xyz.tcheeric.cashu.voucher.nostr.events.VoucherLedgerEvent;

import java.util.List;
import java.util.Optional;

/**
 * Nostr implementation of the VoucherLedgerPort for public voucher audit trail.
 *
 * <p>This adapter implements the voucher ledger using Nostr's NIP-33 (parameterized
 * replaceable events) to create a public, queryable audit trail of voucher lifecycle.
 *
 * <h3>Hexagonal Architecture</h3>
 * <p>This is an <b>adapter</b> that implements the {@link VoucherLedgerPort} defined in
 * the application layer. It translates domain operations into Nostr protocol operations.
 *
 * <h3>NIP-33 Storage Model</h3>
 * <p>Each voucher is stored as a kind 30078 parameterized replaceable event:
 * <ul>
 *   <li><b>Event ID</b>: Unique per update (hash of event content)</li>
 *   <li><b>d tag</b>: "voucher:{voucherId}" - ensures replaceability per voucher</li>
 *   <li><b>Replaceability</b>: New events with same d tag replace old ones</li>
 *   <li><b>Content</b>: JSON serialized SignedVoucher + status</li>
 *   <li><b>Tags</b>: status, amount, unit, expiry for queryability</li>
 * </ul>
 *
 * <h3>Key Features</h3>
 * <ul>
 *   <li>Public audit trail - anyone can query voucher status</li>
 *   <li>Automatic deduplication via NIP-33 replaceability</li>
 *   <li>Multi-relay redundancy for availability</li>
 *   <li>Status history via timestamped events</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * <pre>
 * // Initialize repository
 * NostrClientAdapter client = new NostrClientAdapter(relays, 5000, 3);
 * PublicKey issuerPubKey = new PublicKey("issuer_hex_pubkey");
 * NostrVoucherLedgerRepository ledger = new NostrVoucherLedgerRepository(client, issuerPubKey);
 *
 * try {
 *     client.connect();
 *
 *     // Publish new voucher
 *     SignedVoucher voucher = ...;
 *     ledger.publish(voucher, VoucherStatus.ISSUED);
 *
 *     // Query status
 *     Optional&lt;VoucherStatus&gt; status = ledger.queryStatus(voucherId);
 *
 *     // Update status
 *     ledger.updateStatus(voucherId, VoucherStatus.REDEEMED);
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
 *   <li>Serialization errors (invalid voucher data)</li>
 *   <li>Publishing failures (all relays reject event)</li>
 *   <li>Query timeouts (no response from relays)</li>
 * </ul>
 *
 * @see VoucherLedgerPort
 * @see VoucherLedgerEvent
 * @see NostrClientAdapter
 * @see <a href="https://github.com/nostr-protocol/nips/blob/master/33.md">NIP-33</a>
 */
@Slf4j
public class NostrVoucherLedgerRepository implements VoucherLedgerPort {

    private final NostrClientAdapter nostrClient;
    private final PublicKey issuerPublicKey;
    private final long publishTimeoutMs;
    private final long queryTimeoutMs;

    /**
     * Creates a NostrVoucherLedgerRepository with default timeouts.
     *
     * @param nostrClient the Nostr client adapter (must not be null)
     * @param issuerPublicKey the issuer's public key for signing events (must not be null)
     * @throws IllegalArgumentException if parameters are null
     */
    public NostrVoucherLedgerRepository(
            @NonNull NostrClientAdapter nostrClient,
            @NonNull PublicKey issuerPublicKey
    ) {
        this(nostrClient, issuerPublicKey, 5000L, 5000L);
    }

    /**
     * Creates a NostrVoucherLedgerRepository with custom timeouts.
     *
     * @param nostrClient the Nostr client adapter (must not be null)
     * @param issuerPublicKey the issuer's public key for signing events (must not be null)
     * @param publishTimeoutMs timeout for publishing events in milliseconds
     * @param queryTimeoutMs timeout for querying events in milliseconds
     * @throws IllegalArgumentException if parameters are invalid
     */
    public NostrVoucherLedgerRepository(
            @NonNull NostrClientAdapter nostrClient,
            @NonNull PublicKey issuerPublicKey,
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
        this.issuerPublicKey = issuerPublicKey;
        this.publishTimeoutMs = publishTimeoutMs;
        this.queryTimeoutMs = queryTimeoutMs;

        log.info("NostrVoucherLedgerRepository initialized: issuer={}, publishTimeout={}ms, queryTimeout={}ms",
                issuerPublicKey.toBech32String(), publishTimeoutMs, queryTimeoutMs);
    }

    /**
     * Publishes a voucher to the Nostr ledger with the given status.
     *
     * <p>This method:
     * <ol>
     *   <li>Creates a VoucherLedgerEvent (kind 30078) from the voucher</li>
     *   <li>Sets the issuer's public key</li>
     *   <li>Publishes to all connected relays</li>
     *   <li>Waits for at least one relay to confirm</li>
     * </ol>
     *
     * <p>If a voucher with the same ID already exists, NIP-33 ensures this event
     * replaces the previous one (based on created_at timestamp).
     *
     * @param voucher the signed voucher to publish (must not be null)
     * @param status the initial status (must not be null)
     * @throws IllegalArgumentException if parameters are null
     * @throws VoucherNostrException if publishing fails on all relays
     */
    @Override
    public void publish(@NonNull SignedVoucher voucher, @NonNull VoucherStatus status) {
        String voucherId = voucher.getSecret().getVoucherId();
        log.info("Publishing voucher to ledger: voucherId={}, status={}", voucherId, status);

        try {
            // Create NIP-33 event
            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, status);
            event.setPubKey(issuerPublicKey);

            // TODO: Sign event with issuer's private key
            // This will be implemented when we add key management
            // For now, the event is published unsigned (will fail on real relays)

            // Publish to relays
            boolean success = nostrClient.publishEvent(event, publishTimeoutMs);

            if (!success) {
                throw new VoucherNostrException(
                        "Failed to publish voucher to any relay: voucherId=" + voucherId);
            }

            log.info("Successfully published voucher: voucherId={}, eventId={}", voucherId, event.getId());

        } catch (VoucherNostrException e) {
            log.error("Failed to publish voucher: voucherId={}", voucherId, e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error publishing voucher: voucherId={}", voucherId, e);
            throw new VoucherNostrException("Failed to publish voucher", e);
        }
    }

    /**
     * Queries the current status of a voucher from the Nostr ledger.
     *
     * <p>This method:
     * <ol>
     *   <li>Constructs a subscription ID for the query</li>
     *   <li>Queries all connected relays for events with d tag = "voucher:{voucherId}"</li>
     *   <li>Extracts status from the most recent event (highest created_at)</li>
     * </ol>
     *
     * @param voucherId the unique voucher identifier (must not be null or blank)
     * @return the current status, or empty if voucher not found
     * @throws IllegalArgumentException if voucherId is null or blank
     * @throws VoucherNostrException if query fails
     */
    @Override
    public Optional<VoucherStatus> queryStatus(@NonNull String voucherId) {
        if (voucherId.isBlank()) {
            throw new IllegalArgumentException("Voucher ID cannot be blank");
        }

        log.debug("Querying voucher status: voucherId={}", voucherId);

        try {
            // Generate subscription ID
            String subscriptionId = "voucher_status_" + voucherId;

            // TODO: Set up proper filter for NIP-33 query
            // Filter should include:
            // - kind: 30078
            // - authors: [issuerPublicKey]
            // - #d: ["voucher:{voucherId}"]

            // Query relays
            List<GenericEvent> events = nostrClient.queryEvents(subscriptionId, queryTimeoutMs);

            if (events.isEmpty()) {
                log.debug("Voucher not found in ledger: voucherId={}", voucherId);
                return Optional.empty();
            }

            // Find most recent event
            GenericEvent mostRecent = events.stream()
                    .max((e1, e2) -> Long.compare(e1.getCreatedAt(), e2.getCreatedAt()))
                    .orElseThrow();

            // Convert to VoucherLedgerEvent and extract status
            if (mostRecent instanceof VoucherLedgerEvent ledgerEvent) {
                VoucherStatus status = ledgerEvent.getStatus();
                log.debug("Found voucher status: voucherId={}, status={}", voucherId, status);
                return Optional.of(status);
            } else {
                // Event is not a VoucherLedgerEvent - try to parse it
                log.warn("Received non-VoucherLedgerEvent, attempting to parse: voucherId={}", voucherId);
                // TODO: Implement parsing logic
                return Optional.empty();
            }

        } catch (VoucherNostrException e) {
            log.error("Failed to query voucher status: voucherId={}", voucherId, e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error querying voucher status: voucherId={}", voucherId, e);
            throw new VoucherNostrException("Failed to query voucher status", e);
        }
    }

    /**
     * Updates the status of an existing voucher in the Nostr ledger.
     *
     * <p>This method:
     * <ol>
     *   <li>Queries the current voucher from the ledger</li>
     *   <li>Creates a new VoucherLedgerEvent with updated status</li>
     *   <li>Publishes the new event (replaces old via NIP-33)</li>
     * </ol>
     *
     * <p>Note: This requires retrieving the full voucher first, so we can re-publish
     * it with the new status. The NIP-33 d tag ensures the new event replaces the old.
     *
     * @param voucherId the unique voucher identifier (must not be null or blank)
     * @param newStatus the new status to set (must not be null)
     * @throws IllegalArgumentException if parameters are invalid
     * @throws VoucherNostrException if update fails or voucher not found
     */
    @Override
    public void updateStatus(@NonNull String voucherId, @NonNull VoucherStatus newStatus) {
        if (voucherId.isBlank()) {
            throw new IllegalArgumentException("Voucher ID cannot be blank");
        }

        log.info("Updating voucher status: voucherId={}, newStatus={}", voucherId, newStatus);

        try {
            // Query full voucher
            Optional<SignedVoucher> voucherOpt = queryVoucher(voucherId);

            if (voucherOpt.isEmpty()) {
                throw new VoucherNostrException("Voucher not found in ledger: " + voucherId);
            }

            SignedVoucher voucher = voucherOpt.get();

            // Publish with new status (NIP-33 will replace old event)
            publish(voucher, newStatus);

            log.info("Successfully updated voucher status: voucherId={}, newStatus={}", voucherId, newStatus);

        } catch (VoucherNostrException e) {
            log.error("Failed to update voucher status: voucherId={}", voucherId, e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error updating voucher status: voucherId={}", voucherId, e);
            throw new VoucherNostrException("Failed to update voucher status", e);
        }
    }

    /**
     * Queries the full voucher details from the Nostr ledger.
     *
     * <p>This method retrieves the complete SignedVoucher from the event content.
     * It's used internally by {@link #updateStatus(String, VoucherStatus)} to get
     * the voucher before republishing with new status.
     *
     * @param voucherId the unique voucher identifier (must not be null or blank)
     * @return the complete voucher, or empty if not found
     * @throws IllegalArgumentException if voucherId is null or blank
     * @throws VoucherNostrException if query fails or deserialization fails
     */
    @Override
    public Optional<SignedVoucher> queryVoucher(@NonNull String voucherId) {
        if (voucherId.isBlank()) {
            throw new IllegalArgumentException("Voucher ID cannot be blank");
        }

        log.debug("Querying full voucher: voucherId={}", voucherId);

        try {
            // Generate subscription ID
            String subscriptionId = "voucher_full_" + voucherId;

            // TODO: Set up proper filter for NIP-33 query
            // Filter should include:
            // - kind: 30078
            // - authors: [issuerPublicKey]
            // - #d: ["voucher:{voucherId}"]

            // Query relays
            List<GenericEvent> events = nostrClient.queryEvents(subscriptionId, queryTimeoutMs);

            if (events.isEmpty()) {
                log.debug("Voucher not found in ledger: voucherId={}", voucherId);
                return Optional.empty();
            }

            // Find most recent event
            GenericEvent mostRecent = events.stream()
                    .max((e1, e2) -> Long.compare(e1.getCreatedAt(), e2.getCreatedAt()))
                    .orElseThrow();

            // Convert to VoucherLedgerEvent and extract voucher
            if (mostRecent instanceof VoucherLedgerEvent ledgerEvent) {
                SignedVoucher voucher = ledgerEvent.toVoucher();
                log.debug("Found voucher in ledger: voucherId={}", voucherId);
                return Optional.of(voucher);
            } else {
                // Event is not a VoucherLedgerEvent - try to parse it
                log.warn("Received non-VoucherLedgerEvent, attempting to parse: voucherId={}", voucherId);
                // TODO: Implement parsing logic
                return Optional.empty();
            }

        } catch (VoucherNostrException e) {
            log.error("Failed to query voucher: voucherId={}", voucherId, e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error querying voucher: voucherId={}", voucherId, e);
            throw new VoucherNostrException("Failed to query voucher", e);
        }
    }

    /**
     * Gets the issuer's public key used for signing events.
     *
     * @return the issuer's public key
     */
    public PublicKey getIssuerPublicKey() {
        return issuerPublicKey;
    }

    /**
     * Gets the underlying Nostr client adapter.
     *
     * @return the Nostr client adapter
     */
    public NostrClientAdapter getNostrClient() {
        return nostrClient;
    }
}
