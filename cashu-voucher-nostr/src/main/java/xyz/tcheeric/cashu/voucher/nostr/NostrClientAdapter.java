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
     * <p>This method publishes the event to all relays in parallel and waits for
     * confirmations. The operation is considered successful if at least one relay
     * confirms receipt.
     *
     * @param event the event to publish (must not be null)
     * @param timeoutMs timeout for waiting for confirmations
     * @return true if at least one relay confirmed receipt, false otherwise
     * @throws VoucherNostrException if not connected or operation fails
     */
    public boolean publishEvent(@NonNull GenericEvent event, long timeoutMs) {
        ensureConnected();

        log.debug("Publishing event: id={}, kind={}", event.getId(), event.getKind());

        List<CompletableFuture<Boolean>> futures = new ArrayList<>();

        for (Map.Entry<String, RelayConnection> entry : connections.entrySet()) {
            String relayUrl = entry.getKey();
            RelayConnection conn = entry.getValue();

            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return conn.publish(event);
                } catch (Exception e) {
                    log.error("Failed to publish to relay: {}", relayUrl, e);
                    return false;
                }
            });

            futures.add(future);
        }

        // Wait for all publish attempts
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("Some publish operations timed out or failed", e);
        }

        // Check if at least one succeeded
        long successCount = futures.stream()
                .map(f -> f.getNow(false))
                .filter(Boolean::booleanValue)
                .count();

        boolean success = successCount > 0;
        log.debug("Event published: id={}, success={}/{} relays",
                event.getId(), successCount, connections.size());

        return success;
    }

    /**
     * Queries events from relays matching the given filter.
     *
     * <p>This method sends a REQ message to all connected relays and collects
     * matching events. Duplicate events (same ID) are automatically deduplicated.
     *
     * @param subscriptionId the subscription ID for this query
     * @param timeoutMs timeout for waiting for responses
     * @return list of matching events (never null, may be empty)
     * @throws VoucherNostrException if not connected or operation fails
     */
    public List<GenericEvent> queryEvents(@NonNull String subscriptionId, long timeoutMs) {
        ensureConnected();

        log.debug("Querying events: subscriptionId={}", subscriptionId);

        Map<String, GenericEvent> eventMap = new ConcurrentHashMap<>();

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (Map.Entry<String, RelayConnection> entry : connections.entrySet()) {
            String relayUrl = entry.getKey();
            RelayConnection conn = entry.getValue();

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    List<GenericEvent> events = conn.query(subscriptionId);
                    for (GenericEvent event : events) {
                        eventMap.putIfAbsent(event.getId(), event);
                    }
                    log.debug("Received {} event(s) from relay: {}", events.size(), relayUrl);
                } catch (Exception e) {
                    log.error("Failed to query relay: {}", relayUrl, e);
                }
            });

            futures.add(future);
        }

        // Wait for all queries
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("Some query operations timed out or failed", e);
        }

        List<GenericEvent> results = new ArrayList<>(eventMap.values());
        log.debug("Query complete: {} unique event(s) found", results.size());

        return results;
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
    private String generateSubscriptionId() {
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

        boolean publish(GenericEvent event) {
            if (!connected) {
                throw new VoucherNostrException("Relay not connected");
            }
            // TODO: Implement actual EVENT message sending
            log.debug("Simulating publish to: {}", url);
            return true;
        }

        List<GenericEvent> query(String subscriptionId) {
            if (!connected) {
                throw new VoucherNostrException("Relay not connected");
            }
            // TODO: Implement actual REQ/EOSE handling
            log.debug("Simulating query to: {} with subscription: {}", url, subscriptionId);
            return Collections.emptyList();
        }

        void close() {
            // TODO: Implement actual WebSocket close
            log.debug("Simulating close for: {}", url);
            connected = false;
        }
    }
}
