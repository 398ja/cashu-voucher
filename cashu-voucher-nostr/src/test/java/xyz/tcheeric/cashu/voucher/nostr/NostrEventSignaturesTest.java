package xyz.tcheeric.cashu.voucher.nostr;

import nostr.event.impl.GenericEvent;
import nostr.id.Identity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A ledger event is only evidence if it is signed by the issuer.
 *
 * <p>The voucher ledger read path took the status straight from whatever a relay returned, with
 * no signature or author check (audit H-13), and the publish path wrote events unsigned. A relay
 * is an untrusted transport: anyone can publish a kind-30078 event carrying a
 * {@code d=voucher:<id>} tag. Since the online double-spend check reads its answer from those
 * events, a hostile or compromised relay could flip a REDEEMED voucher back to ACTIVE and let it
 * be redeemed twice.
 *
 * <p>These tests cover the verifier the read path now runs over every event.
 */
@DisplayName("Nostr event signature verification")
class NostrEventSignaturesTest {

    private static GenericEvent signedBy(Identity identity) {
        GenericEvent event = new GenericEvent();
        event.setPubKey(identity.getPublicKey());
        event.setKind(30078);
        event.setContent("ledger event");
        event.setCreatedAt(System.currentTimeMillis() / 1000);
        event.update(); // computes the NIP-01 id over the canonical serialisation
        event.setSignature(identity.sign(event));
        return event;
    }

    @Test
    @DisplayName("an event signed by its author verifies")
    void genuineEventVerifies() {
        Identity issuer = Identity.generateRandomIdentity();

        GenericEvent event = signedBy(issuer);

        assertThat(NostrEventSignatures.verify(event))
                .as("an event the issuer really signed must verify")
                .isTrue();
    }

    @Test
    @DisplayName("an unsigned event does not verify")
    void unsignedEventIsRejected() {
        Identity issuer = Identity.generateRandomIdentity();
        GenericEvent event = new GenericEvent();
        event.setPubKey(issuer.getPublicKey());
        event.setKind(30078);
        event.setContent("ledger event");
        event.setCreatedAt(System.currentTimeMillis() / 1000);
        event.update();

        assertThat(NostrEventSignatures.verify(event))
                .as("publishing unsigned was the original defect; reading unsigned must fail")
                .isFalse();
    }

    @Test
    @DisplayName("an event whose pubkey has been swapped does not verify")
    void swappedAuthorIsRejected() {
        Identity issuer = Identity.generateRandomIdentity();
        Identity attacker = Identity.generateRandomIdentity();

        // The attacker takes a genuine event and relabels it as the issuer's. The signature no
        // longer matches the claimed author, which is the whole point of checking it.
        GenericEvent event = signedBy(attacker);
        event.setPubKey(issuer.getPublicKey());

        assertThat(NostrEventSignatures.verify(event))
                .as("a signature must be checked against the claimed author, not assumed")
                .isFalse();
    }

    @Test
    @DisplayName("an event signed over different content does not verify")
    void tamperedIdIsRejected() {
        Identity issuer = Identity.generateRandomIdentity();
        GenericEvent genuine = signedBy(issuer);

        // Reuse a valid signature on an event with a different id.
        GenericEvent tampered = new GenericEvent();
        tampered.setPubKey(issuer.getPublicKey());
        tampered.setKind(30078);
        tampered.setContent("a different ledger event");
        tampered.setCreatedAt(genuine.getCreatedAt());
        tampered.update();
        tampered.setSignature(genuine.getSignature());

        assertThat(NostrEventSignatures.verify(tampered))
                .as("a signature lifted from another event must not verify")
                .isFalse();
    }
}
