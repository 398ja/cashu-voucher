package xyz.tcheeric.cashu.voucher.nostr;

import nostr.event.impl.GenericEvent;

import java.util.List;
import java.util.Map;

/**
 * Where {@link NostrVoucherLedgerV2Repository} reads events from. The default is the relay
 * adapter's filtered REQ/EOSE query ({@link NostrClientAdapter#queryEvents(Map, long)}).
 *
 * <p>Contract: return every event matching {@code filter} (no {@code limit}), or throw when the
 * answer is not complete. Never return a partial or empty list in place of an error.
 *
 * <p>A caching implementation is only safe as read-through-and-merge (always ask the relay,
 * union with cached events, the repository verifies all), or as a short-circuit after a verified
 * terminal event (REDEEMED or REVOKED). Never short-circuit on ISSUED: a later REVOKED or
 * REDEEMED would be missed.
 */
@FunctionalInterface
public interface LedgerV2EventSource {

    List<GenericEvent> query(Map<String, List<String>> filter, long timeoutMs);
}
