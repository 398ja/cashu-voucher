package xyz.tcheeric.cashu.voucher.nostr.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.GenericTag;
import org.junit.jupiter.api.*;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;
import xyz.tcheeric.cashu.voucher.nostr.VoucherNostrException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VoucherLedgerEvent.
 *
 * <p>These tests verify NIP-33 event mapping between domain vouchers
 * and Nostr events, including serialization and deserialization.
 *
 */
class VoucherLedgerEventTest {

    private static final String TEST_ISSUER_PRIVKEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String TEST_ISSUER_PUBKEY = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
    private static final String ISSUER_ID = "test-merchant";
    private static final String UNIT = "sat";
    private static final long FACE_VALUE = 5000L;

    @Nested
    @DisplayName("fromVoucher() Tests")
    class FromVoucherTests {

        @Test
        @DisplayName("Should create event from voucher")
        void shouldCreateEventFromVoucher() {
            // Given
            SignedVoucher voucher = createTestVoucher("voucher-1");

            // When
            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED);

            // Then
            assertNotNull(event);
            assertEquals(VoucherLedgerEvent.KIND_VOUCHER_LEDGER, event.getKind());
            assertNotNull(event.getContent());
        }

        @Test
        @DisplayName("Should set correct event kind")
        void shouldSetCorrectEventKind() {
            SignedVoucher voucher = createTestVoucher("voucher-1");

            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED);

