package xyz.tcheeric.cashu.voucher.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for redeeming a voucher (Model B - merchant side).
 *
 * <p>This DTO is used by merchants to redeem vouchers presented by customers.
 * In Model B, vouchers can only be redeemed at the issuing merchant, not at the mint.
 *
 * <h3>Usage Example</h3>
 * <pre>
 * // Customer presents voucher token
 * RedeemVoucherRequest request = RedeemVoucherRequest.builder()
 *     .token("cashuA...") // The voucher token
 *     .merchantId("merchant123")
 *     .build();
 *
 * RedeemVoucherResponse response = merchantService.redeem(request);
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedeemVoucherRequest {

    /**
     * The voucher token presented by the customer.
     * Format: "cashuA..." (Cashu v4 token format)
     */
    private String token;

    /**
     * The merchant ID attempting to redeem the voucher.
     * Must match the voucher's issuerId for successful redemption (Model B).
     */
    private String merchantId;

    /**
     * Optional: Whether to verify the voucher online (check ledger status).
     * If true, queries the Nostr ledger to check for double-spend.
     * If false, only performs offline verification (signature + expiry).
     * Default: true (recommended for production)
     */
    @Builder.Default
    private Boolean verifyOnline = true;
}
