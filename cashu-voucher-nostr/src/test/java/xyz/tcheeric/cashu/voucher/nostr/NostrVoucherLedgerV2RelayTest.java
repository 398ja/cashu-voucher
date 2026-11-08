package xyz.tcheeric.cashu.voucher.nostr;

import com.fasterxml.jackson.databind.JsonNode;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;
import xyz.tcheeric.cashu.voucher.domain.ledger.LedgerEntry;
import xyz.tcheeric.cashu.voucher.domain.ledger.VoucherFields;
import xyz.tcheeric.cashu.voucher.domain.ledger.VoucherLedgerRef;
import xyz.tcheeric.cashu.voucher.domain.ledger.VoucherLedgerV2;
import xyz.tcheeric.cashu.voucher.nostr.events.VoucherLedgerV2Event;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ledger v2 reads over a real WebSocket against an in-process relay that applies the filter. */
class NostrVoucherLedgerV2RelayTest {

    static final byte[] PLATFORM_SK = Hex.decode("a1".repeat(32));
    static final byte[] STALL_SK = Hex.decode("b2".repeat(32));
    static final String PLATFORM = NostrVoucherLedgerV2RepositoryTest.PLATFORM;
    static final String STALL = NostrVoucherLedgerV2RepositoryTest.STALL;
    static final String OTHER_ID = "7f2c9d10-3b4a-4c5d-9e6f-a1b2c3d4e5f6";

    FakeNip01Relay relay;
    NostrClientAdapter client;

    @BeforeEach
    void up() throws Exception {
        relay = new FakeNip01Relay().begin();
        client = new NostrClientAdapter(List.of(relay.url()), 2000, 0);
        client.connect();
    }

    @AfterEach
    void down() throws Exception {
        client.disconnect();
        relay.stop(1000);
    }

    static VoucherFields fields(String id) {
        return new VoucherFields(id, STALL, PLATFORM, "sat", 1000L, 2L, 1790000000L, "hi", "MINIMAL", "0.5", null, null);
    }

    void put(VoucherStatus status, VoucherFields f, byte[] signer) {
        relay.store(VoucherLedgerV2Event.toSignedEvent(
                VoucherLedgerV2.seal(new LedgerEntry(status, f, null), signer, VoucherLedgerRef.ledgerRoot(signer)),
                1_700_000_000L));
    }

    NostrVoucherLedgerV2Repository repo(long timeoutMs) {
        return new NostrVoucherLedgerV2Repository(client, List.of(PLATFORM), 1000, timeoutMs);
    }

    @Test
    @DisplayName("resolve sends the locator filter without limit and gets only matching events")
    void filteredQuery() {
        VoucherFields mine = fields(NostrVoucherLedgerV2RepositoryTest.VOUCHER_ID);
        put(VoucherStatus.ISSUED, mine, PLATFORM_SK);
        put(VoucherStatus.REDEEMED, mine, STALL_SK);
        put(VoucherStatus.ISSUED, fields(OTHER_ID), PLATFORM_SK);

        var resolved = repo(2000).resolve(mine.ref()).orElseThrow();
        assertThat(resolved.status()).isEqualTo(VoucherStatus.REDEEMED);
        assertThat(resolved.accepted()).hasSize(2);

        List<?> raw = client.queryEvents(NostrVoucherLedgerV2Repository.filterFor(mine.ref()), 2000);
        assertThat(raw).hasSize(2);

        JsonNode req = relay.received.stream().filter(m -> "REQ".equals(m.get(0).asText())).findFirst().orElseThrow();
        JsonNode filter = req.get(2);
        assertThat(filter.has("limit")).isFalse();
        assertThat(filter.get("kinds").get(0).isInt()).isTrue();
        assertThat(filter.get("kinds").get(0).asInt()).isEqualTo(30078);
        assertThat(filter.get("#d").get(0).asText()).isEqualTo(VoucherLedgerRef.locator(mine.ref()));
        assertThat(relay.received.stream().anyMatch(m -> "CLOSE".equals(m.get(0).asText()))).isTrue();
    }

