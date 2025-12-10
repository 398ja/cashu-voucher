package xyz.tcheeric.cashu.voucher.nostr.events;

import nostr.event.impl.GenericEvent;
import nostr.id.Identity;
import org.junit.jupiter.api.*;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.nostr.VoucherNostrException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VoucherBackupPayload.
 *
 * <p>These tests verify NIP-17 + NIP-44 backup payload creation,
 * encryption, decryption, and voucher extraction.
 *
 * <p><b>TEMPORARILY DISABLED</b>: These tests require VoucherSecret to serialize as a JSON object,
 * but VoucherSecret uses @JsonValue annotation which serializes it as a hex string.
 * This is a design decision in the domain layer (voucher secrets serialize as strings).
 *
 * <p>To re-enable: Create DTO mappers in the Nostr layer that handle the conversion properly.
 */
class VoucherBackupPayloadTest {

    private static final String TEST_USER_PRIVKEY = "c56c7162b49d38e3b14583271ec177617d1bda7cb5415421e50d71d6c3e13446";
    // Derive public key using nostr-java Identity
    private static final String TEST_USER_PUBKEY = Identity.create(TEST_USER_PRIVKEY).getPublicKey().toString();

    private static final String TEST_ISSUER_PRIVKEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String TEST_ISSUER_PUBKEY = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
    private static final String ISSUER_ID = "test-merchant";
    private static final String UNIT = "sat";

    @Nested
    @DisplayName("createBackupEvent() Tests")
    class CreateBackupEventTests {

        @Test
        @DisplayName("Should create backup event for empty list")
        void shouldCreateBackupEventForEmptyList() {
            List<SignedVoucher> vouchers = new ArrayList<>();

            GenericEvent event = VoucherBackupPayload.createBackupEvent(
                    vouchers,
                    TEST_USER_PRIVKEY,
                    TEST_USER_PUBKEY
            );

            assertNotNull(event);
            assertEquals(VoucherBackupPayload.KIND_ENCRYPTED_DM, event.getKind());
            assertNotNull(event.getContent());
            assertFalse(event.getContent().isEmpty());
        }

        @Test
        @DisplayName("Should create backup event for single voucher")
        void shouldCreateBackupEventForSingleVoucher() {
            List<SignedVoucher> vouchers = List.of(
                    createTestVoucher("voucher-1", 1000L)
            );

            GenericEvent event = VoucherBackupPayload.createBackupEvent(
                    vouchers,
                    TEST_USER_PRIVKEY,
                    TEST_USER_PUBKEY
            );

            assertNotNull(event);
            assertEquals(VoucherBackupPayload.KIND_ENCRYPTED_DM, event.getKind());
            assertNotNull(event.getContent());
            assertTrue(event.getContent().length() > 0);
        }

        @Test
        @DisplayName("Should create backup event for multiple vouchers")
        void shouldCreateBackupEventForMultipleVouchers() {
            List<SignedVoucher> vouchers = List.of(
                    createTestVoucher("voucher-1", 1000L),
                    createTestVoucher("voucher-2", 2000L),
                    createTestVoucher("voucher-3", 3000L)
            );

            GenericEvent event = VoucherBackupPayload.createBackupEvent(
                    vouchers,
                    TEST_USER_PRIVKEY,
                    TEST_USER_PUBKEY
            );

            assertNotNull(event);
            assertEquals(VoucherBackupPayload.KIND_ENCRYPTED_DM, event.getKind());
        }

        @Test
        @DisplayName("Should set correct event kind")
        void shouldSetCorrectEventKind() {
            List<SignedVoucher> vouchers = List.of(createTestVoucher("v1", 1000L));

            GenericEvent event = VoucherBackupPayload.createBackupEvent(
                    vouchers,
                    TEST_USER_PRIVKEY,
                    TEST_USER_PUBKEY
            );

            assertEquals(4, event.getKind());
        }

