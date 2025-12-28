package xyz.tcheeric.cashu.voucher.nostr.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import nostr.base.ElementAttribute;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.GenericTag;
import nostr.event.tag.IdentifierTag;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;
import xyz.tcheeric.cashu.voucher.nostr.VoucherNostrException;

import java.util.List;

/**
 * Nostr event for voucher ledger records (NIP-33 parameterized replaceable).
 *
 * <p>This class extends {@link GenericEvent} to provide type-safe voucher ledger
 * events. It uses NIP-33 parameterized replaceable events (kind 30078) to ensure
 * each voucher has a unique, updatable record on the ledger.
 *
 * <h3>Event Structure</h3>
 * <pre>
 * {
 *   "kind": 30078,
 *   "pubkey": "&lt;issuer public key&gt;",
 *   "created_at": &lt;issue timestamp&gt;,
 *   "tags": [
 *     ["d", "voucher:&lt;voucher-id&gt;"],
 *     ["status", "&lt;ISSUED|REDEEMED|REVOKED|EXPIRED&gt;"],
 *     ["amount", "&lt;face value&gt;"],
 *     ["unit", "&lt;sat|usd|etc&gt;"],
 *     ["expiry", "&lt;unix timestamp&gt;"]
 *   ],
 *   "content": "&lt;JSON serialized SignedVoucher&gt;"
 * }
 * </pre>
 *
 * <h3>Usage Example</h3>
 * <pre>
 * // Create event from domain voucher
 * SignedVoucher voucher = ...;
 * VoucherLedgerEvent event = VoucherLedgerEvent.fromVoucher(voucher, VoucherStatus.ISSUED);
 *
 * // Extract voucher from event
 * SignedVoucher restored = event.toVoucher();
 * VoucherStatus status = event.getStatus();
 * String voucherId = event.getVoucherId();
 * </pre>
 *
 * @see <a href="https://github.com/nostr-protocol/nips/blob/master/33.md">NIP-33</a>
 */
@Slf4j
@Getter
@Setter
@NoArgsConstructor
public class VoucherLedgerEvent extends GenericEvent {

    /**
     * Event kind for voucher ledger (NIP-33 parameterized replaceable).
     */
    public static final int KIND_VOUCHER_LEDGER = 30078;

    /**
     * Tag name for voucher status.
     */
    public static final String TAG_STATUS = "status";

    /**
     * Tag name for face value amount.
     */
    public static final String TAG_AMOUNT = "amount";

    /**
     * Tag name for currency/unit.
     */
    public static final String TAG_UNIT = "unit";

    /**
     * Tag name for expiry timestamp.
     */
    public static final String TAG_EXPIRY = "expiry";

