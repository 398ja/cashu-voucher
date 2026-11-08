package xyz.tcheeric.cashu.voucher.app;

import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut18.PaymentPayloadProof;
import xyz.tcheeric.cashu.common.nut18.VoucherPaymentPayload;
import xyz.tcheeric.cashu.common.nut18.VoucherPaymentRequest;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.voucher.app.adapter.MapIssuerKeyRegistry;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.UnlockedVoucherBlob;
import xyz.tcheeric.cashu.voucher.domain.VoucherDocuments;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.util.VoucherSerializationUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Merchant verification of vouchers by reference (cashu-voucher#64). */
@DisplayName("MerchantVerificationService, vouchers by reference")
class MerchantVerificationByReferenceTest {

    private static final String ISSUER_ID = "merchant123";
    private static final byte[] KEY = Hex.decode("07".repeat(32));
    private static final String PUB = Hex.toHexString(Schnorr.genPubKey(KEY));

    private final MerchantVerificationService service = new MerchantVerificationService(
            mock(VoucherLedgerPort.class), MapIssuerKeyRegistry.of(ISSUER_ID, PUB));

    private static byte[] document(String issuerId) {
        VoucherSecret v = VoucherSecret.builder()
                .voucherId(UUID.fromString("11111111-2222-4333-8444-555555555555"))
                .nonce("00".repeat(32)).issuerId(issuerId).unit("sat").faceValue(1000L)
                .expiresAt(4_000_000_000L).backingStrategy(BackingStrategy.FIXED.name())
                .issuanceRatio(1.0).faceDecimals(0).build();
        SignedVoucher signed = VoucherSignatureService.createSigned(v, Hex.toHexString(KEY), PUB);
        VoucherSecret s = signed.getSecret();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("voucherId", s.getVoucherId().toString());
        m.put("issuerId", s.getIssuerId());
        m.put("unit", s.getUnit());
        m.put("faceValue", s.getFaceValue());
        m.put("expiresAt", s.getExpiresAt());
        m.put("backingStrategy", s.getBackingStrategy());
        m.put("issuanceRatio", s.getIssuanceRatio());
        m.put("faceDecimals", s.getFaceDecimals());
        m.put("nonce", s.getNonce());
        m.put("issuerSignature", s.getIssuerSignature());
        m.put("issuerPublicKey", s.getIssuerPublicKey());
        return VoucherSerializationUtils.toCbor(m);
    }

    private static String wire(byte[] data) {
        return "[\"VOUCHER\",{\"nonce\":\"" + "ab".repeat(32) + "\",\"data\":\"" + Hex.toHexString(data)
                + "\",\"tags\":[]}]";
    }

    private static String v2(byte[] doc) {
        return wire(Hex.decode(VoucherDocuments.key(doc)));
    }

    private static WellKnownSecret parse(String s) {
        return (WellKnownSecret) SecretUtil.toSecret(s);
    }

    private static Map<String, byte[]> docs(byte[] doc) {
        return Map.of(VoucherDocuments.key(doc), doc);
    }

    @Test
    @DisplayName("v1 and v2 give the same offline verdict, and v1 matches the SignedVoucher path")
    void sameVerdict() {
        byte[] doc = document(ISSUER_ID);
        var v1 = service.verifyOffline(parse(wire(doc)), null, ISSUER_ID);
        var v2 = service.verifyOffline(parse(v2(doc)), docs(doc), ISSUER_ID);
        var legacy = service.verifyOffline(new SignedVoucher(UnlockedVoucherBlob.read(parse(wire(doc)))), ISSUER_ID);
        assertThat(v1.isValid()).isTrue();
        assertThat(v2.isValid()).isTrue();
        assertThat(legacy.isValid()).isTrue();

        var wrongIssuer1 = service.verifyOffline(parse(wire(doc)), null, "other");
        var wrongIssuer2 = service.verifyOffline(parse(v2(doc)), docs(doc), "other");
        assertThat(wrongIssuer2.getErrors()).isEqualTo(wrongIssuer1.getErrors()).isNotEmpty();
    }

    @Test
    @DisplayName("a v2 proof without its document is refused, never accepted")
    void missingRefused() {
        byte[] doc = document(ISSUER_ID);
        var result = service.verifyOffline(parse(v2(doc)), null, ISSUER_ID);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrorMessage()).contains("missing");
        assertThat(service.verifyOffline(parse(v2(doc)), Map.of(VoucherDocuments.key(doc), "x".getBytes()), ISSUER_ID)
                .getErrorMessage()).contains("does not match");
    }

    private static VoucherPaymentRequest request() {
        return VoucherPaymentRequest.builder().paymentId("p").issuerId(ISSUER_ID).amount(1000).unit("sat").build();
    }

    private static VoucherPaymentPayload payload(String secret) {
        return VoucherPaymentPayload.builder().id("p").issuerId(ISSUER_ID).mint("https://mint.example.com")
                .unit("sat").proofs(List.of(PaymentPayloadProof.builder().amount(1000).keysetId("k")
                        .secret(secret).signature("s").build())).build();
    }

    @Test
    @DisplayName("payload validation requires referenced documents and is unchanged for v1")
    void payload() {
        byte[] doc = document(ISSUER_ID);
        assertThat(service.validatePaymentPayload(payload(wire(doc)), request(), null).isValid()).isTrue();
        assertThat(service.validatePaymentPayload(payload(wire(doc)), request()).isValid()).isTrue();
        assertThat(service.validatePaymentPayload(payload("secret1"), request(), null).isValid()).isTrue();
        assertThat(service.validatePaymentPayload(payload(v2(doc)), request(), docs(doc)).isValid()).isTrue();

        var missing = service.validatePaymentPayload(payload(v2(doc)), request(), null);
        assertThat(missing.isValid()).isFalse();
        assertThat(missing.getErrorMessage()).contains("does not carry");

        var wrong = service.validatePaymentPayload(payload(v2(doc)), request(),
                Map.of(VoucherDocuments.key(doc), "x".getBytes()));
        assertThat(wrong.isValid()).isFalse();
        assertThat(wrong.getErrorMessage()).contains("is not the SHA-256 of its value");

        // A refused map stops before per-proof resolution, but keeps the base payload errors.
        var both = service.validatePaymentPayload(payload(v2(doc)),
                VoucherPaymentRequest.builder().paymentId("other").issuerId(ISSUER_ID).amount(1000).unit("sat").build(),
                Map.of(VoucherDocuments.key(doc), "x".getBytes()));
        assertThat(both.getErrors()).anyMatch(e -> e.contains("not the SHA-256"));
        assertThat(both.getErrors()).hasSizeGreaterThan(1);
        assertThat(both.getErrors()).noneMatch(e -> e.contains("does not carry"));

        Map<String, byte[]> tooMany = new LinkedHashMap<>(docs(doc));
        tooMany.putAll(docs("y".getBytes()));
        assertThat(service.validatePaymentPayload(payload(v2(doc)), request(), tooMany).getErrorMessage())
                .contains("Too many");
    }
}