        @Test
        @DisplayName("Should set created_at timestamp")
        void shouldSetCreatedAtTimestamp() {
            List<SignedVoucher> vouchers = List.of(createTestVoucher("v1", 1000L));

            long beforeCreate = System.currentTimeMillis() / 1000;
            GenericEvent event = VoucherBackupPayload.createBackupEvent(
                    vouchers,
                    TEST_USER_PRIVKEY,
                    TEST_USER_PUBKEY
            );
            long afterCreate = System.currentTimeMillis() / 1000;

            assertTrue(event.getCreatedAt() >= beforeCreate);
            assertTrue(event.getCreatedAt() <= afterCreate + 1);
        }

        @Test
        @DisplayName("Should encrypt content")
        void shouldEncryptContent() {
            List<SignedVoucher> vouchers = List.of(createTestVoucher("v1", 1000L));

            GenericEvent event = VoucherBackupPayload.createBackupEvent(
                    vouchers,
                    TEST_USER_PRIVKEY,
                    TEST_USER_PUBKEY
            );

            String content = event.getContent();
            assertNotNull(content);
            // Encrypted content should not contain plaintext voucher data
            assertFalse(content.contains("voucher-1"));
            assertFalse(content.contains(ISSUER_ID));
        }

        @Test
        @DisplayName("Should reject null vouchers list")
        void shouldRejectNullVouchersList() {
            assertThrows(NullPointerException.class, () -> {
                VoucherBackupPayload.createBackupEvent(
                        null,
                        TEST_USER_PRIVKEY,
                        TEST_USER_PUBKEY
                );
            });
        }

        @Test
        @DisplayName("Should reject null user private key")
        void shouldRejectNullUserPrivateKey() {
            List<SignedVoucher> vouchers = new ArrayList<>();

            assertThrows(NullPointerException.class, () -> {
                VoucherBackupPayload.createBackupEvent(
                        vouchers,
                        null,
                        TEST_USER_PUBKEY
                );
            });
        }

        @Test
        @DisplayName("Should reject blank user private key")
        void shouldRejectBlankUserPrivateKey() {
            List<SignedVoucher> vouchers = new ArrayList<>();

            assertThrows(IllegalArgumentException.class, () -> {
                VoucherBackupPayload.createBackupEvent(
                        vouchers,
                        "",
                        TEST_USER_PUBKEY
                );
            });
        }

        @Test
        @DisplayName("Should reject null user public key")
        void shouldRejectNullUserPublicKey() {
            List<SignedVoucher> vouchers = new ArrayList<>();

            assertThrows(NullPointerException.class, () -> {
                VoucherBackupPayload.createBackupEvent(
                        vouchers,
                        TEST_USER_PRIVKEY,
                        null
                );
            });
        }

        @Test
        @DisplayName("Should reject blank user public key")
        void shouldRejectBlankUserPublicKey() {
            List<SignedVoucher> vouchers = new ArrayList<>();

            assertThrows(IllegalArgumentException.class, () -> {
                VoucherBackupPayload.createBackupEvent(
                        vouchers,
                        TEST_USER_PRIVKEY,
                        ""
                );
            });
        }
    }

    @Nested
    @DisplayName("extractVouchers() Tests")
    class ExtractVouchersTests {

        @Test
        @DisplayName("Should extract empty list from empty backup")
        void shouldExtractEmptyListFromEmptyBackup() {
            List<SignedVoucher> original = new ArrayList<>();
            GenericEvent event = VoucherBackupPayload.createBackupEvent(
                    original,
                    TEST_USER_PRIVKEY,
                    TEST_USER_PUBKEY
            );

            List<SignedVoucher> extracted = VoucherBackupPayload.extractVouchers(
                    event,
                    TEST_USER_PRIVKEY,
                    TEST_USER_PUBKEY
            );

            assertNotNull(extracted);
            assertTrue(extracted.isEmpty());
        }

