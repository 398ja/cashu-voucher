package xyz.tcheeric.cashu.voucher.nostr;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import nostr.event.impl.GenericEvent;
import nostr.event.serializer.EventSerializer;
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
 * sig} is a BIP-340 Schnorr signature over those 32 id bytes by the {@code pubkey}.
 *
 * <h2>Why the id must be recomputed</h2>
 *
 * <p>An earlier version of this class took the id straight off the wire and checked only that the
 * signature matched it, on the argument that callers also pin the author. That argument is wrong,
 * and the two checks are orthogonal: pinning the author says who signed, recomputing the id says
 * what they signed.
 *
 * <p>The ledger is public, so a genuine issuer event for a voucher is world-readable. An attacker
 * copies its {@code id}, {@code sig} and {@code pubkey} verbatim onto an event whose tags say
 * {@code status=ACTIVE} and whose {@code created_at} is bumped. Every field the old check looked
 * at is genuine, so it verified; the status and content, which no check covered, are the
 * attacker's. Because the read path takes the newest authentic event, the forgery wins, and a
 * REDEEMED voucher is spendable again. That is the double-spend this class exists to prevent.
 *
 * <p>So the id is recomputed from {@code pubkey}, {@code created_at}, {@code kind}, {@code tags}
 * and {@code content} and compared against the carried id before the signature is checked. The
 * computation is nostr-java's own {@code EventSerializer}, not a reimplementation, so it cannot
 * drift from the form the publish path produces. Note that {@code GenericEvent.update()} is
 * unusable here: it overwrites {@code created_at} with the current time, which would both destroy
 * the field being authenticated and make the check trivially pass.
 */
@Slf4j
public final class NostrEventSignatures {

    private NostrEventSignatures() {
    }

    /**
     * Whether the event's id matches its contents and its signature is a valid BIP-340 signature
     * over that id by its author.
     *
     * @param event the event to check
     * @return true when the event is authentic in both senses
     */
    public static boolean verify(@NonNull GenericEvent event) {
        if (event.getSignature() == null || event.getId() == null || event.getPubKey() == null) {
            return false;
        }
        if (!idMatchesContents(event)) {
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
     * Recomputes the NIP-01 id over the event's own fields and compares it, case-insensitively,
     * against the id the event carries.
     *
     * <p>This is what binds the signature to the contents. Without it a valid triple lifted from
     * any genuine event of the same author authenticates arbitrary tags and content.
     */
    private static boolean idMatchesContents(GenericEvent event) {
        if (event.getCreatedAt() == null || event.getKind() == null) {
            // Fields that are part of the id cannot be absent from an event claiming to have one.
            return false;
        }
        try {
            String computed = EventSerializer.serializeAndComputeId(
                    event.getPubKey(),
                    event.getCreatedAt(),
                    event.getKind(),
                    event.getTags(),
                    event.getContent());
            if (computed == null || !computed.equalsIgnoreCase(event.getId())) {
                log.debug("Event id does not match its contents: carried={} computed={}",
                        event.getId(), computed);
                return false;
            }
            return true;
        } catch (Exception e) {
            // An event that cannot be canonically serialised cannot be authenticated.
            log.debug("Event id could not be recomputed: {}", e.getMessage());
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
