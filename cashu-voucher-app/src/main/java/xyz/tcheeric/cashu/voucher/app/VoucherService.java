package xyz.tcheeric.cashu.voucher.app;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherRequest;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherResponse;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * System.out.println("Token: " + response.getToken());
 *
 * // Query status
 * Optional&lt;VoucherStatus&gt; status = service.queryStatus(response.getVoucherId());
 *
 * // Backup vouchers
 * service.backup(List.of(response.getVoucher()), userNostrPrivateKey);
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
            secretBuilder.voucherId(java.util.UUID.fromString(request.getVoucherId()));
            log.debug("Created voucher with custom ID: {}", request.getVoucherId());
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

        // Serialize to token
        String token = serializeToToken(signedVoucher);

        return IssueVoucherResponse.builder()
                .voucher(signedVoucher)
                .token(token)
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
     * Serializes a signed voucher to Cashu token format (v4).
     *
     * <p>The token format is a base64-encoded representation that can be:
     * <ul>
     *   <li>Printed as a QR code</li>
     *   <li>Shared via NFC</li>
     *   <li>Sent as text (chat, email, etc.)</li>
     * </ul>
     *
     * <p><b>TODO:</b> Implement full Cashu v4 token serialization.
     * Current implementation returns a placeholder.
     *
     * @param voucher the voucher to serialize
     * @return the token string in Cashu v4 format (starts with "cashuA")
     */
    private String serializeToToken(SignedVoucher voucher) {
        // TODO: Implement full Cashu token v4 serialization
        // For now, return a placeholder that includes the voucher ID
        log.warn("Using placeholder token serialization - full implementation pending");
        return "cashuA" + voucher.getSecret().getVoucherId().toString().replace("-", "");
    }

    /**
     * Serializes merchant metadata map to JSON string.
     *
     * @param metadata the metadata map, may be null
     * @return JSON string representation, or null if input is null/empty
     */
    private String serializeMerchantMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize merchant metadata, ignoring: {}", e.getMessage());
            return null;
        }
    }
}