            assertEquals(30078, event.getKind());
        }

        @Test
        @DisplayName("Should create event with expiry")
        void shouldCreateEventWithExpiry() {
            long expiresAt = System.currentTimeMillis() / 1000 + 86400; // +24h
            SignedVoucher voucher = createTestVoucher("voucher-1", expiresAt);

            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED);

            assertNotNull(event);
            assertEquals(expiresAt, event.getExpiry());
        }

        @Test
        @DisplayName("Should create event without expiry")
        void shouldCreateEventWithoutExpiry() {
            SignedVoucher voucher = createTestVoucher("voucher-1", null);

            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED);

            assertNotNull(event);
            assertNull(event.getExpiry());
        }

        @Test
        @DisplayName("Should reject null voucher")
        void shouldRejectNullVoucher() {
            assertThrows(IllegalArgumentException.class, () -> {
                VoucherLedgerEvent.fromVoucher(null, VoucherStatus.ISSUED);
            });
        }

        @Test
        @DisplayName("Should reject null status")
        void shouldRejectNullStatus() {
            SignedVoucher voucher = createTestVoucher("voucher-1");

            assertThrows(IllegalArgumentException.class, () -> {
                VoucherLedgerEvent.fromVoucher(voucher, null);
            });
        }
    }

    @Nested
    @DisplayName("toVoucher() Tests")
    class ToVoucherTests {

        @Test
        @DisplayName("Should convert event back to voucher")
        void shouldConvertEventBackToVoucher() {
            // Given
            SignedVoucher originalVoucher = createTestVoucher("voucher-1");
            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(originalVoucher, VoucherStatus.ISSUED);

            // When
            SignedVoucher restoredVoucher = event.toVoucher();

            // Then
            assertNotNull(restoredVoucher);
            assertEquals(originalVoucher.getSecret().getVoucherId(),
                    restoredVoucher.getSecret().getVoucherId());
            assertEquals(originalVoucher.getSecret().getFaceValue(),
                    restoredVoucher.getSecret().getFaceValue());
        }

        @Test
        @DisplayName("Should handle empty content")
        void shouldHandleEmptyContent() {
            VoucherLedgerEvent event = new VoucherLedgerEvent();
            event.setKind(VoucherLedgerEvent.KIND_VOUCHER_LEDGER);
            event.setContent("");

            assertThrows(VoucherNostrException.class, () -> {
                event.toVoucher();
            });
        }

        @Test
        @DisplayName("Should handle null content")
        void shouldHandleNullContent() {
            VoucherLedgerEvent event = new VoucherLedgerEvent();
            event.setKind(VoucherLedgerEvent.KIND_VOUCHER_LEDGER);
            event.setContent(null);

            assertThrows(VoucherNostrException.class, () -> {
                event.toVoucher();
            });
        }

        @Test
        @DisplayName("Should reject wrong event kind")
        void shouldRejectWrongEventKind() {
            VoucherLedgerEvent event = new VoucherLedgerEvent();
            event.setKind(1); // Wrong kind
            event.setContent("test");

            assertThrows(VoucherNostrException.class, () -> {
                event.toVoucher();
            });
        }
    }

    @Nested
    @DisplayName("getStatus() Tests")
    class GetStatusTests {

        @Test
        @DisplayName("Should extract ISSUED status")
        void shouldExtractIssuedStatus() {
            SignedVoucher voucher = createTestVoucher("voucher-1");
            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED);

            VoucherStatus status = event.getStatus();

            assertEquals(VoucherStatus.ISSUED, status);
        }

        @Test
        @DisplayName("Should extract REDEEMED status")
        void shouldExtractRedeemedStatus() {
            SignedVoucher voucher = createTestVoucher("voucher-1");
            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.REDEEMED);

            VoucherStatus status = event.getStatus();

            assertEquals(VoucherStatus.REDEEMED, status);
        }

        @Test
        @DisplayName("Should extract REVOKED status")
        void shouldExtractRevokedStatus() {
            SignedVoucher voucher = createTestVoucher("voucher-1");
            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.REVOKED);

            VoucherStatus status = event.getStatus();

            assertEquals(VoucherStatus.REVOKED, status);
        }

        @Test
        @DisplayName("Should extract EXPIRED status")
        void shouldExtractExpiredStatus() {
            SignedVoucher voucher = createTestVoucher("voucher-1");
            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.EXPIRED);

            VoucherStatus status = event.getStatus();

            assertEquals(VoucherStatus.EXPIRED, status);
        }
    }

    @Nested
    @DisplayName("getVoucherId() Tests")
    class GetVoucherIdTests {

        @Test
        @DisplayName("Should extract voucher ID")
        void shouldExtractVoucherId() {
            String voucherId = "test-voucher-123";
            SignedVoucher voucher = createTestVoucher(voucherId);
            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED);

            String extractedId = event.getVoucherId();

            // VoucherId is now a UUID generated from the string
            java.util.UUID expectedId = java.util.UUID.nameUUIDFromBytes(voucherId.getBytes());
            assertEquals(expectedId.toString(), extractedId);
        }
    }

    @Nested
    @DisplayName("Tag Extraction Tests")
    class TagExtractionTests {

        @Test
        @DisplayName("Should extract amount tag")
        void shouldExtractAmountTag() {
            SignedVoucher voucher = createTestVoucher("voucher-1");
            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED);

            Long amount = event.getAmount();

            assertEquals(FACE_VALUE, amount);
        }

        @Test
        @DisplayName("Should extract unit tag")
        void shouldExtractUnitTag() {
            SignedVoucher voucher = createTestVoucher("voucher-1");
            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED);

            String unit = event.getUnit();

            assertEquals(UNIT, unit);
        }

        @Test
        @DisplayName("Should extract expiry tag when present")
        void shouldExtractExpiryTagWhenPresent() {
            long expiresAt = System.currentTimeMillis() / 1000 + 86400;
            SignedVoucher voucher = createTestVoucher("voucher-1", expiresAt);
            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED);

            Long expiry = event.getExpiry();

            assertEquals(expiresAt, expiry);
        }

        @Test
        @DisplayName("Should return null for missing expiry tag")
        void shouldReturnNullForMissingExpiryTag() {
            SignedVoucher voucher = createTestVoucher("voucher-1", null);
            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED);

            Long expiry = event.getExpiry();

            assertNull(expiry);
        }
    }

    @Nested
    @DisplayName("isValid() Tests")
    class IsValidTests {

        @Test
        @DisplayName("Should validate correct event")
        void shouldValidateCorrectEvent() {
            SignedVoucher voucher = createTestVoucher("voucher-1");
            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED);

            assertTrue(event.isValid());
        }

        @Test
        @DisplayName("Should invalidate event with wrong kind")
        void shouldInvalidateEventWithWrongKind() {
            VoucherLedgerEvent event = new VoucherLedgerEvent();
            event.setKind(1);

            assertFalse(event.isValid());
        }
    }

    @Nested
    @DisplayName("Split/Parent Voucher Tests")
    class SplitParentTests {

        private static final ObjectMapper objectMapper = new ObjectMapper();

        @Test
        @DisplayName("Should set isSplit flag in event content")
        void shouldSetIsSplitFlagInContent() throws Exception {
            SignedVoucher voucher = createTestVoucher("voucher-split");

            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED, true);

            JsonNode content = objectMapper.readTree(event.getContent());
            assertTrue(content.get("voucher").get("isSplit").asBoolean());
        }

        @Test
        @DisplayName("Should not set isSplit when false")
        void shouldNotSetIsSplitWhenFalse() throws Exception {
            SignedVoucher voucher = createTestVoucher("voucher-nosplit");

            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED, false);

            JsonNode content = objectMapper.readTree(event.getContent());
            assertTrue(content.get("voucher").get("isSplit") == null
                    || !content.get("voucher").get("isSplit").asBoolean());
        }

        @Test
        @DisplayName("Should set parentVoucherId in content and add parent tag")
        void shouldSetParentVoucherIdInContentAndTag() throws Exception {
            SignedVoucher voucher = createTestVoucher("voucher-child");
            String parentId = "parent-voucher-123";

            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(
                    voucher, VoucherStatus.ISSUED, true, parentId);

            // Verify content
            JsonNode content = objectMapper.readTree(event.getContent());
            assertEquals(parentId, content.get("voucher").get("parentVoucherId").asText());

            // Verify parent tag
            String parentTagValue = getTagValue(event, "parent");
            assertEquals(parentId, parentTagValue);
        }

        @Test
        @DisplayName("Should not add parent tag when parentVoucherId is null")
        void shouldNotAddParentTagWhenNull() {
            SignedVoucher voucher = createTestVoucher("voucher-root");

            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(
                    voucher, VoucherStatus.ISSUED, false, null);

            String parentTagValue = getTagValue(event, "parent");
            assertNull(parentTagValue);
        }

        @Test
        @DisplayName("Should add p tag with issuer public key")
        void shouldAddPTagWithIssuerPublicKey() {
            SignedVoucher voucher = createTestVoucher("voucher-ptag");

            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED);

            String pTagValue = getTagValue(event, "p");
            assertEquals(TEST_ISSUER_PUBKEY, pTagValue);
        }

        @Test
        @DisplayName("Should add a p tag with the merchant issuerId for relay filtering")
        void shouldAddPTagWithMerchantIssuerId() {
            SignedVoucher voucher = createTestVoucher("voucher-merchant-ptag");

            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED);

            // Both the publishing-wallet key AND the merchant issuerId must be present as
            // p tags so the merchant dashboard can filter the relay by merchant (#p==issuerId).
            List<String> pTags = getAllTagValues(event, "p");
            assertTrue(pTags.contains(ISSUER_ID), "expected merchant issuerId p tag, got: " + pTags);
            assertTrue(pTags.contains(TEST_ISSUER_PUBKEY), "expected issuerPublicKey p tag, got: " + pTags);
        }

        @Test
        @DisplayName("Should preserve isSplit through round-trip")
        void shouldPreserveIsSplitThroughRoundTrip() throws Exception {
            SignedVoucher voucher = createTestVoucher("voucher-split-rt");

            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED, true);

            // Verify content has isSplit
            JsonNode content = objectMapper.readTree(event.getContent());
            assertTrue(content.get("voucher").get("isSplit").asBoolean());

            // Verify toVoucher still works
            SignedVoucher restored = event.toVoucher();
            assertNotNull(restored);
            assertEquals(voucher.getSecret().getVoucherId(), restored.getSecret().getVoucherId());
        }

        private String getTagValue(VoucherLedgerEvent event, String tagName) {
            List<BaseTag> tags = event.getTags();
            if (tags == null) return null;
            for (BaseTag tag : tags) {
                if (tag.getCode() != null && tag.getCode().equals(tagName)
                        && tag instanceof GenericTag genericTag) {
                    List<String> params = genericTag.getParams();
                    if (params != null && !params.isEmpty()) {
                        return params.get(0);
                    }
                }
            }
            return null;
        }

        private List<String> getAllTagValues(VoucherLedgerEvent event, String tagName) {
            List<String> values = new java.util.ArrayList<>();
            List<BaseTag> tags = event.getTags();
            if (tags == null) return values;
            for (BaseTag tag : tags) {
                if (tag.getCode() != null && tag.getCode().equals(tagName)
                        && tag instanceof GenericTag genericTag) {
                    List<String> params = genericTag.getParams();
                    if (params != null && !params.isEmpty()) {
                        values.add(params.get(0));
                    }
                }
            }
            return values;
        }
    }

    @Nested
    @DisplayName("Round-trip Tests")
    class RoundTripTests {

        @Test
        @DisplayName("Should preserve voucher data through round-trip")
        void shouldPreserveVoucherDataThroughRoundTrip() {
            // Given
            SignedVoucher original = createTestVoucher("voucher-roundtrip");
            VoucherStatus originalStatus = VoucherStatus.ISSUED;

            // When
            VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(original, originalStatus);
            SignedVoucher restored = event.toVoucher();
            VoucherStatus restoredStatus = event.getStatus();

            // Then
            assertEquals(original.getSecret().getVoucherId(), restored.getSecret().getVoucherId());
            assertEquals(original.getSecret().getIssuerId(), restored.getSecret().getIssuerId());
            assertEquals(original.getSecret().getUnit(), restored.getSecret().getUnit());
            assertEquals(original.getSecret().getFaceValue(), restored.getSecret().getFaceValue());
            assertEquals(original.getSecret().getExpiresAt(), restored.getSecret().getExpiresAt());
            assertEquals(original.getSecret().getMemo(), restored.getSecret().getMemo());
            assertEquals(originalStatus, restoredStatus);
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
        java.util.UUID id = java.util.UUID.nameUUIDFromBytes(voucherId.getBytes());
        VoucherSecret secret = VoucherSecret.builder()
                .voucherId(id)
                .issuerId(ISSUER_ID)
                .unit(UNIT)
                .faceValue(FACE_VALUE)
                .expiresAt(expiresAt)
                .memo("Test voucher")
                .backingStrategy(BackingStrategy.FIXED.name())
                .issuanceRatio(1.0)
                .faceDecimals(0)
                .build();

        return VoucherSignatureService.createSigned(
                secret,
                TEST_ISSUER_PRIVKEY,
                TEST_ISSUER_PUBKEY
        );
    }
}
