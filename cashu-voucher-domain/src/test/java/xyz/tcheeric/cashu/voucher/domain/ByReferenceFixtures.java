package xyz.tcheeric.cashu.voucher.domain;

import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.voucher.domain.util.VoucherSerializationUtils;

import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds v1 (inline) and v2 (by reference) unlocked voucher proofs of one voucher
 * (cashu-voucher#64), deterministically: BIP-340 with all-zero auxiliary randomness.
 */
final class ByReferenceFixtures {

    private ByReferenceFixtures() {
    }

    /** Signs {@code secret} in place, deterministically, with {@code key}. */
    static VoucherSecret sign(VoucherSecret secret, byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(VoucherCanonicalBytes.of(secret));
            secret.setIssuerSignature(Hex.toHexString(Schnorr.sign(digest, key, new byte[32])));
            secret.setIssuerPublicKey(Hex.toHexString(Schnorr.genPubKey(key)));
            return secret;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The voucher document: the CBOR blob a v1 proof carries in {@code data}, in the key
     * order of the design doc. Absent optional fields are omitted.
     */
    static byte[] document(VoucherSecret v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("voucherId", v.getVoucherId().toString());
        m.put("issuerId", v.getIssuerId());
        m.put("unit", v.getUnit());
        m.put("faceValue", v.getFaceValue());
        putIfPresent(m, "expiresAt", v.getExpiresAt());
        putIfPresent(m, "memo", v.getMemo());
        putIfPresent(m, "backingStrategy", v.getBackingStrategy());
        m.put("issuanceRatio", v.getIssuanceRatio());
        m.put("faceDecimals", v.getFaceDecimals());
        putIfPresent(m, "merchantMetadata", v.getMerchantMetadata());
        putIfPresent(m, "issuanceWarrant", v.getIssuanceWarrant());
        putIfPresent(m, "nonce", v.getNonce());
        putIfPresent(m, "issuerSignature", v.getIssuerSignature());
        putIfPresent(m, "issuerPublicKey", v.getIssuerPublicKey());
        return VoucherSerializationUtils.toCbor(m);
    }

    private static void putIfPresent(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    /** The NUT-10 wire string of an unlocked voucher proof with this {@code data}. */
    static String wire(String nonce, byte[] data) {
        return "[\"VOUCHER\",{\"nonce\":\"" + nonce + "\",\"data\":\"" + Hex.toHexString(data)
                + "\",\"tags\":[]}]";
    }

    static String v1(String proofNonce, byte[] document) {
        return wire(proofNonce, document);
    }

    static String v2(String proofNonce, byte[] document) {
        return wire(proofNonce, Hex.decode(VoucherDocuments.key(document)));
    }

    static WellKnownSecret parse(String wire) {
        return (WellKnownSecret) SecretUtil.toSecret(wire);
    }

    static Map<String, byte[]> docs(byte[]... documents) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (byte[] d : documents) {
            m.put(VoucherDocuments.key(d), d);
        }
        return m;
    }
}
