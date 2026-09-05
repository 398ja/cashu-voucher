package xyz.tcheeric.cashu.voucher.nostr;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import nostr.event.impl.GenericEvent;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.crypto.Schnorr;

/**
 * Verifies that a Nostr event was signed by the key it claims to come from.
 *
 * <h2>Why this is here rather than in the library</h2>
 *
 * <p>nostr-java signs events but ships no verifier, so a reader has nothing to call. The voucher
 * ledger read path had a corresponding hole: it trusted the {@code pubkey} field and the status
 * carried by whatever the relay returned (audit H-13). A relay is an untrusted transport, and
 * the status is what the online double-spend check depends on, so a hostile relay could flip a
 * REDEEMED voucher back to ACTIVE and enable a second redemption.
 *
 * <h2>What NIP-01 requires</h2>
 *
 * <p>The event {@code id} is the SHA-256 of a canonical serialisation of the event, and {@code
 * sig} is a BIP-340 Schnorr signature over those 32 id bytes by the {@code pubkey}. Verification
 * is therefore: recompute nothing, take the id as the message, and check the signature against
 * the author's x-only key.
 *
 * <p>Note the deliberate limit: this confirms the signature matches the id, not that the id
 * matches the event contents. Recomputing the id requires the exact canonical form nostr-java
 * produces, and duplicating that here would risk disagreeing with it. Callers additionally
 * require the author to be a specific expected key, which is what makes the pair meaningful.
 */
@Slf4j
public final class NostrEventSignatures {

    private NostrEventSignatures() {
    }

    /**
     * Whether the event's signature is a valid BIP-340 signature over its id by its author.
     *
     * @param event the event to check
     * @return true when the signature verifies
     */
    public static boolean verify(@NonNull GenericEvent event) {
        if (event.getSignature() == null || event.getId() == null || event.getPubKey() == null) {
            return false;
        }
        try {
            byte[] message = Hex.decode(event.getId());
            byte[] signature = Hex.decode(event.getSignature().toString());
            byte[] pubkey = xOnly(Hex.decode(event.getPubKey().toString()));

            if (message.length != 32 || signature.length != 64 || pubkey.length != 32) {
                log.debug("Malformed event for verification: id={} sig={} pubkey={} bytes",
                        message.length, signature.length, pubkey.length);
                return false;
            }
            return Schnorr.verify(message, pubkey, signature);
        } catch (RuntimeException e) {
            // Malformed hex, a bad point, anything: not a valid signature.
            log.debug("Event signature could not be verified: {}", e.getMessage());
            return false;
        }
    }

    /**
     * BIP-340 keys are x-only. A key written in compressed SEC1 form carries a parity prefix that
     * must be dropped before verification; one already x-only is returned unchanged.
     */
    private static byte[] xOnly(byte[] pubkey) {
        if (pubkey.length == 33 && (pubkey[0] == 0x02 || pubkey[0] == 0x03)) {
            byte[] stripped = new byte[32];
            System.arraycopy(pubkey, 1, stripped, 0, 32);
            return stripped;
        }
        return pubkey;
    }
}
