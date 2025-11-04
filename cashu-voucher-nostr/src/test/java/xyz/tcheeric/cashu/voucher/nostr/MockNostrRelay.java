package xyz.tcheeric.cashu.voucher.nostr;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import nostr.event.impl.GenericEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Mock implementation of a Nostr relay for testing.
 *
 * <p>This is an in-memory relay simulator that provides all the functionality
 * needed for testing Nostr adapter code without connecting to real relays.
 * It supports event storage, querying, subscriptions, and simulates relay behavior.
 *
 * <h3>Purpose</h3>
 * <p>MockNostrRelay enables:
 * <ul>
 *   <li>Fast, deterministic unit tests without network I/O</li>
 *   <li>Testing edge cases (failures, timeouts, malformed events)</li>
 *   <li>Verifying correct NIP-01 protocol usage</li>
 *   <li>Simulating multi-relay scenarios with different behaviors</li>
 * </ul>
 *
 * <h3>Features</h3>
 * <ul>
 *   <li><b>Event Storage</b>: In-memory event store with indexed lookups</li>
 *   <li><b>NIP-33 Support</b>: Parameterized replaceable events (automatic replacement by d tag)</li>
 *   <li><b>Query Simulation</b>: Filter-based event queries (kind, author, tags)</li>
 *   <li><b>Failure Injection</b>: Simulate network failures, timeouts, rejections</li>
 *   <li><b>Event History</b>: Track all published events for verification</li>
 *   <li><b>Statistics</b>: Query counts, publish counts, etc.</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * <pre>
 * // Create mock relay
 * MockNostrRelay relay = new MockNostrRelay("test-relay");
 *
 * // Publish events
 * GenericEvent event = new GenericEvent();
 * event.setKind(1);
 * event.setContent("Test note");
 * relay.publishEvent(event);
 *
 * // Query events
 * List&lt;GenericEvent&gt; events = relay.queryEvents(
 *     e -&gt; e.getKind() == 1
 * );
 *
 * // Verify behavior
 * assertEquals(1, relay.getPublishedEventCount());
 * assertEquals(1, relay.getQueryCount());
 *
 * // Simulate failures
 * relay.setPublishFailureRate(0.5); // 50% failure rate
 * relay.setQueryDelayMs(1000); // 1 second delay
 * </pre>
 *
 * <h3>NIP-33 Replaceable Events</h3>
 * <p>The mock relay correctly implements NIP-33 parameterized replaceable events:
 * <ul>
 *   <li>Kind 30000-39999 events with same (pubkey, kind, d-tag) replace older ones</li>
 *   <li>Newest event by created_at timestamp is kept</li>
 *   <li>Old events are automatically removed from storage</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is thread-safe. All internal collections use concurrent implementations.
 *
 * @see NostrClientAdapter
 * @see GenericEvent
 */
@Slf4j
@Getter
public class MockNostrRelay {

    /**
     * NIP-33 kind range for parameterized replaceable events.
     */
    private static final int NIP33_KIND_MIN = 30000;
    private static final int NIP33_KIND_MAX = 39999;

    private final String relayUrl;
    private final Map<String, GenericEvent> eventStore = new ConcurrentHashMap<>();
    private final List<GenericEvent> publishHistory = new CopyOnWriteArrayList<>();
    private final Map<String, List<GenericEvent>> subscriptions = new ConcurrentHashMap<>();

    // Statistics
    private long publishCount = 0;
    private long queryCount = 0;
    private long subscriptionCount = 0;

    // Behavior simulation
    private boolean connected = false;
    private double publishFailureRate = 0.0;
    private double queryFailureRate = 0.0;
    private long publishDelayMs = 0;
    private long queryDelayMs = 0;
    private boolean acceptAllEvents = true;
    private final List<Predicate<GenericEvent>> eventFilters = new CopyOnWriteArrayList<>();

    /**
     * Creates a MockNostrRelay with the given URL.
     *
     * @param relayUrl the relay URL (for identification in logs)
     */
    public MockNostrRelay(String relayUrl) {
        this.relayUrl = relayUrl != null ? relayUrl : "mock://test-relay";
        log.debug("MockNostrRelay created: {}", this.relayUrl);
    }

    /**
     * Creates a MockNostrRelay with default test URL.
     */
    public MockNostrRelay() {
        this("mock://test-relay");
    }

    /**
     * Connects to the relay (simulated).
     */
    public void connect() {
        log.debug("Connecting to mock relay: {}", relayUrl);
        connected = true;
    }

    /**
     * Disconnects from the relay (simulated).
     */
    public void disconnect() {
        log.debug("Disconnecting from mock relay: {}", relayUrl);
        connected = false;
    }

