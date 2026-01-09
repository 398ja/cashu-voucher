package xyz.tcheeric.cashu.voucher.app;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.voucher.app.dto.RedeemVoucherRequest;
import xyz.tcheeric.cashu.voucher.app.dto.RedeemVoucherResponse;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;
import xyz.tcheeric.cashu.voucher.domain.VoucherValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Service for merchant-side voucher verification and redemption (Model B).
 *
 * <p>In Model B, vouchers can ONLY be redeemed at the issuing merchant, not at
 * the mint. This service provides both offline and online verification capabilities.
 *
 * <h3>Verification Modes</h3>
 * <ul>
 *   <li><b>Offline</b>: Signature + expiry validation only (no network required)</li>
 *   <li><b>Online</b>: Offline checks + ledger status query (prevents double-spend)</li>
 * </ul>
 *
 * <h3>Typical Flow</h3>
 * <ol>
 *   <li>Customer presents voucher token to merchant</li>
 *   <li>Merchant calls verifyOnline() to check validity and prevent double-spend</li>
 *   <li>If valid, merchant accepts payment and marks voucher as REDEEMED</li>
 *   <li>Voucher cannot be reused (terminal state)</li>
 * </ol>
 *
 * <h3>Usage Example</h3>
 * <pre>
 * MerchantVerificationService service = new MerchantVerificationService(ledgerPort);
 *
 * // Parse token to get signed voucher (implementation specific)
 * SignedVoucher voucher = parseToken(token);
 *
 * // Verify online (recommended for production)
 * VerificationResult result = service.verifyOnline(voucher, "merchant123");
 *
 * if (result.isValid()) {
 *     // Accept payment
 *     // Mark as redeemed
 *     service.markRedeemed(voucher.getSecret().getVoucherId());
 * } else {
 *     // Reject - show errors
 *     System.err.println("Invalid voucher: " + result.getErrorMessage());
 * }
 * </pre>
 *
 * @see VoucherLedgerPort
 * @see VoucherValidator
 */
@Slf4j
public class MerchantVerificationService {

    private final VoucherLedgerPort ledgerPort;

    /**
     * Constructs a MerchantVerificationService.
     *
     * @param ledgerPort the port for ledger operations (must not be null)
     */
    public MerchantVerificationService(@NonNull VoucherLedgerPort ledgerPort) {
        this.ledgerPort = ledgerPort;
        log.info("MerchantVerificationService initialized");
    }

    /**
     * Result of voucher verification.
     */
    @Getter
    @AllArgsConstructor
    public static class VerificationResult {
        private final boolean valid;
        private final List<String> errors;

        /**
         * Creates a successful verification result.
         */
        public static VerificationResult success() {
            return new VerificationResult(true, Collections.emptyList());
        }

        /**
         * Creates a failed verification result with a single error.
         */
        public static VerificationResult failure(@NonNull String error) {
            List<String> errors = new ArrayList<>();
            errors.add(error);
            return new VerificationResult(false, errors);
        }

        /**
         * Creates a failed verification result with multiple errors.
         */
        public static VerificationResult failure(@NonNull List<String> errors) {
            return new VerificationResult(false, new ArrayList<>(errors));
        }

        /**
         * Gets an unmodifiable view of the errors.
         */
        public List<String> getErrors() {
            return Collections.unmodifiableList(errors);
        }

        /**
         * Gets a formatted error message.
         */
        public String getErrorMessage() {
            return String.join("; ", errors);
        }
    }

