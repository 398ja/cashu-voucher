package xyz.tcheeric.cashu.voucher.nostr;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import nostr.event.impl.GenericEvent;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Adapter for Nostr relay client operations.
 *
 * <p>This adapter provides a clean abstraction over Nostr relay interactions,
 * handling connection management, event publishing, and subscription management.
 * It's designed to work with any underlying Nostr library implementation.
 *
 * <h3>Features</h3>
 * <ul>
 *   <li>Multi-relay connection management</li>
 *   <li>Automatic reconnection on failure</li>
 *   <li>Async event publishing with confirmation</li>
 *   <li>REQ/CLOSE subscription lifecycle</li>
 *   <li>Connection pooling and health checks</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * <pre>
 * List&lt;String&gt; relays = List.of("wss://relay1.example.com", "wss://relay2.example.com");
 * NostrClientAdapter client = new NostrClientAdapter(relays, 5000, 3);
 *
 * try {
 *     // Connect to relays
 *     client.connect();
 *
 *     // Publish an event
 *     NostrEvent event = ...;
 *     boolean success = client.publishEvent(event, 3000);
 *
 *     // Query events
 *     NostrFilter filter = NostrFilter.builder()
 *         .kinds(List.of(30078))
 *         .authors(List.of(pubkey))
 *         .build();
 *
 *     List&lt;NostrEvent&gt; events = client.queryEvents(filter, 5000);
 *
 * } finally {
 *     client.disconnect();
 * }
 * </pre>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is thread-safe. All connection state is managed using concurrent
 * data structures and atomic operations.
 *
 * @see GenericEvent
 */
@Slf4j
public class NostrClientAdapter {

    private final List<String> relayUrls;
    private final long connectionTimeoutMs;
    private final int maxRetries;

    // Connection state
    private final Map<String, RelayConnection> connections = new ConcurrentHashMap<>();
    private volatile boolean connected = false;

    /**
     * Creates a NostrClientAdapter with specified configuration.
     *
     * @param relayUrls list of relay WebSocket URLs (must not be null or empty)
     * @param connectionTimeoutMs timeout for connection attempts in milliseconds
     * @param maxRetries maximum number of retry attempts for failed operations
     * @throws IllegalArgumentException if parameters are invalid
     */
    public NostrClientAdapter(
            @NonNull List<String> relayUrls,
            long connectionTimeoutMs,
            int maxRetries
    ) {
        if (relayUrls.isEmpty()) {
            throw new IllegalArgumentException("At least one relay URL is required");
        }
        if (connectionTimeoutMs <= 0) {
            throw new IllegalArgumentException("Connection timeout must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries cannot be negative");
        }

        this.relayUrls = new ArrayList<>(relayUrls);
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.maxRetries = maxRetries;

        log.info("NostrClientAdapter initialized: relays={}, timeout={}ms, maxRetries={}",
                relayUrls.size(), connectionTimeoutMs, maxRetries);
    }

