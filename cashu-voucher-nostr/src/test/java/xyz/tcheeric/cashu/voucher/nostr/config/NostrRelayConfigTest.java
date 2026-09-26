package xyz.tcheeric.cashu.voucher.nostr.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The relays given to the builder are the relays the config carries (cashu-voucher#44).
 *
 * <p>The hand-written builder methods wrote a field Lombok's {@code @Builder.Default} never read,
 * so {@code build()} always returned the two public default relays and every consumer published
 * there whatever it configured. {@code validate()} and {@code toBuilder()} only ever saw the
 * defaults too.
 */
@DisplayName("NostrRelayConfig relays (#44)")
class NostrRelayConfigTest {

    // The headline of #44: a list given to relayUrls(...) is the list the built config carries.
    @Test
    void relayUrlsSetsTheRelays() {
        NostrRelayConfig config = NostrRelayConfig.builder()
                .relayUrls(List.of("ws://relay.internal:7000"))
                .build();

        assertThat(config.getRelayUrls()).containsExactly("ws://relay.internal:7000");
    }

    // Relays added one at a time replace the defaults rather than being appended to them.
    @Test
    void relayUrlReplacesTheDefaultsAndAccumulates() {
        NostrRelayConfig config = NostrRelayConfig.builder()
                .relayUrl("wss://a.example")
                .relayUrl("wss://b.example")
                .build();

        assertThat(config.getRelayUrls()).containsExactly("wss://a.example", "wss://b.example");
    }

    // With no relays given, the Cashu relays remain the default.
    @Test
    void theDefaultIsTheCashuRelays() {
        assertThat(NostrRelayConfig.builder().build().getRelayUrls())
                .isEqualTo(NostrRelayConfig.CASHU_RELAYS);
        assertThat(NostrRelayConfig.defaultConfig().getRelayUrls())
                .isEqualTo(NostrRelayConfig.CASHU_RELAYS);
    }

    // The preset helpers select their relay sets, which they silently failed to do before.
    @Test
    void thePresetHelpersSelectTheirRelays() {
        assertThat(NostrRelayConfig.builder().useWellKnownRelays().build().getRelayUrls())
                .isEqualTo(NostrRelayConfig.WELL_KNOWN_RELAYS);
        assertThat(NostrRelayConfig.testConfig().getRelayUrls())
                .containsExactly("ws://localhost:7777");
        assertThat(NostrRelayConfig.productionConfig(List.of("wss://p1.example", "wss://p2.example"))
                .getRelayUrls())
                .containsExactly("wss://p1.example", "wss://p2.example");
    }

    // toBuilder() keeps the relays, so a copy with one setting changed is still pointed at them.
    @Test
    void toBuilderKeepsTheRelays() {
        NostrRelayConfig original = NostrRelayConfig.builder()
                .relayUrls(List.of("ws://relay.internal:7000"))
                .build();

        NostrRelayConfig copy = original.toBuilder().maxRetries(9).build();

        assertThat(copy.getRelayUrls()).containsExactly("ws://relay.internal:7000");
        assertThat(copy.getMaxRetries()).isEqualTo(9);
    }

    // validate() now sees the configured list: an explicitly empty one is refused, not replaced by
    // the defaults, and a too-short one fails the minimum-relay rule.
    @Test
    void validateChecksTheConfiguredRelays() {
        NostrRelayConfig empty = NostrRelayConfig.builder().relayUrls(List.of()).build();
        NostrRelayConfig tooFew = NostrRelayConfig.builder()
                .relayUrls(List.of("wss://only.example"))
                .minimumRelays(2)
                .build();

        assertThatThrownBy(empty::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("At least one relay URL");
        assertThatThrownBy(tooFew::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Minimum 2 relay(s) required, but only 1 configured");
    }

    // A built config is immutable: changing the caller's list, or reusing the builder, does not
    // change the relays of a config already built.
    @Test
    void aBuiltConfigDoesNotShareMutableStateWithItsInputs() {
        List<String> callersList = new ArrayList<>(List.of("wss://a.example"));
        NostrRelayConfig.NostrRelayConfigBuilder builder = NostrRelayConfig.builder().relayUrls(callersList);
        NostrRelayConfig first = builder.build();

        callersList.add("wss://evil.example");
        builder.relayUrl("wss://b.example");

        assertThat(first.getRelayUrls()).containsExactly("wss://a.example");
        assertThat(builder.build().getRelayUrls()).containsExactly("wss://a.example", "wss://b.example");
        assertThatThrownBy(() -> first.getRelayUrls().add("wss://evil.example"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // Invalid URLs are still refused where they are given.
    @Test
    void anInvalidRelayUrlIsRefused() {
        assertThatThrownBy(() -> NostrRelayConfig.builder().relayUrls(List.of("https://relay.example")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NostrRelayConfig.builder().relayUrl(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
