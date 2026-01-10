package xyz.tcheeric.cashu.voucher.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.cashu.common.PaymentPayloadProof;
import xyz.tcheeric.cashu.common.VoucherPaymentPayload;
import xyz.tcheeric.cashu.common.VoucherPaymentRequest;
import xyz.tcheeric.cashu.common.VoucherTransport;
import xyz.tcheeric.cashu.voucher.app.dto.GeneratePaymentRequestDTO;
import xyz.tcheeric.cashu.voucher.app.dto.GeneratePaymentRequestResponse;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherBackupPort;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for NUT-18V VoucherPaymentRequest support in cashu-voucher.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NUT-18V Integration")
class NUT18VIntegrationTest {

    private static final String ISSUER_ID = "test-merchant-123";
    private static final String SAMPLE_E = "e".repeat(64);
    private static final String SAMPLE_S = "a".repeat(64);
    private static final String SAMPLE_R = "b".repeat(64);

    @Mock
    private VoucherLedgerPort ledgerPort;

    @Mock
    private VoucherBackupPort backupPort;

    private VoucherService voucherService;
    private MerchantVerificationService verificationService;

    // Use known test keys
    private static final String TEST_PRIVATE_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String TEST_PUBLIC_KEY = "02" + "ab".repeat(32);

    @BeforeEach
    void setUp() {
        voucherService = new VoucherService(ledgerPort, backupPort, TEST_PRIVATE_KEY, TEST_PUBLIC_KEY);
        verificationService = new MerchantVerificationService(ledgerPort);
    }

    @Nested
    @DisplayName("VoucherService.generatePaymentRequest")
    class GeneratePaymentRequestTests {

        @Test
        void shouldGenerateMinimalPaymentRequest() {
            GeneratePaymentRequestDTO dto = GeneratePaymentRequestDTO.builder()
                    .issuerId(ISSUER_ID)
                    .build();

            GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(dto);

            assertThat(response.getEncodedRequest()).startsWith("vreqA");
            assertThat(response.getIssuerId()).isEqualTo(ISSUER_ID);
            assertThat(response.getPaymentId()).isNotNull();
            assertThat(response.getRequest()).isNotNull();
        }

        @Test
        void shouldGeneratePaymentRequestWithAmount() {
            GeneratePaymentRequestDTO dto = GeneratePaymentRequestDTO.builder()
                    .issuerId(ISSUER_ID)
                    .amount(5000)
                    .unit("sat")
                    .description("Coffee purchase")
                    .build();

            GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(dto);

            assertThat(response.getAmount()).isEqualTo(5000);
            assertThat(response.getUnit()).isEqualTo("sat");
            assertThat(response.getRequest().getDescription()).isEqualTo("Coffee purchase");
        }

        @Test
        void shouldGenerateClickableUri() {
            GeneratePaymentRequestDTO dto = GeneratePaymentRequestDTO.builder()
                    .issuerId(ISSUER_ID)
                    .clickable(true)
                    .build();

            GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(dto);

            assertThat(response.getEncodedRequest()).startsWith("cashu:vreqA");
        }

        @Test
        void shouldGeneratePaymentRequestWithMerchantTransport() {
            GeneratePaymentRequestDTO dto = GeneratePaymentRequestDTO.builder()
                    .issuerId(ISSUER_ID)
                    .includeMerchantTransport(true)
                    .build();

            GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(dto);

            assertThat(response.getRequest().getTransports()).hasSize(1);
            VoucherTransport transport = response.getRequest().getTransports().get(0);
            assertThat(transport.isMerchant()).isTrue();
        }

        @Test
        void shouldGeneratePaymentRequestWithHttpPostTransport() {
            GeneratePaymentRequestDTO dto = GeneratePaymentRequestDTO.builder()
                    .issuerId(ISSUER_ID)
                    .callbackUrl("https://merchant.example.com/callback")
                    .includeMerchantTransport(false)
                    .build();

            GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(dto);

            assertThat(response.getRequest().getTransports()).hasSize(1);
            VoucherTransport transport = response.getRequest().getTransports().get(0);
            assertThat(transport.isHttpPost()).isTrue();
            assertThat(transport.getTarget()).isEqualTo("https://merchant.example.com/callback");
        }

