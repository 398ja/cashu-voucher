package xyz.tcheeric.cashu.voucher.nostr;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainer for a real Nostr relay: strfry, the relay the platform runs.
 *
 * <p>Provides a real Nostr relay instance for integration testing.
 *
 * <p>Not nostr-rs-relay: {@code scsibug/nostr-rs-relay:0.8.9} intermittently panics in its
 * websocket task ({@code quanta} "po2_denom was zero!") right after accepting a connection, so the
 * socket opens but no {@code OK} ever comes back. A publish test then waits out its whole timeout,
 * which made the real-relay publish test fail in roughly every other full-module run.
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

    /** strfry, pinned by digest so a new upstream build cannot change the tests' relay. */
    private static final String DEFAULT_IMAGE =
            "ghcr.io/hoytech/strfry@sha256:c52807349888ef8a9f8720f3e51ee81af7e20114bad105915febb78f9c604cb1";

    /** WebSocket port strfry listens on. */
    private static final int RELAY_PORT = 7777;

    /**
     * The image's own config binds 127.0.0.1, which is unreachable through Docker's port mapping.
     * This is that config with only the bind address changed; everything else is strfry's default.
     */
    private static final String CONFIG = """
            db = "./strfry-db/"
            relay {
                bind = "0.0.0.0"
                port = 7777
                nofiles = 0
            }
            """;

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

        withExposedPorts(RELAY_PORT);
        withCopyToContainer(Transferable.of(CONFIG), "/etc/strfry.conf");
        withCommand("--config=/etc/strfry.conf", "relay");

        // strfry serves its NIP-11 document on the relay port once it accepts connections.
        waitingFor(Wait.forHttp("/")
                .forPort(RELAY_PORT)
                .withHeader("Accept", "application/nostr+json")
                .forStatusCode(200));

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
