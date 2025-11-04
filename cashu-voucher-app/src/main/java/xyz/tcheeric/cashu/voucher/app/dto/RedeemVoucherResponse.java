package xyz.tcheeric.cashu.voucher.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;

/**
 * Response DTO for voucher redemption.
 *
 * <p>This DTO indicates whether the redemption was successful and provides
 * details about the redeemed voucher.
 *
 * <h3>Usage Example</h3>
 * <pre>
 * RedeemVoucherResponse response = merchantService.redeem(request);
 *
 * if (response.isSuccess()) {
 *     System.out.println("Redeemed: " + response.getAmount() + " " + response.getUnit());
 *     // Credit customer account, etc.
 * } else {
 *     System.err.println("Redemption failed: " + response.getErrorMessage());
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedeemVoucherResponse {

    /**
     * Whether the redemption was successful.
     */
    private boolean success;

    /**
     * The voucher that was redeemed (null if redemption failed).
     */
    private SignedVoucher voucher;

    /**
     * Error message if redemption failed (null if successful).
     */
    private String errorMessage;

    /**
     * The amount redeemed (convenience accessor).
     */
    public Long getAmount() {
        return voucher != null ? voucher.getSecret().getFaceValue() : null;
    }

    /**
     * The currency unit (convenience accessor).
     */
    public String getUnit() {
        return voucher != null ? voucher.getSecret().getUnit() : null;
    }

    /**
     * The voucher ID (convenience accessor).
     */
    public String getVoucherId() {
        return voucher != null ? voucher.getSecret().getVoucherId() : null;
    }

    /**
     * Creates a successful redemption response.
     */
    public static RedeemVoucherResponse success(SignedVoucher voucher) {
        return RedeemVoucherResponse.builder()
                .success(true)
                .voucher(voucher)
                .build();
    }

    /**
     * Creates a failed redemption response.
     */
    public static RedeemVoucherResponse failure(String errorMessage) {
        return RedeemVoucherResponse.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
