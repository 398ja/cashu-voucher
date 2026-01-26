package xyz.tcheeric.cashu.voucher.app;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.voucher.app.dto.RedeemVoucherRequest;
import xyz.tcheeric.cashu.voucher.app.dto.RedeemVoucherResponse;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherRedemptionPort;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherFingerprint;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * Service for secure voucher redemption with duplicate protection.
 *
 * <p>This service implements a defense-in-depth approach to prevent double redemption:
 * <ol>
 *   <li><b>Fingerprint check</b>: Fast lookup in redemption tracking store</li>
 *   <li><b>Ledger status check</b>: Verify voucher is in ISSUED state</li>
 *   <li><b>Atomic recording</b>: Record redemption before updating ledger</li>
 *   <li><b>Ledger update</b>: Mark voucher as REDEEMED</li>
 * </ol>
 *
 * <h3>Concurrency Safety</h3>
 * <p>The {@link VoucherRedemptionPort#tryRecordRedemption} method provides
 * atomic compare-and-swap semantics. If two concurrent redemption attempts
 * occur, only one will succeed in recording the redemption.
 *
 * <h3>Model B Enforcement</h3>
 * <p>This service optionally verifies that the redeeming merchant matches
 * the voucher's issuer ID (Model B constraint).
 *
 * @see VoucherFingerprint
 * @see VoucherRedemptionPort
 * @see VoucherLedgerPort
 */
@Slf4j
public class VoucherRedemptionService {

    private final VoucherLedgerPort ledgerPort;
    private final VoucherRedemptionPort redemptionPort;

    /**
     * Constructs a VoucherRedemptionService with required dependencies.
     *
     * @param ledgerPort the port for voucher status operations (must not be null)
     * @param redemptionPort the port for redemption tracking (must not be null)
     */
    public VoucherRedemptionService(
            @NonNull VoucherLedgerPort ledgerPort,
            @NonNull VoucherRedemptionPort redemptionPort
    ) {
        this.ledgerPort = ledgerPort;
        this.redemptionPort = redemptionPort;
        log.info("VoucherRedemptionService initialized");
    }

    /**
     * Attempts to redeem a voucher with duplicate protection.
     *
     * <p>This method performs the following steps:
     * <ol>
     *   <li>Computes fingerprint from voucher</li>
     *   <li>Checks if fingerprint already redeemed (fast path rejection)</li>
     *   <li>Optionally queries ledger for current status</li>
     *   <li>Validates voucher signature and expiry</li>
     *   <li>Optionally verifies merchant ID matches (Model B)</li>
     *   <li>Atomically records redemption</li>
     *   <li>Updates ledger status to REDEEMED</li>
     * </ol>
     *
     * @param voucher the signed voucher to redeem (must not be null)
     * @param merchantId the merchant attempting redemption (must not be null)
     * @param verifyOnline whether to verify status on ledger (recommended: true)
     * @return redemption response with success/failure details
     */
    public RedeemVoucherResponse redeem(
            @NonNull SignedVoucher voucher,
            @NonNull String merchantId,
            boolean verifyOnline
    ) {
        Objects.requireNonNull(voucher, "Voucher must not be null");
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");

        String voucherId = voucher.getVoucherId().toString();
        String fingerprint = VoucherFingerprint.compute(voucher);

        log.info("Redemption attempt: voucherId={}, merchantId={}, fingerprint={}...",
                voucherId, merchantId, fingerprint.substring(0, 16));

        // Step 1: Fast path - check if already redeemed by fingerprint
        if (redemptionPort.isRedeemed(fingerprint)) {
            log.warn("Duplicate redemption attempt detected: voucherId={}, fingerprint={}...",
                    voucherId, fingerprint.substring(0, 16));
            return RedeemVoucherResponse.builder()
                    .success(false)
                    .voucherId(voucherId)
                    .error("DUPLICATE_REDEMPTION")
                    .message("This voucher has already been redeemed")
                    .fingerprint(fingerprint)
                    .build();
        }

        // Step 2: Validate voucher signature
        if (!voucher.verify()) {
            log.warn("Invalid voucher signature: voucherId={}", voucherId);
            return RedeemVoucherResponse.builder()
                    .success(false)
                    .voucherId(voucherId)
                    .error("INVALID_SIGNATURE")
                    .message("Voucher signature verification failed")
                    .fingerprint(fingerprint)
                    .build();
        }

        // Step 3: Check expiry
        if (voucher.isExpired()) {
            log.warn("Voucher expired: voucherId={}", voucherId);
            return RedeemVoucherResponse.builder()
                    .success(false)
                    .voucherId(voucherId)
                    .error("VOUCHER_EXPIRED")
                    .message("Voucher has expired")
                    .fingerprint(fingerprint)
                    .build();
        }

        // Step 4: Model B verification - merchant must match issuer
        if (!merchantId.equals(voucher.getIssuerId())) {
            log.warn("Model B violation: voucherId={}, expectedIssuer={}, actualMerchant={}",
                    voucherId, voucher.getIssuerId(), merchantId);
            return RedeemVoucherResponse.builder()
                    .success(false)
                    .voucherId(voucherId)
                    .error("MERCHANT_MISMATCH")
                    .message("Voucher can only be redeemed by the issuing merchant")
                    .fingerprint(fingerprint)
                    .build();
        }

        // Step 5: Online verification (optional but recommended)
        if (verifyOnline) {
            Optional<VoucherStatus> currentStatus = ledgerPort.queryStatus(voucherId);

            if (currentStatus.isEmpty()) {
                log.warn("Voucher not found in ledger: voucherId={}", voucherId);
                return RedeemVoucherResponse.builder()
                        .success(false)
                        .voucherId(voucherId)
                        .error("VOUCHER_NOT_FOUND")
                        .message("Voucher not found in public ledger")
                        .fingerprint(fingerprint)
                        .build();
            }

            if (!currentStatus.get().canBeRedeemed()) {
                log.warn("Voucher not in redeemable state: voucherId={}, status={}",
                        voucherId, currentStatus.get());
                return RedeemVoucherResponse.builder()
                        .success(false)
                        .voucherId(voucherId)
                        .error("INVALID_STATE")
                        .message("Voucher is in state: " + currentStatus.get())
                        .fingerprint(fingerprint)
                        .build();
            }
        }

        // Step 6: Atomic redemption recording
        boolean recorded = redemptionPort.tryRecordRedemption(fingerprint, voucherId, merchantId);
        if (!recorded) {
            // Race condition: another thread redeemed while we were validating
            log.warn("Concurrent redemption detected: voucherId={}, fingerprint={}...",
                    voucherId, fingerprint.substring(0, 16));
            return RedeemVoucherResponse.builder()
                    .success(false)
                    .voucherId(voucherId)
                    .error("CONCURRENT_REDEMPTION")
                    .message("Voucher was redeemed by another process")
                    .fingerprint(fingerprint)
                    .build();
        }

        // Step 7: Update ledger status
        try {
            ledgerPort.updateStatus(voucherId, VoucherStatus.REDEEMED);
            log.info("Voucher redeemed successfully: voucherId={}, merchantId={}", voucherId, merchantId);
        } catch (Exception e) {
            // Redemption was recorded but ledger update failed
            // This is acceptable - the fingerprint record prevents re-redemption
            log.error("Ledger update failed after redemption: voucherId={}", voucherId, e);
        }

        return RedeemVoucherResponse.builder()
                .success(true)
                .voucherId(voucherId)
                .fingerprint(fingerprint)
                .amount(voucher.getFaceValue())
                .unit(voucher.getUnit())
                .message("Voucher redeemed successfully")
                .build();
    }

    /**
     * Convenience method for redeeming with default online verification.
     *
     * @param voucher the signed voucher to redeem
     * @param merchantId the merchant attempting redemption
     * @return redemption response
     */
    public RedeemVoucherResponse redeem(SignedVoucher voucher, String merchantId) {
        return redeem(voucher, merchantId, true);
    }

    /**
     * Checks if a voucher has been redeemed based on its fingerprint.
     *
     * @param voucher the voucher to check
     * @return true if the voucher has been redeemed
     */
    public boolean isRedeemed(SignedVoucher voucher) {
        String fingerprint = VoucherFingerprint.compute(voucher);
        return redemptionPort.isRedeemed(fingerprint);
    }

    /**
     * Gets redemption details for a voucher.
     *
     * @param voucher the voucher to look up
     * @return redemption record if found
     */
    public Optional<VoucherRedemptionPort.RedemptionRecord> getRedemptionRecord(SignedVoucher voucher) {
        String fingerprint = VoucherFingerprint.compute(voucher);
        return redemptionPort.getRedemption(fingerprint);
    }
}
