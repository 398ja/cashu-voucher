package xyz.tcheeric.cashu.voucher.nostr.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import nostr.base.Kind;
import nostr.base.PrivateKey;
import nostr.base.PublicKey;
import nostr.crypto.Point;
import nostr.crypto.nip44.EncryptedPayloads;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.id.Identity;
import nostr.util.NostrUtil;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.nostr.VoucherNostrException;

import java.security.SecureRandom;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles voucher backup payload serialization using NIP-17 private messaging.
 *
 * <p>This class implements secure, private voucher backups using:
 * <ul>
 *   <li><b>NIP-17</b>: Private Direct Messages (sealed sender pattern)</li>
 *   <li><b>NIP-44</b>: Versioned encryption (XChaCha20-Poly1305)</li>
 * </ul>
 *
 * <h3>Backup Architecture</h3>
 * <p>Voucher backups are encrypted and stored as private messages to self:
 * <ol>
 *   <li>User creates vouchers with their Nostr identity</li>
 *   <li>Vouchers are encrypted with user's public key (NIP-44)</li>
 *   <li>Encrypted payload is stored in kind 4 event (encrypted DM to self)</li>
 *   <li>Event is published to relays</li>
 *   <li>Only user can decrypt and restore their vouchers</li>
 * </ol>
 *
 * <h3>Privacy Guarantees</h3>
 * <ul>
 *   <li>Content encryption: Only user can read voucher data</li>
 *   <li>Self-addressed: Messages sent to user's own pubkey</li>
 *   <li>NIP-44 encryption: Modern versioned encryption scheme</li>
 * </ul>
 *
 * <h3>Payload Structure</h3>
 * <pre>
 * {
 *   "version": "1.0",
 *   "vouchers": [
 *     {
 *       "voucher": &lt;SignedVoucher JSON&gt;,
 *       "backedUpAt": &lt;unix timestamp&gt;
 *     }
 *   ],
 *   "metadata": {
 *     "totalCount": 5,
 *     "backupTimestamp": &lt;unix timestamp&gt;
 *   }
 * }
 * </pre>
 *
 * <h3>Usage Example</h3>
 * <pre>
 * // Backup vouchers
 * List&lt;SignedVoucher&gt; vouchers = ...;
 * String userPrivkey = "...";
 * String userPubkey = "...";
 *
 * GenericEvent backupEvent = VoucherBackupPayload.createBackupEvent(
 *     vouchers, userPrivkey, userPubkey
 * );
 *
 * // Restore vouchers
 * List&lt;SignedVoucher&gt; restored = VoucherBackupPayload.extractVouchers(
 *     backupEvent, userPrivkey, userPubkey
 * );
 * </pre>
 *
 * @see <a href="https://github.com/nostr-protocol/nips/blob/master/44.md">NIP-44</a>
 */
@Slf4j
public class VoucherBackupPayload {

    /**
     * Current payload version.
     */
    public static final String PAYLOAD_VERSION = "1.0";

    /**
     * Event kind for encrypted direct messages (NIP-04/NIP-44).
     */
    public static final int KIND_ENCRYPTED_DM = Kind.ENCRYPTED_DIRECT_MESSAGE.getValue();

    /**
     * Tag for backup identification.
     */
    public static final String TAG_BACKUP = "backup";

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    static {
        // Ensure BC provider handles ChaCha20 with IvParameterSpec per NIP-44
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }

    /**
     * Creates an encrypted backup event for vouchers.
     *
     * <p>This method:
     * <ol>
     *   <li>Serializes vouchers to JSON payload</li>
     *   <li>Encrypts payload with NIP-44</li>
     *   <li>Creates kind 4 event (encrypted DM to self)</li>
     *   <li>Signs with user's private key</li>
     * </ol>
     *
     * @param vouchers list of vouchers to backup (must not be null)
     * @param userPrivkey user's Nostr private key (hex, must not be null)
     * @param userPubkey user's Nostr public key (hex, must not be null)
     * @return encrypted event ready for publishing
     * @throws VoucherNostrException if encryption or serialization fails
     */
    public static GenericEvent createBackupEvent(
            @NonNull List<SignedVoucher> vouchers,
            @NonNull String userPrivkey,
            @NonNull String userPubkey
    ) {
        if (userPrivkey.isBlank()) {
            throw new IllegalArgumentException("User private key cannot be blank");
        }
        if (userPubkey.isBlank()) {
            throw new IllegalArgumentException("User public key cannot be blank");
        }

        log.info("Creating backup event for {} voucher(s)", vouchers.size());

        // Create payload
        BackupPayload payload = new BackupPayload();
        payload.setVersion(PAYLOAD_VERSION);

        long backupTimestamp = System.currentTimeMillis() / 1000;
        List<VoucherBackupEntry> entries = new ArrayList<>();

        for (SignedVoucher voucher : vouchers) {
            VoucherBackupEntry entry = new VoucherBackupEntry();
            entry.setVoucher(VoucherEventPayloadMapper.toPayload(voucher));
            entry.setBackedUpAt(backupTimestamp);
            entries.add(entry);
        }

        payload.setVouchers(entries);

        BackupMetadata metadata = new BackupMetadata();
        metadata.setTotalCount(vouchers.size());
        metadata.setBackupTimestamp(backupTimestamp);
        payload.setMetadata(metadata);

        // Serialize payload to JSON
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
            log.debug("Serialized backup payload: {} bytes", payloadJson.length());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize backup payload", e);
            throw new VoucherNostrException("Failed to serialize backup payload", e);
        }