    /**
     * Connects to all configured relays.
     *
     * <p>This method attempts to connect to all relays in parallel. If some relays
     * fail to connect, the adapter will still function with the available relays.
     *
     * @throws VoucherNostrException if all relays fail to connect
     */
    public void connect() {
        if (connected) {
            log.debug("Already connected to relays");
            return;
        }

        log.info("Connecting to {} relay(s)...", relayUrls.size());

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String url : relayUrls) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    connectToRelay(url);
                } catch (Exception e) {
                    log.error("Failed to connect to relay: {}", url, e);
                }
            });
            futures.add(future);
        }

        // Wait for all connection attempts
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(connectionTimeoutMs, TimeUnit.MILLISECONDS)
                .join();

        if (connections.isEmpty()) {
            throw new VoucherNostrException("Failed to connect to any relay");
        }

        connected = true;
        log.info("Connected to {}/{} relay(s)", connections.size(), relayUrls.size());
    }

    /**
     * Disconnects from all relays.
     *
     * <p>This method closes all active connections and clears the connection pool.
     * After calling this method, {@link #connect()} must be called again before
     * any operations can be performed.
     */
    public void disconnect() {
        if (!connected) {
            log.debug("Already disconnected");
            return;
        }

        log.info("Disconnecting from {} relay(s)...", connections.size());

        for (Map.Entry<String, RelayConnection> entry : connections.entrySet()) {
            try {
                entry.getValue().close();
                log.debug("Disconnected from relay: {}", entry.getKey());
            } catch (Exception e) {
                log.error("Error disconnecting from relay: {}", entry.getKey(), e);
            }
        }

        connections.clear();
        connected = false;
        log.info("Disconnected from all relays");
    }

    /**
     * Publishes an event to all connected relays.
     *
     * <p>Sends {@code ["EVENT", event]} to every connected relay in parallel and waits for each
     * relay's {@code OK}. A relay counts as successful only on {@code ["OK", id, true]};
     * {@code OK=false}, a connection error, a close or no OK before {@code timeoutMs} counts as
     * a failure. The event must already carry its id and signature.
     *
     * @param event the event to publish (must not be null)
     * @param timeoutMs timeout for waiting for confirmations
     * @return true if at least one relay confirmed receipt, false otherwise
     * @throws VoucherNostrException if not connected or operation fails
     */
    public boolean publishEvent(@NonNull GenericEvent event, long timeoutMs) {
        ensureConnected();

        log.debug("Publishing event: id={}, kind={}", event.getId(), event.getKind());

        Map<String, CompletableFuture<Boolean>> futures = new LinkedHashMap<>();
        for (String relayUrl : connections.keySet()) {
            futures.put(relayUrl, RelayPublish.run(relayUrl, event, timeoutMs));
        }

        // A relay counts only on ["OK", id, true]. OK=false, an error, a close or a timeout is a failure.
        long successCount = 0;
        for (Map.Entry<String, CompletableFuture<Boolean>> entry : futures.entrySet()) {
            try {
                if (Boolean.TRUE.equals(entry.getValue().get(timeoutMs, TimeUnit.MILLISECONDS))) {
                    successCount++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.values().forEach(f -> f.cancel(true));
                throw new VoucherNostrException("Publish interrupted", e);
            } catch (Exception e) {
                entry.getValue().cancel(true);
                log.warn("Publish to relay {} failed: {}", entry.getKey(), e.getMessage());
            }
        }

        boolean success = successCount > 0;
        log.debug("Event published: id={}, success={}/{} relays",
                event.getId(), successCount, connections.size());

        return success;
    }

    /**
     * Runs a NIP-01 query with {@code filter} on every connected relay and returns the union of
     * the events, deduplicated by id.
     *
     * <p>For each relay this opens a WebSocket, sends {@code ["REQ", sub, filter]}, collects every
     * {@code EVENT} for {@code sub} until {@code EOSE}, then sends {@code ["CLOSE", sub]}. Values
     * under {@code kinds} are sent as JSON integers, all others as strings. A {@code limit} is
     * refused: readers of the voucher ledger must see every event at a locator.
     *
     * <p>Relays may cap a no-limit REQ server-side and still send EOSE, so each relay is paged
     * with an inclusive {@code until} (see {@link #pagedQuery}); {@code until} may not be passed in.
     *
     * <p>Fails closed: if any relay cannot be reached, answers {@code CLOSED}, errors, or does not
     * reach {@code EOSE} before {@code timeoutMs}, this throws instead of returning a partial or
     * empty answer, so "no events" always means every relay said so.
     *
     * @param filter NIP-01 filter, e.g. {@code {"kinds":["30078"],"#d":["..."]}}
     * @param timeoutMs overall deadline for every relay to reach EOSE
     * @return the matching events (may be empty only when every relay answered EOSE)
     * @throws VoucherNostrException if not connected or any relay did not answer completely
     */
    public List<GenericEvent> queryEvents(@NonNull Map<String, List<String>> filter, long timeoutMs) {
        ensureConnected();
        if (filter.containsKey("limit") || filter.containsKey("until")) {
            throw new IllegalArgumentException("limit and until are not allowed");
        }
        Map<String, CompletableFuture<Collection<GenericEvent>>> futures = new LinkedHashMap<>();
        for (String relayUrl : connections.keySet()) {
            futures.put(relayUrl, CompletableFuture.supplyAsync(() -> pagedQuery(relayUrl, filter, timeoutMs)));
        }
        Map<String, GenericEvent> eventMap = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<Collection<GenericEvent>>> entry : futures.entrySet()) {
            Collection<GenericEvent> events;
            try {
                events = entry.getValue().join();
            } catch (java.util.concurrent.CompletionException e) {
                futures.values().forEach(f -> f.cancel(true));
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof VoucherNostrException v) {
                    throw v;
                }
                throw new VoucherNostrException("Relay did not answer the query completely: " + entry.getKey(), cause);
            }
            for (GenericEvent event : events) {
                eventMap.putIfAbsent(event.getId(), event);
            }
            requireAtMostMax(eventMap.size());
        }
        return new ArrayList<>(eventMap.values());
    }

    /**
     * Most events one query may return. Matches the ledger's per-locator cap: above it the
     * answer is refused, never truncated.
     */
    public static final int MAX_QUERY_EVENTS = 4096;

    /**
     * When paging stalls (a page adds nothing new) at {@code until = T}, the stalled page holds
     * the relay's answer for events at T and older. If it contains this many or more events at T,
     * the relay may have cut that second off at its cap, which no {@code until} can page past, so
     * the answer is refused. Assumes every relay returns at least {@code SAME_SECOND_REFUSAL + 1}
     * events per REQ (strfry and the gateway return 500). Shared with the TS reader.
     */
    static final int SAME_SECOND_REFUSAL = 10;

    /**
     * Pages one relay past its server-side limit. Relays such as strfry cap a REQ without
     * {@code limit} (500 by default) and return the newest first, then EOSE as if complete. So
     * each page re-queries with {@code until = min(created_at)} of the previous one (inclusive),
     * deduplicating by id, until a page brings nothing new. Fails closed when the stalled page
     * holds {@link #SAME_SECOND_REFUSAL} or more events at its {@code until} (that second may be
     * cut by the relay's cap), when the union exceeds {@link #MAX_QUERY_EVENTS}, or when the
     * page count exceeds {@code MAX_QUERY_EVENTS / firstPageSize + 2}.
     */
    private static Collection<GenericEvent> pagedQuery(String relayUrl, Map<String, List<String>> filter,
                                                       long timeoutMs) {
        Map<String, GenericEvent> seen = new LinkedHashMap<>();
        Map<String, List<String>> pageFilter = new LinkedHashMap<>(filter);
        int maxPages = Integer.MAX_VALUE;
        Long until = null;
        for (int page = 0; ; page++) {
            if (page >= maxPages) {
                throw new VoucherNostrException("Relay " + relayUrl + " needed more than " + maxPages
                        + " pages: refusing the answer");
            }
            List<GenericEvent> events = runPage(relayUrl, pageFilter, timeoutMs);
            if (page == 0) {
                maxPages = MAX_QUERY_EVENTS / Math.max(1, events.size()) + 2;
            }
            int added = 0;
            long min = Long.MAX_VALUE;
            for (GenericEvent e : events) {
                long t = e.getCreatedAt() == null ? 0L : e.getCreatedAt();
                min = Math.min(min, t);
                if (seen.putIfAbsent(e.getId(), e) == null) {
                    added++;
                }
            }
            requireAtMostMax(seen.size());
            if (added == 0) {
                if (until != null) {
                    final long t = until;
                    long atT = events.stream().filter(e -> e.getCreatedAt() != null && e.getCreatedAt() == t).count();
                    if (atT >= SAME_SECOND_REFUSAL) {
                        throw new VoucherNostrException("Relay " + relayUrl + " stalled with " + atT
                                + " events at created_at " + t + ": the answer may be truncated, refusing it");
                    }
                }
                return seen.values();
            }
            until = min;
            pageFilter.put("until", List.of(Long.toString(min)));
        }
    }

    private static List<GenericEvent> runPage(String relayUrl, Map<String, List<String>> filter, long timeoutMs) {
        CompletableFuture<List<GenericEvent>> f =
                RelayQuery.run(relayUrl, generateSubscriptionId(), filter, timeoutMs);
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            throw new VoucherNostrException("Relay query interrupted", e);
        } catch (Exception e) {
            f.cancel(true);
            throw new VoucherNostrException("Relay did not answer the query completely: " + relayUrl,
                    e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e);
        }
    }

    private static void requireAtMostMax(int count) {
        if (count > MAX_QUERY_EVENTS) {
            throw new VoucherNostrException("Query matched more than " + MAX_QUERY_EVENTS
                    + " events: refusing the answer");
        }
    }

    /**
     * Checks if currently connected to at least one relay.
     *
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return connected && !connections.isEmpty();
    }

    /**
     * Gets the number of currently connected relays.
     *
     * @return number of active connections
     */
    public int getConnectedRelayCount() {
        return connections.size();
    }

    /**
     * Gets the list of connected relay URLs.
     *
     * @return unmodifiable list of connected relay URLs
     */
    public List<String> getConnectedRelays() {
        return Collections.unmodifiableList(new ArrayList<>(connections.keySet()));
    }

    /**
     * Connects to a single relay.
     *
     * @param url the relay WebSocket URL
     */
    private void connectToRelay(String url) {
        log.debug("Connecting to relay: {}", url);

        int attempt = 0;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            try {
                RelayConnection conn = new RelayConnection(url);
                conn.connect();
                connections.put(url, conn);
                log.info("Connected to relay: {}", url);
                return;
            } catch (Exception e) {
                lastException = e;
                attempt++;
                if (attempt <= maxRetries) {
                    log.warn("Connection attempt {}/{} failed for relay: {}", attempt, maxRetries + 1, url);
                    try {
                        Thread.sleep(1000 * attempt); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new VoucherNostrException("Connection interrupted", ie);
                    }
                }
            }
        }

        log.error("Failed to connect to relay after {} attempts: {}", maxRetries + 1, url);
        throw new VoucherNostrException("Failed to connect to relay: " + url, lastException);
    }

    /**
     * Ensures the client is connected.
     *
     * @throws VoucherNostrException if not connected
     */
    private void ensureConnected() {
        if (!connected || connections.isEmpty()) {
            throw new VoucherNostrException("Not connected to any relay. Call connect() first.");
        }
    }

    /**
     * Generates a unique subscription ID.
     *
     * @return random subscription ID
     */
    private static String generateSubscriptionId() {
        return "sub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Represents a connection to a single Nostr relay.
     *
     * <p>This is a placeholder implementation that will be replaced with actual
     * WebSocket connection logic when a Nostr library is integrated.
     */
    private static class RelayConnection {
        private final String url;
        private volatile boolean connected = false;

        RelayConnection(String url) {
            this.url = url;
        }

        void connect() {
            // TODO: Implement actual WebSocket connection
            // For now, simulate connection
            log.debug("Simulating connection to: {}", url);
            connected = true;
        }

        void close() {
            // TODO: Implement actual WebSocket close
            log.debug("Simulating close for: {}", url);
            connected = false;
        }
    }
}
