package xyz.tcheeric.cashu.voucher.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for generating a NUT-18V VoucherPaymentRequest.
 *
 * <p>This DTO encapsulates the parameters needed to create a payment request
 * that can be displayed as a QR code or shared with customers for voucher payments.
 *
 * <h3>Usage Example</h3>
 * <pre>
 * GeneratePaymentRequestDTO request = GeneratePaymentRequestDTO.builder()
 *     .issuerId("merchant123")
 *     .amount(5000)
 *     .unit("sat")
 *     .description("Coffee purchase")
 *     .singleUse(true)
 *     .offlineVerification(false)
 *     .build();
 *
 * GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(request);
 * String qrContent = response.getEncodedRequest(); // vreqA...
 * </pre>
 *
 * @see xyz.tcheeric.cashu.common.VoucherPaymentRequest
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratePaymentRequestDTO {

    /**
     * The merchant/issuer identifier (required).
     * This identifies who can redeem vouchers for this payment request.
     */
    private String issuerId;

    /**
     * Optional: Unique payment request identifier.
     * If null, one will be auto-generated.
     * Useful for correlating payments with orders.
     */
    private String paymentId;

    /**
     * Optional: Requested payment amount.
     * If null, any amount is accepted (open request).
     */
    private Integer amount;

    /**
     * The currency unit (required if amount is specified).
     * Examples: "sat", "usd", "eur"
     */
    private String unit;

    /**
     * Optional: Human-readable description.
     * Displayed to the customer in their wallet.
     */
    private String description;

    /**
     * Optional: Whether this request can only be paid once.
     * If true, the payment request ID should be tracked to prevent reuse.
     */
    private Boolean singleUse;

    /**
     * Optional: Whether offline verification is acceptable.
     * If true, the payment can be verified without network access.
     * Default: false (online verification recommended).
     */
    @Builder.Default
    private Boolean offlineVerification = false;

    /**
     * Optional: Request expiry time as Unix timestamp (seconds).
     * If null, the request does not expire.
     */
    private Long expiresAt;

    /**
     * Optional: List of permitted mint URLs.
     * If empty/null, vouchers from any mint are accepted.
     */
    private List<String> mints;

    /**
     * Optional: Merchant callback URL for HTTP POST transport.
     * If provided, a POST transport will be added.
     */
    private String callbackUrl;

    /**
     * Optional: Nostr nprofile for Nostr transport.
     * If provided, a Nostr NIP-17 transport will be added.
     */
    private String nostrNprofile;

    /**
     * Optional: Whether to include a MERCHANT transport.
     * If true, adds a merchant transport pointing to the issuer.
     */
    @Builder.Default
    private Boolean includeMerchantTransport = true;

    /**
     * Optional: Whether to generate a clickable URI (cashu:vreqA...).
     * If false, generates raw encoded string (vreqA...).
     */
    @Builder.Default
    private Boolean clickable = false;
}
