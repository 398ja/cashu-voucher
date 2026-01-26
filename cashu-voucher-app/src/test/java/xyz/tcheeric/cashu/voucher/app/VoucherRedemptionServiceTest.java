package xyz.tcheeric.cashu.voucher.app;

import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.cashu.common.VoucherSecret;
import xyz.tcheeric.cashu.voucher.app.dto.RedeemVoucherResponse;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherRedemptionPort;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherFingerprint;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VoucherRedemptionService}.
 *
 * <p>This test class verifies:
 * <ul>
 *   <li>Successful redemption flow</li>
 *   <li>Duplicate detection via fingerprint</li>
 *   <li>Model B merchant verification</li>
 *   <li>Expiry and signature validation</li>
 *   <li>Concurrent redemption handling</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VoucherRedemptionService")
class VoucherRedemptionServiceTest {

    @Mock
    private VoucherLedgerPort ledgerPort;

    @Mock
    private VoucherRedemptionPort redemptionPort;

    private VoucherRedemptionService service;

    private static String issuerPrivateKeyHex;
    private static String issuerPublicKeyHex;
    private static final String ISSUER_ID = "merchant123";
    private static final String UNIT = "sat";
    private static final long FACE_VALUE = 1000L;
    private static final BackingStrategy DEFAULT_STRATEGY = BackingStrategy.PROPORTIONAL;
    private static final double DEFAULT_ISSUANCE_RATIO = 0.01;
    private static final int DEFAULT_FACE_DECIMALS = 2;

    @BeforeEach
    void setUp() {
        // Generate test keys
        byte[] privateKeyBytes = Schnorr.generatePrivateKey();
        byte[] publicKeyBytes = Schnorr.genPubKey(privateKeyBytes);
        issuerPrivateKeyHex = Hex.toHexString(privateKeyBytes);
        issuerPublicKeyHex = Hex.toHexString(publicKeyBytes);

        service = new VoucherRedemptionService(ledgerPort, redemptionPort);
    }

    private SignedVoucher createValidVoucher(String issuerId) {
        return createValidVoucher(issuerId, null);
    }

    private SignedVoucher createValidVoucher(String issuerId, Long expiresAt) {
        VoucherSecret secret = VoucherSecret.builder()
                .issuerId(issuerId)
                .unit(UNIT)
                .faceValue(FACE_VALUE)
                .expiresAt(expiresAt)
                .backingStrategy(DEFAULT_STRATEGY.name())
                .issuanceRatio(DEFAULT_ISSUANCE_RATIO)
                .faceDecimals(DEFAULT_FACE_DECIMALS)
                .build();
        return VoucherSignatureService.createSigned(secret, issuerPrivateKeyHex, issuerPublicKeyHex);
    }

    private SignedVoucher createExpiredVoucher(String issuerId) {
        // Set expiry to 1 hour ago
        Long expiresAt = Instant.now().minusSeconds(3600).getEpochSecond();
        return createValidVoucher(issuerId, expiresAt);
    }

    @Nested
    @DisplayName("Successful Redemption")
    class SuccessfulRedemptionTests {

        @Test
        @DisplayName("should successfully redeem a valid voucher")
        void shouldSuccessfullyRedeemValidVoucher() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String fingerprint = VoucherFingerprint.compute(voucher);

            when(redemptionPort.isRedeemed(fingerprint)).thenReturn(false);
            when(redemptionPort.tryRecordRedemption(eq(fingerprint), anyString(), eq(ISSUER_ID))).thenReturn(true);
            when(ledgerPort.queryStatus(anyString())).thenReturn(Optional.of(VoucherStatus.ISSUED));

            // When
            RedeemVoucherResponse response = service.redeem(voucher, ISSUER_ID);

            // Then
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getFingerprint()).isEqualTo(fingerprint);
            assertThat(response.getAmount()).isEqualTo(FACE_VALUE);
            assertThat(response.getUnit()).isEqualTo(UNIT);

