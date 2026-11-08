package xyz.tcheeric.cashu.voucher.nostr;

import nostr.base.PublicKey;
import nostr.base.Signature;
import nostr.crypto.schnorr.Schnorr;
import nostr.event.impl.GenericEvent;
import nostr.event.serializer.EventSerializer;
import org.bouncycastle.util.encoders.Hex;

/** Signs an event keeping its created_at (nostr-java's Identity.sign stamps "now"). */
final class TestEventSigner {
    private TestEventSigner() {
    }

    static GenericEvent sign(GenericEvent e, String skHex, long createdAt) {
        try {
            byte[] sk = Hex.decode(skHex);
            e.setPubKey(new PublicKey(Hex.toHexString(Schnorr.genPubKey(sk))));
            e.setCreatedAt(createdAt);
            String id = EventSerializer.serializeAndComputeId(e.getPubKey(), createdAt, e.getKind(), e.getTags(),
                    e.getContent());
            e.setId(id);
            e.setSignature(Signature.fromString(Hex.toHexString(Schnorr.sign(Hex.decode(id), sk, new byte[32]))));
            if (!NostrEventSignatures.verify(e)) {
                throw new IllegalStateException("test signature does not verify");
            }
            return e;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
