package xyz.tcheeric.cashu.voucher.nostr;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import nostr.event.impl.GenericEvent;
import xyz.tcheeric.cashu.voucher.domain.ledger.LedgerEntry;
import xyz.tcheeric.cashu.voucher.domain.ledger.LedgerEvent;
import xyz.tcheeric.cashu.voucher.domain.ledger.VoucherLedgerRef;
import xyz.tcheeric.cashu.voucher.domain.ledger.VoucherLedgerV2;
import xyz.tcheeric.cashu.voucher.nostr.events.VoucherLedgerV2Event;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Relay repository for voucher ledger v2 ({@code docs/explanation/voucher-ledger-v2.md}).
 *
 * <p>Publishes sealed entries under their per-voucher event key and reads every event at a
 * voucher's locator, keeping only those whose NIP-01 id and signature verify and whose plaintext
 * {@link VoucherLedgerV2#read} accepts. v1 ({@link NostrVoucherLedgerRepository}) is untouched and
 * stays the default until callers switch.
 *
 * <p>Nothing here logs or sends the voucher id: the subscription id and filter carry only the
 * locator {@code d}.
 */
@Slf4j
public class NostrVoucherLedgerV2Repository {

    private final NostrClientAdapter nostrClient;
    private final LedgerV2EventSource eventSource;
    private final Set<String> trustedIssuerKeys;
    private final long publishTimeoutMs;
    private final long queryTimeoutMs;

    /**
     * @param trustedIssuerKeys x-only hex keys trusted to sign {@code ISSUED} (the platform key)
     */
    public NostrVoucherLedgerV2Repository(@NonNull NostrClientAdapter nostrClient,
                                          @NonNull Collection<String> trustedIssuerKeys) {
        this(nostrClient, trustedIssuerKeys, 5000L, 5000L);
    }

    public NostrVoucherLedgerV2Repository(@NonNull NostrClientAdapter nostrClient,
                                          @NonNull Collection<String> trustedIssuerKeys,
                                          long publishTimeoutMs, long queryTimeoutMs) {
        this(nostrClient, nostrClient::queryEvents, trustedIssuerKeys, publishTimeoutMs, queryTimeoutMs);
    }

    /**
     * @param eventSource where events are read from (default: the adapter's filtered relay query);
     *        must throw rather than answer incompletely, see {@link LedgerV2EventSource}
     */
    public NostrVoucherLedgerV2Repository(@NonNull NostrClientAdapter nostrClient,
                                          @NonNull LedgerV2EventSource eventSource,
                                          @NonNull Collection<String> trustedIssuerKeys,
                                          long publishTimeoutMs, long queryTimeoutMs) {
        this.nostrClient = nostrClient;
        this.eventSource = eventSource;
        this.trustedIssuerKeys = Set.copyOf(trustedIssuerKeys);
        this.publishTimeoutMs = publishTimeoutMs;
        this.queryTimeoutMs = queryTimeoutMs;
    }

    /**
     * Seals and publishes an entry.
     *
     * @param entry the entry (terminal credentials are refused by the domain)
     * @param signerSecretKey inner signer: platform key for ISSUED, stall key for REDEEMED/REVOKED
     * @param publisherSecretKey the publisher's own secret key, whose ledger root derives the event key
     * @return the published event
     * @throws VoucherNostrException when no relay accepted it
     */
    public GenericEvent publish(@NonNull LedgerEntry entry, byte @NonNull [] signerSecretKey,
                                byte @NonNull [] publisherSecretKey) {
        byte[] root = VoucherLedgerRef.ledgerRoot(publisherSecretKey);
        GenericEvent event;
        try {
            event = VoucherLedgerV2Event.toSignedEvent(
                    VoucherLedgerV2.seal(entry, signerSecretKey, root), System.currentTimeMillis() / 1000);
        } finally {
            Arrays.fill(root, (byte) 0);
        }
        if (!nostrClient.publishEvent(event, publishTimeoutMs)) {
            throw new VoucherNostrException("Failed to publish ledger v2 event to any relay: id=" + event.getId());
        }
        log.debug("Published ledger v2 event: id={}, status={}", event.getId(), entry.status());
        return event;
    }

    /** The NIP-01 filter for a voucher's events: {@code {"kinds":[30078],"#d":[d]}}. */
    public static Map<String, List<String>> filterFor(byte[] ref) {
        return Map.of("kinds", List.of(String.valueOf(VoucherLedgerV2.KIND)),
                "#d", List.of(VoucherLedgerRef.locator(ref)));
    }

    /** Every accepted event at the voucher's locator. */
    public List<VoucherLedgerV2.Opened> query(byte @NonNull [] ref) {
        List<VoucherLedgerV2.Opened> out = new ArrayList<>();
        for (LedgerEvent e : verifiedEvents(ref)) {
            VoucherLedgerV2.open(e, ref, trustedIssuerKeys).ifPresent(out::add);
        }
        return out;
    }

    /**
     * The folded status of a voucher, or empty when every relay answered and no event is accepted.
     *
     * @throws VoucherNostrException when the relays could not answer completely (no connection,
     *         error, or no EOSE before the timeout). Never answers empty in that case, so a
     *         revoked or redeemed voucher cannot read as unknown.
     * @throws xyz.tcheeric.cashu.voucher.domain.ledger.LedgerRefusedException
     *         {@code TOO_MANY_CANDIDATES} when the locator holds more than
     *         {@link VoucherLedgerV2#MAX_CANDIDATES_PER_LOCATOR} events
     */
    public Optional<VoucherLedgerV2.Resolved> resolve(byte @NonNull [] ref) {
        return VoucherLedgerV2.resolve(verifiedEvents(ref), ref, trustedIssuerKeys);
    }

    private List<LedgerEvent> verifiedEvents(byte[] ref) {
        List<GenericEvent> events;
        try {
            events = eventSource.query(filterFor(ref), queryTimeoutMs);
        } catch (VoucherNostrException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new VoucherNostrException("ledger v2 query failed", e);
        }
        if (events == null) {
            throw new VoucherNostrException("ledger v2 query returned no answer");
        }
        // Refuse a flood before paying a NIP-01 Schnorr verify per event. Never truncate.
        VoucherLedgerV2.checkCandidateCount(events.size());
        List<LedgerEvent> verified = new ArrayList<>();
        for (GenericEvent event : events) {
            VoucherLedgerV2Event.fromVerifiedEvent(event).ifPresent(verified::add);
        }
        return verified;
    }
}
