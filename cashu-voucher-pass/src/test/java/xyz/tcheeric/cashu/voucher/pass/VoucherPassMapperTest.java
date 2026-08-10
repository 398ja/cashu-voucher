package xyz.tcheeric.cashu.voucher.pass;

import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VoucherPassMapper")
class VoucherPassMapperTest {

    private static String issuerPrivateKeyHex;
    static String issuerPublicKeyHex;

    static final String ISSUER_ID = "corner-cafe";
    static final String VOUCHER_ID = "11111111-2222-3333-4444-555555555555";

    @BeforeAll
    static void setupKeys() {
        byte[] privateKeyBytes = Schnorr.generatePrivateKey();
        byte[] publicKeyBytes = Schnorr.genPubKey(privateKeyBytes);
        issuerPrivateKeyHex = Hex.toHexString(privateKeyBytes);
        issuerPublicKeyHex = Hex.toHexString(publicKeyBytes);
    }

    /**
     * Builds a signed voucher. Reused by every test in this class.
     *
     * @param unit ISO 4217 code, lowercase per Cashu convention
     * @param faceValue value in the smallest unit
     * @param faceDecimals decimal places
     * @param expiresAt epoch seconds, or null
     * @param memo description, or null
     */
    static SignedVoucher voucher(String unit, long faceValue, int faceDecimals,
                                 Long expiresAt, String memo) {
        VoucherSecret secret = VoucherSecret.builder()
                .voucherId(UUID.fromString(VOUCHER_ID))
                .issuerId(ISSUER_ID)
                .unit(unit)
                .faceValue(faceValue)
                .faceDecimals(faceDecimals)
                .expiresAt(expiresAt)
                .memo(memo)
                .build();
        // createSigned signs, sets the issuer_sig and issuer_pubkey tags, and wraps.
        // Calling sign() alone returns the bytes without setting the tags, and the
        // SignedVoucher constructor would then reject the secret as unsigned.
        return VoucherSignatureService.createSigned(secret, issuerPrivateKeyHex, issuerPublicKeyHex);
    }

    /** Finds a field by key, failing the test if absent. */
    static PassJson.Field field(List<PassJson.Field> fields, String key) {
        return fields.stream()
                .filter(f -> key.equals(f.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No field with key '" + key + "' in " + fields));
    }

    @Test
    @DisplayName("produces a store card with all required identity fields")
    void producesStoreCard() {
        PassJson pass = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, null, "Gift card"), MerchantBranding.empty());

        assertThat(pass.formatVersion()).isEqualTo(1);
        assertThat(pass.passTypeIdentifier()).isEqualTo("xyz.tcheeric.voucher");
        assertThat(pass.teamIdentifier()).isEqualTo("imani");
        assertThat(pass.serialNumber()).isEqualTo(VOUCHER_ID);
        assertThat(pass.description()).isEqualTo("Gift card");
        assertThat(pass.organizationName()).isEqualTo(ISSUER_ID);
        assertThat(pass.storeCard()).isNotNull();
    }