        @Test
        @DisplayName("Should extract single voucher")
        void shouldExtractSingleVoucher() {
            List<SignedVoucher> original = List.of(
                    createTestVoucher("voucher-1", 1000L)
            );
            GenericEvent event = VoucherBackupPayload.createBackupEvent(
                    original,
                    TEST_USER_PRIVKEY,
                    TEST_USER_PUBKEY
            );

            List<SignedVoucher> extracted = VoucherBackupPayload.extractVouchers(
                    event,
                    TEST_USER_PRIVKEY,
                    TEST_USER_PUBKEY
            );

            assertNotNull(extracted);
            assertEquals(1, extracted.size());
            assertEquals("voucher-1", extracted.get(0).getSecret().getVoucherId());
        }

        @Test
        @DisplayName("Should extract multiple vouchers")
        void shouldExtractMultipleVouchers() {
            List<SignedVoucher> original = List.of(
                    createTestVoucher("voucher-1", 1000L),
                    createTestVoucher("voucher-2", 2000L),
                    createTestVoucher("voucher-3", 3000L)
            );
            GenericEvent event = VoucherBackupPayload.createBackupEvent(
                    original,
                    TEST_USER_PRIVKEY,
                    TEST_USER_PUBKEY
            );

            List<SignedVoucher> extracted = VoucherBackupPayload.extractVouchers(
                    event,
                    TEST_USER_PRIVKEY,
                    TEST_USER_PUBKEY
            );

            assertNotNull(extracted);
            assertEquals(3, extracted.size());
        }

        @Test
        @DisplayName("Should reject null event")
        void shouldRejectNullEvent() {
            assertThrows(NullPointerException.class, () -> {
                VoucherBackupPayload.extractVouchers(
                        null,
                        TEST_USER_PRIVKEY,
                        TEST_USER_PUBKEY
                );
            });
        }

        @Test
        @DisplayName("Should reject null user private key")
        void shouldRejectNullUserPrivateKey() {
            GenericEvent event = new GenericEvent();

            assertThrows(NullPointerException.class, () -> {
                VoucherBackupPayload.extractVouchers(
                        event,
                        null,
                        TEST_USER_PUBKEY
                );
            });
        }

        @Test
        @DisplayName("Should reject blank user private key")
        void shouldRejectBlankUserPrivateKey() {
            GenericEvent event = new GenericEvent();

            assertThrows(IllegalArgumentException.class, () -> {
                VoucherBackupPayload.extractVouchers(
                        event,
                        "",
                        TEST_USER_PUBKEY
                );
            });
        }

        @Test
        @DisplayName("Should reject null user public key")
        void shouldRejectNullUserPublicKey() {
            GenericEvent event = new GenericEvent();

            assertThrows(NullPointerException.class, () -> {
                VoucherBackupPayload.extractVouchers(
                        event,
                        TEST_USER_PRIVKEY,
                        null
                );
            });
        }

        @Test
        @DisplayName("Should reject blank user public key")
        void shouldRejectBlankUserPublicKey() {
            GenericEvent event = new GenericEvent();

            assertThrows(IllegalArgumentException.class, () -> {
                VoucherBackupPayload.extractVouchers(
                        event,
                        TEST_USER_PRIVKEY,
                        ""
                );
            });
        }

        @Test
        @DisplayName("Should reject wrong event kind")
        void shouldRejectWrongEventKind() {
            GenericEvent event = new GenericEvent();
            event.setKind(1); // Wrong kind
            event.setContent("test");

            assertThrows(VoucherNostrException.class, () -> {
                VoucherBackupPayload.extractVouchers(
                        event,
                        TEST_USER_PRIVKEY,
                        TEST_USER_PUBKEY
                );
            });
        }

        @Test
        @DisplayName("Should reject empty content")
        void shouldRejectEmptyContent() {
            GenericEvent event = new GenericEvent();
            event.setKind(VoucherBackupPayload.KIND_ENCRYPTED_DM);
            event.setContent("");

            assertThrows(VoucherNostrException.class, () -> {
                VoucherBackupPayload.extractVouchers(
                        event,
                        TEST_USER_PRIVKEY,
                        TEST_USER_PUBKEY
                );
            });
        }

