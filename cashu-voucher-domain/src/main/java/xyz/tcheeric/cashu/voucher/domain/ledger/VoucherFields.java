package xyz.tcheeric.cashu.voucher.domain.ledger;

import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.SignedLockedVoucher;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;

import java.util.Objects;

/**
 * The {@code voucher} object of a ledger v2 body. {@code voucherId} and {@code issuerId} are
 * required and held in their normalised form (the form that went into {@code ref}); every other
 * field is optional and {@code null} when unknown. Empty strings are treated as unknown.
 */
public record VoucherFields(String voucherId,
                            String issuerId,
                            String issuerPublicKey,
                            String unit,
                            Long faceValue,
                            Long faceDecimals,
                            Long expiresAt,
                            String memo,
                            String backingStrategy,
                            String issuanceRatio,
                            String merchantMetadata,
                            String issuanceWarrant) {

    public VoucherFields {
        voucherId = VoucherLedgerRef.normaliseVoucherId(voucherId);
        issuerId = VoucherLedgerRef.normaliseIssuerId(issuerId);
        issuerPublicKey = blankToNull(issuerPublicKey);
        unit = blankToNull(unit);
        memo = blankToNull(memo);
        backingStrategy = blankToNull(backingStrategy);
        issuanceRatio = blankToNull(issuanceRatio);
        merchantMetadata = blankToNull(merchantMetadata);
        issuanceWarrant = blankToNull(issuanceWarrant);
    }

    /** The ledger ref these fields belong to. */
    public byte[] ref() {
        return VoucherLedgerRef.ref(issuerId, voucherId);
    }

    /** True when the voucher is a terminal credential, which is never published. */
    public boolean isTerminalCredential() {
        return VoucherLedgerV2.isTerminalCredential(merchantMetadata);
    }

    /** Fields of an unlocked voucher secret. */
    public static VoucherFields of(VoucherSecret s) {
        Objects.requireNonNull(s, "secret");
        return new VoucherFields(
                s.getVoucherId() == null ? null : s.getVoucherId().toString(),
                s.getIssuerId(),
                s.getIssuerPublicKey(),
                s.getUnit(),
                s.getFaceValue(),
                (long) s.getFaceDecimals(),
                s.getExpiresAt(),
                s.getMemo(),
                s.getBackingStrategy(),
                VoucherSecret.canonicalIssuanceRatio(s.getIssuanceRatio()),
                s.getMerchantMetadata(),
                s.getIssuanceWarrant());
    }

    /** Fields of a P2PK-locked voucher secret. It carries no issuance warrant. */
    public static VoucherFields of(P2PKVoucherSecret s) {
        Objects.requireNonNull(s, "secret");
        return new VoucherFields(
                s.getVoucherId(),
                s.getIssuerId(),
                s.getIssuerPublicKey(),
                s.getUnit(),
                s.getFaceValue(),
                (long) s.getFaceDecimals(),
                s.getExpiresAt(),
                s.getMemo(),
                s.getBackingStrategy(),
                VoucherSecret.canonicalIssuanceRatio(s.getIssuanceRatio()),
                s.getMerchantMetadata(),
                null);
    }

    public static VoucherFields of(SignedVoucher voucher) {
        return of(Objects.requireNonNull(voucher, "voucher").getSecret());
    }

    public static VoucherFields of(SignedLockedVoucher voucher) {
        return of(Objects.requireNonNull(voucher, "voucher").getSecret());
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
