package xyz.tcheeric.cashu.voucher.domain;

/**
 * Backing strategy for voucher token amounts.
 *
 * <p>The backing strategy determines how many sats back a voucher and whether
 * it can be split peer-to-peer. The merchant chooses the strategy at issuance
 * based on the voucher's use case.
 *
 * <h3>Strategy Comparison</h3>
 * <table>
 *   <tr><th>Strategy</th><th>Token Amount</th><th>Splittable</th><th>Use Case</th></tr>
 *   <tr><td>FIXED</td><td>Constant (e.g., 1 sat)</td><td>No</td><td>Tickets, passes</td></tr>
 *   <tr><td>MINIMAL</td><td>Constant (e.g., 10 sats)</td><td>Yes (coarse)</td><td>Gift cards</td></tr>
 *   <tr><td>PROPORTIONAL</td><td>Scaled to face value</td><td>Yes (fine)</td><td>Split payments</td></tr>
 * </table>
 *
 * @see VoucherSecret
 */
public enum BackingStrategy {

    /**
     * Fixed minimal sat backing for non-splittable vouchers.
     *
     * <p>Use for tickets, event passes, and single-use vouchers that must be
     * redeemed whole. The token amount is a configurable constant (default: 1 sat).
     *
     * <p>Splitting is disabled - attempting to split a FIXED voucher should fail.
     */
    FIXED,

    /**
     * Low constant sat backing for occasional splits.
     *
     * <p>Use for gift cards, loyalty points, and store credit where splits are
     * occasional. The token amount is a configurable constant (default: 10 sats).
     *
     * <p>Splitting is possible but with coarse granularity (limited split points).
     */
    MINIMAL,

    /**
     * Proportional sat backing scaled to face value for precise splits.
     *
     * <p>Use for splittable vouchers, shared payments, and group gifts where
     * precise division is needed. Token amount scales with face value
     * (default: 1 sat per minor unit, e.g., 1 sat per cent for EUR).
     *
     * <p>Splitting supports fine granularity (cent-level precision).
     */
    PROPORTIONAL;

    /**
     * Returns true if this strategy allows splitting.
     *
     * @return true for MINIMAL and PROPORTIONAL, false for FIXED
     */
    public boolean isSplittable() {
        return this != FIXED;
    }

    /**
     * Returns true if this strategy supports fine-grained splits.
     *
     * @return true only for PROPORTIONAL
     */
    public boolean hasFineGrainedSplits() {
        return this == PROPORTIONAL;
    }
}