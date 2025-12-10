package xyz.tcheeric.cashu.voucher.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;

import java.util.Map;

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

    /**
     * The backing strategy for the voucher.
     * Determines how tokens back the voucher and split capabilities.
     * Defaults to FIXED if not specified.
     */
    @Builder.Default
    private BackingStrategy backingStrategy = BackingStrategy.FIXED;

    /**
     * Issuance ratio: face value per sat.
     * Used to calculate face value from token amount after splits.
     * Example: 0.01 means €0.01 per sat (so €10 = 1000 sats).
     * Defaults to 1.0 if not specified.
     */
    @Builder.Default
    private double issuanceRatio = 1.0;

    /**
     * Number of decimal places for the face value currency.
     * Example: 2 for EUR (cents), 0 for JPY, 0 for satoshis.
     * Defaults to 0 if not specified.
     */
    @Builder.Default
    private int faceDecimals = 0;

    /**
     * Optional: Arbitrary merchant-defined metadata.
     * Can contain business-specific data like passenger info, event details, etc.
     */
    private Map<String, Object> merchantMetadata;
}
