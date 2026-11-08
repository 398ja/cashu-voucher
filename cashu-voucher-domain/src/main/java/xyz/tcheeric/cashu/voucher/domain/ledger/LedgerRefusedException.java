package xyz.tcheeric.cashu.voucher.domain.ledger;

/** A voucher ledger v2 event or entry was refused, for the {@link #reason()} given. */
public class LedgerRefusedException extends RuntimeException {

    private final LedgerRefusal reason;

    public LedgerRefusedException(LedgerRefusal reason, String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }

    public LedgerRefusedException(LedgerRefusal reason, String message, Throwable cause) {
        super(reason + ": " + message, cause);
        this.reason = reason;
    }

    public LedgerRefusal reason() {
        return reason;
    }
}
