package xyz.tcheeric.cashu.voucher.app.adapter;

import lombok.NonNull;
import xyz.tcheeric.cashu.voucher.app.ports.IssuerKeyRegistry;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * An {@link IssuerKeyRegistry} backed by a fixed map of issuer id to public key.
 *
 * <p>Enough for the common case: a merchant application that knows its own issuer id and signing
 * key from configuration. Deployments serving many issuers should implement the port against
 * whatever already holds that mapping rather than growing this class.
 *
 * <p>Issuer ids are matched case-insensitively, and keys are compared that way too, because hex
 * is written both ways in practice and a case difference is not a different key.
 */
public final class MapIssuerKeyRegistry implements IssuerKeyRegistry {

    private final Map<String, String> keysByIssuerId;

    public MapIssuerKeyRegistry(@NonNull Map<String, String> keysByIssuerId) {
        this.keysByIssuerId = keysByIssuerId.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        e -> e.getKey().toLowerCase(Locale.ROOT),
                        Map.Entry::getValue));
    }

    /** A registry holding a single issuer, the usual shape for one merchant. */
    public static MapIssuerKeyRegistry of(@NonNull String issuerId, @NonNull String publicKeyHex) {
        return new MapIssuerKeyRegistry(Map.of(issuerId, publicKeyHex));
    }

    @Override
    public Optional<String> publicKeyFor(final String issuerId) {
        if (issuerId == null || issuerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(keysByIssuerId.get(issuerId.toLowerCase(Locale.ROOT)));
    }
}
