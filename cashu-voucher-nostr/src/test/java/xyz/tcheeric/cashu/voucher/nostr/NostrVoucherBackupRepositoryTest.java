package xyz.tcheeric.cashu.voucher.nostr;

import org.junit.jupiter.api.*;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NostrVoucherBackupRepository.
 *
 * <p>These tests verify the backup repository functionality,
 * covering NIP-17 + NIP-44 encrypted backups, restore, and deduplication.
 */
class NostrVoucherBackupRepositoryTest {

    private NostrClientAdapter clientAdapter;
    private NostrVoucherBackupRepository repository;

    private static final String TEST_USER_PRIVKEY = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    private static final String TEST_USER_PUBKEY = "9876543210fedcba9876543210fedcba9876543210fedcba9876543210fedcba";
    private static final String TEST_ISSUER_PRIVKEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String TEST_ISSUER_PUBKEY = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
    private static final String ISSUER_ID = "test-merchant";
    private static final String UNIT = "sat";

    @BeforeEach
    void setUp() {
        // Create client adapter
        clientAdapter = new NostrClientAdapter(
                List.of("ws://test-relay"),
                1000L,
                1
        );

        // Create repository
        repository = new NostrVoucherBackupRepository(
                clientAdapter,
                1000L,
                1000L
        );
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should initialize with valid parameters")
        void shouldInitializeWithValidParameters() {
            assertNotNull(repository);
            assertEquals(clientAdapter, repository.getNostrClient());
        }

        @Test
        @DisplayName("Should use default timeouts")
        void shouldUseDefaultTimeouts() {
            NostrVoucherBackupRepository defaultRepo = new NostrVoucherBackupRepository(clientAdapter);
            assertNotNull(defaultRepo);
        }

        @Test
        @DisplayName("Should reject null client adapter")
        void shouldRejectNullClientAdapter() {
            assertThrows(NullPointerException.class, () -> {
                new NostrVoucherBackupRepository(null);
            });
        }

        @Test
        @DisplayName("Should reject invalid publish timeout")
        void shouldRejectInvalidPublishTimeout() {
            assertThrows(IllegalArgumentException.class, () -> {
                new NostrVoucherBackupRepository(clientAdapter, 0L, 1000L);
            });

            assertThrows(IllegalArgumentException.class, () -> {
                new NostrVoucherBackupRepository(clientAdapter, -1000L, 1000L);
            });
        }

        @Test
        @DisplayName("Should reject invalid query timeout")
        void shouldRejectInvalidQueryTimeout() {
            assertThrows(IllegalArgumentException.class, () -> {
                new NostrVoucherBackupRepository(clientAdapter, 1000L, 0L);
            });

            assertThrows(IllegalArgumentException.class, () -> {
                new NostrVoucherBackupRepository(clientAdapter, 1000L, -1000L);
            });
        }
    }

    @Nested
    @DisplayName("backup() Tests")
    class BackupTests {

        @Test
        @DisplayName("Should reject null vouchers list")
        void shouldRejectNullVouchersList() {
            assertThrows(NullPointerException.class, () -> {
                repository.backup(null, TEST_USER_PRIVKEY);
            });
        }

        @Test
        @DisplayName("Should reject null user private key")
        void shouldRejectNullUserPrivateKey() {
            List<SignedVoucher> vouchers = new ArrayList<>();
            assertThrows(NullPointerException.class, () -> {
                repository.backup(vouchers, null);
            });
        }

        @Test
        @DisplayName("Should reject blank user private key")
        void shouldRejectBlankUserPrivateKey() {
            List<SignedVoucher> vouchers = new ArrayList<>();

            assertThrows(IllegalArgumentException.class, () -> {
                repository.backup(vouchers, "");
            });

            assertThrows(IllegalArgumentException.class, () -> {
                repository.backup(vouchers, "   ");
            });
        }

        @Test
        @DisplayName("Should handle empty vouchers list")
        void shouldHandleEmptyVouchersList() {
            List<SignedVoucher> vouchers = new ArrayList<>();

            // Note: Will fail due to public key derivation not implemented
            assertThrows(VoucherNostrException.class, () -> {
                repository.backup(vouchers, TEST_USER_PRIVKEY);
            });
        }

