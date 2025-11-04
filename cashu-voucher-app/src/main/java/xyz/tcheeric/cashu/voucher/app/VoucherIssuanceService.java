package xyz.tcheeric.cashu.voucher.app;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherRequest;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherResponse;

/**
 * Service for voucher issuance with mint-specific integration logic.
 *
 * <p>This service wraps VoucherService with additional mint-specific business
 * logic and validations. It serves as the integration point between the mint's
 * API and the core voucher service.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Validate mint-specific business rules (quotas, limits, permissions)</li>
 *   <li>Enforce issuance policies (max amount, expiry constraints)</li>
 *   <li>Track issuance metrics and statistics</li>
 *   <li>Provide mint-friendly API wrapper around VoucherService</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * <pre>
 * VoucherIssuanceService service = new VoucherIssuanceService(voucherService);
 *
 * IssueVoucherRequest request = IssueVoucherRequest.builder()
 *     .issuerId("merchant123")
 *     .unit("sat")
 *     .amount(50000L)
 *     .build();
 *
 * IssueVoucherResponse response = service.issueVoucher(request);
 * </pre>
 *
 * @see VoucherService
 */
@Slf4j
public class VoucherIssuanceService {

    private final VoucherService voucherService;
    private final long maxVoucherAmount;
    private final int maxExpiryDays;

    /**
     * Constructs a VoucherIssuanceService with default limits.
     *
     * @param voucherService the underlying voucher service (must not be null)
     */
    public VoucherIssuanceService(@NonNull VoucherService voucherService) {
        this(voucherService, Long.MAX_VALUE, 3650); // 10 years max expiry
    }

    /**
     * Constructs a VoucherIssuanceService with custom limits.
     *
     * @param voucherService the underlying voucher service (must not be null)
     * @param maxVoucherAmount the maximum allowed voucher amount (must be positive)
     * @param maxExpiryDays the maximum allowed expiry in days (must be positive)
     */
    public VoucherIssuanceService(
            @NonNull VoucherService voucherService,
            long maxVoucherAmount,
            int maxExpiryDays
    ) {
        if (maxVoucherAmount <= 0) {
            throw new IllegalArgumentException("Max voucher amount must be positive");
        }
        if (maxExpiryDays <= 0) {
            throw new IllegalArgumentException("Max expiry days must be positive");
        }

        this.voucherService = voucherService;
        this.maxVoucherAmount = maxVoucherAmount;
        this.maxExpiryDays = maxExpiryDays;

        log.info("VoucherIssuanceService initialized: maxAmount={}, maxExpiryDays={}",
                maxVoucherAmount, maxExpiryDays);
    }

    /**
     * Issues a new voucher with policy enforcement.
     *
     * <p>This method validates the request against mint policies before
     * delegating to the core VoucherService.
     *
     * @param request the voucher issuance request (must not be null)
     * @return the issuance response containing the voucher and token
     * @throws IllegalArgumentException if request violates policies
     * @throws RuntimeException if issuance fails
     */
    public IssueVoucherResponse issueVoucher(@NonNull IssueVoucherRequest request) {
        log.info("Processing voucher issuance request: issuerId={}, amount={}",
                request.getIssuerId(), request.getAmount());

        // Validate against mint policies
        validateIssuancePolicy(request);

        // Delegate to core service
        try {
            IssueVoucherResponse response = voucherService.issue(request);
            log.info("Voucher issued successfully: voucherId={}, amount={}",
                    response.getVoucherId(), response.getAmount());
            return response;
        } catch (Exception e) {
            log.error("Failed to issue voucher: issuerId={}, amount={}",
                    request.getIssuerId(), request.getAmount(), e);
            throw e;
        }
    }

    /**
     * Validates the issuance request against mint policies.
     *
     * @param request the request to validate
     * @throws IllegalArgumentException if validation fails
     */
    private void validateIssuancePolicy(IssueVoucherRequest request) {
        // Validate amount limit
        if (request.getAmount() > maxVoucherAmount) {
            String msg = String.format(
                    "Voucher amount %d exceeds maximum allowed %d",
                    request.getAmount(), maxVoucherAmount
            );
            log.warn("Policy violation: {}", msg);
            throw new IllegalArgumentException(msg);
        }

        // Validate expiry limit
        if (request.getExpiresInDays() != null && request.getExpiresInDays() > maxExpiryDays) {
            String msg = String.format(
                    "Voucher expiry %d days exceeds maximum allowed %d days",
                    request.getExpiresInDays(), maxExpiryDays
            );
            log.warn("Policy violation: {}", msg);
            throw new IllegalArgumentException(msg);
        }

        log.debug("Issuance policy validation passed");
    }

    /**
     * Gets the maximum allowed voucher amount.
     *
     * @return the maximum amount
     */
    public long getMaxVoucherAmount() {
        return maxVoucherAmount;
    }

    /**
     * Gets the maximum allowed expiry in days.
     *
     * @return the maximum expiry days
     */
    public int getMaxExpiryDays() {
        return maxExpiryDays;
    }
}
