package xyz.tcheeric.cashu.voucher.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherRequest;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherResponse;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherBackupPort;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VoucherService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VoucherService")
class VoucherServiceTest {

    @Mock
    private VoucherLedgerPort ledgerPort;

    @Mock
    private VoucherBackupPort backupPort;

    private VoucherService service;

    private static final String ISSUER_PRIVKEY = "a".repeat(64); // 32 bytes hex
    private static final String ISSUER_PUBKEY = "b".repeat(64);
    private static final String ISSUER_ID = "merchant123";
    private static final String UNIT = "sat";
    private static final long AMOUNT = 10000L;

    @BeforeEach
    void setUp() {
        service = new VoucherService(ledgerPort, backupPort, ISSUER_PRIVKEY, ISSUER_PUBKEY);
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("should create service with valid parameters")
        void shouldCreateServiceWithValidParameters() {
            // When / Then
            assertThatNoException().isThrownBy(() ->
                    new VoucherService(ledgerPort, backupPort, ISSUER_PRIVKEY, ISSUER_PUBKEY)
            );
        }

        @Test
        @DisplayName("should reject null ledger port")
        void shouldRejectNullLedgerPort() {
            // When / Then
            assertThatThrownBy(() ->
                    new VoucherService(null, backupPort, ISSUER_PRIVKEY, ISSUER_PUBKEY)
            ).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject null backup port")
        void shouldRejectNullBackupPort() {
            // When / Then
            assertThatThrownBy(() ->
                    new VoucherService(ledgerPort, null, ISSUER_PRIVKEY, ISSUER_PUBKEY)
            ).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject blank private key")
        void shouldRejectBlankPrivateKey() {
            // When / Then
            assertThatThrownBy(() ->
                    new VoucherService(ledgerPort, backupPort, "", ISSUER_PUBKEY)
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("private key cannot be blank");
        }

        @Test
        @DisplayName("should reject blank public key")
        void shouldRejectBlankPublicKey() {
            // When / Then
            assertThatThrownBy(() ->
                    new VoucherService(ledgerPort, backupPort, ISSUER_PRIVKEY, "")
            ).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("public key cannot be blank");
        }
    }

    @Nested
    @DisplayName("issue()")
    class IssueTests {

        @Test
        @DisplayName("should issue voucher with valid request")
        void shouldIssueVoucherWithValidRequest() {
            // Given
            IssueVoucherRequest request = IssueVoucherRequest.builder()
                    .issuerId(ISSUER_ID)
                    .unit(UNIT)
                    .amount(AMOUNT)
                    .build();

            // When
            IssueVoucherResponse response = service.issue(request);

            // Then
            assertThat(response).isNotNull();
            assertThat(response.getVoucher()).isNotNull();
            // Token is not set by VoucherService - requires wallet/mint interaction
            assertThat(response.getToken()).isNull();
            assertThat(response.getVoucherId()).isNotBlank();
            assertThat(response.getAmount()).isEqualTo(AMOUNT);
            assertThat(response.getUnit()).isEqualTo(UNIT);

            // Verify ledger publish was called
            verify(ledgerPort).publish(any(SignedVoucher.class), eq(VoucherStatus.ISSUED));
        }

        @Test
        @DisplayName("should issue voucher with expiry")
        void shouldIssueVoucherWithExpiry() {
            // Given
            IssueVoucherRequest request = IssueVoucherRequest.builder()
                    .issuerId(ISSUER_ID)
                    .unit(UNIT)
                    .amount(AMOUNT)
                    .expiresInDays(365)
                    .build();

            // When
            IssueVoucherResponse response = service.issue(request);

            // Then
            assertThat(response.getVoucher().getSecret().getExpiresAt()).isNotNull();
            assertThat(response.getVoucher().getSecret().getExpiresAt()).isGreaterThan(
                    System.currentTimeMillis() / 1000
            );
        }

        @Test
        @DisplayName("should issue voucher with memo")
        void shouldIssueVoucherWithMemo() {
            // Given
            String memo = "Birthday gift card";
            IssueVoucherRequest request = IssueVoucherRequest.builder()
                    .issuerId(ISSUER_ID)
                    .unit(UNIT)
                    .amount(AMOUNT)
                    .memo(memo)
                    .build();

            // When
            IssueVoucherResponse response = service.issue(request);

            // Then
            assertThat(response.getVoucher().getSecret().getMemo()).isEqualTo(memo);
        }

        @Test
        @DisplayName("should issue voucher with custom ID")
        void shouldIssueVoucherWithCustomId() {
            // Given - use valid UUID string format
            String customId = "550e8400-e29b-41d4-a716-446655440000";
            IssueVoucherRequest request = IssueVoucherRequest.builder()
                    .issuerId(ISSUER_ID)
                    .unit(UNIT)
                    .amount(AMOUNT)
                    .voucherId(customId)
                    .build();

            // When
            IssueVoucherResponse response = service.issue(request);

            // Then
            assertThat(response.getVoucherId()).isEqualTo(customId);
        }

        @Test
        @DisplayName("should reject null request")
        void shouldRejectNullRequest() {
            // When / Then
            assertThatThrownBy(() -> service.issue(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("should reject request with null issuer ID")
        void shouldRejectNullIssuerId() {
            // Given
            IssueVoucherRequest request = IssueVoucherRequest.builder()
                    .issuerId(null)
                    .unit(UNIT)
                    .amount(AMOUNT)
                    .build();

            // When / Then
            assertThatThrownBy(() -> service.issue(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Issuer ID is required");
        }

        @Test
        @DisplayName("should reject request with blank unit")
        void shouldRejectBlankUnit() {
            // Given
            IssueVoucherRequest request = IssueVoucherRequest.builder()
                    .issuerId(ISSUER_ID)
                    .unit("")
                    .amount(AMOUNT)
                    .build();

            // When / Then
            assertThatThrownBy(() -> service.issue(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unit is required");
        }

        @Test
        @DisplayName("should reject request with zero amount")
        void shouldRejectZeroAmount() {
            // Given
            IssueVoucherRequest request = IssueVoucherRequest.builder()
                    .issuerId(ISSUER_ID)
                    .unit(UNIT)
                    .amount(0L)
                    .build();

            // When / Then
            assertThatThrownBy(() -> service.issue(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Amount must be positive");
        }

        @Test
        @DisplayName("should reject request with negative expiry days")
        void shouldRejectNegativeExpiryDays() {
            // Given
            IssueVoucherRequest request = IssueVoucherRequest.builder()
                    .issuerId(ISSUER_ID)
                    .unit(UNIT)
                    .amount(AMOUNT)
                    .expiresInDays(-10)
                    .build();

            // When / Then
            assertThatThrownBy(() -> service.issue(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Expiry days must be positive");
        }

        @Test
        @DisplayName("should wrap ledger publish exception")
        void shouldWrapLedgerPublishException() {
            // Given
            IssueVoucherRequest request = IssueVoucherRequest.builder()
                    .issuerId(ISSUER_ID)
                    .unit(UNIT)
                    .amount(AMOUNT)
                    .build();

            doThrow(new RuntimeException("Network error"))
                    .when(ledgerPort).publish(any(), any());

            // When / Then
            assertThatThrownBy(() -> service.issue(request))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to publish voucher to ledger");
        }
    }

    @Nested
    @DisplayName("queryStatus()")
    class QueryStatusTests {

        @Test
        @DisplayName("should query status successfully")
        void shouldQueryStatusSuccessfully() {
            // Given
            String voucherId = "test-id";
            when(ledgerPort.queryStatus(voucherId)).thenReturn(Optional.of(VoucherStatus.ISSUED));

            // When
            Optional<VoucherStatus> result = service.queryStatus(voucherId);

            // Then
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(VoucherStatus.ISSUED);
            verify(ledgerPort).queryStatus(voucherId);
        }

        @Test
        @DisplayName("should return empty when voucher not found")
        void shouldReturnEmptyWhenNotFound() {
            // Given
            String voucherId = "non-existent";
            when(ledgerPort.queryStatus(voucherId)).thenReturn(Optional.empty());

            // When
            Optional<VoucherStatus> result = service.queryStatus(voucherId);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should reject blank voucher ID")
        void shouldRejectBlankVoucherId() {
            // When / Then
            assertThatThrownBy(() -> service.queryStatus(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Voucher ID cannot be blank");
        }

        @Test
        @DisplayName("should wrap ledger query exception")
        void shouldWrapLedgerQueryException() {
            // Given
            String voucherId = "test-id";
            when(ledgerPort.queryStatus(voucherId)).thenThrow(new RuntimeException("Network error"));

            // When / Then
            assertThatThrownBy(() -> service.queryStatus(voucherId))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to query voucher status");
        }
    }

    @Nested
    @DisplayName("updateStatus()")
    class UpdateStatusTests {

        @Test
        @DisplayName("should update status successfully")
        void shouldUpdateStatusSuccessfully() {
            // Given
            String voucherId = "test-id";
            VoucherStatus newStatus = VoucherStatus.REDEEMED;

            // When
            service.updateStatus(voucherId, newStatus);

            // Then
            verify(ledgerPort).updateStatus(voucherId, newStatus);
        }

        @Test
        @DisplayName("should reject blank voucher ID")
        void shouldRejectBlankVoucherId() {
            // When / Then
            assertThatThrownBy(() -> service.updateStatus("", VoucherStatus.REDEEMED))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Voucher ID cannot be blank");
        }

        @Test
        @DisplayName("should reject null status")
        void shouldRejectNullStatus() {
            // When / Then
            assertThatThrownBy(() -> service.updateStatus("test-id", null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("backup() and restore()")
    class BackupRestoreTests {

        @Test
        @DisplayName("should backup vouchers successfully")
        void shouldBackupSuccessfully() {
            // Given
            IssueVoucherRequest request = IssueVoucherRequest.builder()
                    .issuerId(ISSUER_ID)
                    .unit(UNIT)
                    .amount(AMOUNT)
                    .build();

            IssueVoucherResponse response = service.issue(request);
            List<SignedVoucher> vouchers = List.of(response.getVoucher());
            String userKey = "c".repeat(64);

            // When
            service.backup(vouchers, userKey);

            // Then
            verify(backupPort).backup(vouchers, userKey);
        }

        @Test
        @DisplayName("should skip backup for empty list")
        void shouldSkipBackupForEmptyList() {
            // Given
            List<SignedVoucher> vouchers = List.of();
            String userKey = "c".repeat(64);

            // When
            service.backup(vouchers, userKey);

            // Then
            verifyNoInteractions(backupPort);
        }

        @Test
        @DisplayName("should reject blank user key for backup")
        void shouldRejectBlankUserKeyForBackup() {
            // Given
            List<SignedVoucher> vouchers = List.of();

            // When / Then
            assertThatThrownBy(() -> service.backup(vouchers, ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User private key cannot be blank");
        }

        @Test
        @DisplayName("should restore vouchers successfully")
        void shouldRestoreSuccessfully() {
            // Given
            String userKey = "c".repeat(64);
            List<SignedVoucher> expected = List.of();
            when(backupPort.restore(userKey)).thenReturn(expected);

            // When
            List<SignedVoucher> result = service.restore(userKey);

            // Then
            assertThat(result).isSameAs(expected);
            verify(backupPort).restore(userKey);
        }

        @Test
        @DisplayName("should reject blank user key for restore")
        void shouldRejectBlankUserKeyForRestore() {
            // When / Then
            assertThatThrownBy(() -> service.restore(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User private key cannot be blank");
        }
    }

    @Nested
    @DisplayName("exists()")
    class ExistsTests {

        @Test
        @DisplayName("should return true when voucher exists")
        void shouldReturnTrueWhenExists() {
            // Given
            String voucherId = "test-id";
            when(ledgerPort.exists(voucherId)).thenReturn(true);

            // When
            boolean result = service.exists(voucherId);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when voucher does not exist")
        void shouldReturnFalseWhenNotExists() {
            // Given
            String voucherId = "non-existent";
            when(ledgerPort.exists(voucherId)).thenReturn(false);

            // When
            boolean result = service.exists(voucherId);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should reject blank voucher ID")
        void shouldRejectBlankVoucherId() {
            // When / Then
            assertThatThrownBy(() -> service.exists(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Voucher ID cannot be blank");
        }
    }
}
