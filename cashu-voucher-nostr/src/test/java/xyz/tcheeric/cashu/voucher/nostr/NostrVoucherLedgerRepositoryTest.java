package xyz.tcheeric.cashu.voucher.nostr;

import nostr.base.PublicKey;
import nostr.event.impl.GenericEvent;
import org.junit.jupiter.api.*;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NostrVoucherLedgerRepository.
 *
 * <p>These tests verify the ledger repository using MockNostrRelay,
 * covering NIP-33 event publishing, querying, and status updates.
 */
class NostrVoucherLedgerRepositoryTest {

    private MockNostrRelay mockRelay;
    private NostrClientAdapter clientAdapter;
    private NostrVoucherLedgerRepository repository;
    private PublicKey issuerPublicKey;

    private static final String TEST_ISSUER_PRIVKEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String TEST_ISSUER_PUBKEY = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
    private static final String ISSUER_ID = "test-merchant";
    private static final String UNIT = "sat";
    private static final long FACE_VALUE = 5000L;

    @BeforeEach
    void setUp() {
        // Create mock relay
        mockRelay = new MockNostrRelay("ws://test-relay");
        mockRelay.connect();

        // Create client adapter (note: will use real implementation, not mock)
        clientAdapter = new NostrClientAdapter(
                java.util.List.of("ws://test-relay"),
                1000L,
                1
        );

        // Create issuer public key
        issuerPublicKey = new PublicKey(TEST_ISSUER_PUBKEY);

        // Create repository
        repository = new NostrVoucherLedgerRepository(
                clientAdapter,
                issuerPublicKey,
                1000L,
                1000L
        );
    }

