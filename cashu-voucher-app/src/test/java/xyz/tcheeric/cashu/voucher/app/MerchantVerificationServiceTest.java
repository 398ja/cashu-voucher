package xyz.tcheeric.cashu.voucher.app;

import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.cashu.voucher.app.dto.RedeemVoucherRequest;
import xyz.tcheeric.cashu.voucher.app.dto.RedeemVoucherResponse;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.common.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MerchantVerificationService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MerchantVerificationService")
class MerchantVerificationServiceTest {

    @Mock
    private VoucherLedgerPort ledgerPort;

    private MerchantVerificationService service;

    private static String ISSUER_PRIVKEY;
    private static String ISSUER_PUBKEY;
    private static final String ISSUER_ID = "merchant123";
    private static final String UNIT = "sat";
    private static final long AMOUNT = 10000L;

    @BeforeAll
    static void setupKeys() {
        // Generate a test secp256k1 key pair (Schnorr/Nostr compatible)
        byte[] privateKeyBytes = Schnorr.generatePrivateKey();
        byte[] publicKeyBytes = Schnorr.genPubKey(privateKeyBytes);

        ISSUER_PRIVKEY = Hex.toHexString(privateKeyBytes);
        ISSUER_PUBKEY = Hex.toHexString(publicKeyBytes);
    }

    @BeforeEach
    void setUp() {
        service = new MerchantVerificationService(ledgerPort);
    }

    /**
     * Helper method to create a valid signed voucher.
     */
    private SignedVoucher createValidVoucher(String issuerId) {
        VoucherSecret secret = VoucherSecret.builder()
                .issuerId(issuerId)
                .unit(UNIT)
                .faceValue(AMOUNT)
                .backingStrategy(BackingStrategy.FIXED.name())
                .issuanceRatio(1.0)
                .faceDecimals(0)
                .build();
        return VoucherSignatureService.createSigned(secret, ISSUER_PRIVKEY, ISSUER_PUBKEY);
    }

    /**
     * Helper method to create an expired voucher.
     */
    private SignedVoucher createExpiredVoucher(String issuerId) {
        long pastExpiry = (System.currentTimeMillis() / 1000) - 86400; // 1 day ago
        VoucherSecret secret = VoucherSecret.builder()
                .issuerId(issuerId)
                .unit(UNIT)
                .faceValue(AMOUNT)
                .expiresAt(pastExpiry)
                .backingStrategy(BackingStrategy.FIXED.name())
                .issuanceRatio(1.0)
                .faceDecimals(0)
                .build();
        return VoucherSignatureService.createSigned(secret, ISSUER_PRIVKEY, ISSUER_PUBKEY);
    }

    /**
     * Helper method to create a voucher with invalid signature.
     */
    private SignedVoucher createInvalidSignatureVoucher(String issuerId) {
        VoucherSecret secret = VoucherSecret.builder()
                .issuerId(issuerId)
                .unit(UNIT)
                .faceValue(AMOUNT)
                .backingStrategy(BackingStrategy.FIXED.name())
                .issuanceRatio(1.0)
                .faceDecimals(0)
                .build();
        SignedVoucher valid = VoucherSignatureService.createSigned(secret, ISSUER_PRIVKEY, ISSUER_PUBKEY);

        // Corrupt the signature - need new secret since signature modifies it
        VoucherSecret secret2 = VoucherSecret.builder()
                .voucherId(secret.getVoucherId())
                .issuerId(issuerId)
                .unit(UNIT)
                .faceValue(AMOUNT)
                .backingStrategy(BackingStrategy.FIXED.name())
                .issuanceRatio(1.0)
                .faceDecimals(0)
                .build();
        byte[] corruptedSignature = valid.getIssuerSignature().clone();
        corruptedSignature[0] ^= 0xFF;

        return new SignedVoucher(secret2, corruptedSignature, valid.getIssuerPublicKey());
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should create service with valid ledger port")
        void shouldCreateServiceWithValidLedgerPort() {
            // When / Then
            assertThatNoException().isThrownBy(() ->
                    new MerchantVerificationService(ledgerPort)
            );
        }

        @Test
        @DisplayName("should reject null ledger port")
        void shouldRejectNullLedgerPort() {
            // When / Then
            assertThatThrownBy(() ->
                    new MerchantVerificationService(null)
            ).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("verifyOffline()")
    class VerifyOfflineTests {

        @Test
        @DisplayName("should verify valid voucher with matching issuer")
        void shouldVerifyValidVoucherWithMatchingIssuer() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);

            // When
            MerchantVerificationService.VerificationResult result =
                    service.verifyOffline(voucher, ISSUER_ID);

            // Then
            assertThat(result.isValid()).isTrue();
            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getErrorMessage()).isEmpty();
        }

