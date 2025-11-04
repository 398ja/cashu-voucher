package xyz.tcheeric.cashu.voucher.nostr.config;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for Nostr relay connections.
 *
 * <p>This class encapsulates all configuration needed to connect to and manage
 * Nostr relays, including URLs, timeouts, retry policies, and health check settings.
 *
 * <h3>Design Philosophy</h3>
 * <p>This is an immutable configuration object that follows the builder pattern
 * for flexible construction. It provides sensible defaults while allowing full
 * customization for production deployments.
 *
 * <h3>Configuration Categories</h3>
 * <ul>
 *   <li><b>Relay URLs</b>: List of WebSocket relay endpoints</li>
 *   <li><b>Connection Settings</b>: Timeouts, retry attempts, backoff strategy</li>
 *   <li><b>Operation Settings</b>: Publish/query timeouts, batch sizes</li>
 *   <li><b>Health Checks</b>: Ping intervals, failure thresholds</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * <pre>
 * // Default configuration with custom relays
 * NostrRelayConfig config = NostrRelayConfig.builder()
 *     .relayUrl("wss://relay.damus.io")
 *     .relayUrl("wss://relay.cashu.xyz")
 *     .build();
 *
 * // Production configuration with custom timeouts
 * NostrRelayConfig config = NostrRelayConfig.builder()
 *     .relayUrl("wss://relay1.example.com")
 *     .relayUrl("wss://relay2.example.com")
 *     .connectionTimeoutMs(10000L)
 *     .publishTimeoutMs(8000L)
 *     .queryTimeoutMs(15000L)
 *     .maxRetries(5)
 *     .build();
 *
 * // Use with NostrClientAdapter
 * NostrClientAdapter adapter = new NostrClientAdapter(
 *     config.getRelayUrls(),
 *     config.getConnectionTimeoutMs(),
 *     config.getMaxRetries()
 * );
 * </pre>
 *
 * <h3>Default Values</h3>
 * <ul>
 *   <li>Connection timeout: 5000ms (5 seconds)</li>
 *   <li>Publish timeout: 5000ms (5 seconds)</li>
 *   <li>Query timeout: 10000ms (10 seconds)</li>
 *   <li>Max retries: 3</li>
 *   <li>Retry backoff: Exponential (1s, 2s, 4s)</li>
 *   <li>Health check interval: 60000ms (1 minute)</li>
 *   <li>Max consecutive failures: 3</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is immutable and thread-safe. All collections are defensively
 * copied and returned as unmodifiable views.
 *
 * @see xyz.tcheeric.cashu.voucher.nostr.NostrClientAdapter
 */
@Getter
@ToString
@Builder(toBuilder = true)
public class NostrRelayConfig {

    /**
     * Default connection timeout in milliseconds.
     */
    public static final long DEFAULT_CONNECTION_TIMEOUT_MS = 5000L;

    /**
     * Default publish timeout in milliseconds.
     */
    public static final long DEFAULT_PUBLISH_TIMEOUT_MS = 5000L;

    /**
     * Default query timeout in milliseconds.
     */
    public static final long DEFAULT_QUERY_TIMEOUT_MS = 10000L;

    /**
     * Default maximum retry attempts.
     */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /**
     * Default health check interval in milliseconds.
     */
    public static final long DEFAULT_HEALTH_CHECK_INTERVAL_MS = 60000L;

    /**
     * Default maximum consecutive failures before marking relay as unhealthy.
     */
    public static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 3;

    /**
     * Default batch size for bulk operations.
     */
    public static final int DEFAULT_BATCH_SIZE = 100;

    /**
     * Well-known public Nostr relays (for convenience).
     */
    public static final List<String> WELL_KNOWN_RELAYS = List.of(
            "wss://relay.damus.io",
            "wss://relay.snort.social",
            "wss://nos.lol",
            "wss://relay.nostr.band",
            "wss://nostr.wine"
    );

    /**
     * Cashu-specific relay suggestions.
     */
    public static final List<String> CASHU_RELAYS = List.of(
            "wss://relay.damus.io",
            "wss://relay.cashu.xyz"
    );

