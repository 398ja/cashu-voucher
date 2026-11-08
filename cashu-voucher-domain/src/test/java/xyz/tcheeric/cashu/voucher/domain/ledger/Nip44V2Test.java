package xyz.tcheeric.cashu.voucher.domain.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Replays the official paulmillr/nip44 v2 vectors. The {@code get_conversation_key} vectors
 * (valid and invalid) are skipped on purpose: they test secp256k1 ECDH, which the voucher ledger
 * does not use (its conversation key is {@code HMAC("imani-voucher-key-v1", ref)}).
 */
class Nip44V2Test {

    static JsonNode v2() throws Exception {
        try (InputStream in = Nip44V2Test.class.getResourceAsStream("/nip44.vectors.json")) {
            return new ObjectMapper().readTree(in).get("v2");
        }
    }

    @Test
    @DisplayName("get_message_keys")
    void messageKeys() throws Exception {
        JsonNode mk = v2().get("valid").get("get_message_keys");
        byte[] ck = Hex.decode(mk.get("conversation_key").asText());
        int n = 0;
        for (JsonNode k : mk.get("keys")) {
            Nip44V2.MessageKeys keys = Nip44V2.messageKeys(ck, Hex.decode(k.get("nonce").asText()));
            assertThat(Hex.toHexString(keys.chachaKey())).isEqualTo(k.get("chacha_key").asText());
            assertThat(Hex.toHexString(keys.chachaNonce())).isEqualTo(k.get("chacha_nonce").asText());
            assertThat(Hex.toHexString(keys.hmacKey())).isEqualTo(k.get("hmac_key").asText());
            n++;
        }
        assertThat(n).isPositive();
    }

    @Test
    @DisplayName("calc_padded_len")
    void paddedLen() throws Exception {
        for (JsonNode p : v2().get("valid").get("calc_padded_len")) {
            assertThat(Nip44V2.calcPaddedLen(p.get(0).asInt())).as(p.toString()).isEqualTo(p.get(1).asInt());
        }
    }

    @Test
    @DisplayName("encrypt_decrypt")
    void encryptDecrypt() throws Exception {
        for (JsonNode v : v2().get("valid").get("encrypt_decrypt")) {
            byte[] ck = Hex.decode(v.get("conversation_key").asText());
            String payload = Nip44V2.encrypt(v.get("plaintext").asText(), ck, Hex.decode(v.get("nonce").asText()));
            assertThat(payload).isEqualTo(v.get("payload").asText());
            assertThat(Nip44V2.decrypt(payload, ck)).isEqualTo(v.get("plaintext").asText());
        }
    }

    @Test
    @DisplayName("encrypt_decrypt_long_msg")
    void longMessages() throws Exception {
        for (JsonNode v : v2().get("valid").get("encrypt_decrypt_long_msg")) {
            String plaintext = v.get("pattern").asText().repeat(v.get("repeat").asInt());
            assertThat(sha(plaintext)).isEqualTo(v.get("plaintext_sha256").asText());
            byte[] ck = Hex.decode(v.get("conversation_key").asText());
            String payload = Nip44V2.encrypt(plaintext, ck, Hex.decode(v.get("nonce").asText()));
            assertThat(sha(payload)).isEqualTo(v.get("payload_sha256").asText());
            assertThat(Nip44V2.decrypt(payload, ck)).isEqualTo(plaintext);
        }
    }

    @Test
    @DisplayName("invalid.decrypt")
    void invalidDecrypt() throws Exception {
        for (JsonNode v : v2().get("invalid").get("decrypt")) {
            byte[] ck = Hex.decode(v.get("conversation_key").asText());
            assertThatThrownBy(() -> Nip44V2.decrypt(v.get("payload").asText(), ck))
                    .as(v.get("note").asText()).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("invalid.encrypt_msg_lengths")
    void invalidLengths() throws Exception {
        byte[] key = new byte[32];
        for (JsonNode len : v2().get("invalid").get("encrypt_msg_lengths")) {
            int n = len.asInt();
            String text = n > 200_000 ? "a".repeat(65536) + "b" : "a".repeat(n);
            assertThatThrownBy(() -> Nip44V2.encrypt(text, key, new byte[32]))
                    .as("length " + n).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("refuses bad key and nonce sizes")
    void badSizes() {
        assertThatThrownBy(() -> Nip44V2.encrypt("a", new byte[31], new byte[32])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Nip44V2.encrypt("a", new byte[32], new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Nip44V2.calcPaddedLen(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Nip44V2.decrypt(null, new byte[32])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Nip44V2.decrypt("", new byte[32])).isInstanceOf(IllegalArgumentException.class);
    }

    private static String sha(String s) {
        return Hex.toHexString(VoucherLedgerRef.sha256(s.getBytes(StandardCharsets.UTF_8)));
    }
}
