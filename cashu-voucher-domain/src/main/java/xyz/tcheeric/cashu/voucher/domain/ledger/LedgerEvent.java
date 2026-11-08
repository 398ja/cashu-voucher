package xyz.tcheeric.cashu.voucher.domain.ledger;

import java.util.List;

/**
 * The NIP-01 fields of a voucher ledger v2 event that the domain cares about. On write it is the
 * template to sign (id, created_at and sig are the transport's business); on read it is what the
 * transport hands over after verifying the event's own NIP-01 id and signature.
 *
 * @param pubkey x-only hex of the per-voucher event key
 * @param kind always 30078 on write
 * @param tags {@code [["d", d]]} plus an optional {@code ["expiration", E]}
 * @param content the NIP-44 v2 payload
 */
public record LedgerEvent(String pubkey, int kind, List<List<String>> tags, String content,
                          long createdAt, String id) {

    public LedgerEvent {
        tags = tags == null ? List.of() : tags.stream().map(List::copyOf).toList();
        id = id == null ? "" : id;
    }

    /** A template or test event without transport metadata ({@code createdAt} 0, empty id). */
    public LedgerEvent(String pubkey, int kind, List<List<String>> tags, String content) {
        this(pubkey, kind, tags, content, 0L, "");
    }
}
