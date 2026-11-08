package xyz.tcheeric.cashu.voucher.nostr;

import nostr.base.Signature;
import nostr.event.impl.GenericEvent;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;
import xyz.tcheeric.cashu.voucher.domain.ledger.LedgerEntry;
import xyz.tcheeric.cashu.voucher.domain.ledger.LedgerEvent;
import xyz.tcheeric.cashu.voucher.domain.ledger.VoucherFields;
import xyz.tcheeric.cashu.voucher.domain.ledger.VoucherLedgerRef;
import xyz.tcheeric.cashu.voucher.domain.ledger.VoucherLedgerV2;
import xyz.tcheeric.cashu.voucher.nostr.events.VoucherLedgerV2Event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NostrVoucherLedgerV2RepositoryTest {

    static final byte[] PLATFORM_SK = Hex.decode("a1".repeat(32));
    static final byte[] STALL_SK = Hex.decode("b2".repeat(32));
    static final String VOUCHER_ID = "0b8e3f6a-1c2d-4e5f-8a9b-0c1d2e3f4a5b";

    static String pub(byte[] sk) {
        try {
            return Hex.toHexString(nostr.crypto.schnorr.Schnorr.genPubKey(sk));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final String PLATFORM = pub(PLATFORM_SK);
    static final String STALL = pub(STALL_SK);

    static VoucherFields fields() {
        return new VoucherFields(VOUCHER_ID, STALL, PLATFORM, "sat", 1000L, 2L, 1790000000L, "hi",
                "MINIMAL", "0.5", null, null);
    }

    @Test
    @DisplayName("signed event carries the template, a valid NIP-01 id and sig, and no voucher data in clear")
    void signsTemplate() {
        VoucherLedgerV2.Sealed sealed = VoucherLedgerV2.seal(new LedgerEntry(VoucherStatus.ISSUED, fields(), null),
                PLATFORM_SK, VoucherLedgerRef.ledgerRoot(PLATFORM_SK));
        LedgerEvent template = sealed.template();
        GenericEvent event = VoucherLedgerV2Event.toSignedEvent(sealed, 1_700_000_000L);
        assertThat(NostrEventSignatures.verify(event)).isTrue();
        assertThat(event.getPubKey().toString()).isEqualTo(template.pubkey()).isNotEqualTo(PLATFORM).isNotEqualTo(STALL);
        assertThat(event.getContent()).isEqualTo(template.content()).doesNotContain(VOUCHER_ID);
        LedgerEvent back = VoucherLedgerV2Event.fromVerifiedEvent(event).orElseThrow();
        assertThat(new LedgerEvent(back.pubkey(), back.kind(), back.tags(), back.content())).isEqualTo(template);
        assertThat(back.createdAt()).isEqualTo(event.getCreatedAt());
        assertThat(back.id()).isEqualTo(event.getId());
        assertThat(VoucherLedgerV2.open(back, fields().ref(), List.of(PLATFORM))).isPresent();
    }

    @Test
    @DisplayName("an event whose outer id or sig is broken is dropped before the domain sees it")
    void outerSignatureChecked() {
        GenericEvent event = VoucherLedgerV2Event.toSignedEvent(VoucherLedgerV2.seal(
                new LedgerEntry(VoucherStatus.ISSUED, fields(), null), PLATFORM_SK,
                VoucherLedgerRef.ledgerRoot(PLATFORM_SK)), 1L);
        event.setContent(event.getContent() + "x");
        assertThat(VoucherLedgerV2Event.fromVerifiedEvent(event)).isEmpty();
        assertThat(VoucherLedgerV2Event.fromVerifiedEvent(null)).isEmpty();
        GenericEvent unsigned = new GenericEvent();
        assertThat(VoucherLedgerV2Event.fromVerifiedEvent(unsigned)).isEmpty();
    }

    @Test
    @DisplayName("publish and resolve through the adapter")
    void publishAndResolve() {
        NostrClientAdapter client = mock(NostrClientAdapter.class);
        List<GenericEvent> relay = new ArrayList<>();
        when(client.publishEvent(any(), anyLong())).thenAnswer(inv -> relay.add(inv.getArgument(0)));
        when(client.queryEvents(anyMap(), anyLong())).thenAnswer(inv -> List.copyOf(relay));
        NostrVoucherLedgerV2Repository repo = new NostrVoucherLedgerV2Repository(client, List.of(PLATFORM));

        repo.publish(new LedgerEntry(VoucherStatus.ISSUED, fields(), null), PLATFORM_SK, PLATFORM_SK);
        assertThat(repo.resolve(fields().ref()).orElseThrow().status()).isEqualTo(VoucherStatus.ISSUED);
        repo.publish(new LedgerEntry(VoucherStatus.REDEEMED, fields(), null), STALL_SK, STALL_SK);
        assertThat(repo.query(fields().ref())).hasSize(2);
        assertThat(repo.resolve(fields().ref()).orElseThrow().status()).isEqualTo(VoucherStatus.REDEEMED);

        byte[] other = VoucherLedgerRef.ref(STALL, "7f2c9d10-3b4a-4c5d-9e6f-a1b2c3d4e5f6");
        assertThat(repo.resolve(other)).isEmpty();
    }

    @Test
    void publishFailureThrows() {
        NostrClientAdapter client = mock(NostrClientAdapter.class);
        when(client.publishEvent(any(), anyLong())).thenReturn(false);
        NostrVoucherLedgerV2Repository repo = new NostrVoucherLedgerV2Repository(client, List.of(PLATFORM), 1, 1);
        assertThatThrownBy(() -> repo.publish(new LedgerEntry(VoucherStatus.ISSUED, fields(), null), PLATFORM_SK, PLATFORM_SK))
                .isInstanceOf(VoucherNostrException.class);
    }

    @Test
    void filterUsesLocatorOnly() {
        Map<String, List<String>> f = NostrVoucherLedgerV2Repository.filterFor(fields().ref());
        assertThat(f.get("#d")).containsExactly(VoucherLedgerRef.locator(fields().ref()));
        assertThat(f.get("kinds")).containsExactly("30078");
        assertThat(f.toString()).doesNotContain(VOUCHER_ID);
    }

    @Test
    void ignoresForgedSignatureObject() {
        GenericEvent event = VoucherLedgerV2Event.toSignedEvent(VoucherLedgerV2.seal(
                new LedgerEntry(VoucherStatus.ISSUED, fields(), null), PLATFORM_SK,
                VoucherLedgerRef.ledgerRoot(PLATFORM_SK)), 1L);
        event.setSignature(Signature.fromString("00".repeat(64)));
        assertThat(VoucherLedgerV2Event.fromVerifiedEvent(event)).isEmpty();
    }
}
