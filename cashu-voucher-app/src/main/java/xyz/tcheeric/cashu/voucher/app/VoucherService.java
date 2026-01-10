package xyz.tcheeric.cashu.voucher.app;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.voucher.app.dto.GeneratePaymentRequestDTO;
import xyz.tcheeric.cashu.voucher.app.dto.GeneratePaymentRequestResponse;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherRequest;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherResponse;
import xyz.tcheeric.cashu.common.VoucherPaymentRequest;
import xyz.tcheeric.cashu.common.VoucherTransport;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherBackupPort;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.common.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Main voucher service that orchestrates use cases.
 *
 * <p>This service provides the primary business logic for voucher operations,
 * coordinating between the domain layer (voucher creation, signing, validation)
 * and the infrastructure layer (storage via ports).
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Issue new vouchers (create, sign, publish to ledger)</li>
 *   <li>Query voucher status from the public ledger</li>
 *   <li>Update voucher status (state transitions)</li>
 *   <li>Orchestrate backup and restore operations</li>
 *   <li>Serialize vouchers to Cashu token format</li>
 * </ul>
 *
 * <h3>Architecture</h3>
 * <p>This service follows hexagonal architecture principles:
 * <ul>
 *   <li>Depends on <b>ports</b> (interfaces) not concrete implementations</li>
 *   <li>Uses domain entities (VoucherSecret, SignedVoucher)</li>
 *   <li>Returns DTOs for API boundaries</li>
 *   <li>Infrastructure-agnostic (no knowledge of Nostr, SQL, etc.)</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * <pre>
 * // Setup (typically in Spring/Guice configuration)
 * VoucherLedgerPort ledger = new NostrVoucherLedgerRepository(...);
 * VoucherBackupPort backup = new NostrVoucherBackupRepository(...);
 * VoucherService service = new VoucherService(ledger, backup, privKey, pubKey);
 *
 * // Issue a voucher
 * IssueVoucherRequest request = IssueVoucherRequest.builder()
 *     .issuerId("merchant123")
 *     .unit("sat")
 *     .amount(10000L)
 *     .expiresInDays(365)
 *     .memo("Birthday gift")
 *     .build();
 *
 * IssueVoucherResponse response = service.issue(request);
 * SignedVoucher voucher = response.getVoucher();
 * System.out.println("Voucher ID: " + response.getVoucherId());
 *
 * // To create a shareable token, use a wallet (e.g., cashu-client)
 * // that can swap proofs at the mint with the voucher as the secret.
 *
 * // Query status
 * Optional&lt;VoucherStatus&gt; status = service.queryStatus(response.getVoucherId());
 *
 * // Backup vouchers
 * service.backup(List.of(voucher), userNostrPrivateKey);
 * </pre>
 *
 * @see VoucherLedgerPort
 * @see VoucherBackupPort
 * @see IssueVoucherRequest
 * @see IssueVoucherResponse
 */
@Slf4j
public class VoucherService {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final VoucherLedgerPort ledgerPort;
    private final VoucherBackupPort backupPort;
    private final String mintIssuerPrivateKey;
    private final String mintIssuerPublicKey;

    /**
     * Constructs a VoucherService with the required dependencies.
     *
     * @param ledgerPort the port for public ledger operations (must not be null)
     * @param backupPort the port for private backup operations (must not be null)
     * @param mintIssuerPrivateKey the mint's private key for signing vouchers (hex-encoded, must not be null)
     * @param mintIssuerPublicKey the mint's public key for voucher verification (hex-encoded, must not be null)
     */
    public VoucherService(
            @NonNull VoucherLedgerPort ledgerPort,
            @NonNull VoucherBackupPort backupPort,
            @NonNull String mintIssuerPrivateKey,
            @NonNull String mintIssuerPublicKey
    ) {
        if (mintIssuerPrivateKey.isBlank()) {
            throw new IllegalArgumentException("Mint issuer private key cannot be blank");
        }
        if (mintIssuerPublicKey.isBlank()) {
            throw new IllegalArgumentException("Mint issuer public key cannot be blank");
        }

        this.ledgerPort = ledgerPort;
        this.backupPort = backupPort;
        this.mintIssuerPrivateKey = mintIssuerPrivateKey;
        this.mintIssuerPublicKey = mintIssuerPublicKey;

        log.info("VoucherService initialized with issuer public key: {}...",
                mintIssuerPublicKey.substring(0, Math.min(8, mintIssuerPublicKey.length())));
    }

