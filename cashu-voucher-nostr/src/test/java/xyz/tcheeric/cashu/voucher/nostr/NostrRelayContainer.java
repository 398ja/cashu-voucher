package xyz.tcheeric.cashu.voucher.nostr;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainer for nostr-rs-relay.
 *
 * <p>Provides a real Nostr relay instance for integration testing.
 * Uses the scsibug/nostr-rs-relay Docker image.
 *
 * <h3>Usage Example</h3>
 * <pre>
 * &#64;Testcontainers
 * class MyTest {
 *     &#64;Container
 *     static NostrRelayContainer relay = new NostrRelayContainer();
 *
 *     &#64;Test
 *     void test() {
 *         String relayUrl = relay.getWebSocketUrl();
 *         // Use relayUrl to connect...
 *     }
 * }
 * </pre>
 */
public class NostrRelayContainer extends GenericContainer<NostrRelayContainer> {

    /**
     * Default nostr-rs-relay Docker image.
     */
    private static final String DEFAULT_IMAGE = "scsibug/nostr-rs-relay:0.8.9";

    /**
     * Default WebSocket port for Nostr relay.
     */
    private static final int RELAY_PORT = 8080;

    /**
     * Creates a new Nostr relay container with the default image.
     */
    public NostrRelayContainer() {
        this(DEFAULT_IMAGE);
    }

    /**
     * Creates a new Nostr relay container with a specific image.
     *
     * @param dockerImageName the Docker image to use
     */
    public NostrRelayContainer(String dockerImageName) {
        super(DockerImageName.parse(dockerImageName));

        // Expose the relay WebSocket port
        withExposedPorts(RELAY_PORT);

        // Wait for the relay to be ready
        // nostr-rs-relay doesn't have a health endpoint, so wait for port
        waitingFor(Wait.forListeningPort());

        // Set resource limits
        withCreateContainerCmdModifier(cmd -> {
            cmd.getHostConfig()
                .withMemory(256 * 1024 * 1024L)  // 256 MB
                .withMemorySwap(512 * 1024 * 1024L);  // 512 MB swap
        });
    }

    /**
     * Gets the WebSocket URL for connecting to the relay.
     *
     * @return WebSocket URL (ws://host:port)
     */
    public String getWebSocketUrl() {
        return String.format("ws://%s:%d", getHost(), getMappedPort(RELAY_PORT));
    }

    /**
     * Gets the relay port (mapped to host).
     *
     * @return the mapped port number
     */
    public Integer getRelayPort() {
        return getMappedPort(RELAY_PORT);
    }
}
