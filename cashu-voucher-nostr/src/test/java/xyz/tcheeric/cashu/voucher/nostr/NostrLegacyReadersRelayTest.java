package xyz.tcheeric.cashu.voucher.nostr;

import com.fasterxml.jackson.databind.JsonNode;
import nostr.base.PrivateKey;
import nostr.event.impl.GenericEvent;
import nostr.id.Identity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;
import xyz.tcheeric.cashu.voucher.nostr.events.VoucherBackupPayload;
import xyz.tcheeric.cashu.voucher.nostr.events.VoucherLedgerEvent;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1 ledger and backup reads over a real WebSocket against {@link FakeNip01Relay}, which applies
 * the NIP-01 filter. Covers found, not found, wrong author ignored, and timeout fails closed.
 */
class NostrLegacyReadersRelayTest {

    static final String ISSUER_SK = "c3".repeat(32);
    static final String OTHER_SK = "d4".repeat(32);
    static final String USER_SK = "e5".repeat(32);

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

    static Identity id(String sk) {
        return Identity.create(new PrivateKey(sk));
    }

    static SignedVoucher voucher(UUID voucherId) {
        Identity issuer = id(ISSUER_SK);
        VoucherSecret secret = VoucherSecret.builder()
                .voucherId(voucherId).issuerId("test-merchant").unit("sat").faceValue(5000L)
                .memo("m").backingStrategy(BackingStrategy.FIXED.name()).issuanceRatio(1.0).faceDecimals(0)
                .build();
        return VoucherSignatureService.createSigned(secret, ISSUER_SK, issuer.getPublicKey().toString());
    }

    void putLedger(SignedVoucher v, VoucherStatus status, String signerSk, long createdAt) {
        VoucherLedgerEvent e = VoucherLedgerEvent.fromVoucher(v, status);
        relay.store(TestEventSigner.sign(e, signerSk, createdAt));
    }

    JsonNode lastReqFilter() {
        return relay.received.stream().filter(m -> "REQ".equals(m.get(0).asText()))
                .reduce((a, b) -> b).orElseThrow().get(2);
    }

    @Nested
    @DisplayName("NostrVoucherLedgerRepository (v1)")
    class Ledger {

        NostrVoucherLedgerRepository repo(long timeoutMs) {
            return new NostrVoucherLedgerRepository(client, id(ISSUER_SK).getPublicKey(), 1000, timeoutMs);
        }

        @Test
        @DisplayName("found: latest issuer event wins, filter is kind/authors/#d without limit")
        void found() {
            UUID vid = UUID.randomUUID();
            SignedVoucher v = voucher(vid);
            putLedger(v, VoucherStatus.ISSUED, ISSUER_SK, 1_700_000_000L);
            putLedger(v, VoucherStatus.REDEEMED, ISSUER_SK, 1_700_000_100L);
            putLedger(voucher(UUID.randomUUID()), VoucherStatus.ISSUED, ISSUER_SK, 1_700_000_200L);

            assertThat(repo(2000).queryStatus(vid.toString())).contains(VoucherStatus.REDEEMED);
            JsonNode f = lastReqFilter();
            assertThat(f.has("limit")).isFalse();
            assertThat(f.get("kinds").get(0).asInt()).isEqualTo(30078);
            assertThat(f.get("authors").get(0).asText()).isEqualTo(id(ISSUER_SK).getPublicKey().toString());
            assertThat(f.get("#d").get(0).asText()).isEqualTo("voucher:" + vid);

            assertThat(repo(2000).queryVoucher(vid.toString())).hasValueSatisfying(
                    sv -> assertThat(sv.getSecret().getVoucherId()).isEqualTo(vid));
        }

        @Test
        @DisplayName("not found: every relay reached EOSE with nothing")
        void notFound() {
            putLedger(voucher(UUID.randomUUID()), VoucherStatus.ISSUED, ISSUER_SK, 1_700_000_000L);
            assertThat(repo(2000).queryStatus(UUID.randomUUID().toString())).isEmpty();
            assertThat(repo(2000).queryVoucher(UUID.randomUUID().toString())).isEmpty();
        }

        @Test
        @DisplayName("wrong author is ignored even if the relay ignores the authors filter")
        void wrongAuthorIgnored() {
            UUID vid = UUID.randomUUID();
            SignedVoucher v = voucher(vid);
            putLedger(v, VoucherStatus.REDEEMED, ISSUER_SK, 1_700_000_000L);
            putLedger(v, VoucherStatus.ISSUED, OTHER_SK, 1_700_000_500L);
            // Strip "authors" so a forged newer event reaches the client: the signature check must drop it.
            NostrVoucherLedgerRepository r = new NostrVoucherLedgerRepository(client, id(ISSUER_SK).getPublicKey(), 1000, 2000) {
                @Override
                java.util.Map<String, List<String>> filterFor(String voucherId) {
                    java.util.Map<String, List<String>> m = new java.util.HashMap<>(super.filterFor(voucherId));
                    m.remove("authors");
                    return m;
                }
            };
            assertThat(r.queryStatus(vid.toString())).contains(VoucherStatus.REDEEMED);
        }