    /**
     * Issues a new voucher.
     *
     * <p>This method performs the following operations:
     * <ol>
     *   <li>Calculates expiry timestamp (if specified)</li>
     *   <li>Creates a VoucherSecret with the provided parameters</li>
     *   <li>Signs the voucher with the mint's private key</li>
     *   <li>Publishes the voucher to the public ledger with ISSUED status</li>
     *   <li>Serializes to Cashu token format</li>
     *   <li>Returns response with voucher and token</li>
     * </ol>
     *
     * @param request the voucher issuance request (must not be null)
     * @return the issuance response containing the voucher and token
     * @throws IllegalArgumentException if request parameters are invalid
     * @throws RuntimeException if signing or publishing fails
     */
    public IssueVoucherResponse issue(@NonNull IssueVoucherRequest request) {
        log.info("Issuing voucher: issuerId={}, unit={}, amount={}",
                request.getIssuerId(), request.getUnit(), request.getAmount());

        // Validate request
        validateIssueRequest(request);

        // Calculate expiry
        Long expiresAt = null;
        if (request.getExpiresInDays() != null) {
            expiresAt = Instant.now()
                    .plus(request.getExpiresInDays(), ChronoUnit.DAYS)
                    .getEpochSecond();
            log.debug("Voucher will expire at: {} (in {} days)",
                    Instant.ofEpochSecond(expiresAt), request.getExpiresInDays());
        }

        // Create voucher secret (with optional custom ID for testing)
        VoucherSecret.Builder secretBuilder = VoucherSecret.builder()
                .issuerId(request.getIssuerId())
                .unit(request.getUnit())
                .faceValue(request.getAmount())
                .expiresAt(expiresAt)
                .memo(request.getMemo())
                .backingStrategy(request.getBackingStrategy() != null ? request.getBackingStrategy().name() : null)
                .issuanceRatio(request.getIssuanceRatio())
                .faceDecimals(request.getFaceDecimals())
                .merchantMetadata(serializeMerchantMetadata(request.getMerchantMetadata()));

        if (request.getVoucherId() != null && !request.getVoucherId().isBlank()) {
            try {
                secretBuilder.voucherId(java.util.UUID.fromString(request.getVoucherId()));
                log.debug("Created voucher with custom ID: {}", request.getVoucherId());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid voucher ID format: must be a valid UUID (e.g., 550e8400-e29b-41d4-a716-446655440000)", e);
            }
        }

        VoucherSecret secret = secretBuilder.build();

        if (request.getVoucherId() == null || request.getVoucherId().isBlank()) {
            log.debug("Created voucher with auto-generated ID: {}", secret.getVoucherId());
        }

        // Sign voucher
        SignedVoucher signedVoucher = VoucherSignatureService.createSigned(
                secret,
                mintIssuerPrivateKey,
                mintIssuerPublicKey
        );
        log.debug("Voucher signed successfully");

        // Publish to ledger
        try {
            ledgerPort.publish(signedVoucher, VoucherStatus.ISSUED);
            log.info("Voucher published to ledger: voucherId={}, status=ISSUED", secret.getVoucherId());
        } catch (Exception e) {
            log.error("Failed to publish voucher to ledger: voucherId={}", secret.getVoucherId(), e);
            throw new RuntimeException("Failed to publish voucher to ledger", e);
        }

        // Note: Token serialization requires mint interaction (blind signatures, keyset).
        // Use cashu-client's VoucherService.issueAndBackup() for complete token creation.
        // This service returns the SignedVoucher which can be used with a wallet to create tokens.

        return IssueVoucherResponse.builder()
                .voucher(signedVoucher)
                .build();
    }

    /**
     * Queries the current status of a voucher from the public ledger.
     *
     * @param voucherId the unique voucher identifier (must not be null or blank)
     * @return the current status, or empty if voucher not found in ledger
     * @throws IllegalArgumentException if voucherId is null or blank
     * @throws RuntimeException if ledger query fails
     */
    public Optional<VoucherStatus> queryStatus(@NonNull String voucherId) {
        if (voucherId.isBlank()) {
            throw new IllegalArgumentException("Voucher ID cannot be blank");
        }

        log.debug("Querying voucher status: voucherId={}", voucherId);

        try {
            Optional<VoucherStatus> status = ledgerPort.queryStatus(voucherId);
            if (status.isPresent()) {
                log.debug("Voucher status found: voucherId={}, status={}", voucherId, status.get());
            } else {
                log.debug("Voucher not found in ledger: voucherId={}", voucherId);
            }
            return status;
        } catch (Exception e) {
            log.error("Failed to query voucher status: voucherId={}", voucherId, e);
            throw new RuntimeException("Failed to query voucher status", e);
        }
    }