        @Test
        void shouldGeneratePaymentRequestWithMultipleTransports() {
            GeneratePaymentRequestDTO dto = GeneratePaymentRequestDTO.builder()
                    .issuerId(ISSUER_ID)
                    .includeMerchantTransport(true)
                    .callbackUrl("https://merchant.example.com/callback")
                    .nostrNprofile("nprofile1abc")
                    .build();

            GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(dto);

            assertThat(response.getRequest().getTransports()).hasSize(3);
        }

        @Test
        void shouldUseProvidedPaymentId() {
            GeneratePaymentRequestDTO dto = GeneratePaymentRequestDTO.builder()
                    .issuerId(ISSUER_ID)
                    .paymentId("order-12345")
                    .build();

            GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(dto);

            assertThat(response.getPaymentId()).isEqualTo("order-12345");
        }

        @Test
        void shouldIncludeMints() {
            GeneratePaymentRequestDTO dto = GeneratePaymentRequestDTO.builder()
                    .issuerId(ISSUER_ID)
                    .mints(List.of("https://mint1.example.com", "https://mint2.example.com"))
                    .build();

            GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(dto);

            assertThat(response.getRequest().getMints()).hasSize(2);
        }

        @Test
        void shouldSetOfflineVerificationFlag() {
            GeneratePaymentRequestDTO dto = GeneratePaymentRequestDTO.builder()
                    .issuerId(ISSUER_ID)
                    .offlineVerification(true)
                    .build();

            GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(dto);

            assertThat(response.getRequest().requiresOfflineVerification()).isTrue();
        }

        @Test
        void shouldSetSingleUseFlag() {
            GeneratePaymentRequestDTO dto = GeneratePaymentRequestDTO.builder()
                    .issuerId(ISSUER_ID)
                    .singleUse(true)
                    .build();

            GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(dto);

            assertThat(response.getRequest().isSingleUseRequest()).isTrue();
        }

        @Test
        void shouldRejectMissingIssuerId() {
            GeneratePaymentRequestDTO dto = GeneratePaymentRequestDTO.builder()
                    .amount(100)
                    .unit("sat")
                    .build();

            assertThrows(IllegalArgumentException.class, () ->
                    voucherService.generatePaymentRequest(dto));
        }

        @Test
        void shouldRejectAmountWithoutUnit() {
            GeneratePaymentRequestDTO dto = GeneratePaymentRequestDTO.builder()
                    .issuerId(ISSUER_ID)
                    .amount(100)
                    .build();

            assertThrows(IllegalArgumentException.class, () ->
                    voucherService.generatePaymentRequest(dto));
        }
    }

    @Nested
    @DisplayName("MerchantVerificationService.validatePaymentPayload")
    class ValidatePaymentPayloadTests {

        @Test
        void shouldValidateMatchingPayload() {
            VoucherPaymentRequest request = VoucherPaymentRequest.builder()
                    .paymentId("pay-001")
                    .issuerId(ISSUER_ID)
                    .amount(1000)
                    .unit("sat")
                    .build();

            VoucherPaymentPayload payload = VoucherPaymentPayload.builder()
                    .id("pay-001")
                    .issuerId(ISSUER_ID)
                    .mint("https://mint.example.com")
                    .unit("sat")
                    .proofs(List.of(
                            PaymentPayloadProof.builder()
                                    .amount(1000)
                                    .keysetId("keyset1")
                                    .secret("secret1")
                                    .signature("sig1")
                                    .build()
                    ))
                    .build();

            MerchantVerificationService.VerificationResult result =
                    verificationService.validatePaymentPayload(payload, request);

            assertThat(result.isValid()).isTrue();
        }

        @Test
        void shouldRejectPaymentIdMismatch() {
            VoucherPaymentRequest request = VoucherPaymentRequest.builder()
                    .paymentId("pay-001")
                    .issuerId(ISSUER_ID)
                    .build();

            VoucherPaymentPayload payload = VoucherPaymentPayload.builder()
                    .id("pay-002")
                    .issuerId(ISSUER_ID)
                    .mint("https://mint.example.com")
                    .unit("sat")
                    .proofs(List.of())
                    .build();

            MerchantVerificationService.VerificationResult result =
                    verificationService.validatePaymentPayload(payload, request);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorMessage()).contains("Payment ID mismatch");
        }