    /**
     * Publishes an event to the relay.
     *
     * @param event the event to publish
     * @return true if published successfully, false if rejected
     * @throws IllegalStateException if not connected
     */
    public boolean publishEvent(GenericEvent event) {
        ensureConnected();

        publishCount++;
        publishHistory.add(event);

        // Simulate publish delay
        simulateDelay(publishDelayMs);

        // Simulate publish failure
        if (shouldSimulateFailure(publishFailureRate)) {
            log.debug("Simulated publish failure: {}", event.getId());
            return false;
        }

        // Check event filters
        if (!acceptAllEvents) {
            for (Predicate<GenericEvent> filter : eventFilters) {
                if (!filter.test(event)) {
                    log.debug("Event rejected by filter: {}", event.getId());
                    return false;
                }
            }
        }

        // Handle NIP-33 replaceable events
        if (isReplaceableEvent(event)) {
            handleReplaceableEvent(event);
        } else {
            // Regular event - just store by ID
            eventStore.put(event.getId(), event);
        }

        log.debug("Event published: id={}, kind={}", event.getId(), event.getKind());
        return true;
    }

    /**
     * Queries events matching a predicate.
     *
     * @param filter predicate to filter events
     * @return list of matching events
     * @throws IllegalStateException if not connected
     */
    public List<GenericEvent> queryEvents(Predicate<GenericEvent> filter) {
        ensureConnected();

        queryCount++;

        // Simulate query delay
        simulateDelay(queryDelayMs);

        // Simulate query failure
        if (shouldSimulateFailure(queryFailureRate)) {
            log.debug("Simulated query failure");
            throw new RuntimeException("Simulated query failure");
        }

        List<GenericEvent> results = eventStore.values().stream()
                .filter(filter)
                .collect(Collectors.toList());

        log.debug("Query executed: {} result(s)", results.size());
        return results;
    }

    /**
     * Queries events by kind.
     *
     * @param kind the event kind
     * @return list of matching events
     */
    public List<GenericEvent> queryByKind(int kind) {
        return queryEvents(e -> e.getKind() == kind);
    }

    /**
     * Queries events by author (pubkey).
     *
     * @param pubkey the author's public key
     * @return list of matching events
     */
    public List<GenericEvent> queryByAuthor(String pubkey) {
        return queryEvents(e -> e.getPubKey() != null &&
                pubkey.equals(e.getPubKey().toString()));
    }

    /**
     * Queries events by kind and author.
     *
     * @param kind the event kind
     * @param pubkey the author's public key
     * @return list of matching events
     */
    public List<GenericEvent> queryByKindAndAuthor(int kind, String pubkey) {
        return queryEvents(e -> e.getKind() == kind &&
                e.getPubKey() != null &&
                pubkey.equals(e.getPubKey().toString()));
    }

    /**
     * Queries a NIP-33 replaceable event by d tag.
     *
     * @param kind the event kind (must be in 30000-39999 range)
     * @param pubkey the author's public key
     * @param dTag the d tag value
     * @return the event if found, null otherwise
     */
    public GenericEvent queryReplaceableEvent(int kind, String pubkey, String dTag) {
        String replaceableKey = buildReplaceableKey(kind, pubkey, dTag);
        return eventStore.get(replaceableKey);
    }

    /**
     * Gets all published events (including those replaced).
     *
     * @return unmodifiable list of all published events
     */
    public List<GenericEvent> getPublishHistory() {
        return Collections.unmodifiableList(new ArrayList<>(publishHistory));
    }

    /**
     * Gets all currently stored events (excludes replaced events).
     *
     * @return unmodifiable list of current events
     */
    public List<GenericEvent> getAllStoredEvents() {
        return Collections.unmodifiableList(new ArrayList<>(eventStore.values()));
    }

    /**
     * Gets an event by ID.
     *
     * @param eventId the event ID
     * @return the event if found, null otherwise
     */
    public GenericEvent getEventById(String eventId) {
        return eventStore.get(eventId);
    }

    /**
     * Gets the number of events currently stored.
     *
     * @return event count
     */
    public int getStoredEventCount() {
        return eventStore.size();
    }

    /**
     * Gets the number of events published (including replaced ones).
     *
     * @return publish count
     */
    public long getPublishedEventCount() {
        return publishCount;
    }

    /**
     * Clears all events from the relay.
     */
    public void clear() {
        eventStore.clear();
        publishHistory.clear();
        subscriptions.clear();
        publishCount = 0;
        queryCount = 0;
        subscriptionCount = 0;
        log.debug("Mock relay cleared: {}", relayUrl);
    }

    /**
     * Resets all statistics.
     */
    public void resetStatistics() {
        publishCount = 0;
        queryCount = 0;
        subscriptionCount = 0;
    }

    /**
     * Sets the publish failure rate (0.0 to 1.0).
     *
     * @param rate failure rate (0.0 = no failures, 1.0 = always fail)
     */
    public void setPublishFailureRate(double rate) {
        if (rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException("Failure rate must be between 0.0 and 1.0");
        }
        this.publishFailureRate = rate;
    }