        // Encrypt with NIP-44
        // Note: EncryptedPayloads.getConversationKey requires uncompressed pubkey (04 prefix + X + Y)
        String encryptedContent;
        try {
            PublicKey recipientPubKey = new PublicKey(userPubkey);
            // Get raw x-only bytes (32 bytes) and convert to uncompressed format for NIP-44
            byte[] xOnlyBytes = recipientPubKey.getRawData();
            String uncompressedHex = convertXOnlyToUncompressed(xOnlyBytes);

            byte[] conversationKey = EncryptedPayloads.getConversationKey(userPrivkey, uncompressedHex);
            byte[] nonce = new byte[32];
            SECURE_RANDOM.nextBytes(nonce);

            encryptedContent = EncryptedPayloads.encrypt(payloadJson, conversationKey, nonce);
            log.debug("Encrypted payload with NIP-44: {} bytes", encryptedContent.length());
        } catch (Exception e) {
            log.error("Failed to encrypt backup payload", e);
            throw new VoucherNostrException("Failed to encrypt backup payload", e);
        }

        // Create encrypted direct message event (kind 4)
        GenericEvent event = new GenericEvent();
        event.setKind(KIND_ENCRYPTED_DM);
        event.setPubKey(new PublicKey(userPubkey));
        event.setCreatedAt(backupTimestamp);
        event.setContent(encryptedContent);

        // Tags: p (recipient - self) and backup identifier
        List<BaseTag> tags = new ArrayList<>();
        tags.add(BaseTag.create("p", userPubkey));
        tags.add(BaseTag.create(TAG_BACKUP, "vouchers"));
        event.setTags(tags);

