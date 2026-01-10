package xyz.tcheeric.cashu.voucher.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;

/**
 * Response DTO for voucher issuance.
 *
 * <p>This DTO contains the newly issued voucher. The voucher is signed by the issuer
 * and published to the ledger, but does not include a shareable token.
 *
 * <h3>Token Creation</h3>
 * <p>Creating a shareable Cashu token requires mint interaction (blind signatures, keyset).
 * Use a wallet implementation (e.g., cashu-client's VoucherService) to:
 * <ol>
 *   <li>Take the SignedVoucher from this response</li>
 *   <li>Swap proofs at the mint with the voucher as the secret</li>
 *   <li>Encode the result as a TokenV4 (cashuB...) token</li>
 * </ol>
 *
 * <h3>Usage Example</h3>
 * <pre>
 * // Issue voucher (creates and signs voucher, publishes to ledger)
 * IssueVoucherResponse response = voucherService.issue(request);
 * SignedVoucher voucher = response.getVoucher();
 *
 * // To create a shareable token, use a wallet:
 * // IssueVoucherResult result = walletVoucherService.issueAndBackup(...);
 * // String token = result.token();  // "cashuB..." format
 * </pre>
 *
 * @see SignedVoucher
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
     * The voucher serialized as a Cashu token.
     *
     * @deprecated Token creation requires mint interaction and is not supported
     *             by this library. Use a wallet implementation (cashu-client) to
     *             create shareable tokens from the SignedVoucher.
     *             This field will always be null when returned from VoucherService.issue().
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    private String token;

    /**
     * The unique voucher identifier (convenience accessor).
     * Equivalent to voucher.getSecret().getVoucherId()
     */
    public String getVoucherId() {
        return voucher != null && voucher.getSecret().getVoucherId() != null
                ? voucher.getSecret().getVoucherId().toString() : null;
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
