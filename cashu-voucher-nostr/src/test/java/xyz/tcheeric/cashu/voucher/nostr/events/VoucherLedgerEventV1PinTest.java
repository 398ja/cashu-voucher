package xyz.tcheeric.cashu.voucher.nostr.events;

import nostr.event.BaseTag;
import nostr.event.tag.GenericTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins ledger v1 output byte for byte, recorded before v2 landed (0.18.0). v1 stays the default
 * until callers switch, so its wire form must not drift while v2 is introduced.
 */
class VoucherLedgerEventV1PinTest {

    @Test
    @DisplayName("v1 content and tags for a fixed voucher are unchanged")
    void v1Unchanged() {
        VoucherSecret s = VoucherSecret.builder().voucherId(UUID.fromString("0b8e3f6a-1c2d-4e5f-8a9b-0c1d2e3f4a5b"))
                .issuerId("6aa3da9b5c1d61956076cb3014ffdaa0996bacdae29ba4b89e39b4088f86ec78").unit("sat")
                .faceValue(5000L).expiresAt(1790000000L).memo("pinned").backingStrategy("MINIMAL")
                .issuanceRatio(0.5).faceDecimals(2).issuerSignature("11".repeat(64))
                .issuerPublicKey("ab5d2e79cfd621b1b027ffb24e2453ed7fb571ba9a841ff0e2473466cabd168d").build();
        VoucherLedgerEvent e = VoucherLedgerEvent.fromVoucher(new SignedVoucher(s), VoucherStatus.ISSUED);

        assertThat(e.getKind()).isEqualTo(30078);
        assertThat(e.getContent()).isEqualTo("{\"voucher\":{\"voucherId\":\"0b8e3f6a-1c2d-4e5f-8a9b-0c1d2e3f4a5b\","
                + "\"issuerId\":\"6aa3da9b5c1d61956076cb3014ffdaa0996bacdae29ba4b89e39b4088f86ec78\",\"unit\":\"sat\","
                + "\"faceValue\":5000,\"expiresAt\":1790000000,\"memo\":\"pinned\",\"backingStrategy\":\"MINIMAL\","
                + "\"issuanceRatio\":0.5,\"faceDecimals\":2,\"issuerSignature\":\"" + "1".repeat(128) + "\","
                + "\"issuerPublicKey\":\"ab5d2e79cfd621b1b027ffb24e2453ed7fb571ba9a841ff0e2473466cabd168d\"},"
                + "\"status\":\"ISSUED\"}");
        List<String> tags = new ArrayList<>();
        for (BaseTag t : e.getTags()) {
            tags.add(t.getCode() + "=" + ((GenericTag) t).getParams());
        }
        assertThat(tags).containsExactly(
                "d=[voucher:0b8e3f6a-1c2d-4e5f-8a9b-0c1d2e3f4a5b]",
                "status=[ISSUED]",
                "amount=[5000]",
                "unit=[sat]",
                "expiry=[1790000000]",
                "p=[ab5d2e79cfd621b1b027ffb24e2453ed7fb571ba9a841ff0e2473466cabd168d]",
                "p=[6aa3da9b5c1d61956076cb3014ffdaa0996bacdae29ba4b89e39b4088f86ec78]");
    }
}