    /**
     * Updates the status of a voucher in the public ledger.
     *
     * <p>This method records a state transition in the ledger. Common transitions:
     * <ul>
     *   <li>ISSUED → REDEEMED</li>
     *   <li>ISSUED → REVOKED</li>
     *   <li>ISSUED → EXPIRED</li>
     * </ul>
     *
     * @param voucherId the unique voucher identifier (must not be null or blank)
     * @param newStatus the new status to set (must not be null)
     * @throws IllegalArgumentException if parameters are invalid
     * @throws RuntimeException if ledger update fails
     */
    public void updateStatus(@NonNull String voucherId, @NonNull VoucherStatus newStatus) {
        if (voucherId.isBlank()) {
            throw new IllegalArgumentException("Voucher ID cannot be blank");
        }

        log.info("Updating voucher status: voucherId={}, newStatus={}", voucherId, newStatus);

        try {
            ledgerPort.updateStatus(voucherId, newStatus);
            log.info("Voucher status updated successfully: voucherId={}, status={}", voucherId, newStatus);
        } catch (Exception e) {
            log.error("Failed to update voucher status: voucherId={}, status={}", voucherId, newStatus, e);
            throw new RuntimeException("Failed to update voucher status", e);
        }
    }

    /**
     * Backs up vouchers to private user storage.
     *
     * <p>This method encrypts and stores the vouchers in a way that only the
     * user can retrieve them using their private key.
     *
     * @param vouchers the list of vouchers to backup (must not be null, can be empty)
     * @param userPrivateKey the user's private key for encryption (must not be null or blank)
     * @throws IllegalArgumentException if parameters are invalid
     * @throws RuntimeException if backup fails
     */
    public void backup(@NonNull List<SignedVoucher> vouchers, @NonNull String userPrivateKey) {
        if (userPrivateKey.isBlank()) {
            throw new IllegalArgumentException("User private key cannot be blank");
        }

        log.info("Backing up {} voucher(s)", vouchers.size());

        if (vouchers.isEmpty()) {
            log.debug("No vouchers to backup, skipping");
            return;
        }

        try {
            backupPort.backup(vouchers, userPrivateKey);
            log.info("Successfully backed up {} voucher(s)", vouchers.size());
        } catch (Exception e) {
            log.error("Failed to backup {} voucher(s)", vouchers.size(), e);
            throw new RuntimeException("Failed to backup vouchers", e);
        }
    }

    /**
     * Restores vouchers from private user storage.
     *
     * <p>This method retrieves and decrypts all voucher backups associated with
     * the user's private key.
     *
     * @param userPrivateKey the user's private key for decryption (must not be null or blank)
     * @return list of restored vouchers (never null, but may be empty)
     * @throws IllegalArgumentException if userPrivateKey is invalid
     * @throws RuntimeException if restore fails
     */
    public List<SignedVoucher> restore(@NonNull String userPrivateKey) {
        if (userPrivateKey.isBlank()) {
            throw new IllegalArgumentException("User private key cannot be blank");
        }

        log.info("Restoring vouchers from backup");

        try {
            List<SignedVoucher> restored = backupPort.restore(userPrivateKey);
            log.info("Successfully restored {} voucher(s)", restored.size());
            return restored;
        } catch (Exception e) {
            log.error("Failed to restore vouchers", e);
            throw new RuntimeException("Failed to restore vouchers", e);
        }
    }

    /**
     * Checks if a voucher exists in the public ledger.
     *
     * @param voucherId the unique voucher identifier (must not be null or blank)
     * @return true if the voucher exists in the ledger, false otherwise
     * @throws IllegalArgumentException if voucherId is null or blank
     * @throws RuntimeException if ledger query fails
     */
    public boolean exists(@NonNull String voucherId) {
        if (voucherId.isBlank()) {
            throw new IllegalArgumentException("Voucher ID cannot be blank");
        }

        return ledgerPort.exists(voucherId);
    }