        @Test
        @DisplayName("relay caps at N newest: pages past N+1 newer events to the latest anyway")
        void pagesPastRelayCap() {
            relay.cap = 5;
            UUID vid = UUID.randomUUID();
            SignedVoucher v = voucher(vid);
            putLedger(v, VoucherStatus.REDEEMED, ISSUER_SK, 1_700_000_000L);
            for (int i = 0; i < 6; i++) {
                putLedger(voucher(UUID.randomUUID()), VoucherStatus.ISSUED, ISSUER_SK, 1_700_000_100L + i);
            }
            // The fake relay matches #d, so put same-d junk too: forged by another author, newer.
            for (int i = 0; i < 6; i++) {
                putLedger(v, VoucherStatus.ISSUED, OTHER_SK, 1_700_000_200L + i);
            }
            NostrVoucherLedgerRepository r = new NostrVoucherLedgerRepository(client, id(ISSUER_SK).getPublicKey(), 1000, 5000) {
                @Override
                java.util.Map<String, List<String>> filterFor(String voucherId) {
                    java.util.Map<String, List<String>> m = new java.util.HashMap<>(super.filterFor(voucherId));
                    m.remove("authors");
                    return m;
                }
            };
            assertThat(r.queryStatus(vid.toString())).contains(VoucherStatus.REDEEMED);
            assertThat(relay.reqs.get()).isGreaterThan(1);
        }

        @Test
        @DisplayName("relay timeout: throws, never answers not found")
        void timeoutThrows() {
            UUID vid = UUID.randomUUID();
            putLedger(voucher(vid), VoucherStatus.REDEEMED, ISSUER_SK, 1_700_000_000L);
            relay.silent = true;
            assertThatThrownBy(() -> repo(300).queryStatus(vid.toString())).isInstanceOf(VoucherNostrException.class);
            assertThatThrownBy(() -> repo(300).queryVoucher(vid.toString())).isInstanceOf(VoucherNostrException.class);
            assertThatThrownBy(() -> repo(300).exists(vid.toString())).isInstanceOf(VoucherNostrException.class);
        }
    }

    @Nested
    @DisplayName("NostrVoucherBackupRepository")
    class Backup {

        NostrVoucherBackupRepository repo(long timeoutMs) {
            return new NostrVoucherBackupRepository(client, 1000, timeoutMs);
        }

        void putBackup(String authorSk, String recipientSk, SignedVoucher... vouchers) {
            String recipientPub = id(recipientSk).getPublicKey().toString();
            GenericEvent e = VoucherBackupPayload.createBackupEvent(List.of(vouchers), recipientSk, recipientPub);
            relay.store(TestEventSigner.sign(e, authorSk, 1_700_000_000L + relay.stored.size()));
        }

        @Test
        @DisplayName("found: restores the user's backup, filter is kind 4/authors/#p/#backup without limit")
        void found() {
            UUID vid = UUID.randomUUID();
            putBackup(USER_SK, USER_SK, voucher(vid));

            List<SignedVoucher> restored = repo(2000).restore(USER_SK);
            assertThat(restored).extracting(sv -> sv.getSecret().getVoucherId()).containsExactly(vid);
            String pub = id(USER_SK).getPublicKey().toString();
            JsonNode f = lastReqFilter();
            assertThat(f.has("limit")).isFalse();
            assertThat(f.get("kinds").get(0).asInt()).isEqualTo(4);
            assertThat(f.get("authors").get(0).asText()).isEqualTo(pub);
            assertThat(f.get("#p").get(0).asText()).isEqualTo(pub);
            assertThat(f.get("#backup").get(0).asText()).isEqualTo("vouchers");
            assertThat(repo(2000).hasBackups(USER_SK)).isTrue();
        }

        @Test
        @DisplayName("not found: every relay reached EOSE with nothing")
        void notFound() {
            putBackup(OTHER_SK, OTHER_SK, voucher(UUID.randomUUID()));
            assertThat(repo(2000).restore(USER_SK)).isEmpty();
            assertThat(repo(2000).hasBackups(USER_SK)).isFalse();
        }

        @Test
        @DisplayName("wrong author is ignored: an event addressed to the user but signed by someone else")
        void wrongAuthorIgnored() {
            putBackup(OTHER_SK, USER_SK, voucher(UUID.randomUUID()));
            assertThat(repo(2000).restore(USER_SK)).isEmpty();
            assertThat(repo(2000).hasBackups(USER_SK)).isFalse();
        }

        @Test
        @DisplayName("relay caps at N newest: restore pages and gets every backup")
        void pagesPastRelayCap() throws Exception {
            relay.cap = 2;
            java.util.Set<UUID> ids = new java.util.HashSet<>();
            for (int i = 0; i < 5; i++) {
                UUID vid = UUID.randomUUID();
                ids.add(vid);
                putBackup(USER_SK, USER_SK, voucher(vid));
            }
            assertThat(repo(5000).restore(USER_SK)).extracting(sv -> sv.getSecret().getVoucherId())
                    .containsExactlyInAnyOrderElementsOf(ids);
        }

        @Test
        @DisplayName("relay timeout: throws, never answers no backups")
        void timeoutThrows() {
            putBackup(USER_SK, USER_SK, voucher(UUID.randomUUID()));
            relay.silent = true;
            assertThatThrownBy(() -> repo(300).restore(USER_SK)).isInstanceOf(VoucherNostrException.class);
            assertThatThrownBy(() -> repo(300).hasBackups(USER_SK)).isInstanceOf(VoucherNostrException.class);
        }
    }
}
