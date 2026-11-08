package xyz.tcheeric.cashu.voucher.nostr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;
import xyz.tcheeric.cashu.voucher.domain.ledger.LedgerEntry;
import xyz.tcheeric.cashu.voucher.domain.ledger.VoucherLedgerRef;
import xyz.tcheeric.cashu.voucher.domain.ledger.VoucherLedgerV2;
import xyz.tcheeric.cashu.voucher.nostr.events.VoucherLedgerEvent;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real EVENT/OK publishing against {@link FakeNip01Relay}: accepts, rejects, silent. */
class NostrClientAdapterPublishTest {

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

    nostr.event.impl.GenericEvent event() {
        VoucherLedgerEvent e = VoucherLedgerEvent.fromVoucher(NostrLegacyReadersRelayTest.voucher(UUID.randomUUID()),
                VoucherStatus.ISSUED);
        return TestEventSigner.sign(e, NostrLegacyReadersRelayTest.ISSUER_SK, 1_700_000_000L);
    }

    @Test
    @DisplayName("relay answers OK=true: published, and the relay stored it")
    void accepted() {
        var e = event();
        assertThat(client.publishEvent(e, 2000)).isTrue();
        assertThat(relay.stored).anyMatch(n -> n.get("id").asText().equals(e.getId()));
        assertThat(client.queryEvents(java.util.Map.of("ids", List.of(e.getId())), 2000)).hasSize(1);
    }

    @Test
    @DisplayName("relay answers OK=false: not published")
    void rejected() {
        relay.rejectEvents = true;
        assertThat(client.publishEvent(event(), 2000)).isFalse();
        assertThat(relay.stored).isEmpty();
    }

    @Test
    @DisplayName("relay never answers OK: not published")
    void silent() {
        relay.silent = true;
        assertThat(client.publishEvent(event(), 300)).isFalse();
    }

    @Test
    @DisplayName("relay unreachable: not published")
    void unreachable() throws Exception {
        relay.stop(1000);
        assertThat(client.publishEvent(event(), 1000)).isFalse();
    }

    @Test
    @DisplayName("repositories turn a rejected publish into an exception")
    void repositoriesThrow() {
        relay.rejectEvents = true;
        var v1 = new NostrVoucherLedgerRepository(client,
                nostr.id.Identity.create(new nostr.base.PrivateKey(NostrLegacyReadersRelayTest.ISSUER_SK)));
        assertThatThrownBy(() -> v1.publish(NostrLegacyReadersRelayTest.voucher(UUID.randomUUID()), VoucherStatus.ISSUED))
                .isInstanceOf(VoucherNostrException.class);
        var backup = new NostrVoucherBackupRepository(client, 1000, 1000);
        assertThatThrownBy(() -> backup.backup(List.of(NostrLegacyReadersRelayTest.voucher(UUID.randomUUID())),
                NostrLegacyReadersRelayTest.USER_SK)).isInstanceOf(VoucherNostrException.class);
        byte[] platformSk = NostrVoucherLedgerV2RelayTest.PLATFORM_SK;
        var v2 = new NostrVoucherLedgerV2Repository(client, List.of(NostrVoucherLedgerV2RelayTest.PLATFORM));
        assertThatThrownBy(() -> v2.publish(new LedgerEntry(VoucherStatus.ISSUED,
                NostrVoucherLedgerV2RelayTest.fields(NostrVoucherLedgerV2RepositoryTest.VOUCHER_ID), null),
                platformSk, platformSk)).isInstanceOf(VoucherNostrException.class);
    }
}
