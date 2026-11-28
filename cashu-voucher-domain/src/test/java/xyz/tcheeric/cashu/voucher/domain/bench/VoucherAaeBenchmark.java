package xyz.tcheeric.cashu.voucher.domain.bench;

import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.openjdk.jmh.annotations.*;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherCanonicalBytes;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;
import xyz.tcheeric.cashu.voucher.domain.VoucherValidator;
import xyz.tcheeric.cashu.voucher.domain.ledger.*;
import xyz.tcheeric.cashu.voucher.domain.util.VoucherSerializationUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Frozen AAE harness (aae/nov27). Do not edit during the tuning loop.
 * Every benchmark returns its result so nothing is dead-code eliminated.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgs = {"-Xms1g", "-Xmx1g"})
@State(Scope.Benchmark)
public class VoucherAaeBenchmark {

    static final String PLATFORM_SK = "a1".repeat(32);
    static final String STALL_SK = "b2".repeat(32);
    static final String VOUCHER_ID = "0b8e3f6a-1c2d-4e5f-8a9b-0c1d2e3f4a5b";

    String issuerSk;
    String issuerPk;
    VoucherSecret unsigned;
    SignedVoucher signed;
    byte[] signature;

    String platformPub;
    String stall;
    byte[] platformRoot;
    LedgerEntry ledgerEntry;
    LedgerEvent ledgerEvent;
    byte[] ref;

    byte[] convKey;
    byte[] nonce;
    String plaintext;
    String payload;

    Map<String, Object> cborMap;
    byte[] cborBytes;

    @Setup
    public void setup() throws Exception {
        issuerSk = STALL_SK;
        issuerPk = Hex.toHexString(Schnorr.genPubKey(Hex.decode(issuerSk)));
        unsigned = secret();
        VoucherSecret toSign = secret();
        signed = VoucherSignatureService.createSigned(toSign, issuerSk, issuerPk);
        signature = Hex.decode(signed.getSecret().getIssuerSignature());

        platformPub = Hex.toHexString(Schnorr.genPubKey(Hex.decode(PLATFORM_SK)));
        stall = issuerPk;
        platformRoot = VoucherLedgerRef.ledgerRoot(Hex.decode(PLATFORM_SK));
        VoucherFields f = new VoucherFields(VOUCHER_ID, stall, platformPub, "sat", 1000L, 2L, 4102444800L,
                "benchmark memo with some \"quotes\" and text", "MINIMAL", "0.5", "{\"stall\":\"x\"}", null);
        ledgerEntry = new LedgerEntry(VoucherStatus.ISSUED, f, null);
        ledgerEvent = VoucherLedgerV2.seal(ledgerEntry, Hex.decode(PLATFORM_SK), platformRoot,
                new byte[32], new byte[32]).template();
        ref = f.ref();

        convKey = new byte[32];
        for (int i = 0; i < 32; i++) convKey[i] = (byte) (i * 7 + 1);
        nonce = new byte[32];
        plaintext = "x".repeat(700);
        payload = Nip44V2.encrypt(plaintext, convKey, nonce);

        cborMap = new LinkedHashMap<>();
        cborMap.put("voucherId", VOUCHER_ID);
        cborMap.put("issuerId", "merchant123");
        cborMap.put("unit", "EUR");
        cborMap.put("faceValue", 1000L);
        cborMap.put("expiresAt", 4102444800L);
        cborMap.put("memo", "benchmark memo");
        cborMap.put("tags", List.of("a", "b", "c"));
        cborBytes = VoucherSerializationUtils.toCbor(cborMap);
    }

    private static VoucherSecret secret() {
        return VoucherSecret.builder()
                .voucherId(UUID.fromString(VOUCHER_ID))
                .issuerId("merchant123")
                .unit("EUR")
                .faceValue(1000L)
                .expiresAt(4102444800L)
                .memo("Benchmark voucher \"memo\"\twith escapes")
                .backingStrategy("PROPORTIONAL")
                .issuanceRatio(0.01)
                .faceDecimals(2)
                .build();
    }

    @Benchmark public byte[] canonicalBytes() { return VoucherCanonicalBytes.of(signed.getSecret()); }
    @Benchmark public byte[] sign() { return VoucherSignatureService.sign(unsigned, issuerSk); }
    @Benchmark public boolean verify() { return VoucherSignatureService.verify(signed.getSecret()); }
    @Benchmark public boolean verifyTampered() {
        signature[5] ^= 1;
        boolean r = VoucherSignatureService.verify(signed.getSecret(), signature, issuerPk);
        signature[5] ^= 1;
        return r;
    }
    @Benchmark public Object validate() { return VoucherValidator.validate(signed); }
    @Benchmark public Object ledgerSeal() {
        return VoucherLedgerV2.seal(ledgerEntry, Hex.decode(PLATFORM_SK), platformRoot, nonce, nonce).template();
    }
    @Benchmark public Object ledgerOpen() { return VoucherLedgerV2.open(ledgerEvent, ref, List.of(platformPub)); }
    @Benchmark public String nip44Encrypt() { return Nip44V2.encrypt(plaintext, convKey, nonce); }
    @Benchmark public String nip44Decrypt() { return Nip44V2.decrypt(payload, convKey); }
    @Benchmark public Object cborRoundTrip() {
        return VoucherSerializationUtils.fromCbor(VoucherSerializationUtils.toCbor(cborMap));
    }
}
