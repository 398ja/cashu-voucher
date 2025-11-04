package xyz.tcheeric.cashu.voucher.nostr;

import nostr.event.impl.GenericEvent;
import org.junit.jupiter.api.*;
import xyz.tcheeric.cashu.voucher.nostr.config.NostrRelayConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NostrClientAdapter.
 *
 * <p>These tests verify the basic functionality of the Nostr client adapter,
 * including connection management, event publishing, and querying.
 */
class NostrClientAdapterTest {

    private NostrClientAdapter adapter;
    private NostrRelayConfig config;

    @BeforeEach
    void setUp() {
        config = NostrRelayConfig.testConfig();
        adapter = new NostrClientAdapter(
                config.getRelayUrls(),
                config.getConnectionTimeoutMs(),
                config.getMaxRetries()
        );
    }

    @AfterEach
    void tearDown() {
        if (adapter != null && adapter.isConnected()) {
            adapter.disconnect();
        }
    }

    @Test
    @DisplayName("Should initialize with valid configuration")
    void testInitialization() {
        assertNotNull(adapter);
        assertFalse(adapter.isConnected());
        assertEquals(0, adapter.getConnectedRelayCount());
    }

    @Test
    @DisplayName("Should connect to relays (simulated)")
    void testConnect() {
        // Note: This uses simulated connections in current implementation
        assertDoesNotThrow(() -> adapter.connect());
        assertTrue(adapter.isConnected());
        // May connect to multiple relays depending on implementation
        assertTrue(adapter.getConnectedRelayCount() > 0);
    }

    @Test
    @DisplayName("Should disconnect from relays")
    void testDisconnect() {
        adapter.connect();
        assertTrue(adapter.isConnected());

        adapter.disconnect();
        assertFalse(adapter.isConnected());
        assertEquals(0, adapter.getConnectedRelayCount());
    }

    @Test
    @DisplayName("Should handle multiple connect calls")
    void testMultipleConnects() {
        adapter.connect();
        assertTrue(adapter.isConnected());

        // Second connect should be idempotent
        assertDoesNotThrow(() -> adapter.connect());
        assertTrue(adapter.isConnected());
    }

    @Test
    @DisplayName("Should handle multiple disconnect calls")
    void testMultipleDisconnects() {
        adapter.connect();
        adapter.disconnect();
        assertFalse(adapter.isConnected());

        // Second disconnect should be idempotent
        assertDoesNotThrow(() -> adapter.disconnect());
        assertFalse(adapter.isConnected());
    }

    @Test
    @DisplayName("Should publish event when connected")
    void testPublishEvent() {
        adapter.connect();

        GenericEvent event = new GenericEvent();
        event.setKind(1);
        event.setContent("Test note");

        boolean result = adapter.publishEvent(event, 1000L);
        // Current implementation simulates success
        assertTrue(result);
    }

    @Test
    @DisplayName("Should fail to publish when not connected")
    void testPublishEventNotConnected() {
        GenericEvent event = new GenericEvent();
        event.setKind(1);
        event.setContent("Test note");

        assertThrows(VoucherNostrException.class, () -> {
            adapter.publishEvent(event, 1000L);
        });
    }

    @Test
    @DisplayName("Should query events when connected")
    void testQueryEvents() {
        adapter.connect();

        String subscriptionId = "test-sub-1";
        List<GenericEvent> events = adapter.queryEvents(subscriptionId, 1000L);

        assertNotNull(events);
        // Current implementation returns empty list
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("Should fail to query when not connected")
    void testQueryEventsNotConnected() {
        String subscriptionId = "test-sub-1";

        assertThrows(VoucherNostrException.class, () -> {
            adapter.queryEvents(subscriptionId, 1000L);
        });
    }

    @Test
    @DisplayName("Should track connected relay count")
    void testConnectedRelayCount() {
        assertEquals(0, adapter.getConnectedRelayCount());

        adapter.connect();
        assertTrue(adapter.getConnectedRelayCount() > 0);

        adapter.disconnect();
        assertEquals(0, adapter.getConnectedRelayCount());
    }

    @Test
    @DisplayName("Should return connected relay URLs")
    void testGetConnectedRelays() {
        adapter.connect();

        List<String> connectedRelays = adapter.getConnectedRelays();
        assertNotNull(connectedRelays);
        assertFalse(connectedRelays.isEmpty());
        // Should have at least the configured relay
        assertTrue(connectedRelays.size() > 0);
        // Should contain the test relay URL
        assertTrue(connectedRelays.stream().anyMatch(url -> url.contains("localhost:7777")));
    }

    @Test
    @DisplayName("Should handle null event gracefully")
    void testPublishNullEvent() {
        adapter.connect();

        assertThrows(NullPointerException.class, () -> {
            adapter.publishEvent(null, 1000L);
        });
    }

    @Test
    @DisplayName("Should handle null subscription ID gracefully")
    void testQueryWithNullSubscriptionId() {
        adapter.connect();

        assertThrows(NullPointerException.class, () -> {
            adapter.queryEvents(null, 1000L);
        });
    }

    @Test
    @DisplayName("Should initialize with multiple relay URLs")
    void testMultipleRelays() {
        List<String> relays = List.of(
                "ws://relay1.test",
                "ws://relay2.test",
                "ws://relay3.test"
        );

        NostrClientAdapter multiAdapter = new NostrClientAdapter(relays, 5000L, 3);
        assertNotNull(multiAdapter);
        assertEquals(0, multiAdapter.getConnectedRelayCount());

        multiAdapter.connect();
        assertTrue(multiAdapter.isConnected());
        // Should connect to at least one relay
        assertTrue(multiAdapter.getConnectedRelayCount() > 0);

        multiAdapter.disconnect();
    }

    @Test
    @DisplayName("Should reject empty relay list")
    void testEmptyRelayList() {
        assertThrows(IllegalArgumentException.class, () -> {
            new NostrClientAdapter(List.of(), 5000L, 3);
        });
    }

    @Test
    @DisplayName("Should reject invalid timeout")
    void testInvalidTimeout() {
        assertThrows(IllegalArgumentException.class, () -> {
            new NostrClientAdapter(List.of("ws://test"), 0L, 3);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new NostrClientAdapter(List.of("ws://test"), -1000L, 3);
        });
    }

    @Test
    @DisplayName("Should reject invalid max retries")
    void testInvalidMaxRetries() {
        assertThrows(IllegalArgumentException.class, () -> {
            new NostrClientAdapter(List.of("ws://test"), 5000L, -1);
        });
    }
}