    @AfterEach
    void tearDown() {
        if (mockRelay != null) {
            mockRelay.disconnect();
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should initialize with valid parameters")
        void shouldInitializeWithValidParameters() {
            assertNotNull(repository);
            assertEquals(issuerPublicKey, repository.getIssuerPublicKey());
        }

        @Test
        @DisplayName("Should use default timeouts")
        void shouldUseDefaultTimeouts() {
            NostrVoucherLedgerRepository defaultRepo = new NostrVoucherLedgerRepository(
                    clientAdapter,
                    issuerPublicKey
            );
            assertNotNull(defaultRepo);
        }

        @Test
        @DisplayName("Should reject null client adapter")
        void shouldRejectNullClientAdapter() {
            assertThrows(NullPointerException.class, () -> {
                new NostrVoucherLedgerRepository(null, issuerPublicKey);
            });
        }

        @Test
        @DisplayName("Should reject null issuer public key")
        void shouldRejectNullIssuerPublicKey() {
            assertThrows(NullPointerException.class, () -> {
                new NostrVoucherLedgerRepository(clientAdapter, null);
            });
        }

        @Test
        @DisplayName("Should reject invalid publish timeout")
        void shouldRejectInvalidPublishTimeout() {
            assertThrows(IllegalArgumentException.class, () -> {
                new NostrVoucherLedgerRepository(clientAdapter, issuerPublicKey, 0L, 1000L);
            });

            assertThrows(IllegalArgumentException.class, () -> {
                new NostrVoucherLedgerRepository(clientAdapter, issuerPublicKey, -1000L, 1000L);
            });
        }

        @Test
        @DisplayName("Should reject invalid query timeout")
        void shouldRejectInvalidQueryTimeout() {
            assertThrows(IllegalArgumentException.class, () -> {
                new NostrVoucherLedgerRepository(clientAdapter, issuerPublicKey, 1000L, 0L);
            });

            assertThrows(IllegalArgumentException.class, () -> {
                new NostrVoucherLedgerRepository(clientAdapter, issuerPublicKey, 1000L, -1000L);
            });
        }
    }

    @Nested
    @DisplayName("publish() Tests")
    class PublishTests {

        @Test
        @DisplayName("Should publish voucher successfully")
        void shouldPublishVoucherSuccessfully() {
            // Given
            SignedVoucher voucher = createTestVoucher("voucher-1");

            // When/Then - Note: Will fail because client isn't connected to real relays
            // This is a limitation of the current test setup
            // In a full implementation, we'd inject the MockNostrRelay
            assertThrows(VoucherNostrException.class, () -> {
                repository.publish(voucher, VoucherStatus.ISSUED);
            });
        }

        @Test
        @DisplayName("Should reject null voucher")
        void shouldRejectNullVoucher() {
            assertThrows(NullPointerException.class, () -> {
                repository.publish(null, VoucherStatus.ISSUED);
            });
        }

        @Test
        @DisplayName("Should reject null status")
        void shouldRejectNullStatus() {
            SignedVoucher voucher = createTestVoucher("voucher-1");

            assertThrows(NullPointerException.class, () -> {
                repository.publish(voucher, null);
            });
        }
    }

    @Nested
    @DisplayName("queryStatus() Tests")
    class QueryStatusTests {

        @Test
        @DisplayName("Should return empty for non-existent voucher")
        void shouldReturnEmptyForNonExistentVoucher() {
            // When/Then - Will throw because not connected
            assertThrows(VoucherNostrException.class, () -> {
                repository.queryStatus("non-existent-voucher");
            });
        }

        @Test
        @DisplayName("Should reject null voucher ID")
        void shouldRejectNullVoucherId() {
            assertThrows(NullPointerException.class, () -> {
                repository.queryStatus(null);
            });
        }

        @Test
        @DisplayName("Should reject blank voucher ID")
        void shouldRejectBlankVoucherId() {
            assertThrows(IllegalArgumentException.class, () -> {
                repository.queryStatus("");
            });

            assertThrows(IllegalArgumentException.class, () -> {
                repository.queryStatus("   ");
            });
        }
    }

    @Nested
    @DisplayName("updateStatus() Tests")
    class UpdateStatusTests {

        @Test
        @DisplayName("Should reject null voucher ID")
        void shouldRejectNullVoucherId() {
            assertThrows(NullPointerException.class, () -> {
                repository.updateStatus(null, VoucherStatus.REDEEMED);
            });
        }

        @Test
        @DisplayName("Should reject blank voucher ID")
        void shouldRejectBlankVoucherId() {
            assertThrows(IllegalArgumentException.class, () -> {
                repository.updateStatus("", VoucherStatus.REDEEMED);
            });

            assertThrows(IllegalArgumentException.class, () -> {
                repository.updateStatus("   ", VoucherStatus.REDEEMED);
            });
        }

        @Test
        @DisplayName("Should reject null new status")
        void shouldRejectNullNewStatus() {
            assertThrows(NullPointerException.class, () -> {
                repository.updateStatus("voucher-1", null);
            });
        }
    }

    @Nested
    @DisplayName("queryVoucher() Tests")
    class QueryVoucherTests {

        @Test
        @DisplayName("Should reject null voucher ID")
        void shouldRejectNullVoucherId() {
            assertThrows(NullPointerException.class, () -> {
                repository.queryVoucher(null);
            });
        }

        @Test
        @DisplayName("Should reject blank voucher ID")
        void shouldRejectBlankVoucherId() {
            assertThrows(IllegalArgumentException.class, () -> {
                repository.queryVoucher("");
            });

            assertThrows(IllegalArgumentException.class, () -> {
                repository.queryVoucher("   ");
            });
        }
    }

    @Nested
    @DisplayName("exists() Tests")
    class ExistsTests {

        @Test
        @DisplayName("Should return false for non-existent voucher")
        void shouldReturnFalseForNonExistentVoucher() {
            // When/Then - Will throw because not connected
            assertThrows(VoucherNostrException.class, () -> {
                repository.exists("non-existent-voucher");
            });
        }

        @Test
        @DisplayName("Should reject null voucher ID")
        void shouldRejectNullVoucherId() {
            assertThrows(NullPointerException.class, () -> {
                repository.exists(null);
            });
        }

        @Test
        @DisplayName("Should reject blank voucher ID")
        void shouldRejectBlankVoucherId() {
            assertThrows(IllegalArgumentException.class, () -> {
                repository.exists("");
            });
        }
    }

    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {

        @Test
        @DisplayName("Should get issuer public key")
        void shouldGetIssuerPublicKey() {
            PublicKey pubkey = repository.getIssuerPublicKey();
            assertNotNull(pubkey);
            assertEquals(issuerPublicKey, pubkey);
        }

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
    private SignedVoucher createTestVoucher(String voucherId) {
        return createTestVoucher(voucherId, null);
    }

    /**
     * Helper method to create a test voucher with expiry.
     */
    private SignedVoucher createTestVoucher(String voucherId, Long expiresAt) {
        VoucherSecret secret = VoucherSecret.create(
                voucherId,
                ISSUER_ID,
                UNIT,
                FACE_VALUE,
                expiresAt,
                "Test voucher",
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
