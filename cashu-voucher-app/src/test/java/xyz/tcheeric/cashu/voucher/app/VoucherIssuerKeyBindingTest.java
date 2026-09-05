package xyz.tcheeric.cashu.voucher.app;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.voucher.app.adapter.MapIssuerKeyRegistry;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A voucher must not certify itself.
 *
 * <p>Offline verification checked the issuer signature against the {@code issuer_pubkey} tag
 * carried by the voucher, and matched the issuer id as a plain string (audit H-14). Both are
 * fields the person building the voucher fills in, so the check amounted to "is this voucher
 * internally consistent?" rather than "did this merchant issue it?". An attacker generates a
 * keypair, writes the merchant's issuer id into the voucher, signs with their own key, and
 * offline verification passes. Redemption accepts offline verification whenever the request asks
 * for it, so a merchant redeeming without connectivity would honour the forgery.
 *
 * <p>The fix binds the issuer id to a registered key. These tests pin that binding.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("A voucher signed by an unregistered key is rejected")
class VoucherIssuerKeyBindingTest {

    private static final String ISSUER_ID = "merchant123";
    private static final String UNIT = "sat";
    private static final long AMOUNT = 10_000L;

    private static String merchantPrivKey;
    private static String merchantPubKey;
    private static String attackerPrivKey;
    private static String attackerPubKey;

    @Mock
    private VoucherLedgerPort ledgerPort;

    private MerchantVerificationService service;

    @BeforeAll
    static void generateKeys() {
        byte[] merchantPriv = Schnorr.generatePrivateKey();
        merchantPrivKey = Hex.toHexString(merchantPriv);
        merchantPubKey = Hex.toHexString(Schnorr.genPubKey(merchantPriv));

        byte[] attackerPriv = Schnorr.generatePrivateKey();
        attackerPrivKey = Hex.toHexString(attackerPriv);
        attackerPubKey = Hex.toHexString(Schnorr.genPubKey(attackerPriv));
    }

    @BeforeEach
    void setUp() {
        service = new MerchantVerificationService(ledgerPort,
                MapIssuerKeyRegistry.of(ISSUER_ID, merchantPubKey));
    }

    private static SignedVoucher voucherSignedBy(String privKey, String pubKey) {
        VoucherSecret secret = VoucherSecret.builder()
                .issuerId(ISSUER_ID)
                .unit(UNIT)
                .faceValue(AMOUNT)
                .backingStrategy(BackingStrategy.FIXED.name())
                .issuanceRatio(1.0)
                .faceDecimals(0)
                .build();
        return VoucherSignatureService.createSigned(secret, privKey, pubKey);
    }

    @Test
    @DisplayName("a voucher self-signed by an attacker claiming the merchant's issuer id is rejected")
    void selfSignedForgeryIsRejected() {
        // Perfectly well-formed: the signature verifies under the key the voucher carries, and
        // the issuer id is the merchant's. Only the key is wrong.
        SignedVoucher forged = voucherSignedBy(attackerPrivKey, attackerPubKey);

        MerchantVerificationService.VerificationResult result =
                service.verifyOffline(forged, ISSUER_ID);

        assertThat(result.isValid())
                .as("a voucher signed by a key the merchant never registered must not verify")
                .isFalse();
        assertThat(result.getErrorMessage()).contains("not registered");
    }

    @Test
    @DisplayName("the forged voucher's own signature really is internally valid")
    void forgeryIsInternallyConsistent() {
        // Pins that the previous test is testing the key binding and not some other defect: on
        // its own terms the forgery is a properly signed voucher, which is exactly why checking
        // it against itself was never enough.
        SignedVoucher forged = voucherSignedBy(attackerPrivKey, attackerPubKey);

        assertThat(forged.verify())
                .as("the attacker holds the key named in the voucher, so the signature checks out")
                .isTrue();
    }

    @Test
    @DisplayName("a genuine voucher from the registered key still verifies")
    void genuineVoucherStillVerifies() {
        SignedVoucher genuine = voucherSignedBy(merchantPrivKey, merchantPubKey);

        MerchantVerificationService.VerificationResult result =
                service.verifyOffline(genuine, ISSUER_ID);

        assertThat(result.isValid())
                .as("a voucher the merchant really issued must still redeem: " + result.getErrorMessage())
                .isTrue();
    }

    @Test
    @DisplayName("an issuer with no registered key is rejected rather than trusted")
    void unknownIssuerIsRejected() {
        MerchantVerificationService unknownIssuerService =
                new MerchantVerificationService(ledgerPort,
                        MapIssuerKeyRegistry.of("some-other-merchant", merchantPubKey));
        SignedVoucher genuine = voucherSignedBy(merchantPrivKey, merchantPubKey);

        MerchantVerificationService.VerificationResult result =
                unknownIssuerService.verifyOffline(genuine, ISSUER_ID);

        assertThat(result.isValid())
                .as("absence of a registered key is not permission to trust the voucher's own key")
                .isFalse();
        assertThat(result.getErrorMessage()).contains("No registered signing key");
    }
}
