package xyz.tcheeric.cashu.voucher.nostr;

import nostr.event.impl.GenericEvent;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for NostrClientAdapter using a real Nostr relay.
 *
 * <p>These tests use Testcontainers to spin up a nostr-rs-relay instance.
 * They verify real connectivity, event publishing, and querying.
 *
 * <p><b>Note:</b> These tests require Docker to be running.
 */
@Testcontainers
@Tag("integration")
@DisplayName("NostrClientAdapter Integration Tests")
class NostrClientAdapterIntegrationTest {

    @Container
    static NostrRelayContainer relay = new NostrRelayContainer();

    private NostrClientAdapter adapter;

    @BeforeEach
    void setUp() {
        String relayUrl = relay.getWebSocketUrl();
        adapter = new NostrClientAdapter(List.of(relayUrl), 5000L, 3);
    }

    @AfterEach
    void tearDown() {
        if (adapter != null && adapter.isConnected()) {
            adapter.disconnect();
        }
    }

    @Test
    @DisplayName("Should connect to real relay")
    void shouldConnectToRealRelay() {
        assertDoesNotThrow(() -> adapter.connect());
        assertTrue(adapter.isConnected());
        assertEquals(1, adapter.getConnectedRelayCount());
    }

    @Test
    @DisplayName("Should publish event to real relay")
    void shouldPublishEventToRealRelay() {
        adapter.connect();

        GenericEvent event = new GenericEvent();
        event.setKind(1);
        event.setContent("Integration test event");

        boolean result = adapter.publishEvent(event, 5000L);
        assertTrue(result, "Event should be published successfully");
    }

    @Test
    @DisplayName("Should reconnect after disconnect")
    void shouldReconnectAfterDisconnect() {
        adapter.connect();
        assertTrue(adapter.isConnected());

        adapter.disconnect();
        assertFalse(adapter.isConnected());

        adapter.connect();
        assertTrue(adapter.isConnected());
    }

    @Test
    @DisplayName("Should handle connection to unavailable relay")
    void shouldHandleUnavailableRelay() {
        NostrClientAdapter badAdapter = new NostrClientAdapter(
                List.of("ws://localhost:9999"),
                1000L,
                1
        );

        assertDoesNotThrow(() -> badAdapter.connect());
        // Connection may fail but should not throw
    }

    @Test
    @DisplayName("Should return relay URL")
    void shouldReturnRelayUrl() {
        adapter.connect();

        List<String> connectedRelays = adapter.getConnectedRelays();
        assertNotNull(connectedRelays);
        assertFalse(connectedRelays.isEmpty());

        String expectedUrl = relay.getWebSocketUrl();
        assertTrue(connectedRelays.contains(expectedUrl),
                "Should contain relay URL: " + expectedUrl);
    }
}
