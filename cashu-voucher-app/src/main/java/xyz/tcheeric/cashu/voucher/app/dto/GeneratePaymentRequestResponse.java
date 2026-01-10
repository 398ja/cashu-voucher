package xyz.tcheeric.cashu.voucher.app.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import xyz.tcheeric.cashu.common.VoucherPaymentRequest;

/**
 * Response DTO for generating a NUT-18V VoucherPaymentRequest.
 *
 * <p>Contains the encoded payment request string that can be displayed as a QR code
 * or shared with customers, along with the parsed request object for reference.
 *
 * <h3>Usage Example</h3>
 * <pre>
 * GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(request);
 *
 * // Get the encoded string for QR code
 * String qrContent = response.getEncodedRequest();
 *
 * // Get payment ID for tracking
 * String paymentId = response.getPaymentId();
 *
 * // Access the full request object if needed
 * VoucherPaymentRequest voucherRequest = response.getRequest();
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratePaymentRequestResponse {

    /**
     * The encoded payment request string.
     * Format: "vreqA..." or "cashu:vreqA..." if clickable.
     */
    private String encodedRequest;

    /**
     * The payment ID for this request.
     * Use this to correlate incoming payments with orders.
     */
    private String paymentId;

    /**
     * The issuer ID included in the request.
     */
    private String issuerId;

    /**
     * The requested amount (if any).
     */
    private Integer amount;

    /**
     * The currency unit (if specified).
     */
    private String unit;

    /**
     * The full VoucherPaymentRequest object.
     * Useful for accessing all request details.
     */
    private VoucherPaymentRequest request;

    /**
     * Creates a successful response from an encoded request and parsed object.
     *
     * @param encodedRequest the encoded request string
     * @param request the parsed VoucherPaymentRequest
     * @return a new response instance
     */
    public static GeneratePaymentRequestResponse from(String encodedRequest, VoucherPaymentRequest request) {
        return GeneratePaymentRequestResponse.builder()
                .encodedRequest(encodedRequest)
                .paymentId(request.getPaymentId())
                .issuerId(request.getIssuerId())
                .amount(request.getAmount())
                .unit(request.getUnit())
                .request(request)
                .build();
    }
}
