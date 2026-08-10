package xyz.tcheeric.cashu.voucher.pass;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    static final String DATE_STYLE_MEDIUM = "PKDateStyleMedium";

    static final String DEFAULT_BACKGROUND_COLOR = "rgb(20,20,20)";
    static final String DEFAULT_FOREGROUND_COLOR = "rgb(255,255,255)";

    private VoucherPassMapper() {
    }

    /**
     * Renders a voucher as a store card, not voided.
     *
     * @param voucher the signed voucher; must not be null
     * @param branding merchant branding, or null for defaults
     * @return the pass document
     */
    public static PassJson toPass(SignedVoucher voucher, MerchantBranding branding) {
        return toPass(voucher, branding, null);
    }

    /**
     * Renders a voucher as a store card, voided according to ledger status.
     *
     * <p>{@link VoucherStatus} is not carried on {@link SignedVoucher} — it comes from
     * the ledger — so the caller resolves it. A null status means not voided.
     *
     * @param voucher the signed voucher; must not be null
     * @param branding merchant branding, or null for defaults
     * @param status ledger status, or null
     * @return the pass document
     */
    public static PassJson toPass(SignedVoucher voucher, MerchantBranding branding,
                                  VoucherStatus status) {
        Objects.requireNonNull(voucher, "voucher");
        MerchantBranding b = branding != null ? branding : MerchantBranding.empty();
        VoucherSecret secret = voucher.getSecret();
        String expirationDate = expirationDate(secret);

        PassJson.StoreCard storeCard = new PassJson.StoreCard(
                List.of(balanceField(secret)),
                expirationDate == null ? null : List.of(expiryField(expirationDate)),
                backFields(secret));

        return new PassJson(
                FORMAT_VERSION,
                PASS_TYPE_IDENTIFIER,
                TEAM_IDENTIFIER,
                String.valueOf(secret.getVoucherId()),
                description(secret, b),
                organizationName(secret, b),
                b.organizationName(),
                orDefault(b.backgroundColor(), DEFAULT_BACKGROUND_COLOR),
                orDefault(b.foregroundColor(), DEFAULT_FOREGROUND_COLOR),
                orDefault(b.foregroundColor(), DEFAULT_FOREGROUND_COLOR),
                expirationDate,
                isVoided(status),
                storeCard,
                null,
                userInfo(secret, b));
    }

    /** Epoch seconds to an ISO-8601 instant, or null when the voucher never expires. */
    private static String expirationDate(VoucherSecret secret) {
        Long expiresAt = secret.getExpiresAt();
        return expiresAt == null
                ? null
                : DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(expiresAt));
    }

    private static PassJson.Field expiryField(String expirationDate) {
        return new PassJson.Field("expires", "EXPIRES", expirationDate, null, DATE_STYLE_MEDIUM);
    }

    /**
     * A pass is voided once the voucher can no longer be spent. {@code EXPIRED} is
     * excluded deliberately: {@code expirationDate} already communicates it, and
     * renderers grey the card out on that alone.
     */
    private static boolean isVoided(VoucherStatus status) {
        return status == VoucherStatus.REDEEMED || status == VoucherStatus.REVOKED;
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

    private static String orDefault(String value, String fallback) {
        return isPresent(value) ? value : fallback;
    }

    /**
     * The app-private dictionary our renderer reads.
     *
     * <p>{@code pass.json} has no image fields — Apple carries images as files inside
     * the {@code .pkpass} bundle, referenced by filename convention. We emit JSON only,
     * so branding URLs live here. Absent URLs are omitted rather than stored as nulls.
     *
     * <p>{@code voucherId} links the card to whichever proofs the wallet already holds.
     * No bearer secret is ever placed in a pass.
     */
    private static Map<String, Object> userInfo(VoucherSecret secret, MerchantBranding b) {
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("voucherId", String.valueOf(secret.getVoucherId()));
        if (isPresent(b.logoUrl())) {
            userInfo.put("logoUrl", b.logoUrl());
        }
        if (isPresent(b.bannerUrl())) {
            userInfo.put("stripUrl", b.bannerUrl());
        }
        return Map.copyOf(userInfo);
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