    // Relay URLs
    @Builder.Default
    private final List<String> relayUrls = new ArrayList<>(CASHU_RELAYS);

    // Connection settings
    @Builder.Default
    private final long connectionTimeoutMs = DEFAULT_CONNECTION_TIMEOUT_MS;

    @Builder.Default
    private final int maxRetries = DEFAULT_MAX_RETRIES;

    @Builder.Default
    private final boolean exponentialBackoff = true;

    // Operation settings
    @Builder.Default
    private final long publishTimeoutMs = DEFAULT_PUBLISH_TIMEOUT_MS;

    @Builder.Default
    private final long queryTimeoutMs = DEFAULT_QUERY_TIMEOUT_MS;

    @Builder.Default
    private final int batchSize = DEFAULT_BATCH_SIZE;

    // Health check settings
    @Builder.Default
    private final boolean healthCheckEnabled = true;

    @Builder.Default
    private final long healthCheckIntervalMs = DEFAULT_HEALTH_CHECK_INTERVAL_MS;

    @Builder.Default
    private final int maxConsecutiveFailures = DEFAULT_MAX_CONSECUTIVE_FAILURES;

    // Advanced settings
    @Builder.Default
    private final boolean autoReconnect = true;

    @Builder.Default
    private final boolean requireMinimumRelays = true;

    @Builder.Default
    private final int minimumRelays = 1;

    /**
     * Custom builder to support adding relay URLs one at a time.
     */
    public static class NostrRelayConfigBuilder {
        private List<String> relayUrls = new ArrayList<>();

        /**
         * Adds a single relay URL to the configuration.
         *
         * @param relayUrl the WebSocket URL of the relay (must start with wss:// or ws://)
         * @return this builder for chaining
         * @throws IllegalArgumentException if URL is invalid
         */
        public NostrRelayConfigBuilder relayUrl(String relayUrl) {
            validateRelayUrl(relayUrl);
            if (this.relayUrls == null) {
                this.relayUrls = new ArrayList<>();
            }
            this.relayUrls.add(relayUrl);
            return this;
        }

        /**
         * Sets multiple relay URLs at once.
         *
         * @param relayUrls list of relay URLs
         * @return this builder for chaining
         * @throws IllegalArgumentException if any URL is invalid
         */
        public NostrRelayConfigBuilder relayUrls(List<String> relayUrls) {
            if (relayUrls != null) {
                relayUrls.forEach(NostrRelayConfigBuilder::validateRelayUrl);
                this.relayUrls = new ArrayList<>(relayUrls);
            }
            return this;
        }

        /**
         * Uses well-known public relays.
         *
         * @return this builder for chaining
         */
        public NostrRelayConfigBuilder useWellKnownRelays() {
            this.relayUrls = new ArrayList<>(WELL_KNOWN_RELAYS);
            return this;
        }

        /**
         * Uses Cashu-specific relays.
         *
         * @return this builder for chaining
         */
        public NostrRelayConfigBuilder useCashuRelays() {
            this.relayUrls = new ArrayList<>(CASHU_RELAYS);
            return this;
        }

        /**
         * Validates a relay URL format.
         */
        private static void validateRelayUrl(String url) {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("Relay URL cannot be null or blank");
            }

