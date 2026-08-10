package xyz.tcheeric.cashu.voucher.pass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Maps a {@link SignedVoucher} to a {@link PassJson} document.
 *
 * <p>The pass is a <em>derived view</em>: the signed voucher is the only source of
 * truth, and nothing parses a pass back into a voucher. Rendering is a pure function,
 * so a balance change after partial redemption needs no synchronisation — re-render.
 *
 * <p>Apple Wallet is not a target. We adopt the {@code pass.json} schema only; there
 * is no certificate, no {@code .pkpass} container, and no pass update web service.
 */
public final class VoucherPassMapper {

    private static final Logger log = LoggerFactory.getLogger(VoucherPassMapper.class);

    static final int FORMAT_VERSION = 1;

    /** Schema-required, semantically meaningless — we target no Apple infrastructure. */
    static final String PASS_TYPE_IDENTIFIER = "xyz.tcheeric.voucher";

    /** Schema-required, semantically meaningless — we target no Apple infrastructure. */
    static final String TEAM_IDENTIFIER = "imani";

    static final String DEFAULT_DESCRIPTION = "Gift Card";

    static final String TERMS =
            "Redeemable only with the issuing merchant. Not redeemable at the mint.";

    private VoucherPassMapper() {
    }

    /**
     * Renders a voucher as a store card.
     *
     * @param voucher the signed voucher; must not be null
     * @param branding merchant branding, or null for defaults
     * @return the pass document
     */
    public static PassJson toPass(SignedVoucher voucher, MerchantBranding branding) {
        Objects.requireNonNull(voucher, "voucher must not be null");
        MerchantBranding b = branding != null ? branding : MerchantBranding.empty();
        VoucherSecret secret = voucher.getSecret();

        PassJson.StoreCard storeCard = new PassJson.StoreCard(
                List.of(balanceField(secret)),
                null,
                backFields(secret));

        return new PassJson(
                FORMAT_VERSION,
                PASS_TYPE_IDENTIFIER,
                TEAM_IDENTIFIER,
                String.valueOf(secret.getVoucherId()),
                description(secret, b),
                organizationName(secret, b),
                null,
                null,
                null,
                null,
                null,
                false,
                storeCard,
                null,
                null);
    }

    private static String description(VoucherSecret secret, MerchantBranding b) {
        if (isPresent(secret.getMemo())) {
            return secret.getMemo();
        }
        if (isPresent(b.storeDescription())) {
            return b.storeDescription();
        }
        return DEFAULT_DESCRIPTION;
    }

    private static String organizationName(VoucherSecret secret, MerchantBranding b) {
        return isPresent(b.organizationName()) ? b.organizationName() : secret.getIssuerId();
    }

    /**
     * Provenance shown on the back of the card.
     *
     * <p>Deliberately excludes {@code issuer_sig}: surfacing a signature in a UI
     * invites treatment as a credential. {@code backing_strategy}, {@code issuance_ratio}
     * and {@code merchant_metadata} are excluded too — none has display meaning.
     */
    private static List<PassJson.Field> backFields(VoucherSecret secret) {
        return List.of(
                PassJson.Field.of("voucherId", "Voucher ID", String.valueOf(secret.getVoucherId())),
                PassJson.Field.of("issuer", "Issuer", secret.getIssuerId()),
                PassJson.Field.of("issuerKey", "Issuer Public Key", secret.getIssuerPublicKey()),
                PassJson.Field.of("terms", "Terms", TERMS));
    }

    /**
     * The balance field, as a {@link BigDecimal} in major units under the ISO 4217 code.
     *
     * <p>{@code pass.json} formats currency in major units, so scaling here is
     * correctness, not presentation. A {@code BigDecimal} keeps the scale exact
     * when it serialises to a JSON number.
     */
    private static PassJson.Field balanceField(VoucherSecret secret) {
        Currency currency = currency(secret.getUnit());
        long faceValue = faceValue(secret);
        int decimals = faceDecimals(secret, currency);

        return new PassJson.Field(
                "balance",
                "BALANCE",
                BigDecimal.valueOf(faceValue, decimals),
                currency.getCurrencyCode(),
                null);
    }

    /**
     * Validates that the voucher's unit is ISO 4217. {@link Currency#getInstance}
     * throws on an unknown code, so no separate lookup table is needed.
     *
     * <p>Cashu writes units lowercase; ISO 4217 codes are uppercase, so we upper-case
     * with {@link Locale#ROOT} to avoid the Turkish dotless-i locale bug.
     */
    private static Currency currency(String unit) {
        if (!isPresent(unit)) {
            throw new IllegalArgumentException("Voucher has no unit; expected an ISO 4217 code");
        }
        try {
            return Currency.getInstance(unit.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid ISO 4217 currency: " + unit, e);
        }
    }

    private static long faceValue(VoucherSecret secret) {
        Long faceValue = secret.getFaceValue();
        if (faceValue == null) {
            throw new IllegalArgumentException("Voucher has no face value");
        }
        if (faceValue < 0) {
            throw new IllegalArgumentException("Voucher face value is negative: " + faceValue);
        }
        return faceValue;
    }

    /**
     * Warns when {@code face_decimals} disagrees with the currency's default, but
     * trusts {@code face_decimals} anyway.
     *
     * <p>It is the issuer-signed value the rest of the stack accepts as valid;
     * a display mapper is the wrong layer to overrule it. The warning surfaces bad
     * minting without letting a cosmetic concern break rendering.
     */
    private static int faceDecimals(VoucherSecret secret, Currency currency) {
        int decimals = secret.getFaceDecimals();
        int expected = currency.getDefaultFractionDigits();
        if (expected >= 0 && decimals != expected) {
            log.warn("voucher_pass face_decimals_mismatch currency={} declared={} expected={}",
                    currency.getCurrencyCode(), decimals, expected);
        }
        return decimals;
    }

    static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