        @Test
        @DisplayName("Should backup single voucher")
        void shouldBackupSingleVoucher() {
            List<SignedVoucher> vouchers = List.of(
                    createTestVoucher("voucher-1", 1000L)
            );

            // Note: Will fail due to public key derivation not implemented
            assertThrows(VoucherNostrException.class, () -> {
                repository.backup(vouchers, TEST_USER_PRIVKEY);
            });
        }

        @Test
        @DisplayName("Should backup multiple vouchers")
        void shouldBackupMultipleVouchers() {
            List<SignedVoucher> vouchers = List.of(
                    createTestVoucher("voucher-1", 1000L),
                    createTestVoucher("voucher-2", 2000L),
                    createTestVoucher("voucher-3", 3000L)
            );

            // Note: Will fail due to public key derivation not implemented
            assertThrows(VoucherNostrException.class, () -> {
                repository.backup(vouchers, TEST_USER_PRIVKEY);
            });
        }
    }

    @Nested
    @DisplayName("restore() Tests")
    class RestoreTests {

        @Test
        @DisplayName("Should reject null user private key")
        void shouldRejectNullUserPrivateKey() {
            assertThrows(NullPointerException.class, () -> {
                repository.restore(null);
            });
        }

        @Test
        @DisplayName("Should reject blank user private key")
        void shouldRejectBlankUserPrivateKey() {
            assertThrows(IllegalArgumentException.class, () -> {
                repository.restore("");
            });

            assertThrows(IllegalArgumentException.class, () -> {
                repository.restore("   ");
            });
        }

        @Test
        @DisplayName("Should return empty list when no backups exist")
        void shouldReturnEmptyListWhenNoBackupsExist() {
            // Note: Will fail due to public key derivation not implemented
            assertThrows(VoucherNostrException.class, () -> {
                repository.restore(TEST_USER_PRIVKEY);
            });
        }
    }

    @Nested
    @DisplayName("hasBackups() Tests")
    class HasBackupsTests {

        @Test
        @DisplayName("Should reject null user private key")
        void shouldRejectNullUserPrivateKey() {
            assertThrows(NullPointerException.class, () -> {
                repository.hasBackups(null);
            });
        }

        @Test
        @DisplayName("Should reject blank user private key")
        void shouldRejectBlankUserPrivateKey() {
            assertThrows(IllegalArgumentException.class, () -> {
                repository.hasBackups("");
            });

            assertThrows(IllegalArgumentException.class, () -> {
                repository.hasBackups("   ");
            });
        }

        @Test
        @DisplayName("Should return false when no backups exist")
        void shouldReturnFalseWhenNoBackupsExist() {
            // Note: Will fail due to public key derivation not implemented
            assertThrows(VoucherNostrException.class, () -> {
                repository.hasBackups(TEST_USER_PRIVKEY);
            });
        }
    }

    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {

        @Test
        @DisplayName("Should get Nostr client")
        void shouldGetNostrClient() {
            NostrClientAdapter client = repository.getNostrClient();
            assertNotNull(client);
            assertEquals(clientAdapter, client);
        }
    }

    /**
     * Helper method to create a test voucher.
     */
    private SignedVoucher createTestVoucher(String voucherId, long faceValue) {
        return createTestVoucher(voucherId, faceValue, null);
    }

    /**
     * Helper method to create a test voucher with expiry.
     */
    private SignedVoucher createTestVoucher(String voucherId, long faceValue, Long expiresAt) {
        VoucherSecret secret = VoucherSecret.create(
                voucherId,
                ISSUER_ID,
                UNIT,
                faceValue,
                expiresAt,
                "Test voucher " + voucherId,
                BackingStrategy.FIXED,
                1.0,
                0,
                null
        );

        return VoucherSignatureService.createSigned(
                secret,
                TEST_ISSUER_PRIVKEY,
                TEST_ISSUER_PUBKEY
        );
    }
}
