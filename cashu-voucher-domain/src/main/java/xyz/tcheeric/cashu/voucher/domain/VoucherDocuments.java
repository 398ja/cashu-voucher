package xyz.tcheeric.cashu.voucher.domain;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Voucher documents referenced by hash from an unlocked voucher proof (cashu-voucher#64).
 *
 * <p>A v1 (inline) unlocked {@code VOUCHER} proof carries the signed voucher document
 * {@code D} in {@code data}. A v2 (by reference) proof carries {@code SHA256(D)} instead, and
 * the document travels once beside the proofs, in a map keyed by {@code hex(SHA256(D))}. See
 * {@code docs/explanation/voucher-by-reference.md}.
 *
 * <p>Resolution turns a v2 secret back into exactly the v1 secret of the same voucher (same
 * kind, same nonce, {@code data = D}, no tags), so every existing check runs unchanged on
 * it. Nothing here throws on untrusted input: a missing or wrong document is an answer.
 */
public final class VoucherDocuments {

    /** Largest document accepted, in bytes. */
    public static final int MAX_DOCUMENT_BYTES = 65_536;

    /**
     * Largest total of document bytes one token, request or payload may carry. With up to 1000
     * proofs per container, the per-document bound alone would allow 64 MiB.
     */
    public static final int MAX_TOTAL_DOCUMENT_BYTES = 1_048_576;

    /** Length of a by-reference {@code data}: one SHA-256. */
    public static final int REFERENCE_BYTES = 32;

    private static final Pattern KEY = Pattern.compile("^[0-9a-f]{64}$");
    private static final HexFormat HEX = HexFormat.of();

    /** Test seam: number of document hashes computed (cashu-voucher#64, review M2). */
    static final AtomicLong HASHES = new AtomicLong();

    private VoucherDocuments() {
    }

    /** The map key for a document: lowercase {@code hex(SHA256(doc))}. */
    public static String key(@NonNull byte[] document) {
        return HEX.formatHex(sha256(document));
    }

    /**
     * Whether this secret references its document by hash: kind {@code VOUCHER}, no tags,
     * and {@code data} of exactly 32 bytes. A real document always holds a 36-character
     * voucher id, so no v1 proof matches.
     */
    public static boolean isByReference(WellKnownSecret secret) {
        if (secret == null || secret.getKind() != WellKnownSecret.Kind.VOUCHER) {
            return false;
        }
        if (secret.getTags() != null && !secret.getTags().isEmpty()) {
            return false;
        }
        byte[] data = secret.getData();
        return data != null && data.length == REFERENCE_BYTES;
    }

    /**
     * The reasons a document map must be refused, or an empty list when it is acceptable.
     *
     * <p>Refused: a key that is not 64 lowercase hex characters or not {@code hex(SHA256(value))},
     * an empty or null value, a value over {@link #MAX_DOCUMENT_BYTES}, values adding up to more
     * than {@link #MAX_TOTAL_DOCUMENT_BYTES}, or more entries than {@code maxEntries} (the number
     * of proofs or inputs). A null map is acceptable (no documents).
     */
    public static List<String> validate(Map<String, byte[]> documents, int maxEntries) {
        List<String> errors = new ArrayList<>();
        if (documents == null) {
            return errors;
        }
        if (documents.size() > Math.max(0, maxEntries)) {
            errors.add("Too many voucher documents: " + documents.size() + " > " + Math.max(0, maxEntries));
        }
        // Sizes before hashes, so an oversized map costs no hashing.
        long total = 0;
        for (byte[] value : documents.values()) {
            total += value == null ? 0 : value.length;
        }
        if (total > MAX_TOTAL_DOCUMENT_BYTES) {
            errors.add("Voucher documents exceed " + MAX_TOTAL_DOCUMENT_BYTES + " bytes in total");
            return errors;
        }
        for (Map.Entry<String, byte[]> entry : documents.entrySet()) {
            String key = entry.getKey();
            byte[] value = entry.getValue();
            if (key == null || !KEY.matcher(key).matches()) {
                errors.add("Voucher document key is not 64 lowercase hex characters");
                continue;
            }
            if (value == null || value.length == 0) {
                errors.add("Voucher document " + key + " is empty");
                continue;
            }
            if (value.length > MAX_DOCUMENT_BYTES) {
                errors.add("Voucher document " + key + " exceeds " + MAX_DOCUMENT_BYTES + " bytes");
                continue;
            }
            if (!key.equals(key(value))) {
                errors.add("Voucher document key " + key + " is not the SHA-256 of its value");
            }
        }
        return errors;
    }

    /**
     * The v1-equivalent secret of a by-reference secret, or empty when the secret is not by
     * reference, or its document is missing, oversized, or hashes to something else.
     */
    public static Optional<WellKnownSecret> resolve(WellKnownSecret secret, Map<String, byte[]> documents) {
        return resolveForVerification(secret, documents) instanceof Resolution.Resolved resolved
                ? Optional.of(resolved.secret())
                : Optional.empty();
    }

    /**
     * Classifies a proof secret for a verifier, such as the mint's spending condition.
     *
     * <ul>
     *   <li>{@link Resolution.NotByReference}: verify the secret as today.</li>
     *   <li>{@link Resolution.Resolved}: verify {@code secret()} as today, in place of the original.</li>
     *   <li>{@link Resolution.Missing}: refuse, the voucher is unverifiable. Never treat as plain sats.</li>
     *   <li>{@link Resolution.Mismatch}: refuse, the document does not match the reference.</li>
     * </ul>
     */
    public static Resolution resolveForVerification(WellKnownSecret secret, Map<String, byte[]> documents) {
        if (!isByReference(secret)) {
            return new Resolution.NotByReference();
        }
        String key = HEX.formatHex(secret.getData());
        byte[] document = documents == null ? null : documents.get(key);
        if (document == null || document.length == 0) {
            return new Resolution.Missing(key);
        }
        if (document.length > MAX_DOCUMENT_BYTES || !key.equals(key(document))) {
            return new Resolution.Mismatch(key);
        }
        return new Resolution.Resolved(key, resolvedSecret(secret, document));
    }

    /**
     * Validates a document map once and returns a value that resolves proofs by lookup only.
     *
     * <p>Each document is hashed exactly once, here. When {@link Validated#errors()} is not
     * empty the map must be refused and {@link Validated#resolve} throws.
     */
    public static Validated validated(Map<String, byte[]> documents, int maxEntries) {
        List<String> errors = validate(documents, maxEntries);
        Map<String, byte[]> copy = new HashMap<>();
        if (errors.isEmpty() && documents != null) {
            documents.forEach((k, v) -> copy.put(k, v.clone()));
        }
        return new Validated(List.copyOf(errors), copy);
    }

    /** A document map whose every entry has been hash-checked by {@link #validated}. */
    public static final class Validated {
        private final List<String> errors;
        private final Map<String, byte[]> documents;

        private Validated(List<String> errors, Map<String, byte[]> documents) {
            this.errors = errors;
            this.documents = documents;
        }

        /** Why the map was refused, or empty when every entry matched its key. */
        public List<String> errors() {
            return errors;
        }

        /**
         * Like {@link VoucherDocuments#resolveForVerification}, without re-hashing: every entry
         * already hashes to its key. Never answers {@link Resolution.Mismatch}.
         *
         * @throws IllegalStateException when the map was refused
         */
        public Resolution resolve(WellKnownSecret secret) {
            if (!errors.isEmpty()) {
                throw new IllegalStateException("Voucher documents were refused: " + errors);
            }
            if (!isByReference(secret)) {
                return new Resolution.NotByReference();
            }
            String key = HEX.formatHex(secret.getData());
            byte[] document = documents.get(key);
            if (document == null) {
                return new Resolution.Missing(key);
            }
            return new Resolution.Resolved(key, resolvedSecret(secret, document));
        }
    }

    private static VoucherSecret resolvedSecret(WellKnownSecret secret, byte[] document) {
        VoucherSecret resolved = new VoucherSecret();
        resolved.setData(document.clone());
        resolved.setNonce(secret.getNonce());
        return resolved;
    }

    /** Outcome of {@link #resolveForVerification}. */
    public sealed interface Resolution {

        /** An inline voucher, a locked voucher, or not a voucher: nothing to resolve. */
        record NotByReference() implements Resolution {
        }

        /** The document was present and matched; {@code secret} is the v1 form. */
        record Resolved(String key, WellKnownSecret secret) implements Resolution {
        }

        /** The referenced document was not supplied. */
        record Missing(String key) implements Resolution {
        }

        /** A document was supplied under the key but does not hash to it, or is oversized. */
        record Mismatch(String key) implements Resolution {
        }
    }

    private static byte[] sha256(byte[] input) {
        HASHES.incrementAndGet();
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