    /**
     * Generates a NUT-18V VoucherPaymentRequest for receiving voucher payments.
     *
     * <p>This method creates an encoded payment request that can be:
     * <ul>
     *   <li>Displayed as a QR code at point-of-sale</li>
     *   <li>Shared via NFC, link, or message</li>
     *   <li>Used to initiate voucher redemption flow</li>
     * </ul>
     *
     * <p>The generated request uses the {@code vreqA} prefix format and includes
     * the issuer ID, amount, and configured transports.
     *
     * <h3>Usage Example</h3>
     * <pre>
     * GeneratePaymentRequestDTO dto = GeneratePaymentRequestDTO.builder()
     *     .issuerId("merchant123")
     *     .amount(5000)
     *     .unit("sat")
     *     .description("Coffee purchase")
     *     .callbackUrl("https://merchant.com/api/redeem")
     *     .build();
     *
     * GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(dto);
     * String qrContent = response.getEncodedRequest();
     * </pre>
     *
     * @param dto the payment request parameters (must not be null)
     * @return the response containing the encoded request and metadata
     * @throws IllegalArgumentException if required parameters are missing
     * @see xyz.tcheeric.cashu.common.VoucherPaymentRequest
     */
    public GeneratePaymentRequestResponse generatePaymentRequest(@NonNull GeneratePaymentRequestDTO dto) {
        log.info("Generating payment request: issuerId={}, amount={}, unit={}",
                dto.getIssuerId(), dto.getAmount(), dto.getUnit());

        // Validate required fields
        if (dto.getIssuerId() == null || dto.getIssuerId().isBlank()) {
            throw new IllegalArgumentException("Issuer ID is required for payment request");
        }
        if (dto.getAmount() != null && (dto.getUnit() == null || dto.getUnit().isBlank())) {
            throw new IllegalArgumentException("Unit is required when amount is specified");
        }

        // Generate payment ID if not provided
        String paymentId = dto.getPaymentId();
        if (paymentId == null || paymentId.isBlank()) {
            paymentId = UUID.randomUUID().toString().substring(0, 8);
            log.debug("Generated payment ID: {}", paymentId);
        }

        // Build transports list
        List<VoucherTransport> transports = new ArrayList<>();

        // Add merchant transport if requested
        if (Boolean.TRUE.equals(dto.getIncludeMerchantTransport())) {
            transports.add(VoucherTransport.merchant(
                    "merchant:" + dto.getIssuerId(),
                    dto.getIssuerId()
            ));
        }

        // Add HTTP POST transport if callback URL provided
        if (dto.getCallbackUrl() != null && !dto.getCallbackUrl().isBlank()) {
            transports.add(VoucherTransport.httpPost(dto.getCallbackUrl()));
        }

        // Add Nostr transport if nprofile provided
        if (dto.getNostrNprofile() != null && !dto.getNostrNprofile().isBlank()) {
            transports.add(VoucherTransport.nostrNip17(dto.getNostrNprofile()));
        }

        // Build the payment request
        VoucherPaymentRequest request = VoucherPaymentRequest.builder()
                .paymentId(paymentId)
                .issuerId(dto.getIssuerId())
                .amount(dto.getAmount())
                .unit(dto.getUnit())
                .description(dto.getDescription())
                .singleUse(dto.getSingleUse())
                .offlineVerification(dto.getOfflineVerification())
                .expiresAt(dto.getExpiresAt())
                .mints(dto.getMints() != null ? new ArrayList<>(dto.getMints()) : new ArrayList<>())
                .transports(transports)
                .build();

        // Serialize to encoded format
        boolean clickable = Boolean.TRUE.equals(dto.getClickable());
        String encodedRequest = request.serialize(clickable);

        log.info("Generated payment request: paymentId={}, encoded length={}",
                paymentId, encodedRequest.length());

        return GeneratePaymentRequestResponse.from(encodedRequest, request);
    }

    /**
     * Validates an issue voucher request.
     *
     * @param request the request to validate
     * @throws IllegalArgumentException if validation fails
     */
    private void validateIssueRequest(IssueVoucherRequest request) {
        if (request.getIssuerId() == null || request.getIssuerId().isBlank()) {
            throw new IllegalArgumentException("Issuer ID is required");
        }
        if (request.getUnit() == null || request.getUnit().isBlank()) {
            throw new IllegalArgumentException("Unit is required");
        }
        if (request.getAmount() == null || request.getAmount() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (request.getExpiresInDays() != null && request.getExpiresInDays() <= 0) {
            throw new IllegalArgumentException("Expiry days must be positive if specified");
        }
    }


    /**
     * Serializes merchant metadata map to JSON string.
     *
     * @param metadata the metadata map, may be null
     * @return JSON string representation, or null if input is null/empty
     * @throws IllegalArgumentException if metadata cannot be serialized to JSON
     */
    private String serializeMerchantMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize merchant metadata: {}", e.getMessage());
            throw new IllegalArgumentException("Merchant metadata cannot be serialized to JSON", e);
        }
    }
}