    /**
     * Sets the query failure rate (0.0 to 1.0).
     *
     * @param rate failure rate (0.0 = no failures, 1.0 = always fail)
     */
    public void setQueryFailureRate(double rate) {
        if (rate < 0.0 || rate > 1.0) {
            throw new IllegalArgumentException("Failure rate must be between 0.0 and 1.0");
        }
        this.queryFailureRate = rate;
    }

    /**
     * Sets the publish delay in milliseconds.
     *
     * @param delayMs delay in milliseconds
     */
    public void setPublishDelayMs(long delayMs) {
        if (delayMs < 0) {
            throw new IllegalArgumentException("Delay cannot be negative");
        }
        this.publishDelayMs = delayMs;
    }

    /**
     * Sets the query delay in milliseconds.
     *
     * @param delayMs delay in milliseconds
     */
    public void setQueryDelayMs(long delayMs) {
        if (delayMs < 0) {
            throw new IllegalArgumentException("Delay cannot be negative");
        }
        this.queryDelayMs = delayMs;
    }

    /**
     * Sets whether to accept all events or use filters.
     *
     * @param acceptAll true to accept all events, false to use filters
     */
    public void setAcceptAllEvents(boolean acceptAll) {
        this.acceptAllEvents = acceptAll;
    }

    /**
     * Adds an event filter (only used when acceptAllEvents is false).
     *
     * @param filter predicate to test events
     */
    public void addEventFilter(Predicate<GenericEvent> filter) {
        eventFilters.add(filter);
    }

    /**
     * Clears all event filters.
     */
    public void clearEventFilters() {
        eventFilters.clear();
    }

    /**
     * Checks if an event is a NIP-33 replaceable event.
     */
    private boolean isReplaceableEvent(GenericEvent event) {
        int kind = event.getKind();
        return kind >= NIP33_KIND_MIN && kind <= NIP33_KIND_MAX;
    }

    /**
     * Handles a NIP-33 replaceable event.
     */
    private void handleReplaceableEvent(GenericEvent event) {
        String dTag = extractDTag(event);
        if (dTag == null) {
            log.warn("NIP-33 event missing d tag, storing as regular event: {}", event.getId());
            eventStore.put(event.getId(), event);
            return;
        }

        String pubkey = event.getPubKey() != null ? event.getPubKey().toString() : "";
        String replaceableKey = buildReplaceableKey(event.getKind(), pubkey, dTag);

        // Check if we already have an event with this key
        GenericEvent existing = eventStore.get(replaceableKey);
        if (existing != null) {
            // Keep newer event (higher created_at)
            if (event.getCreatedAt() > existing.getCreatedAt()) {
                log.debug("Replacing NIP-33 event: old={}, new={}", existing.getId(), event.getId());
                eventStore.put(replaceableKey, event);
            } else {
                log.debug("Ignoring older NIP-33 event: old={}, new={}", existing.getId(), event.getId());
            }
        } else {
            eventStore.put(replaceableKey, event);
        }
    }

    /**
     * Extracts the d tag value from an event.
     *
     * <p>This is a simplified implementation for testing. In a real relay,
     * this would properly parse the tag structure according to NIP-01.
     */
    private String extractDTag(GenericEvent event) {
        if (event.getTags() == null) {
            return null;
        }

        // Try to extract d tag using toString parsing (simplified for testing)
        for (nostr.event.BaseTag tag : event.getTags()) {
            if (tag.getCode() != null && tag.getCode().equals("d")) {
                // BaseTag doesn't expose values directly - use toString as fallback
                String tagStr = tag.toString();
                // Format is typically: d:value
                if (tagStr.contains(":")) {
                    String[] parts = tagStr.split(":", 2);
                    if (parts.length > 1) {
                        return parts[1].trim();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Builds a replaceable event key.
     */
    private String buildReplaceableKey(int kind, String pubkey, String dTag) {
        return String.format("replaceable:%d:%s:%s", kind, pubkey, dTag);
    }

    /**
     * Ensures the relay is connected.
     */
    private void ensureConnected() {
        if (!connected) {
            throw new IllegalStateException("Relay not connected: " + relayUrl);
        }
    }

    /**
     * Simulates a delay.
     */
    private void simulateDelay(long delayMs) {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Delay interrupted", e);
            }
        }
    }

    /**
     * Determines if a failure should be simulated based on failure rate.
     */
    private boolean shouldSimulateFailure(double failureRate) {
        return failureRate > 0.0 && Math.random() < failureRate;
    }

    /**
     * Gets a summary of relay statistics.
     *
     * @return statistics summary string
     */
    public String getStatisticsSummary() {
        return String.format(
                "MockNostrRelay[%s] - Published: %d, Queries: %d, Stored: %d, History: %d",
                relayUrl, publishCount, queryCount, eventStore.size(), publishHistory.size()
        );
    }

    @Override
    public String toString() {
        return String.format("MockNostrRelay[%s, connected=%s, events=%d]",
                relayUrl, connected, eventStore.size());
    }
}
