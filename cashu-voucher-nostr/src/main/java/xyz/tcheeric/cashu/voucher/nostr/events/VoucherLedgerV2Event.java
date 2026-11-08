package xyz.tcheeric.cashu.voucher.nostr.events;

import nostr.base.PrivateKey;
import nostr.base.PublicKey;
import nostr.event.BaseTag;
import nostr.event.impl.GenericEvent;
import nostr.event.tag.GenericTag;
import nostr.id.Identity;
import xyz.tcheeric.cashu.voucher.domain.ledger.LedgerEvent;
import xyz.tcheeric.cashu.voucher.domain.ledger.VoucherLedgerV2;
import xyz.tcheeric.cashu.voucher.nostr.NostrEventSignatures;
import xyz.tcheeric.cashu.voucher.nostr.VoucherNostrException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * NIP-01 transport for voucher ledger v2 ({@code docs/explanation/voucher-ledger-v2.md}).
 *
 * <p>Turns a sealed domain template into a {@link GenericEvent} signed under its per-voucher
 * event key, and turns a relay event back into a domain {@link LedgerEvent} only after its own
 * NIP-01 id and signature verify. All voucher semantics live in {@link VoucherLedgerV2}.
 */
public final class VoucherLedgerV2Event {

    private VoucherLedgerV2Event() {
    }

    /**
     * Signs a sealed template with its per-voucher key, then wipes the key.
     *
     * @param sealed output of {@link VoucherLedgerV2#seal}
     * @param createdAt unix seconds
     */
    public static GenericEvent toSignedEvent(VoucherLedgerV2.Sealed sealed, long createdAt) {
        LedgerEvent template = sealed.template();
        byte[] sk = sealed.eventSecretKey();
        try {
            List<BaseTag> tags = new ArrayList<>();
            for (List<String> tag : template.tags()) {
                tags.add(BaseTag.create(tag.get(0), tag.subList(1, tag.size())));
            }
            GenericEvent event = new GenericEvent(new PublicKey(template.pubkey()), template.kind(),
                    tags, template.content());
            event.setCreatedAt(createdAt);
            Identity identity = Identity.create(new PrivateKey(sk));
            event.setSignature(identity.sign(event));
            if (!NostrEventSignatures.verify(event)) {
                throw new VoucherNostrException("signed ledger v2 event does not verify");
            }
            return event;
        } finally {
            java.util.Arrays.fill(sk, (byte) 0);
            sealed.wipe();
        }
    }

    /**
     * The domain view of a relay event, or empty when its NIP-01 id or signature does not verify.
     * Passing verification here says nothing about the voucher: {@link VoucherLedgerV2#read}
     * decides that.
     */
    public static Optional<LedgerEvent> fromVerifiedEvent(GenericEvent event) {
        if (event == null || !NostrEventSignatures.verify(event)) {
            return Optional.empty();
        }
        List<List<String>> tags = new ArrayList<>();
        if (event.getTags() != null) {
            for (BaseTag tag : event.getTags()) {
                List<String> t = new ArrayList<>();
                t.add(tag.getCode());
                if (tag instanceof GenericTag generic && generic.getParams() != null) {
                    t.addAll(generic.getParams());
                }
                tags.add(t);
            }
        }
        return Optional.of(new LedgerEvent(event.getPubKey().toString(), event.getKind(), tags, event.getContent()));
    }
}