    @Test
    @DisplayName("back fields carry voucher id, issuer, issuer key and terms")
    void backFieldsCarryProvenance() {
        List<PassJson.Field> back = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, null, "Gift card"), MerchantBranding.empty())
                .storeCard().backFields();

        assertThat(field(back, "voucherId").value()).isEqualTo(VOUCHER_ID);
        assertThat(field(back, "issuer").value()).isEqualTo(ISSUER_ID);
        assertThat(field(back, "issuerKey").value()).isEqualTo(issuerPublicKeyHex);
        assertThat(field(back, "terms").value().toString()).contains("issuing merchant");
    }

    @Test
    @DisplayName("does not expose the issuer signature anywhere")
    void doesNotExposeSignature() {
        SignedVoucher v = voucher("eur", 5000L, 2, null, "Gift card");

        List<PassJson.Field> back = VoucherPassMapper.toPass(v, MerchantBranding.empty())
                .storeCard().backFields();

        assertThat(back).extracting(PassJson.Field::key).doesNotContain("issuerSig");
        assertThat(back).extracting(f -> String.valueOf(f.value()))
                .doesNotContain(v.getSecret().getIssuerSignature());
    }

    @Test
    @DisplayName("rejects a null voucher")
    void rejectsNullVoucher() {
        assertThatThrownBy(() -> VoucherPassMapper.toPass(null, MerchantBranding.empty()))
                .isInstanceOf(NullPointerException.class);
    }

    private static PassJson.Field balanceOf(String unit, long faceValue, int faceDecimals) {
        return field(VoucherPassMapper.toPass(
                voucher(unit, faceValue, faceDecimals, null, "Gift card"),
                MerchantBranding.empty()).storeCard().primaryFields(), "balance");
    }

    @Test
    @DisplayName("scales balance into major units with the currency code")
    void scalesBalance() {
        PassJson.Field balance = balanceOf("eur", 5000L, 2);

        assertThat(balance.value()).isEqualTo(new BigDecimal("50.00"));
        assertThat(balance.currencyCode()).isEqualTo("EUR");
        assertThat(balance.label()).isEqualTo("BALANCE");
    }

    @Test
    @DisplayName("handles zero-decimal and three-decimal currencies")
    void handlesOtherCurrencyScales() {
        assertThat(balanceOf("jpy", 5000L, 0).value()).isEqualTo(new BigDecimal("5000"));
        assertThat(balanceOf("jpy", 5000L, 0).currencyCode()).isEqualTo("JPY");
        assertThat(balanceOf("kwd", 5000L, 3).value()).isEqualTo(new BigDecimal("5.000"));
        assertThat(balanceOf("kwd", 5000L, 3).currencyCode()).isEqualTo("KWD");
    }

    @Test
    @DisplayName("trusts face_decimals when it disagrees with the currency default")
    void trustsFaceDecimalsOverCurrencyDefault() {
        // JPY defaults to 0 fraction digits; the voucher says 2. face_decimals wins.
        assertThat(balanceOf("jpy", 5000L, 2).value()).isEqualTo(new BigDecimal("50.00"));
    }

    @Test
    @DisplayName("rejects an unknown currency unit")
    void rejectsUnknownUnit() {
        assertThatThrownBy(() -> VoucherPassMapper.toPass(
                voucher("zzz", 5000L, 2, null, "Gift card"), MerchantBranding.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO 4217");
    }

    @Test
    @DisplayName("rejects a negative face value")
    void rejectsNegativeFaceValue() {
        assertThatThrownBy(() -> VoucherPassMapper.toPass(
                voucher("eur", -1L, 2, null, "Gift card"), MerchantBranding.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("accepts an already-uppercase unit")
    void acceptsUppercaseUnit() {
        assertThatCode(() -> VoucherPassMapper.toPass(
                voucher("EUR", 5000L, 2, null, "Gift card"), MerchantBranding.empty()))
                .doesNotThrowAnyException();
    }

    private static final long EXPIRES_AT = 1893456000L; // 2030-01-01T00:00:00Z

    @Test
    @DisplayName("sets an ISO-8601 expirationDate and an auxiliary expiry field")
    void setsExpiry() {
        PassJson pass = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, EXPIRES_AT, "Gift card"), MerchantBranding.empty());

        assertThat(pass.expirationDate()).isEqualTo("2030-01-01T00:00:00Z");

        PassJson.Field expires = field(pass.storeCard().auxiliaryFields(), "expires");
        assertThat(expires.value()).isEqualTo("2030-01-01T00:00:00Z");
        assertThat(expires.dateStyle()).isEqualTo("PKDateStyleMedium");
        assertThat(expires.currencyCode()).isNull();
    }

    @Test
    @DisplayName("omits expiry entirely when the voucher has none")
    void omitsExpiryWhenAbsent() {
        PassJson pass = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, null, "Gift card"), MerchantBranding.empty());

        assertThat(pass.expirationDate()).isNull();
        assertThat(pass.storeCard().auxiliaryFields()).isNull();
    }

    @Test
    @DisplayName("voids the pass for redeemed and revoked vouchers only")
    void voidsForTerminalStatuses() {
        SignedVoucher v = voucher("eur", 5000L, 2, null, "Gift card");

        assertThat(VoucherPassMapper.toPass(v, MerchantBranding.empty(), VoucherStatus.REDEEMED)
                .voided()).isTrue();
        assertThat(VoucherPassMapper.toPass(v, MerchantBranding.empty(), VoucherStatus.REVOKED)
                .voided()).isTrue();
        assertThat(VoucherPassMapper.toPass(v, MerchantBranding.empty(), VoucherStatus.ISSUED)
                .voided()).isFalse();
    }

    @Test
    @DisplayName("defaults to not voided when no status is supplied")
    void defaultsToNotVoided() {
        assertThat(VoucherPassMapper.toPass(voucher("eur", 5000L, 2, null, "Gift card"),
                MerchantBranding.empty()).voided()).isFalse();
    }
}
