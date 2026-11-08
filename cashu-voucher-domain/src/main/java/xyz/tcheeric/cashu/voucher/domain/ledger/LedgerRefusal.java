package xyz.tcheeric.cashu.voucher.domain.ledger;

/**
 * Why a voucher ledger v2 event or entry was refused. The names are part of the golden vector
 * file ({@code voucher-ledger-v2-vectors.json}) so another implementation can assert the same
 * outcome.
 */
public enum LedgerRefusal {
    /** Not kind 30078, or not exactly one {@code d} tag equal to the expected locator. */
    NOT_THIS_ADDRESS,
    /** NIP-44 decryption failed (bad version, length, MAC or padding). */
    UNDECRYPTABLE,
    /** The envelope is not the strict four-key JSON object. */
    MALFORMED_ENVELOPE,
    /** A JSON object repeats a key. */
    DUPLICATE_KEY,
    /** A JSON object carries a key the spec does not define. */
    UNKNOWN_KEY,
    /** The inner BIP-340 signature does not verify against {@code signer}. */
    BAD_SIGNATURE,
    /** The body is not the strict JSON object of the spec (types, required fields, encodings). */
    MALFORMED_BODY,
    /** {@code body.v != 2}. */
    WRONG_VERSION,
    /** {@code body.ref} or the voucher's own ids do not derive the expected ref. */
    REF_MISMATCH,
    /** {@code EXPIRED} or an unknown status. */
    STATUS_REFUSED,
    /** {@code seq} does not match {@code status}. */
    SEQ_MISMATCH,
    /** {@code signer} is not authorised for the status. */
    UNAUTHORISED_SIGNER,
    /** {@code doc} verifies, but the body's voucher fields are not exactly the doc's. */
    DOC_MISMATCH,
    /** Writer side: a terminal credential is never published. */
    TERMINAL_CREDENTIAL,
    /** Writer side: the envelope does not fit 65535 bytes. */
    TOO_LARGE,
    /** More events at one locator than a reader will process; the answer is refused, not truncated. */
    TOO_MANY_CANDIDATES
}
