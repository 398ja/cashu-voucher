package xyz.tcheeric.cashu.voucher.pass;

import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;

import java.util.List;
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
                null,
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

    static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