    /**
     * Verifies a voucher offline (signature + expiry only).
     *
     * <p>This method performs cryptographic signature verification and expiry
     * checks without requiring network access. Useful for:
     * <ul>
     *   <li>Offline merchants (temporary network outage)</li>
     *   <li>Quick preliminary checks</li>
     *   <li>Testing environments</li>
     * </ul>
     *
     * <p><b>Warning:</b> Offline verification cannot detect double-spending.
     * Use online verification for production.
     *
     * @param voucher the signed voucher to verify (must not be null)
     * @param expectedIssuerId the merchant's issuer ID (must not be null or blank)
     * @return the verification result
     */
    public VerificationResult verifyOffline(
            @NonNull SignedVoucher voucher,
            @NonNull String expectedIssuerId
    ) {
        if (expectedIssuerId.isBlank()) {
            throw new IllegalArgumentException("Expected issuer ID cannot be blank");
        }

        log.debug("Performing offline verification: voucherId={}, expectedIssuer={}",
                voucher.getSecret().getVoucherId(), expectedIssuerId);

        List<String> errors = new ArrayList<>();

        // Check issuer (Model B: must match expected merchant)
        String actualIssuerId = voucher.getSecret().getIssuerId();
        if (!actualIssuerId.equals(expectedIssuerId)) {
            String error = String.format(
                    "Voucher issued by '%s' but expected issuer is '%s' (Model B: vouchers only redeemable at issuing merchant)",
                    actualIssuerId, expectedIssuerId
            );
            errors.add(error);
            log.warn("Issuer mismatch: {}", error);
        }

        // Validate using domain validator (signature + expiry + business rules)
        VoucherValidator.ValidationResult domainResult = VoucherValidator.validate(voucher);
        if (!domainResult.isValid()) {
            errors.addAll(domainResult.getErrors());
            log.warn("Domain validation failed: {}", domainResult.getErrorMessage());
        }

        if (errors.isEmpty()) {
            log.debug("Offline verification passed: voucherId={}", voucher.getSecret().getVoucherId());
            return VerificationResult.success();
        } else {
            log.debug("Offline verification failed: voucherId={}, errors={}",
                    voucher.getSecret().getVoucherId(), errors.size());
            return VerificationResult.failure(errors);
        }
    }

    /**
     * Verifies a voucher online (offline checks + ledger status).
     *
     * <p>This method performs all offline checks plus queries the public ledger
     * to check for:
     * <ul>
     *   <li>Voucher existence in ledger</li>
     *   <li>Current status (ISSUED vs REDEEMED/REVOKED/EXPIRED)</li>
     *   <li>Double-spend detection</li>
     * </ul>
     *
     * <p>This is the <b>recommended verification mode for production</b>.
     *
     * @param voucher the signed voucher to verify (must not be null)
     * @param expectedIssuerId the merchant's issuer ID (must not be null or blank)
     * @return the verification result
     */
    public VerificationResult verifyOnline(
            @NonNull SignedVoucher voucher,
            @NonNull String expectedIssuerId
    ) {
        if (expectedIssuerId.isBlank()) {
            throw new IllegalArgumentException("Expected issuer ID cannot be blank");
        }

        String voucherId = voucher.getSecret().getVoucherId() != null
                ? voucher.getSecret().getVoucherId().toString() : null;
        log.info("Performing online verification: voucherId={}, expectedIssuer={}",
                voucherId, expectedIssuerId);

        // First perform offline verification
        VerificationResult offlineResult = verifyOffline(voucher, expectedIssuerId);
        if (!offlineResult.isValid()) {
            log.warn("Online verification failed at offline stage: voucherId={}", voucherId);
            return offlineResult;
        }

        // Query ledger for status
        try {
            Optional<VoucherStatus> statusOpt = ledgerPort.queryStatus(voucherId);

            if (statusOpt.isEmpty()) {
                String error = "Voucher not found in public ledger";
                log.warn("Online verification failed: voucherId={}, {}", voucherId, error);
                return VerificationResult.failure(error);
            }

            VoucherStatus status = statusOpt.get();
            log.debug("Voucher ledger status: voucherId={}, status={}", voucherId, status);

            // Check status
            if (status == VoucherStatus.REDEEMED) {
                String error = "Voucher already redeemed (double-spend attempt detected)";
                log.warn("Double-spend detected: voucherId={}", voucherId);
                return VerificationResult.failure(error);
            }

            if (status == VoucherStatus.REVOKED) {
                String error = "Voucher has been revoked by issuer";
                log.warn("Revoked voucher: voucherId={}", voucherId);
                return VerificationResult.failure(error);
            }

            if (status == VoucherStatus.EXPIRED) {
                String error = "Voucher has expired";
                log.warn("Expired voucher (ledger status): voucherId={}", voucherId);
                return VerificationResult.failure(error);
            }

            if (status == VoucherStatus.ISSUED) {
                log.info("Online verification passed: voucherId={}, status=ISSUED", voucherId);
                return VerificationResult.success();
            }

            // Unknown status
            String error = "Unknown voucher status: " + status;
            log.error("Unknown status in ledger: voucherId={}, status={}", voucherId, status);
            return VerificationResult.failure(error);

        } catch (Exception e) {
            String error = "Failed to query voucher status from ledger: " + e.getMessage();
            log.error("Ledger query failed: voucherId={}", voucherId, e);
            return VerificationResult.failure(error);
        }
    }

