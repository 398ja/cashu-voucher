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
import nostr.base.PublicKey;
import nostr.encryption.MessageCipher44;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.util.NostrUtil;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.nostr.VoucherNostrException;

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
            entry.setVoucher(voucher);
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
        String encryptedContent;
        try {
            // Convert hex keys to byte arrays
            byte[] privKeyBytes = NostrUtil.hexToBytes(userPrivkey);
            byte[] pubKeyBytes = NostrUtil.hexToBytes(userPubkey);

            // Create cipher and encrypt
            MessageCipher44 cipher = new MessageCipher44(privKeyBytes, pubKeyBytes);
            encryptedContent = cipher.encrypt(payloadJson);
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
            // Convert hex keys to byte arrays
            byte[] privKeyBytes = NostrUtil.hexToBytes(userPrivkey);
            byte[] pubKeyBytes = NostrUtil.hexToBytes(userPubkey);

            // Create cipher and decrypt
            MessageCipher44 cipher = new MessageCipher44(privKeyBytes, pubKeyBytes);
            decryptedJson = cipher.decrypt(encryptedContent);
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
                vouchers.add(entry.getVoucher());
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
        private SignedVoucher voucher;

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