        @Test
        void shouldRejectIssuerIdMismatch() {
            VoucherPaymentRequest request = VoucherPaymentRequest.builder()
                    .issuerId(ISSUER_ID)
                    .build();

            VoucherPaymentPayload payload = VoucherPaymentPayload.builder()
                    .id("pay-001")
                    .issuerId("wrong-issuer")
                    .mint("https://mint.example.com")
                    .unit("sat")
                    .proofs(List.of())
                    .build();

            MerchantVerificationService.VerificationResult result =
                    verificationService.validatePaymentPayload(payload, request);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorMessage()).contains("Issuer ID mismatch");
        }

        @Test
        void shouldRejectInsufficientAmount() {
            VoucherPaymentRequest request = VoucherPaymentRequest.builder()
                    .issuerId(ISSUER_ID)
                    .amount(1000)
                    .unit("sat")
                    .build();

            VoucherPaymentPayload payload = VoucherPaymentPayload.builder()
                    .id("pay-001")
                    .issuerId(ISSUER_ID)
                    .mint("https://mint.example.com")
                    .unit("sat")
                    .proofs(List.of(
                            PaymentPayloadProof.builder()
                                    .amount(500)
                                    .keysetId("keyset1")
                                    .secret("secret1")
                                    .signature("sig1")
                                    .build()
                    ))
                    .build();

            MerchantVerificationService.VerificationResult result =
                    verificationService.validatePaymentPayload(payload, request);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorMessage()).contains("Insufficient amount");
        }

        @Test
        void shouldAcceptExcessAmount() {
            VoucherPaymentRequest request = VoucherPaymentRequest.builder()
                    .issuerId(ISSUER_ID)
                    .amount(1000)
                    .unit("sat")
                    .build();

            VoucherPaymentPayload payload = VoucherPaymentPayload.builder()
                    .id("pay-001")
                    .issuerId(ISSUER_ID)
                    .mint("https://mint.example.com")
                    .unit("sat")
                    .proofs(List.of(
                            PaymentPayloadProof.builder()
                                    .amount(1500)
                                    .keysetId("keyset1")
                                    .secret("secret1")
                                    .signature("sig1")
                                    .build()
                    ))
                    .build();

            MerchantVerificationService.VerificationResult result =
                    verificationService.validatePaymentPayload(payload, request);

            assertThat(result.isValid()).isTrue();
        }

        @Test
        void shouldRejectUnpermittedMint() {
            VoucherPaymentRequest request = VoucherPaymentRequest.builder()
                    .issuerId(ISSUER_ID)
                    .mints(List.of("https://permitted-mint.example.com"))
                    .build();

            VoucherPaymentPayload payload = VoucherPaymentPayload.builder()
                    .id("pay-001")
                    .issuerId(ISSUER_ID)
                    .mint("https://other-mint.example.com")
                    .unit("sat")
                    .proofs(List.of())
                    .build();

            MerchantVerificationService.VerificationResult result =
                    verificationService.validatePaymentPayload(payload, request);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorMessage()).contains("not in permitted list");
        }

        @Test
        void shouldAcceptAnyMintWhenNoRestriction() {
            VoucherPaymentRequest request = VoucherPaymentRequest.builder()
                    .issuerId(ISSUER_ID)
                    .build();

            VoucherPaymentPayload payload = VoucherPaymentPayload.builder()
                    .id("pay-001")
                    .issuerId(ISSUER_ID)
                    .mint("https://any-mint.example.com")
                    .unit("sat")
                    .proofs(List.of())
                    .build();

            MerchantVerificationService.VerificationResult result =
                    verificationService.validatePaymentPayload(payload, request);

            assertThat(result.isValid()).isTrue();
        }

        @Test
        void shouldRequireDLEQForOfflineVerification() {
            VoucherPaymentRequest request = VoucherPaymentRequest.builder()
                    .issuerId(ISSUER_ID)
                    .offlineVerification(true)
                    .build();

            VoucherPaymentPayload payload = VoucherPaymentPayload.builder()
                    .id("pay-001")
                    .issuerId(ISSUER_ID)
                    .mint("https://mint.example.com")
                    .unit("sat")
                    .proofs(List.of(
                            PaymentPayloadProof.builder()
                                    .amount(100)
                                    .keysetId("keyset1")
                                    .secret("secret1")
                                    .signature("sig1")
                                    // No DLEQ
                                    .build()
                    ))
                    .build();

            MerchantVerificationService.VerificationResult result =
                    verificationService.validatePaymentPayload(payload, request);

            assertThat(result.isValid()).isFalse();
            assertThat(result.getErrorMessage()).contains("DLEQ");
        }

        @Test
        void shouldAcceptDLEQForOfflineVerification() {
            VoucherPaymentRequest request = VoucherPaymentRequest.builder()
                    .issuerId(ISSUER_ID)
                    .offlineVerification(true)
                    .build();

            VoucherPaymentPayload payload = VoucherPaymentPayload.builder()
                    .id("pay-001")
                    .issuerId(ISSUER_ID)
                    .mint("https://mint.example.com")
                    .unit("sat")
                    .proofs(List.of(
                            PaymentPayloadProof.builder()
                                    .amount(100)
                                    .keysetId("keyset1")
                                    .secret("secret1")
                                    .signature("sig1")
                                    .dleq(PaymentPayloadProof.PaymentPayloadDLEQ.builder()
                                            .e(SAMPLE_E).s(SAMPLE_S).r(SAMPLE_R).build())
                                    .build()
                    ))
                    .build();

            MerchantVerificationService.VerificationResult result =
                    verificationService.validatePaymentPayload(payload, request);

            assertThat(result.isValid()).isTrue();
        }
    }

    @Nested
    @DisplayName("Round-Trip Tests")
    class RoundTripTests {

        @Test
        void shouldRoundTripPaymentRequest() {
            // Generate request
            GeneratePaymentRequestDTO dto = GeneratePaymentRequestDTO.builder()
                    .issuerId(ISSUER_ID)
                    .paymentId("round-trip-001")
                    .amount(2500)
                    .unit("sat")
                    .description("Round-trip test")
                    .singleUse(true)
                    .mints(List.of("https://mint.example.com"))
                    .callbackUrl("https://merchant.example.com/callback")
                    .build();

            GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(dto);
            String encoded = response.getEncodedRequest();

            // Decode
            VoucherPaymentRequest decoded = VoucherPaymentRequest.deserialize(encoded);

            // Verify
            assertThat(decoded.getPaymentId()).isEqualTo("round-trip-001");
            assertThat(decoded.getIssuerId()).isEqualTo(ISSUER_ID);
            assertThat(decoded.getAmount()).isEqualTo(2500);
            assertThat(decoded.getUnit()).isEqualTo("sat");
            assertThat(decoded.getDescription()).isEqualTo("Round-trip test");
            assertThat(decoded.isSingleUseRequest()).isTrue();
            assertThat(decoded.getMints()).contains("https://mint.example.com");
        }

        @Test
        void shouldCompleteFullPaymentFlow() {
            // 1. Merchant generates payment request
            GeneratePaymentRequestDTO dto = GeneratePaymentRequestDTO.builder()
                    .issuerId(ISSUER_ID)
                    .paymentId("full-flow-001")
                    .amount(1000)
                    .unit("sat")
                    .build();

            GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(dto);

            // 2. Customer decodes request
            VoucherPaymentRequest decoded = VoucherPaymentRequest.deserialize(response.getEncodedRequest());

            // 3. Customer creates matching payload
            VoucherPaymentPayload payload = VoucherPaymentPayload.fromRequest(
                    decoded,
                    List.of(
                            PaymentPayloadProof.builder()
                                    .amount(1000)
                                    .keysetId("keyset1")
                                    .secret("secret1")
                                    .signature("sig1")
                                    .build()
                    ),
                    "https://mint.example.com",
                    "sat"
            );

            // 4. Merchant validates payload
            MerchantVerificationService.VerificationResult result =
                    verificationService.processPaymentPayload(payload, decoded);

            assertThat(result.isValid()).isTrue();
        }
    }
}
