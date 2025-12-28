package xyz.tcheeric.cashu.voucher.domain;

/**
 * Represents the lifecycle status of a voucher.
 *
 * <p>Voucher status transitions follow this typical flow:
 * <pre>
 *     ISSUED → REDEEMED
 *     ISSUED → REVOKED
 *     ISSUED → EXPIRED (time-based transition)
 * </pre>
 *
 * <h3>Status Descriptions</h3>
 * <ul>
 *   <li><b>ISSUED</b>: Voucher has been created and published to Nostr ledger, ready for redemption</li>
 *   <li><b>REDEEMED</b>: Voucher has been successfully redeemed by the merchant (terminal state)</li>
 *   <li><b>REVOKED</b>: Voucher has been revoked by the issuer before redemption (terminal state)</li>
 *   <li><b>EXPIRED</b>: Voucher's expiry time has passed without redemption (terminal state)</li>
 * </ul>
 *
 * <h3>Model B Constraints</h3>
 * <p>In Model B, vouchers can only be redeemed (for goods/services) at the issuing merchant.
 * Swaps at the mint are allowed (essential for P2P transfers and double-spend prevention).
 * Model B enforcement happens at the application layer (merchant verification), not the mint.
 *
 * @see VoucherSecret
 * @see SignedVoucher
 * @see VoucherValidator
 */
public enum VoucherStatus {

    /**
     * Voucher has been issued and published to the Nostr ledger.
     * It is ready for redemption by the holder at the issuing merchant.
     */
    ISSUED,

    /**
     * Voucher has been successfully redeemed by the merchant.
     * This is a terminal state - the voucher cannot be reused.
     */
    REDEEMED,

    /**
     * Voucher has been revoked by the issuer before redemption.
     * This is a terminal state - the voucher can no longer be redeemed.
     */
    REVOKED,

    /**
     * Voucher's expiry time has passed without being redeemed.
     * This is a terminal state - the voucher can no longer be redeemed.
     */
    EXPIRED;

    /**
     * Checks if this status represents a terminal state.
     *
     * <p>Terminal states are: REDEEMED, REVOKED, EXPIRED
     *
     * @return true if this is a terminal state, false otherwise
     */
    public boolean isTerminal() {
        return this == REDEEMED || this == REVOKED || this == EXPIRED;
    }

    /**
     * Checks if this status allows the voucher to be redeemed.
     *
     * <p>Only vouchers in ISSUED status can be redeemed.
     *
     * @return true if redemption is allowed, false otherwise
     */
    public boolean canBeRedeemed() {
        return this == ISSUED;
    }

    /**
     * Returns a human-readable description of this status.
     *
     * @return status description
     */
    public String getDescription() {
        return switch (this) {
            case ISSUED -> "Voucher is active and ready for redemption";
            case REDEEMED -> "Voucher has been redeemed and cannot be reused";
            case REVOKED -> "Voucher has been revoked by the issuer";
            case EXPIRED -> "Voucher has expired and can no longer be redeemed";
        };
    }
}
