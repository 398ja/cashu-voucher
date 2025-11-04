package xyz.tcheeric.cashu.voucher.app;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
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
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.security.SecureRandom;
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
        // Generate a real ED25519 key pair for valid signatures
        SecureRandom random = new SecureRandom();
        byte[] privateKeyBytes = new byte[32];
        random.nextBytes(privateKeyBytes);

        Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(privateKeyBytes, 0);
        byte[] publicKeyBytes = privateKey.generatePublicKey().getEncoded();

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
        VoucherSecret secret = VoucherSecret.create(
                issuerId,
                UNIT,
                AMOUNT,
                null, // No expiry
                null  // No memo
        );
        return VoucherSignatureService.createSigned(secret, ISSUER_PRIVKEY, ISSUER_PUBKEY);
    }

    /**
     * Helper method to create an expired voucher.
     */
    private SignedVoucher createExpiredVoucher(String issuerId) {
        long pastExpiry = (System.currentTimeMillis() / 1000) - 86400; // 1 day ago
        VoucherSecret secret = VoucherSecret.create(
                issuerId,
                UNIT,
                AMOUNT,
                pastExpiry,
                null
        );
        return VoucherSignatureService.createSigned(secret, ISSUER_PRIVKEY, ISSUER_PUBKEY);
    }

    /**
     * Helper method to create a voucher with invalid signature.
     */
    private SignedVoucher createInvalidSignatureVoucher(String issuerId) {
        VoucherSecret secret = VoucherSecret.create(
                issuerId,
                UNIT,
                AMOUNT,
                null,
                null
        );
        SignedVoucher valid = VoucherSignatureService.createSigned(secret, ISSUER_PRIVKEY, ISSUER_PUBKEY);

        // Corrupt the signature
        byte[] corruptedSignature = valid.getIssuerSignature().clone();
        corruptedSignature[0] ^= 0xFF;

        return new SignedVoucher(secret, corruptedSignature, valid.getIssuerPublicKey());
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
            String voucherId = voucher.getSecret().getVoucherId();
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
            String voucherId = voucher.getSecret().getVoucherId();
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
            String voucherId = voucher.getSecret().getVoucherId();
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
            String voucherId = voucher.getSecret().getVoucherId();
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
            String voucherId = voucher.getSecret().getVoucherId();
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
            String voucherId = voucher.getSecret().getVoucherId();
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
            String voucherId = voucher.getSecret().getVoucherId();

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
            String voucherId = voucher.getSecret().getVoucherId();

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
            String voucherId = voucher.getSecret().getVoucherId();

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
}