            verify(redemptionPort).tryRecordRedemption(eq(fingerprint), anyString(), eq(ISSUER_ID));
            verify(ledgerPort).updateStatus(anyString(), eq(VoucherStatus.REDEEMED));
        }

        @Test
        @DisplayName("should skip online verification when disabled")
        void shouldSkipOnlineVerificationWhenDisabled() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String fingerprint = VoucherFingerprint.compute(voucher);

            when(redemptionPort.isRedeemed(fingerprint)).thenReturn(false);
            when(redemptionPort.tryRecordRedemption(eq(fingerprint), anyString(), eq(ISSUER_ID))).thenReturn(true);

            // When
            RedeemVoucherResponse response = service.redeem(voucher, ISSUER_ID, false);

            // Then
            assertThat(response.isSuccess()).isTrue();
            verify(ledgerPort, never()).queryStatus(anyString());
        }
    }

    @Nested
    @DisplayName("Duplicate Detection")
    class DuplicateDetectionTests {

        @Test
        @DisplayName("should reject already redeemed voucher (fingerprint check)")
        void shouldRejectAlreadyRedeemedVoucherByFingerprint() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String fingerprint = VoucherFingerprint.compute(voucher);

            when(redemptionPort.isRedeemed(fingerprint)).thenReturn(true);

            // When
            RedeemVoucherResponse response = service.redeem(voucher, ISSUER_ID);

            // Then
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getError()).isEqualTo("DUPLICATE_REDEMPTION");
            assertThat(response.getFingerprint()).isEqualTo(fingerprint);

            verify(redemptionPort, never()).tryRecordRedemption(any(), any(), any());
        }

        @Test
        @DisplayName("should handle concurrent redemption attempt")
        void shouldHandleConcurrentRedemptionAttempt() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String fingerprint = VoucherFingerprint.compute(voucher);

            when(redemptionPort.isRedeemed(fingerprint)).thenReturn(false);
            when(ledgerPort.queryStatus(anyString())).thenReturn(Optional.of(VoucherStatus.ISSUED));
            // Simulate race condition - another thread recorded first
            when(redemptionPort.tryRecordRedemption(eq(fingerprint), anyString(), eq(ISSUER_ID))).thenReturn(false);

            // When
            RedeemVoucherResponse response = service.redeem(voucher, ISSUER_ID);

            // Then
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getError()).isEqualTo("CONCURRENT_REDEMPTION");
        }
    }

    @Nested
    @DisplayName("Validation Failures")
    class ValidationFailureTests {

        @Test
        @DisplayName("should reject expired voucher")
        void shouldRejectExpiredVoucher() {
            // Given
            SignedVoucher voucher = createExpiredVoucher(ISSUER_ID);
            String fingerprint = VoucherFingerprint.compute(voucher);

            when(redemptionPort.isRedeemed(fingerprint)).thenReturn(false);

            // When
            RedeemVoucherResponse response = service.redeem(voucher, ISSUER_ID);

            // Then
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getError()).isEqualTo("VOUCHER_EXPIRED");
        }

        @Test
        @DisplayName("should reject voucher with mismatched merchant (Model B)")
        void shouldRejectMismatchedMerchant() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String fingerprint = VoucherFingerprint.compute(voucher);
            String wrongMerchant = "different-merchant";

            when(redemptionPort.isRedeemed(fingerprint)).thenReturn(false);

            // When
            RedeemVoucherResponse response = service.redeem(voucher, wrongMerchant);

            // Then
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getError()).isEqualTo("MERCHANT_MISMATCH");
        }

        @Test
        @DisplayName("should reject voucher not found in ledger")
        void shouldRejectVoucherNotFoundInLedger() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String fingerprint = VoucherFingerprint.compute(voucher);

            when(redemptionPort.isRedeemed(fingerprint)).thenReturn(false);
            when(ledgerPort.queryStatus(anyString())).thenReturn(Optional.empty());

            // When
            RedeemVoucherResponse response = service.redeem(voucher, ISSUER_ID);

            // Then
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getError()).isEqualTo("VOUCHER_NOT_FOUND");
        }

        @Test
        @DisplayName("should reject voucher in non-redeemable state")
        void shouldRejectVoucherInNonRedeemableState() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String fingerprint = VoucherFingerprint.compute(voucher);

            when(redemptionPort.isRedeemed(fingerprint)).thenReturn(false);
            when(ledgerPort.queryStatus(anyString())).thenReturn(Optional.of(VoucherStatus.REVOKED));

            // When
            RedeemVoucherResponse response = service.redeem(voucher, ISSUER_ID);

            // Then
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getError()).isEqualTo("INVALID_STATE");
        }
    }

    @Nested
    @DisplayName("Ledger Update Handling")
    class LedgerUpdateHandlingTests {

        @Test
        @DisplayName("should succeed even if ledger update fails")
        void shouldSucceedEvenIfLedgerUpdateFails() {
            // Given - redemption recorded but ledger update fails
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String fingerprint = VoucherFingerprint.compute(voucher);

            when(redemptionPort.isRedeemed(fingerprint)).thenReturn(false);
            when(redemptionPort.tryRecordRedemption(eq(fingerprint), anyString(), eq(ISSUER_ID))).thenReturn(true);
            when(ledgerPort.queryStatus(anyString())).thenReturn(Optional.of(VoucherStatus.ISSUED));
            doThrow(new RuntimeException("Ledger update failed")).when(ledgerPort).updateStatus(anyString(), any());

            // When
            RedeemVoucherResponse response = service.redeem(voucher, ISSUER_ID);

            // Then - still succeeds because fingerprint prevents re-redemption
            assertThat(response.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("Helper Methods")
    class HelperMethodTests {

        @Test
        @DisplayName("isRedeemed should check redemption port")
        void isRedeemedShouldCheckRedemptionPort() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String fingerprint = VoucherFingerprint.compute(voucher);

            when(redemptionPort.isRedeemed(fingerprint)).thenReturn(true);

            // When
            boolean result = service.isRedeemed(voucher);

            // Then
            assertThat(result).isTrue();
            verify(redemptionPort).isRedeemed(fingerprint);
        }

        @Test
        @DisplayName("getRedemptionRecord should return record from port")
        void getRedemptionRecordShouldReturnRecordFromPort() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);
            String fingerprint = VoucherFingerprint.compute(voucher);
            VoucherRedemptionPort.RedemptionRecord record = new VoucherRedemptionPort.RedemptionRecord(
                    fingerprint, voucher.getVoucherId().toString(), ISSUER_ID, Instant.now());

            when(redemptionPort.getRedemption(fingerprint)).thenReturn(Optional.of(record));

            // When
            Optional<VoucherRedemptionPort.RedemptionRecord> result = service.getRedemptionRecord(voucher);

            // Then
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(record);
        }
    }

    @Nested
    @DisplayName("Input Validation")
    class InputValidationTests {

        @Test
        @DisplayName("should reject null voucher")
        void shouldRejectNullVoucher() {
            // When / Then
            assertThatThrownBy(() -> service.redeem(null, ISSUER_ID))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject null merchant ID")
        void shouldRejectNullMerchantId() {
            // Given
            SignedVoucher voucher = createValidVoucher(ISSUER_ID);

            // When / Then
            assertThatThrownBy(() -> service.redeem(voucher, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