        @Test
        @DisplayName("should reject voucher with wrong issuer (Model B)")
        void shouldRejectVoucherWithWrongIssuer() {
            // Given
            SignedVoucher voucher = createValidVoucher("different-merchant");

            // When
            MerchantVerificationService.VerificationResult result =
                    service.verifyOffline(voucher, ISSUER_ID);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrorMessage())
                    .contains("different-merchant")
                    .contains(ISSUER_ID)
                    .contains("Model B");
        }

        @Test
        @DisplayName("should reject expired voucher")
        void shouldRejectExpiredVoucher() {
            // Given
            SignedVoucher voucher = createExpiredVoucher(ISSUER_ID);

            // When
            MerchantVerificationService.VerificationResult result =
                    service.verifyOffline(voucher, ISSUER_ID);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).isNotEmpty();
            assertThat(result.getErrorMessage()).contains("expired");
        }

        @Test
        @DisplayName("should reject voucher with invalid signature")
        void shouldRejectVoucherWithInvalidSignature() {
            // Given
            SignedVoucher voucher = createInvalidSignatureVoucher(ISSUER_ID);

            // When
            MerchantVerificationService.VerificationResult result =
                    service.verifyOffline(voucher, ISSUER_ID);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).isNotEmpty();
            assertThat(result.getErrorMessage()).contains("signature");
        }

        @Test
        @DisplayName("should reject null voucher")
        void shouldRejectNullVoucher() {
            // When / Then
            assertThatThrownBy(() ->
                    service.verifyOffline(null, ISSUER_ID)
            ).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject blank expected issuer ID")
        void shouldRejectBlankExpectedIssuerId() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);

            // When / Then
            assertThatThrownBy(() ->
                    service.verifyOffline(voucher, "")
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Expected issuer ID cannot be blank");
        }
    }

    @Nested
    @DisplayName("verifyOnline()")
    class VerifyOnlineTests {

        @Test
        @DisplayName("should verify valid voucher with ISSUED status in ledger")
        void shouldVerifyValidVoucherWithIssuedStatus() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String voucherId = voucher.getSecret().getVoucherId().toString();
            when(ledgerPort.queryStatus(voucherId)).thenReturn(Optional.of(VoucherStatus.ISSUED));

            // When
            MerchantVerificationService.VerificationResult result =
                    service.verifyOnline(voucher, ISSUER_ID);

            // Then
            assertThat(result.isValid()).isTrue();
            assertThat(result.getErrors()).isEmpty();
            verify(ledgerPort).queryStatus(voucherId);
        }

        @Test
        @DisplayName("should fail if offline verification fails")
        void shouldFailIfOfflineVerificationFails() {
            // Given - expired voucher
            SignedVoucher voucher = createExpiredVoucher(ISSUER_ID);

            // When
            MerchantVerificationService.VerificationResult result =
                    service.verifyOnline(voucher, ISSUER_ID);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorMessage()).contains("expired");
            // Should not query ledger if offline verification fails
            verifyNoInteractions(ledgerPort);
        }

        @Test
        @DisplayName("should reject voucher not found in ledger")
        void shouldRejectVoucherNotFoundInLedger() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String voucherId = voucher.getSecret().getVoucherId().toString();
            when(ledgerPort.queryStatus(voucherId)).thenReturn(Optional.empty());

            // When
            MerchantVerificationService.VerificationResult result =
                    service.verifyOnline(voucher, ISSUER_ID);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorMessage()).contains("not found in public ledger");
            verify(ledgerPort).queryStatus(voucherId);
        }

        @Test
        @DisplayName("should detect double-spend (REDEEMED status)")
        void shouldDetectDoubleSpend() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String voucherId = voucher.getSecret().getVoucherId().toString();
            when(ledgerPort.queryStatus(voucherId)).thenReturn(Optional.of(VoucherStatus.REDEEMED));

            // When
            MerchantVerificationService.VerificationResult result =
                    service.verifyOnline(voucher, ISSUER_ID);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorMessage())
                    .contains("already redeemed")
                    .contains("double-spend");
            verify(ledgerPort).queryStatus(voucherId);
        }

        @Test
        @DisplayName("should reject REVOKED voucher")
        void shouldRejectRevokedVoucher() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String voucherId = voucher.getSecret().getVoucherId().toString();
            when(ledgerPort.queryStatus(voucherId)).thenReturn(Optional.of(VoucherStatus.REVOKED));

            // When
            MerchantVerificationService.VerificationResult result =
                    service.verifyOnline(voucher, ISSUER_ID);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorMessage()).contains("revoked by issuer");
            verify(ledgerPort).queryStatus(voucherId);
        }

        @Test
        @DisplayName("should reject EXPIRED voucher from ledger")
        void shouldRejectExpiredVoucherFromLedger() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String voucherId = voucher.getSecret().getVoucherId().toString();
            when(ledgerPort.queryStatus(voucherId)).thenReturn(Optional.of(VoucherStatus.EXPIRED));

            // When
            MerchantVerificationService.VerificationResult result =
                    service.verifyOnline(voucher, ISSUER_ID);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorMessage()).contains("expired");
            verify(ledgerPort).queryStatus(voucherId);
        }

        @Test
        @DisplayName("should handle ledger query exception")
        void shouldHandleLedgerQueryException() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String voucherId = voucher.getSecret().getVoucherId().toString();
            when(ledgerPort.queryStatus(voucherId))
                    .thenThrow(new RuntimeException("Network timeout"));

            // When
            MerchantVerificationService.VerificationResult result =
                    service.verifyOnline(voucher, ISSUER_ID);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorMessage())
                    .contains("Failed to query voucher status")
                    .contains("Network timeout");
        }

        @Test
        @DisplayName("should reject blank expected issuer ID")
        void shouldRejectBlankExpectedIssuerId() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);

            // When / Then
            assertThatThrownBy(() ->
                    service.verifyOnline(voucher, "")
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Expected issuer ID cannot be blank");
        }
    }

    @Nested
    @DisplayName("markRedeemed()")
    class MarkRedeemedTests {

        @Test
        @DisplayName("should mark voucher as redeemed successfully")
        void shouldMarkVoucherAsRedeemed() {
            // Given
            String voucherId = "test-voucher-id";

            // When
            service.markRedeemed(voucherId);

            // Then
            verify(ledgerPort).updateStatus(voucherId, VoucherStatus.REDEEMED);
        }

        @Test
        @DisplayName("should reject blank voucher ID")
        void shouldRejectBlankVoucherId() {
            // When / Then
            assertThatThrownBy(() ->
                    service.markRedeemed("")
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Voucher ID cannot be blank");
        }

        @Test
        @DisplayName("should wrap ledger update exception")
        void shouldWrapLedgerUpdateException() {
            // Given
            String voucherId = "test-voucher-id";
            doThrow(new RuntimeException("Database error"))
                    .when(ledgerPort).updateStatus(anyString(), any());

            // When / Then
            assertThatThrownBy(() ->
                    service.markRedeemed(voucherId)
            ).isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to mark voucher as redeemed");
        }
    }

    @Nested
    @DisplayName("redeem()")
    class RedeemTests {

        @Test
        @DisplayName("should redeem voucher successfully with online verification")
        void shouldRedeemVoucherWithOnlineVerification() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String voucherId = voucher.getSecret().getVoucherId().toString();

            RedeemVoucherRequest request = RedeemVoucherRequest.builder()
                    .token("cashuA" + voucherId)
                    .merchantId(ISSUER_ID)
                    .verifyOnline(true)
                    .build();

            when(ledgerPort.queryStatus(voucherId)).thenReturn(Optional.of(VoucherStatus.ISSUED));

            // When
            RedeemVoucherResponse response = service.redeem(request, voucher);

            // Then
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getErrorMessage()).isNull();
            assertThat(response.getVoucher()).isEqualTo(voucher);
            assertThat(response.getAmount()).isEqualTo(AMOUNT);

            verify(ledgerPort).queryStatus(voucherId);
            verify(ledgerPort).updateStatus(voucherId, VoucherStatus.REDEEMED);
        }

        @Test
        @DisplayName("should redeem voucher with offline verification")
        void shouldRedeemVoucherWithOfflineVerification() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String voucherId = voucher.getSecret().getVoucherId().toString();

            RedeemVoucherRequest request = RedeemVoucherRequest.builder()
                    .token("cashuA" + voucherId)
                    .merchantId(ISSUER_ID)
                    .verifyOnline(false)
                    .build();

            // When
            RedeemVoucherResponse response = service.redeem(request, voucher);

            // Then
            assertThat(response.isSuccess()).isTrue();

            // Should not query ledger for offline verification
            verify(ledgerPort, never()).queryStatus(anyString());
            // But should still mark as redeemed
            verify(ledgerPort).updateStatus(voucherId, VoucherStatus.REDEEMED);
        }

        @Test
        @DisplayName("should fail redemption if verification fails")
        void shouldFailRedemptionIfVerificationFails() {
            // Given - expired voucher
            SignedVoucher voucher = createExpiredVoucher(ISSUER_ID);

            RedeemVoucherRequest request = RedeemVoucherRequest.builder()
                    .token("cashuAtest")
                    .merchantId(ISSUER_ID)
                    .verifyOnline(true)
                    .build();

            // When
            RedeemVoucherResponse response = service.redeem(request, voucher);

            // Then
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorMessage()).contains("expired");

            // Should not mark as redeemed if verification fails
            verify(ledgerPort, never()).updateStatus(anyString(), any());
        }

        @Test
        @DisplayName("should fail if marking as redeemed fails")
        void shouldFailIfMarkingFails() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String voucherId = voucher.getSecret().getVoucherId().toString();

            RedeemVoucherRequest request = RedeemVoucherRequest.builder()
                    .token("cashuA" + voucherId)
                    .merchantId(ISSUER_ID)
                    .verifyOnline(true)
                    .build();

            when(ledgerPort.queryStatus(voucherId)).thenReturn(Optional.of(VoucherStatus.ISSUED));
            doThrow(new RuntimeException("Ledger error"))
                    .when(ledgerPort).updateStatus(anyString(), any());

            // When
            RedeemVoucherResponse response = service.redeem(request, voucher);

            // Then
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorMessage())
                    .contains("Verification passed but failed to mark as redeemed");
        }

        @Test
        @DisplayName("should reject null request")
        void shouldRejectNullRequest() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);

            // When / Then
            assertThatThrownBy(() ->
                    service.redeem(null, voucher)
            ).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject null voucher")
        void shouldRejectNullVoucher() {
            // Given
            RedeemVoucherRequest request = RedeemVoucherRequest.builder()
                    .token("cashuAtest")
                    .merchantId(ISSUER_ID)
                    .verifyOnline(true)
                    .build();

            // When / Then
            assertThatThrownBy(() ->
                    service.redeem(request, null)
            ).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("VerificationResult")
    class VerificationResultTests {

        @Test
        @DisplayName("success() should create valid result")
        void successShouldCreateValidResult() {
            // When
            MerchantVerificationService.VerificationResult result =
                    MerchantVerificationService.VerificationResult.success();

            // Then
            assertThat(result.isValid()).isTrue();
            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getErrorMessage()).isEmpty();
        }

        @Test
        @DisplayName("failure(String) should create invalid result with single error")
        void failureWithStringShouldCreateInvalidResult() {
            // When
            MerchantVerificationService.VerificationResult result =
                    MerchantVerificationService.VerificationResult.failure("Test error");

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).containsExactly("Test error");
            assertThat(result.getErrorMessage()).isEqualTo("Test error");
        }

        @Test
        @DisplayName("failure(List) should create invalid result with multiple errors")
        void failureWithListShouldCreateInvalidResult() {
            // Given
            var errors = java.util.List.of("Error 1", "Error 2", "Error 3");

            // When
            MerchantVerificationService.VerificationResult result =
                    MerchantVerificationService.VerificationResult.failure(errors);

            // Then
            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrors()).containsExactlyElementsOf(errors);
            assertThat(result.getErrorMessage()).isEqualTo("Error 1; Error 2; Error 3");
        }

        @Test
        @DisplayName("getErrors() should return unmodifiable list")
        void getErrorsShouldReturnUnmodifiableList() {
            // Given
            MerchantVerificationService.VerificationResult result =
                    MerchantVerificationService.VerificationResult.failure("Test error");

            // When / Then
            assertThatThrownBy(() ->
                    result.getErrors().add("Another error")
            ).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /**
     * End-to-end tests for merchant voucher verification workflow.
     *
     * <p>These tests verify the complete flow of merchant verification,
     * from receiving a voucher to verifying it offline and online.
     */
    @Nested
    @DisplayName("E2E Merchant Verification Tests")
    class E2EMerchantVerificationTests {

        /**
         * E2E Test: Complete offline verification workflow.
         *
         * <p>This test demonstrates the full offline verification flow:
         * <ol>
         *   <li>Merchant receives a voucher from customer</li>
         *   <li>Merchant verifies voucher signature (offline)</li>
         *   <li>Merchant checks expiry (offline)</li>
         *   <li>Merchant confirms issuer matches (offline)</li>
         *   <li>Verification succeeds without network access</li>
         * </ol>
         *
         * <p>This satisfies part of task 6.4: "Write E2E test: Merchant verify"
         */
        @Test
        @DisplayName("E2E: Complete offline verification workflow")
        void e2eTest_CompleteOfflineVerificationWorkflow() {
            // ========== STEP 1: Customer presents voucher to merchant ==========
            // Simulate a customer presenting a valid voucher to a merchant
            SignedVoucher customerVoucher = createValidVoucher(ISSUER_ID);

            // Verify voucher has valid properties
            assertThat(customerVoucher.getSecret().getIssuerId()).isEqualTo(ISSUER_ID);
            assertThat(customerVoucher.getSecret().getFaceValue()).isEqualTo(AMOUNT);
            assertThat(customerVoucher.getSecret().getUnit()).isEqualTo(UNIT);

            // ========== STEP 2: Merchant performs offline verification ==========
            // Merchant verifies without network access
            MerchantVerificationService.VerificationResult result =
                    service.verifyOffline(customerVoucher, ISSUER_ID);

            // ========== STEP 3: Verify signature is valid ==========
            assertThat(result.isValid())
                    .as("Voucher signature should be valid")
                    .isTrue();

            // ========== STEP 4: Verify no errors ==========
            assertThat(result.getErrors())
                    .as("No validation errors should occur")
                    .isEmpty();

            // ========== STEP 5: Verify voucher properties match ==========
            assertThat(customerVoucher.getSecret().getIssuerId())
                    .as("Issuer ID should match merchant")
                    .isEqualTo(ISSUER_ID);

            assertThat(customerVoucher.isExpired())
                    .as("Voucher should not be expired")
                    .isFalse();

            assertThat(customerVoucher.verify())
                    .as("Voucher signature should be cryptographically valid")
                    .isTrue();

            // ========== STEP 6: Merchant can accept voucher ==========
            // At this point, merchant knows voucher is valid offline
            // and can proceed with redemption
            assertThat(result.isValid()).isTrue();
        }

        /**
         * E2E Test: Complete online verification workflow.
         *
         * <p>This test demonstrates the full online verification flow:
         * <ol>
         *   <li>Merchant receives a voucher from customer</li>
         *   <li>Merchant performs offline checks first</li>
         *   <li>Merchant queries Nostr ledger for voucher status</li>
         *   <li>Merchant confirms voucher is ISSUED (not already redeemed)</li>
         *   <li>Verification succeeds with ledger confirmation</li>
         * </ol>
         */
        @Test
        @DisplayName("E2E: Complete online verification workflow")
        void e2eTest_CompleteOnlineVerificationWorkflow() {
            // ========== STEP 1: Customer presents voucher ==========
            SignedVoucher customerVoucher = createValidVoucher(ISSUER_ID);
            String voucherId = customerVoucher.getSecret().getVoucherId().toString();

            // ========== STEP 2: Mock Nostr ledger response ==========
            // Simulate Nostr ledger confirming voucher is ISSUED
            when(ledgerPort.queryStatus(voucherId))
                    .thenReturn(Optional.of(VoucherStatus.ISSUED));

            // ========== STEP 3: Merchant performs online verification ==========
            MerchantVerificationService.VerificationResult result =
                    service.verifyOnline(customerVoucher, ISSUER_ID);

            // ========== STEP 4: Verify offline checks passed ==========
            assertThat(result.isValid())
                    .as("Online verification should succeed")
                    .isTrue();
            assertThat(result.getErrors()).isEmpty();

            // ========== STEP 5: Verify ledger was queried ==========
            verify(ledgerPort, times(1))
                    .queryStatus(voucherId);

            // ========== STEP 6: Verify voucher status is ISSUED ==========
            Optional<VoucherStatus> ledgerStatus = ledgerPort.queryStatus(voucherId);
            assertThat(ledgerStatus)
                    .as("Ledger should return voucher status")
                    .isPresent();
            assertThat(ledgerStatus.get())
                    .as("Voucher should be in ISSUED state")
                    .isEqualTo(VoucherStatus.ISSUED);

            // ========== STEP 7: Merchant can safely accept voucher ==========
            // Voucher passed both offline and online checks
            assertThat(result.isValid()).isTrue();
        }

        /**
         * E2E Test: Merchant rejects double-spend attempt.
         *
         * <p>This test verifies that merchants can detect when a voucher
         * has already been redeemed (double-spend protection via Nostr ledger).
         */
        @Test
        @DisplayName("E2E: Merchant detects and rejects double-spend attempt")
        void e2eTest_MerchantRejectsDoubleSpend() {
            // ========== STEP 1: Customer attempts to use already-redeemed voucher ==========
            SignedVoucher alreadyRedeemedVoucher = createValidVoucher(ISSUER_ID);
            String voucherId = alreadyRedeemedVoucher.getSecret().getVoucherId().toString();

            // ========== STEP 2: Nostr ledger shows voucher was already redeemed ==========
            when(ledgerPort.queryStatus(voucherId))
                    .thenReturn(Optional.of(VoucherStatus.REDEEMED));

            // ========== STEP 3: Merchant performs online verification ==========
            MerchantVerificationService.VerificationResult result =
                    service.verifyOnline(alreadyRedeemedVoucher, ISSUER_ID);

            // ========== STEP 4: Verification fails ==========
            assertThat(result.isValid())
                    .as("Double-spend attempt should be rejected")
                    .isFalse();

            // ========== STEP 5: Error indicates double-spend ==========
            assertThat(result.getErrors())
                    .as("Should have double-spend error")
                    .hasSize(1);
            assertThat(result.getErrorMessage())
                    .as("Error should mention voucher already redeemed")
                    .containsIgnoringCase("already redeemed")
                    .containsIgnoringCase("double-spend");

            // ========== STEP 6: Merchant rejects transaction ==========
            verify(ledgerPort, times(1)).queryStatus(voucherId);
            assertThat(result.isValid()).isFalse();
        }

        /**
         * E2E Test: Merchant rejects expired voucher.
         *
         * <p>This test verifies that merchants properly reject expired vouchers
         * during offline verification.
         */
        @Test
        @DisplayName("E2E: Merchant rejects expired voucher")
        void e2eTest_MerchantRejectsExpiredVoucher() {
            // ========== STEP 1: Customer presents expired voucher ==========
            SignedVoucher expiredVoucher = createExpiredVoucher(ISSUER_ID);

            // Verify voucher is actually expired
            assertThat(expiredVoucher.isExpired())
                    .as("Test voucher should be expired")
                    .isTrue();

            // ========== STEP 2: Merchant performs offline verification ==========
            MerchantVerificationService.VerificationResult result =
                    service.verifyOffline(expiredVoucher, ISSUER_ID);

            // ========== STEP 3: Verification fails ==========
            assertThat(result.isValid())
                    .as("Expired voucher should be rejected")
                    .isFalse();

            // ========== STEP 4: Error indicates expiry ==========
            assertThat(result.getErrors())
                    .as("Should have expiry error")
                    .isNotEmpty();
            assertThat(result.getErrorMessage())
                    .as("Error should mention expiry")
                    .containsIgnoringCase("expired");

            // ========== STEP 5: Merchant rejects transaction ==========
            assertThat(result.isValid()).isFalse();
        }

        /**
         * E2E Test: Merchant rejects voucher from different issuer.
         *
         * <p>This test verifies Model B enforcement: merchants only accept
         * vouchers they themselves issued.
         */
        @Test
        @DisplayName("E2E: Merchant rejects voucher from different issuer (Model B)")
        void e2eTest_MerchantRejectsWrongIssuer() {
            // ========== STEP 1: Customer presents voucher from different merchant ==========
            String differentIssuer = "other-merchant-456";
            SignedVoucher otherMerchantVoucher = createValidVoucher(differentIssuer);

            // Verify voucher is for a different issuer
            assertThat(otherMerchantVoucher.getSecret().getIssuerId())
                    .as("Voucher should be from different issuer")
                    .isNotEqualTo(ISSUER_ID);

            // ========== STEP 2: Merchant performs offline verification ==========
            // Merchant checks if voucher is for their store (ISSUER_ID)
            MerchantVerificationService.VerificationResult result =
                    service.verifyOffline(otherMerchantVoucher, ISSUER_ID);

            // ========== STEP 3: Verification fails (Model B enforcement) ==========
            assertThat(result.isValid())
                    .as("Voucher from different issuer should be rejected")
                    .isFalse();

            // ========== STEP 4: Error indicates wrong issuer ==========
            assertThat(result.getErrors())
                    .as("Should have issuer mismatch error")
                    .isNotEmpty();
            assertThat(result.getErrorMessage())
                    .as("Error should mention wrong merchant")
                    .containsIgnoringCase("merchant")
                    .containsIgnoringCase("issuer");

            // ========== STEP 5: Merchant rejects transaction ==========
            // This is Model B in action: voucher can only be used at issuing merchant
            assertThat(result.isValid()).isFalse();
        }

        /**
         * E2E Test: Complete redemption workflow.
         *
         * <p>This test demonstrates the full redemption flow from verification to marking redeemed:
         * <ol>
         *   <li>Merchant verifies voucher (offline and online)</li>
         *   <li>Merchant marks voucher as redeemed in Nostr ledger</li>
         *   <li>Merchant provides goods/services to customer</li>
         * </ol>
         */
        @Test
        @DisplayName("E2E: Complete verification and redemption workflow")
        void e2eTest_CompleteVerificationAndRedemption() {
            // ========== STEP 1: Customer presents valid voucher ==========
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String voucherId = voucher.getSecret().getVoucherId().toString();

            // ========== STEP 2: Merchant verifies online ==========
            when(ledgerPort.queryStatus(voucherId))
                    .thenReturn(Optional.of(VoucherStatus.ISSUED));

            MerchantVerificationService.VerificationResult verifyResult =
                    service.verifyOnline(voucher, ISSUER_ID);

            assertThat(verifyResult.isValid())
                    .as("Verification should succeed")
                    .isTrue();

            // ========== STEP 3: Merchant redeems voucher ==========
            RedeemVoucherRequest redeemRequest = RedeemVoucherRequest.builder()
                    .merchantId(ISSUER_ID)
                    .build();

            doNothing().when(ledgerPort).updateStatus(eq(voucherId), eq(VoucherStatus.REDEEMED));

            RedeemVoucherResponse redeemResponse = service.redeem(redeemRequest, voucher);

            // ========== STEP 4: Verify redemption succeeded ==========
            assertThat(redeemResponse.isSuccess())
                    .as("Redemption should succeed")
                    .isTrue();
            assertThat(redeemResponse.getVoucherId())
                    .as("Response should include voucher ID")
                    .isEqualTo(voucherId);
            assertThat(redeemResponse.getAmount())
                    .as("Response should include voucher amount")
                    .isEqualTo(AMOUNT);

            // ========== STEP 5: Verify ledger was updated ==========
            verify(ledgerPort, times(1))
                    .updateStatus(voucherId, VoucherStatus.REDEEMED);

            // ========== STEP 6: Merchant provides goods/services ==========
            // At this point, voucher is marked redeemed in Nostr ledger
            // and merchant can safely provide goods/services
            assertThat(redeemResponse.isSuccess()).isTrue();
        }
    }
}