        @Test
        @DisplayName("Should reject null content")
        void shouldRejectNullContent() {
            GenericEvent event = new GenericEvent();
            event.setKind(VoucherBackupPayload.KIND_ENCRYPTED_DM);
            event.setContent(null);

            assertThrows(VoucherNostrException.class, () -> {
                VoucherBackupPayload.extractVouchers(
                        event,
                        TEST_USER_PRIVKEY,
                        TEST_USER_PUBKEY
                );
            });
        }
    }

    @Nested
    @DisplayName("isValidBackupEvent() Tests")
    class IsValidBackupEventTests {

        @Test
        @DisplayName("Should validate correct backup event")
        void shouldValidateCorrectBackupEvent() {
            List<SignedVoucher> vouchers = List.of(createTestVoucher("v1", 1000L));
            GenericEvent event = VoucherBackupPayload.createBackupEvent(
                    vouchers,
                    TEST_USER_PRIVKEY,
                    TEST_USER_PUBKEY
            );

            assertTrue(VoucherBackupPayload.isValidBackupEvent(event));
        }

        @Test
        @DisplayName("Should invalidate null event")
        void shouldInvalidateNullEvent() {
            assertFalse(VoucherBackupPayload.isValidBackupEvent(null));
        }

        @Test
        @DisplayName("Should invalidate event with wrong kind")
        void shouldInvalidateEventWithWrongKind() {
            GenericEvent event = new GenericEvent();
            event.setKind(1);
            event.setContent("test");

            assertFalse(VoucherBackupPayload.isValidBackupEvent(event));
        }

        @Test
        @DisplayName("Should invalidate event with empty content")
        void shouldInvalidateEventWithEmptyContent() {
            GenericEvent event = new GenericEvent();
            event.setKind(VoucherBackupPayload.KIND_ENCRYPTED_DM);
            event.setContent("");

            assertFalse(VoucherBackupPayload.isValidBackupEvent(event));
        }

        @Test
        @DisplayName("Should invalidate event with null content")
        void shouldInvalidateEventWithNullContent() {
            GenericEvent event = new GenericEvent();
            event.setKind(VoucherBackupPayload.KIND_ENCRYPTED_DM);
            event.setContent(null);

            assertFalse(VoucherBackupPayload.isValidBackupEvent(event));
        }
    }

    @Nested
    @DisplayName("Round-trip Tests")
    class RoundTripTests {

        @Test
        @DisplayName("Should preserve voucher data through encryption round-trip")
        void shouldPreserveVoucherDataThroughEncryptionRoundTrip() {
            // Given
            List<SignedVoucher> original = List.of(
                    createTestVoucher("voucher-rt1", 1000L),
                    createTestVoucher("voucher-rt2", 2000L)
            );

            // When
            GenericEvent event = VoucherBackupPayload.createBackupEvent(
                    original,
                    TEST_USER_PRIVKEY,
                    TEST_USER_PUBKEY
            );
            List<SignedVoucher> restored = VoucherBackupPayload.extractVouchers(
                    event,
                    TEST_USER_PRIVKEY,
                    TEST_USER_PUBKEY
            );

            // Then
            assertEquals(original.size(), restored.size());
            for (int i = 0; i < original.size(); i++) {
                assertEquals(original.get(i).getSecret().getVoucherId(),
                        restored.get(i).getSecret().getVoucherId());
                assertEquals(original.get(i).getSecret().getFaceValue(),
                        restored.get(i).getSecret().getFaceValue());
            }
        }
    }

    /**
     * Helper method to create a test voucher.
     */
    private SignedVoucher createTestVoucher(String voucherId, long faceValue) {
        VoucherSecret secret = VoucherSecret.create(
                voucherId,
                ISSUER_ID,
                UNIT,
                faceValue,
                null,
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
