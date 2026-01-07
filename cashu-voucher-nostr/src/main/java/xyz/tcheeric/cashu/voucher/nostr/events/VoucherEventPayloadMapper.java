package xyz.tcheeric.cashu.voucher.nostr.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nostr.util.NostrUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.common.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;

import java.util.Map;
import java.util.Objects;

/**
 * Maps between domain {@link SignedVoucher} instances and JSON-friendly payloads used by Nostr events.
 */
final class VoucherEventPayloadMapper {

    private static final Logger log = LoggerFactory.getLogger(VoucherEventPayloadMapper.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private VoucherEventPayloadMapper() {
    }

    /**
     * Deserializes merchant metadata JSON string to Map.
     */
    private static Map<String, Object> deserializeMerchantMetadata(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize merchant metadata: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Serializes merchant metadata Map to JSON string.
     */
    private static String serializeMerchantMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize merchant metadata: {}", e.getMessage());
            return null;
        }
    }

    static VoucherPayload toPayload(SignedVoucher voucher) {
        Objects.requireNonNull(voucher, "voucher");

        VoucherSecret secret = voucher.getSecret();
        VoucherPayload payload = new VoucherPayload();
        payload.setVoucherId(secret.getVoucherId() != null ? secret.getVoucherId().toString() : null);
        payload.setIssuerId(secret.getIssuerId());
        payload.setUnit(secret.getUnit());
        payload.setFaceValue(secret.getFaceValue() != null ? secret.getFaceValue() : 0L);
        payload.setExpiresAt(secret.getExpiresAt());
        payload.setMemo(secret.getMemo());
        payload.setBackingStrategy(secret.getBackingStrategy());
        payload.setIssuanceRatio(secret.getIssuanceRatio());
        payload.setFaceDecimals(secret.getFaceDecimals());
        payload.setMerchantMetadata(deserializeMerchantMetadata(secret.getMerchantMetadata()));
        payload.setIssuerSignature(NostrUtil.bytesToHex(voucher.getIssuerSignature()));
        payload.setIssuerPublicKey(voucher.getIssuerPublicKey());
        return payload;
    }

    static SignedVoucher toDomain(VoucherPayload payload) {
        Objects.requireNonNull(payload, "payload");

        String backingStrategy = payload.getBackingStrategy() != null
                ? payload.getBackingStrategy()
                : BackingStrategy.FIXED.name();

        VoucherSecret secret = VoucherSecret.builder()
                .voucherId(java.util.UUID.fromString(
                        Objects.requireNonNull(payload.getVoucherId(), "voucherId")))
                .issuerId(Objects.requireNonNull(payload.getIssuerId(), "issuerId"))
                .unit(Objects.requireNonNull(payload.getUnit(), "unit"))
                .faceValue(payload.getFaceValue())
                .expiresAt(payload.getExpiresAt())
                .memo(payload.getMemo())
                .backingStrategy(backingStrategy)
                .issuanceRatio(payload.getIssuanceRatio() > 0 ? payload.getIssuanceRatio() : 1.0)
                .faceDecimals(payload.getFaceDecimals())
                .merchantMetadata(serializeMerchantMetadata(payload.getMerchantMetadata()))
                .build();

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