            try {
                URI uri = new URI(url);
                String scheme = uri.getScheme();
                if (!"wss".equals(scheme) && !"ws".equals(scheme)) {
                    throw new IllegalArgumentException(
                            "Relay URL must use wss:// or ws:// scheme: " + url);
                }
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid relay URL: " + url, e);
            }
        }
    }

    /**
     * Gets an unmodifiable copy of the relay URLs.
     *
     * @return unmodifiable list of relay URLs
     */
    public List<String> getRelayUrls() {
        return Collections.unmodifiableList(new ArrayList<>(relayUrls));
    }

    /**
     * Gets the number of configured relays.
     *
     * @return relay count
     */
    public int getRelayCount() {
        return relayUrls.size();
    }

    /**
     * Checks if the configuration has at least the minimum required relays.
     *
     * @return true if minimum relay requirement is met
     */
    public boolean hasMinimumRelays() {
        return relayUrls.size() >= minimumRelays;
    }

    /**
     * Validates the configuration.
     *
     * @throws IllegalStateException if configuration is invalid
     */
    public void validate() {
        if (relayUrls.isEmpty()) {
            throw new IllegalStateException("At least one relay URL must be configured");
        }

        if (requireMinimumRelays && !hasMinimumRelays()) {
            throw new IllegalStateException(
                    String.format("Minimum %d relay(s) required, but only %d configured",
                            minimumRelays, relayUrls.size()));
        }

        if (connectionTimeoutMs <= 0) {
            throw new IllegalStateException("Connection timeout must be positive");
        }

        if (publishTimeoutMs <= 0) {
            throw new IllegalStateException("Publish timeout must be positive");
        }

        if (queryTimeoutMs <= 0) {
            throw new IllegalStateException("Query timeout must be positive");
        }

        if (maxRetries < 0) {
            throw new IllegalStateException("Max retries cannot be negative");
        }

        if (healthCheckEnabled && healthCheckIntervalMs <= 0) {
            throw new IllegalStateException("Health check interval must be positive");
        }

        if (maxConsecutiveFailures <= 0) {
            throw new IllegalStateException("Max consecutive failures must be positive");
        }

        if (batchSize <= 0) {
            throw new IllegalStateException("Batch size must be positive");
        }

        if (minimumRelays < 1) {
            throw new IllegalStateException("Minimum relays must be at least 1");
        }
    }

    /**
     * Creates a default configuration with Cashu relays.
     *
     * @return default NostrRelayConfig
     */
    public static NostrRelayConfig defaultConfig() {
        return NostrRelayConfig.builder().build();
    }

    /**
     * Creates a configuration for testing with localhost relay.
     *
     * @return test NostrRelayConfig
     */
    public static NostrRelayConfig testConfig() {
        return NostrRelayConfig.builder()
                .relayUrls(List.of("ws://localhost:7777"))
                .connectionTimeoutMs(1000L)
                .publishTimeoutMs(1000L)
                .queryTimeoutMs(2000L)
                .maxRetries(1)
                .healthCheckEnabled(false)
                .requireMinimumRelays(false)
                .build();
    }

    /**
     * Creates a configuration for production with custom relays.
     *
     * @param relayUrls list of production relay URLs
     * @return production NostrRelayConfig
     */
    public static NostrRelayConfig productionConfig(@NonNull List<String> relayUrls) {
        return NostrRelayConfig.builder()
                .relayUrls(relayUrls)
                .connectionTimeoutMs(10000L)
                .publishTimeoutMs(8000L)
                .queryTimeoutMs(15000L)
                .maxRetries(5)
                .healthCheckEnabled(true)
                .healthCheckIntervalMs(30000L)
                .requireMinimumRelays(true)
                .minimumRelays(2)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NostrRelayConfig that = (NostrRelayConfig) o;
        return connectionTimeoutMs == that.connectionTimeoutMs &&
                maxRetries == that.maxRetries &&
                exponentialBackoff == that.exponentialBackoff &&
                publishTimeoutMs == that.publishTimeoutMs &&
                queryTimeoutMs == that.queryTimeoutMs &&
                batchSize == that.batchSize &&
                healthCheckEnabled == that.healthCheckEnabled &&
                healthCheckIntervalMs == that.healthCheckIntervalMs &&
                maxConsecutiveFailures == that.maxConsecutiveFailures &&
                autoReconnect == that.autoReconnect &&
                requireMinimumRelays == that.requireMinimumRelays &&
                minimumRelays == that.minimumRelays &&
                Objects.equals(relayUrls, that.relayUrls);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relayUrls, connectionTimeoutMs, maxRetries, exponentialBackoff,
                publishTimeoutMs, queryTimeoutMs, batchSize, healthCheckEnabled,
                healthCheckIntervalMs, maxConsecutiveFailures, autoReconnect,
                requireMinimumRelays, minimumRelays);
    }
}
