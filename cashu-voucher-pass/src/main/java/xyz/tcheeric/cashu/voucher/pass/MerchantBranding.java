package xyz.tcheeric.cashu.voucher.pass;

/**
 * Merchant branding resolved at render time and passed to {@link VoucherPassMapper}.
 *
 * <p>Branding is deliberately <em>not</em> read from the voucher. Branding is mutable
 * and a signed voucher is not, so a value baked into the signed secret at issuance
 * would leave every outstanding voucher showing stale branding or needing re-issuance.
 *
 * <p>The caller populates this from the merchant's existing Nostr identity, served by
 * {@code GET /api/v1/merchant/bootstrap}:
 * <ul>
 *   <li>{@code organizationName} — kind-0 {@code name}</li>
 *   <li>{@code logoUrl} — kind-0 {@code picture}</li>
 *   <li>{@code bannerUrl} — kind-0 {@code banner}</li>
 *   <li>{@code storeDescription} — kind-30078 {@code d=imani:merchant}</li>
 * </ul>
 *
 * <p>Do not use {@code businessName} from the merchant profile — possa-merchant is
 * removing it. kind-0 {@code name} is the live field.
 *
 * <p>Every field is nullable; {@link VoucherPassMapper} defaults each one.
 */
public record MerchantBranding(
        String organizationName,
        String logoUrl,
        String bannerUrl,
        String storeDescription,
        String backgroundColor,
        String foregroundColor
) {

    private static final MerchantBranding EMPTY =
            new MerchantBranding(null, null, null, null, null, null);

    /**
     * Branding with no values set. Equivalent to passing {@code null} to the mapper.
     *
     * @return a branding instance with every field null
     */
    public static MerchantBranding empty() {
        return EMPTY;
    }
}