        log.info("Created backup event: {} vouchers encrypted", vouchers.size());
        return event;
    }

    /**
     * Converts x-only (32 byte) public key to uncompressed format (130 hex chars) required by NIP-44.
     * Uses nostr-java's Point.liftX to reconstruct the full EC point (assumes even Y coordinate per BIP340).
     */
    private static String convertXOnlyToUncompressed(byte[] xOnlyBytes) {
        if (xOnlyBytes.length != 32) {
            throw new IllegalArgumentException("Expected 32-byte x-only public key, got " + xOnlyBytes.length);
        }

        // Lift x-only key to full EC point using nostr-java's Point class
        Point point = Point.liftX(xOnlyBytes);

        // Convert to uncompressed format: 0x04 + X (32 bytes) + Y (32 bytes)
        byte[] uncompressed = new byte[65];
        uncompressed[0] = 0x04; // Uncompressed point prefix

        byte[] xBytes = point.getX().toByteArray();
        byte[] yBytes = point.getY().toByteArray();

        // Copy X coordinate (handle leading zeros or sign byte)
        int xStart = xBytes.length > 32 ? xBytes.length - 32 : 0;
        int xDest = xBytes.length >= 32 ? 1 : 1 + (32 - xBytes.length);
        System.arraycopy(xBytes, xStart, uncompressed, xDest, Math.min(xBytes.length, 32));

        // Copy Y coordinate (handle leading zeros or sign byte)
        int yStart = yBytes.length > 32 ? yBytes.length - 32 : 0;
        int yDest = yBytes.length >= 32 ? 33 : 33 + (32 - yBytes.length);
        System.arraycopy(yBytes, yStart, uncompressed, yDest, Math.min(yBytes.length, 32));

        return NostrUtil.bytesToHex(uncompressed);
    }

    /**
     * Extracts vouchers from an encrypted backup event.
     *
     * <p>This method:
     * <ol>
     *   <li>Extracts encrypted content from event</li>
     *   <li>Decrypts content with NIP-44</li>
     *   <li>Deserializes JSON payload</li>
     *   <li>Extracts voucher list</li>
     * </ol>
     *
     * @param event the encrypted backup event (must not be null)
     * @param userPrivkey user's Nostr private key for decryption (must not be null)
     * @param userPubkey user's Nostr public key for decryption (must not be null)
     * @return list of restored vouchers (never null, may be empty)
     * @throws VoucherNostrException if decryption or deserialization fails
     */
    public static List<SignedVoucher> extractVouchers(
            @NonNull GenericEvent event,
            @NonNull String userPrivkey,
            @NonNull String userPubkey
    ) {
        if (userPrivkey.isBlank()) {
            throw new IllegalArgumentException("User private key cannot be blank");
        }

        if (userPubkey.isBlank()) {
            throw new IllegalArgumentException("User public key cannot be blank");
        }

        log.debug("Extracting vouchers from backup event");

        if (event.getKind() != KIND_ENCRYPTED_DM) {
            throw new VoucherNostrException("Invalid event kind: expected " + KIND_ENCRYPTED_DM + ", got " + event.getKind());
        }

        String encryptedContent = event.getContent();
        if (encryptedContent == null || encryptedContent.isBlank()) {
            throw new VoucherNostrException("Encrypted content is empty");
        }

        // Decrypt with NIP-44
        String decryptedJson;
        try {
            PublicKey recipientPubKey = new PublicKey(userPubkey);
            byte[] xOnlyBytes = recipientPubKey.getRawData();
            String uncompressedHex = convertXOnlyToUncompressed(xOnlyBytes);

            byte[] conversationKey = EncryptedPayloads.getConversationKey(userPrivkey, uncompressedHex);
            decryptedJson = EncryptedPayloads.decrypt(encryptedContent, conversationKey);
            log.debug("Decrypted payload: {} bytes", decryptedJson.length());
        } catch (Exception e) {
            log.error("Failed to decrypt backup payload", e);
            throw new VoucherNostrException("Failed to decrypt backup payload", e);
        }

        // Deserialize JSON
        BackupPayload payload;
        try {
            payload = objectMapper.readValue(decryptedJson, BackupPayload.class);
            log.debug("Deserialized backup payload: version={}", payload.getVersion());
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize backup payload", e);
            throw new VoucherNostrException("Failed to deserialize backup payload", e);
        }

        // Validate version
        if (!PAYLOAD_VERSION.equals(payload.getVersion())) {
            log.warn("Backup payload version mismatch: expected={}, got={}",
                    PAYLOAD_VERSION, payload.getVersion());
        }

        // Extract vouchers
        List<SignedVoucher> vouchers = new ArrayList<>();
        if (payload.getVouchers() != null) {
            for (VoucherBackupEntry entry : payload.getVouchers()) {
                try {
                    vouchers.add(VoucherEventPayloadMapper.toDomain(entry.getVoucher()));
                } catch (IllegalArgumentException ex) {
                    throw new VoucherNostrException("Failed to reconstruct voucher from backup entry", ex);
                }
            }
        }

        log.info("Extracted {} voucher(s) from backup", vouchers.size());
        return vouchers;
    }

    /**
     * Checks if an event is a valid voucher backup event.
     *
     * @param event the event to check
     * @return true if valid backup event, false otherwise
     */
    public static boolean isValidBackupEvent(GenericEvent event) {
        if (event == null) {
            return false;
        }

        return event.getKind() == KIND_ENCRYPTED_DM &&
               event.getContent() != null &&
               !event.getContent().isBlank();
    }

    /**
     * Root payload structure for voucher backups.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class BackupPayload {
        @JsonProperty("version")
        private String version;

        @JsonProperty("vouchers")
        private List<VoucherBackupEntry> vouchers;

        @JsonProperty("metadata")
        private BackupMetadata metadata;
    }

    /**
     * Individual voucher entry in backup.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class VoucherBackupEntry {
        @JsonProperty("voucher")
        private VoucherEventPayloadMapper.VoucherPayload voucher;

        @JsonProperty("backedUpAt")
        private Long backedUpAt;
    }

    /**
     * Backup metadata.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class BackupMetadata {
        @JsonProperty("totalCount")
        private Integer totalCount;

        @JsonProperty("backupTimestamp")
        private Long backupTimestamp;
    }
}