    /**
     * Prefix for 'd' tag identifiers.
     */
    public static final String D_TAG_PREFIX = "voucher:";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates a VoucherLedgerEvent from a domain voucher and status.
     *
     * @param voucher the signed voucher (must not be null)
     * @param status the voucher status (must not be null)
     * @return VoucherLedgerEvent ready for publishing
     * @throws VoucherNostrException if serialization fails
     */
    public static VoucherLedgerEvent fromVoucher(SignedVoucher voucher, VoucherStatus status) {
        if (voucher == null) {
            throw new IllegalArgumentException("Voucher cannot be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("Status cannot be null");
        }

        VoucherEventPayloadMapper.VoucherPayload voucherPayload = VoucherEventPayloadMapper.toPayload(voucher);
        String voucherId = voucherPayload.getVoucherId();

        log.debug("Creating VoucherLedgerEvent: voucherId={}, status={}", voucherId, status);

        VoucherLedgerEvent event = new VoucherLedgerEvent();
        event.setKind(KIND_VOUCHER_LEDGER);
        // Note: setPubKey needs to be called with actual PublicKey object by the repository
        event.setCreatedAt(System.currentTimeMillis() / 1000);

        // Add tags using BaseTag.create() so they are properly serialized by nostr-java
        // NIP-33 requires 'd' tag for replaceable events
        event.addTag(BaseTag.create("d", D_TAG_PREFIX + voucherId));
        event.addTag(BaseTag.create(TAG_STATUS, status.name()));
        event.addTag(BaseTag.create(TAG_AMOUNT, String.valueOf(voucherPayload.getFaceValue())));
        event.addTag(BaseTag.create(TAG_UNIT, voucherPayload.getUnit()));

        if (voucherPayload.getExpiresAt() != null) {
            event.addTag(BaseTag.create(TAG_EXPIRY, String.valueOf(voucherPayload.getExpiresAt())));
        }

        // Serialize voucher to JSON content
        try {
            VoucherContent content = new VoucherContent(voucherPayload, status);
            String contentJson = objectMapper.writeValueAsString(content);
            event.setContent(contentJson);
            log.debug("Serialized voucher to event content: {} bytes", contentJson.length());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize voucher to JSON: voucherId={}", voucherId, e);
            throw new VoucherNostrException("Failed to serialize voucher", e);
        }

        log.debug("Created VoucherLedgerEvent: kind={}, tags={}", event.getKind(), event.getTags().size());
        return event;
    }

    /**
     * Converts this event back to a domain SignedVoucher.
     *
     * @return the signed voucher
     * @throws VoucherNostrException if deserialization fails
     */
    public SignedVoucher toVoucher() {
        if (getKind() != KIND_VOUCHER_LEDGER) {
            throw new VoucherNostrException(
                    "Invalid event kind: expected " + KIND_VOUCHER_LEDGER + ", got " + getKind()
            );
        }

        log.debug("Extracting voucher from VoucherLedgerEvent: kind={}", getKind());

        String content = getContent();
        if (content == null || content.isBlank()) {
            throw new VoucherNostrException("Event content is empty");
        }

        try {
            VoucherContent voucherContent = objectMapper.readValue(content, VoucherContent.class);
            log.debug("Deserialized voucher from event content");
            return VoucherEventPayloadMapper.toDomain(voucherContent.getVoucher());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("Failed to deserialize voucher from event content", e);
            throw new VoucherNostrException("Failed to deserialize voucher", e);
        }
    }

    /**
     * Gets the voucher status from this event's tags.
     *
     * @return the voucher status
     * @throws VoucherNostrException if status tag is missing or invalid
     */
    public VoucherStatus getStatus() {
        String statusStr = getTagValue(TAG_STATUS);
        if (statusStr == null) {
            throw new VoucherNostrException("Status tag not found in event");
        }

        try {
            return VoucherStatus.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            throw new VoucherNostrException("Invalid status value: " + statusStr, e);
        }
    }

    /**
     * Gets the voucher ID from this event's 'd' tag.
     *
     * @return the voucher ID (without prefix)
     * @throws VoucherNostrException if 'd' tag is missing or invalid
     */
    public String getVoucherId() {
        String dValue = getTagValue("d");
        if (dValue == null) {
            throw new VoucherNostrException("d tag not found in event");
        }

        if (!dValue.startsWith(D_TAG_PREFIX)) {
            throw new VoucherNostrException("d tag does not have expected prefix: " + dValue);
        }

        return dValue.substring(D_TAG_PREFIX.length());
    }

    /**
     * Gets the voucher amount from tags.
     *
     * @return the amount
     */
    public Long getAmount() {
        String amountStr = getTagValue(TAG_AMOUNT);
        return amountStr != null ? Long.parseLong(amountStr) : null;
    }

    /**
     * Gets the voucher unit from tags.
     *
     * @return the unit
     */
    public String getUnit() {
        return getTagValue(TAG_UNIT);
    }

    /**
     * Gets the expiry timestamp from tags.
     *
     * @return the expiry timestamp, or null if not set
     */
    public Long getExpiry() {
        String expiryStr = getTagValue(TAG_EXPIRY);
        return expiryStr != null ? Long.parseLong(expiryStr) : null;
    }

    /**
     * Helper to get a tag value from the parent GenericEvent's tags.
     *
     * <p>Handles both GenericTag (for custom tags like status, amount, unit) and
     * IdentifierTag (for the "d" tag which is registered in nostr-java's TagRegistry).
     *
     * @param tagName the tag name (code)
     * @return the first parameter value, or null if not found
     */
    private String getTagValue(String tagName) {
        List<BaseTag> tags = super.getTags();
        if (tags == null || tags.isEmpty()) {
            return null;
        }

        for (BaseTag tag : tags) {
            if (tag.getCode() != null && tag.getCode().equals(tagName)) {
                // Handle IdentifierTag (registered for "d" tag in nostr-java)
                if (tag instanceof IdentifierTag identifierTag) {
                    return identifierTag.getUuid();
                }
                // Handle GenericTag (for unregistered tags like status, amount, unit)
                if (tag instanceof GenericTag genericTag) {
                    List<ElementAttribute> attrs = genericTag.getAttributes();
                    if (attrs != null && !attrs.isEmpty()) {
                        return attrs.get(0).value().toString();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Checks if this event is a valid voucher ledger event.
     *
     * @return true if valid, false otherwise
     */
    public boolean isValid() {
        if (getKind() != KIND_VOUCHER_LEDGER) {
            return false;
        }

        try {
            String voucherId = getVoucherId();
            return voucherId != null && !voucherId.isBlank();
        } catch (VoucherNostrException e) {
            return false;
        }
    }

    /**
     * Internal DTO for event content serialization.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    private static class VoucherContent {
        @JsonProperty("voucher")
        private VoucherEventPayloadMapper.VoucherPayload voucher;

        @JsonProperty("status")
        private VoucherStatus status;

        public VoucherContent(VoucherEventPayloadMapper.VoucherPayload voucher, VoucherStatus status) {
            this.voucher = voucher;
            this.status = status;
        }
    }
}
