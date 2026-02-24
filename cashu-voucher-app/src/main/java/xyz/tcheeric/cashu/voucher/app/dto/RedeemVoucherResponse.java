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
 *     System.err.println("Redemption failed: " + response.getError());
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
     * The voucher ID (set explicitly when voucher object is not included).
     */
    private String voucherId;

    /**
     * The cryptographic fingerprint of the voucher.
     * Used for duplicate detection and tracking.
     */
    private String fingerprint;

    /**
     * The amount redeemed.
     */
    private Long amount;

    /**
     * The currency unit.
     */
    private String unit;

    /**
     * Error code if redemption failed (null if successful).
     * Standard codes: DUPLICATE_REDEMPTION, INVALID_SIGNATURE, VOUCHER_EXPIRED,
     * MERCHANT_MISMATCH, VOUCHER_NOT_FOUND, INVALID_STATE, CONCURRENT_REDEMPTION
     */
    private String error;

    /**
     * Human-readable message describing the result.
     */
    private String message;

    /**
     * Error message if redemption failed (null if successful).
     * @deprecated Use {@link #getError()} and {@link #getMessage()} instead
     */
    @Deprecated
    private String errorMessage;

    /**
     * The amount redeemed (convenience accessor from voucher if not set directly).
     */
    public Long getAmount() {
        if (amount != null) {
            return amount;
        }
        return voucher != null ? voucher.getSecret().getFaceValue() : null;
    }

    /**
     * The currency unit (convenience accessor from voucher if not set directly).
     */
    public String getUnit() {
        if (unit != null) {
            return unit;
        }
        return voucher != null ? voucher.getSecret().getUnit() : null;
    }

    /**
     * The voucher ID (convenience accessor from voucher if not set directly).
     */
    public String getVoucherId() {
        if (voucherId != null) {
            return voucherId;
        }
        return voucher != null && voucher.getSecret().getVoucherId() != null
                ? voucher.getSecret().getVoucherId().toString() : null;
    }

    /**
     * Creates a successful redemption response.
     */
    public static RedeemVoucherResponse success(SignedVoucher voucher) {
        return RedeemVoucherResponse.builder()
                .success(true)
                .voucher(voucher)
                .message("Voucher redeemed successfully")
                .build();
    }

    /**
     * Creates a failed redemption response.
     */
    public static RedeemVoucherResponse failure(String errorMessage) {
        return RedeemVoucherResponse.builder()
                .success(false)
                .errorMessage(errorMessage)
                .message(errorMessage)
                .build();
    }

    /**
     * Creates a failed redemption response with error code.
     *
     * @param errorCode the error code (e.g., DUPLICATE_REDEMPTION)
     * @param message human-readable error description
     * @return failure response
     */
    public static RedeemVoucherResponse failure(String errorCode, String message) {
        return RedeemVoucherResponse.builder()
                .success(false)
                .error(errorCode)
                .message(message)
                .errorMessage(message) // backward compatibility
                .build();
    }
}
