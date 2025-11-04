package xyz.tcheeric.cashu.voucher.app.ports;

import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.util.Optional;

/**
 * Port for voucher ledger operations (public audit trail).
 *
 * <p>This port defines the interface for publishing and querying voucher status
 * on a public ledger. In the Nostr implementation, this uses NIP-33 (parameterized
 * replaceable events) to maintain a public audit trail of voucher lifecycle.
 *
 * <h3>Hexagonal Architecture</h3>
 * <p>This is a <b>port</b> (interface) in hexagonal architecture terminology.
 * The actual implementation (adapter) is provided by the infrastructure layer
 * (e.g., NostrVoucherLedgerRepository in cashu-voucher-nostr module).
 *
 * <h3>Design Principles</h3>
 * <ul>
 *   <li><b>Infrastructure-agnostic</b>: No dependencies on Nostr or any specific storage</li>
 *   <li><b>Pluggable</b>: Can be implemented with Nostr, SQL, IPFS, etc.</li>
 *   <li><b>Testable</b>: Easy to mock for unit testing application services</li>
 * </ul>
 *
 * <h3>Model B Constraint</h3>
 * <p>The ledger tracks vouchers that are only redeemable at the issuing merchant.
 * Status transitions enforce Model B business rules (no mint redemption).
 *
 * <h3>Example Usage</h3>
 * <pre>
 * // Publishing a new voucher
 * SignedVoucher voucher = ...;
 * ledgerPort.publish(voucher, VoucherStatus.ISSUED);
 *
 * // Checking voucher status
 * Optional&lt;VoucherStatus&gt; status = ledgerPort.queryStatus(voucherId);
 * if (status.isPresent() && status.get() == VoucherStatus.ISSUED) {
 *     // Voucher is valid for redemption
 * }
 *
 * // Marking voucher as redeemed
 * ledgerPort.updateStatus(voucherId, VoucherStatus.REDEEMED);
 * </pre>
 *
 * @see VoucherBackupPort
 * @see xyz.tcheeric.cashu.voucher.domain.SignedVoucher
 * @see xyz.tcheeric.cashu.voucher.domain.VoucherStatus
 */
public interface VoucherLedgerPort {

    /**
     * Publishes a voucher to the public ledger with the given status.
     *
     * <p>This creates a new ledger entry for the voucher. If a voucher with the
     * same ID already exists, the behavior depends on the implementation:
     * <ul>
     *   <li>NIP-33 (Nostr): Replaces the previous entry (replaceable event)</li>
     *   <li>SQL: May throw exception or update existing record</li>
     * </ul>
     *
     * <p>The voucher's signature should be verified before publishing, but this
     * method does not perform validation (separation of concerns).
     *
     * @param voucher the signed voucher to publish (must not be null)
     * @param status the initial status (typically ISSUED, must not be null)
     * @throws IllegalArgumentException if parameters are invalid
     * @throws RuntimeException if publishing fails (network, storage, etc.)
     */
    void publish(SignedVoucher voucher, VoucherStatus status);

    /**
     * Queries the current status of a voucher from the ledger.
     *
     * <p>Returns the most recent status of the voucher as recorded in the ledger.
     * If the voucher does not exist in the ledger, returns {@link Optional#empty()}.
     *
     * <p>This is a read-only operation and does not modify the ledger state.
     *
     * @param voucherId the unique voucher identifier (must not be null or blank)
     * @return the current status, or empty if voucher not found
     * @throws IllegalArgumentException if voucherId is null or blank
     * @throws RuntimeException if query fails (network, storage, etc.)
     */
    Optional<VoucherStatus> queryStatus(String voucherId);

    /**
     * Updates the status of an existing voucher in the ledger.
     *
     * <p>This method changes the voucher's status, recording a state transition
     * in the public ledger. Common transitions:
     * <ul>
     *   <li>ISSUED → REDEEMED (merchant accepts voucher)</li>
     *   <li>ISSUED → REVOKED (issuer cancels voucher)</li>
     *   <li>ISSUED → EXPIRED (time-based expiry)</li>
     * </ul>
     *
     * <p>Terminal states (REDEEMED, REVOKED, EXPIRED) should not be changed
     * once set, but this is enforced at the service layer, not by this port.
     *
     * @param voucherId the unique voucher identifier (must not be null or blank)
     * @param newStatus the new status to set (must not be null)
     * @throws IllegalArgumentException if parameters are invalid
     * @throws RuntimeException if update fails (network, storage, voucher not found, etc.)
     */
    void updateStatus(String voucherId, VoucherStatus newStatus);

    /**
     * Checks if a voucher exists in the ledger.
     *
     * <p>This is a convenience method equivalent to:
     * <pre>queryStatus(voucherId).isPresent()</pre>
     *
     * @param voucherId the unique voucher identifier (must not be null or blank)
     * @return true if the voucher exists in the ledger, false otherwise
     * @throws IllegalArgumentException if voucherId is null or blank
     * @throws RuntimeException if check fails (network, storage, etc.)
     */
    default boolean exists(String voucherId) {
        return queryStatus(voucherId).isPresent();
    }

    /**
     * Queries the full voucher details from the ledger.
     *
     * <p>This retrieves both the voucher secret and its current status from the
     * ledger. This is useful for validation and verification operations.
     *
     * <p>Note: This is an optional operation. Implementations may not support
     * retrieving full voucher details from the ledger (e.g., if only status
     * transitions are stored). Default implementation throws UnsupportedOperationException.
     *
     * @param voucherId the unique voucher identifier (must not be null or blank)
     * @return the complete voucher with current status, or empty if not found
     * @throws UnsupportedOperationException if implementation doesn't support full retrieval
     * @throws IllegalArgumentException if voucherId is null or blank
     * @throws RuntimeException if query fails (network, storage, etc.)
     */
    default Optional<SignedVoucher> queryVoucher(String voucherId) {
        throw new UnsupportedOperationException(
                "This implementation does not support retrieving full voucher details from ledger");
    }
}
