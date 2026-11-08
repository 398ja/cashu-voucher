package xyz.tcheeric.cashu.voucher.domain.ledger;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * One voucher ledger v2 entry: the plaintext of an event.
 *
 * @param status {@code ISSUED}, {@code REDEEMED} or {@code REVOKED}; never {@code EXPIRED}
 * @param voucher the voucher's fields
 * @param doc the voucher document (signed CBOR blob of an unlocked voucher), or {@code null}
 */
public record LedgerEntry(xyz.tcheeric.cashu.voucher.domain.VoucherStatus status,
                          VoucherFields voucher,
                          byte[] doc) {

    public LedgerEntry {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(voucher, "voucher");
        if (status == xyz.tcheeric.cashu.voucher.domain.VoucherStatus.EXPIRED) {
            throw new LedgerRefusedException(LedgerRefusal.STATUS_REFUSED,
                    "EXPIRED is never published: anyone holding ref can read the expiry");
        }
        doc = doc == null || doc.length == 0 ? null : doc.clone();
    }

    /** 1 for {@code ISSUED}, 2 for {@code REDEEMED} and {@code REVOKED}. */
    public int seq() {
        return seqOf(status);
    }

    @Override
    public byte[] doc() {
        return doc == null ? null : doc.clone();
    }

    public Optional<byte[]> docBytes() {
        return Optional.ofNullable(doc());
    }

    /** The same entry without its document. */
    public LedgerEntry withoutDoc() {
        return new LedgerEntry(status, voucher, null);
    }

    static int seqOf(xyz.tcheeric.cashu.voucher.domain.VoucherStatus status) {
        return status == xyz.tcheeric.cashu.voucher.domain.VoucherStatus.ISSUED ? 1 : 2;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LedgerEntry that && status == that.status
                && voucher.equals(that.voucher) && Arrays.equals(doc, that.doc);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, voucher, Arrays.hashCode(doc));
    }

    @Override
    public String toString() {
        return "LedgerEntry[status=" + status + ", voucher=" + voucher
                + ", doc=" + (doc == null ? "none" : doc.length + " bytes") + "]";
    }
}
