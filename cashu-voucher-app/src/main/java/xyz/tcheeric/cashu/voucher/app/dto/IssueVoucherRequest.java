package xyz.tcheeric.cashu.voucher.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for issuing a new voucher.
 *
 * <p>This DTO encapsulates all the parameters needed to create a new gift card voucher.
 * It is used by the VoucherService to issue vouchers through the mint's API.
 *
 * <h3>Usage Example</h3>
 * <pre>
 * IssueVoucherRequest request = IssueVoucherRequest.builder()
 *     .issuerId("merchant123")
 *     .unit("sat")
 *     .amount(10000L)
 *     .expiresInDays(365)
 *     .memo("Birthday gift card")
 *     .build();
 *
 * IssueVoucherResponse response = voucherService.issue(request);
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueVoucherRequest {

    /**
     * The merchant/entity identifier issuing the voucher.
     * This must match the mint's configured issuer ID.
     */
    private String issuerId;

    /**
     * The currency unit for the voucher.
     * Examples: "sat" (satoshis), "usd", "eur"
     */
    private String unit;

    /**
     * The face value of the voucher in the smallest unit.
     * For "sat": amount in satoshis
     * For "usd": amount in cents
     * Must be positive.
     */
    private Long amount;

    /**
     * Optional: Number of days until voucher expires (from now).
     * If null, the voucher never expires.
     * If provided, must be positive.
     */
    private Integer expiresInDays;

    /**
     * Optional: Human-readable memo/description for the voucher.
     * Examples: "Birthday gift", "Store credit", "Promotional voucher"
     */
    private String memo;

    /**
     * Optional: Specific voucher ID to use (for testing/debugging).
     * If null, a UUID will be auto-generated.
     * Production code should typically leave this null.
     */
    private String voucherId;
}