    /**
     * Marks a voucher as redeemed in the ledger.
     *
     * <p>Call this method after successfully accepting a voucher payment.
     * This records the redemption in the public ledger to prevent double-spending.
     *
     * @param voucherId the voucher ID to mark as redeemed (must not be null or blank)
     * @throws IllegalArgumentException if voucherId is invalid
     * @throws RuntimeException if ledger update fails
     */
    public void markRedeemed(@NonNull String voucherId) {
        if (voucherId.isBlank()) {
            throw new IllegalArgumentException("Voucher ID cannot be blank");
        }

        log.info("Marking voucher as redeemed: voucherId={}", voucherId);

        try {
            ledgerPort.updateStatus(voucherId, VoucherStatus.REDEEMED);
            log.info("Voucher marked as redeemed successfully: voucherId={}", voucherId);
        } catch (Exception e) {
            log.error("Failed to mark voucher as redeemed: voucherId={}", voucherId, e);
            throw new RuntimeException("Failed to mark voucher as redeemed", e);
        }
    }

    /**
     * Redeems a voucher (verify + mark as redeemed).
     *
     * <p>This is a convenience method that combines verification and redemption
     * in a single operation. It:
     * <ol>
     *   <li>Verifies the voucher online</li>
     *   <li>If valid, marks it as REDEEMED</li>
     *   <li>Returns the redemption response</li>
     * </ol>
     *
     * @param request the redemption request (must not be null)
     * @param voucher the parsed voucher from the token (must not be null)
     * @return the redemption response
     */
    public RedeemVoucherResponse redeem(
            @NonNull RedeemVoucherRequest request,
            @NonNull SignedVoucher voucher
    ) {
        String voucherId = voucher.getSecret().getVoucherId() != null
                ? voucher.getSecret().getVoucherId().toString() : null;
        log.info("Processing redemption request: voucherId={}, merchantId={}",
                voucherId, request.getMerchantId());

        // Verify based on request setting
        VerificationResult verifyResult;
        if (Boolean.TRUE.equals(request.getVerifyOnline())) {
            verifyResult = verifyOnline(voucher, request.getMerchantId());
        } else {
            log.warn("Offline verification requested: voucherId={} - double-spend not prevented!", voucherId);
            verifyResult = verifyOffline(voucher, request.getMerchantId());
        }

        // Check if verification passed
        if (!verifyResult.isValid()) {
            log.warn("Redemption rejected: voucherId={}, errors={}", voucherId, verifyResult.getErrorMessage());
            return RedeemVoucherResponse.failure(verifyResult.getErrorMessage());
        }

        // Mark as redeemed
        try {
            markRedeemed(voucherId);
            log.info("Redemption successful: voucherId={}, amount={}", voucherId, voucher.getSecret().getFaceValue());
            return RedeemVoucherResponse.success(voucher);
        } catch (Exception e) {
            String error = "Verification passed but failed to mark as redeemed: " + e.getMessage();
            log.error("Redemption failed at marking stage: voucherId={}", voucherId, e);
            return RedeemVoucherResponse.failure(error);
        }
    }
}
