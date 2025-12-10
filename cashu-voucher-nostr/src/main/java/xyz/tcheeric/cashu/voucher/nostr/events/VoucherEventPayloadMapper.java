package xyz.tcheeric.cashu.voucher.nostr.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nostr.util.NostrUtil;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherSecret;

import java.util.Map;
import java.util.Objects;

/**
 * Maps between domain {@link SignedVoucher} instances and JSON-friendly payloads used by Nostr events.
 */
final class VoucherEventPayloadMapper {

    private VoucherEventPayloadMapper() {
    }

    static VoucherPayload toPayload(SignedVoucher voucher) {
        Objects.requireNonNull(voucher, "voucher");

        VoucherSecret secret = voucher.getSecret();
        VoucherPayload payload = new VoucherPayload();
        payload.setVoucherId(secret.getVoucherId());
        payload.setIssuerId(secret.getIssuerId());
        payload.setUnit(secret.getUnit());
        payload.setFaceValue(secret.getFaceValue());
        payload.setExpiresAt(secret.getExpiresAt());
        payload.setMemo(secret.getMemo());
        payload.setBackingStrategy(secret.getBackingStrategy().name());
        payload.setIssuanceRatio(secret.getIssuanceRatio());
        payload.setFaceDecimals(secret.getFaceDecimals());
        payload.setMerchantMetadata(secret.getMerchantMetadata());
        payload.setIssuerSignature(NostrUtil.bytesToHex(voucher.getIssuerSignature()));
        payload.setIssuerPublicKey(voucher.getIssuerPublicKey());
        return payload;
    }

    static SignedVoucher toDomain(VoucherPayload payload) {
        Objects.requireNonNull(payload, "payload");

        BackingStrategy backingStrategy = payload.getBackingStrategy() != null
                ? BackingStrategy.valueOf(payload.getBackingStrategy())
                : BackingStrategy.FIXED;

        VoucherSecret secret = VoucherSecret.create(
                Objects.requireNonNull(payload.getVoucherId(), "voucherId"),
                Objects.requireNonNull(payload.getIssuerId(), "issuerId"),
                Objects.requireNonNull(payload.getUnit(), "unit"),
                payload.getFaceValue(),
                payload.getExpiresAt(),
                payload.getMemo(),
                backingStrategy,
                payload.getIssuanceRatio() > 0 ? payload.getIssuanceRatio() : 1.0,
                payload.getFaceDecimals(),
                payload.getMerchantMetadata()
        );

        String signatureHex = Objects.requireNonNull(payload.getIssuerSignature(), "issuerSignature");
        byte[] signatureBytes = NostrUtil.hex128ToBytes(signatureHex);
        String publicKey = Objects.requireNonNull(payload.getIssuerPublicKey(), "issuerPublicKey");

        return new SignedVoucher(secret, signatureBytes, publicKey);
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static final class VoucherPayload {

        @JsonProperty("voucherId")
        private String voucherId;

        @JsonProperty("issuerId")
        private String issuerId;

        @JsonProperty("unit")
        private String unit;

        @JsonProperty("faceValue")
        private long faceValue;

        @JsonProperty("expiresAt")
        private Long expiresAt;

        @JsonProperty("memo")
        private String memo;

        @JsonProperty("backingStrategy")
        private String backingStrategy;

        @JsonProperty("issuanceRatio")
        private double issuanceRatio;

        @JsonProperty("faceDecimals")
        private int faceDecimals;

        @JsonProperty("merchantMetadata")
        private Map<String, Object> merchantMetadata;

        @JsonProperty("issuerSignature")
        private String issuerSignature;

        @JsonProperty("issuerPublicKey")
        private String issuerPublicKey;
    }
}

