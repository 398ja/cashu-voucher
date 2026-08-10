package xyz.tcheeric.cashu.voucher.pass;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PassJson")
class PassJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("omits null fields so the document stays schema-clean")
    void omitsNullFields() throws Exception {
        PassJson pass = new PassJson(
                1, "xyz.tcheeric.voucher", "imani", "serial-1", "A card", "Corner Cafe",
                null, null, null, null, null, false,
                new PassJson.StoreCard(List.of(), null, null), null, null);

        JsonNode json = mapper.valueToTree(pass);

        assertThat(json.has("logoText")).isFalse();
        assertThat(json.has("backgroundColor")).isFalse();
        assertThat(json.has("expirationDate")).isFalse();
        assertThat(json.has("barcodes")).isFalse();
        assertThat(json.has("userInfo")).isFalse();
        assertThat(json.path("storeCard").has("auxiliaryFields")).isFalse();
        // voided is a primitive and is always emitted; false is valid pass.json
        assertThat(json.path("voided").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("serialises a currency field as a JSON number with its code")
    void serialisesCurrencyField() throws Exception {
        PassJson.Field balance =
                new PassJson.Field("balance", "BALANCE", new BigDecimal("50.00"), "EUR", null);

        JsonNode json = mapper.valueToTree(balance);

        assertThat(json.path("value").isNumber()).isTrue();
        assertThat(json.path("value").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(json.path("currencyCode").asText()).isEqualTo("EUR");
        assertThat(json.has("dateStyle")).isFalse();
    }

    @Test
    @DisplayName("Field.of leaves currencyCode and dateStyle unset")
    void fieldOfLeavesOptionalsUnset() {
        PassJson.Field field = PassJson.Field.of("issuer", "Issuer", "corner-cafe");

        assertThat(field.key()).isEqualTo("issuer");
        assertThat(field.label()).isEqualTo("Issuer");
        assertThat(field.value()).isEqualTo("corner-cafe");
        assertThat(field.currencyCode()).isNull();
        assertThat(field.dateStyle()).isNull();
    }

    @Test
    @DisplayName("serialises nested pass structure with the expected key names")
    void serialisesNestedStructure() throws Exception {
        PassJson pass = new PassJson(
                1, "xyz.tcheeric.voucher", "imani", "serial-1", "A card", "Corner Cafe",
                "Corner Cafe", "rgb(20,20,20)", "rgb(255,255,255)", "rgb(255,255,255)",
                "2030-01-01T00:00:00Z", true,
                new PassJson.StoreCard(
                        List.of(PassJson.Field.of("balance", "BALANCE", 1)),
                        List.of(PassJson.Field.of("expires", "EXPIRES", "x")),
                        List.of(PassJson.Field.of("issuer", "Issuer", "corner-cafe"))),
                List.of(new PassJson.Barcode("PKBarcodeFormatQR", "voucher:x", "UTF-8", "x")),
                Map.of("voucherId", "x"));

        JsonNode json = mapper.valueToTree(pass);

        assertThat(json.path("formatVersion").asInt()).isEqualTo(1);
        assertThat(json.path("voided").asBoolean()).isTrue();
        assertThat(json.path("expirationDate").asText()).isEqualTo("2030-01-01T00:00:00Z");
        assertThat(json.path("storeCard").path("primaryFields")).hasSize(1);
        assertThat(json.path("storeCard").path("backFields").get(0).path("key").asText())
                .isEqualTo("issuer");
        assertThat(json.path("barcodes").get(0).path("format").asText())
                .isEqualTo("PKBarcodeFormatQR");
        assertThat(json.path("userInfo").path("voucherId").asText()).isEqualTo("x");
    }
}
