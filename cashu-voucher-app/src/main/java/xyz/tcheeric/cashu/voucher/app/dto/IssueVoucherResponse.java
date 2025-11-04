package xyz.tcheeric.cashu.voucher.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;

/**
 * Response DTO for voucher issuance.
 *
 * <p>This DTO contains the newly issued voucher along with its serialized token
 * representation that can be shared with the recipient.
 *
 * <h3>Usage Example</h3>
 * <pre>
 * IssueVoucherResponse response = voucherService.issue(request);
 *
 * // Get the voucher object
 * SignedVoucher voucher = response.getVoucher();
 *
 * // Get the shareable token string
 * String token = response.getToken();
 * // Token can be: printed as QR code, sent via NFC, shared as text, etc.
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IssueVoucherResponse {

    /**
     * The complete signed voucher object.
     * Contains the voucher secret, issuer signature, and public key.
     */
    private SignedVoucher voucher;

    /**
     * The voucher serialized as a Cashu token (v4 format).
     * This is the string representation that can be shared with recipients.
     * Format: "cashuA..." (base64-encoded)
     */
    private String token;

    /**
     * The unique voucher identifier (convenience accessor).
     * Equivalent to voucher.getSecret().getVoucherId()
     */
    public String getVoucherId() {
        return voucher != null ? voucher.getSecret().getVoucherId() : null;
    }

    /**
     * The face value of the voucher (convenience accessor).
     * Equivalent to voucher.getSecret().getFaceValue()
     */
    public Long getAmount() {
        return voucher != null ? voucher.getSecret().getFaceValue() : null;
    }

    /**
     * The currency unit (convenience accessor).
     * Equivalent to voucher.getSecret().getUnit()
     */
    public String getUnit() {
        return voucher != null ? voucher.getSecret().getUnit() : null;
    }
}