    void putAt(VoucherStatus status, VoucherFields f, byte[] signer, long createdAt) {
        VoucherLedgerV2.Sealed sealed =
                VoucherLedgerV2.seal(new LedgerEntry(status, f, null), signer, VoucherLedgerRef.ledgerRoot(signer));
        String sk = Hex.toHexString(sealed.eventSecretKey());
        // Re-sign with the per-voucher event key at the wanted created_at.
        relay.store(TestEventSigner.sign(VoucherLedgerV2Event.toSignedEvent(sealed, createdAt), sk, createdAt));
    }

    @Test
    @DisplayName("N1: relay caps at N newest; N+1 newer junk events cannot hide a REVOKED")
    void pagesPastRelayCap() {
        int n = 20;
        relay.cap = n;
        VoucherFields mine = fields(NostrVoucherLedgerV2RepositoryTest.VOUCHER_ID);
        putAt(VoucherStatus.ISSUED, mine, PLATFORM_SK, 1_700_000_000L);
        putAt(VoucherStatus.REVOKED, mine, STALL_SK, 1_700_000_001L);
        for (int i = 0; i < n + 1; i++) {
            // Newer genuine-looking ISSUED re-publishes: without paging only these are seen.
            putAt(VoucherStatus.ISSUED, mine, PLATFORM_SK, 1_700_000_100L + i);
        }
        var resolved = repo(5000).resolve(mine.ref()).orElseThrow();
        assertThat(resolved.status()).isEqualTo(VoucherStatus.REVOKED);
        assertThat(relay.reqs.get()).isGreaterThan(1);
        for (JsonNode m : relay.received) {
            if ("REQ".equals(m.get(0).asText())) {
                assertThat(m.get(2).has("limit")).isFalse();
            }
        }
    }

    @Test
    @DisplayName("N1: a full capped page inside one second cannot be paged past: refused")
    void sameSecondPageRefused() {
        relay.cap = NostrClientAdapter.SAME_SECOND_PAGE_LIMIT;
        VoucherFields mine = fields(NostrVoucherLedgerV2RepositoryTest.VOUCHER_ID);
        putAt(VoucherStatus.REVOKED, mine, STALL_SK, 1_700_000_000L);
        for (int i = 0; i < NostrClientAdapter.SAME_SECOND_PAGE_LIMIT + 1; i++) {
            putAt(VoucherStatus.ISSUED, mine, PLATFORM_SK, 1_700_000_500L);
        }
        assertThatThrownBy(() -> repo(5000).resolve(mine.ref())).isInstanceOf(VoucherNostrException.class);
    }

    @Test
    @DisplayName("every relay answered and nothing matched: empty")
    void emptyAfterEose() {
        put(VoucherStatus.ISSUED, fields(OTHER_ID), PLATFORM_SK);
        assertThat(repo(2000).resolve(fields(NostrVoucherLedgerV2RepositoryTest.VOUCHER_ID).ref())).isEmpty();
    }

    @Test
    @DisplayName("no EOSE before the timeout: resolve throws, never answers empty")
    void timeoutFailsClosed() {
        put(VoucherStatus.REVOKED, fields(NostrVoucherLedgerV2RepositoryTest.VOUCHER_ID), STALL_SK);
        relay.silent = true;
        assertThatThrownBy(() -> repo(300).resolve(fields(NostrVoucherLedgerV2RepositoryTest.VOUCHER_ID).ref()))
                .isInstanceOf(VoucherNostrException.class);
    }

    @Test
    @DisplayName("relay unreachable: resolve throws")
    void unreachableFailsClosed() throws Exception {
        relay.stop(1000);
        assertThatThrownBy(() -> repo(1000).resolve(fields(NostrVoucherLedgerV2RepositoryTest.VOUCHER_ID).ref()))
                .isInstanceOf(VoucherNostrException.class);
    }

    @Test
    @DisplayName("not connected: resolve throws")
    void notConnectedFailsClosed() {
        client.disconnect();
        assertThatThrownBy(() -> repo(1000).resolve(fields(NostrVoucherLedgerV2RepositoryTest.VOUCHER_ID).ref()))
                .isInstanceOf(VoucherNostrException.class);
    }

    @Test
    @DisplayName("a filter with limit is refused")
    void limitRefused() {
        assertThatThrownBy(() -> client.queryEvents(Map.of("limit", List.of("1")), 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
