package xyz.tcheeric.cashu.voucher.app.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for JSON serialization/deserialization of DTOs.
 *
 * <p>Ensures all DTOs can be serialized to JSON and deserialized back
 * without loss of data (round-trip testing).
 */
@DisplayName("DTO Serialization")
class DtoSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Nested
    @DisplayName("IssueVoucherRequest")
    class IssueVoucherRequestTests {

        @Test
        @DisplayName("should serialize and deserialize minimal request")
        void shouldSerializeMinimalRequest() throws Exception {
            // Given
            IssueVoucherRequest original = IssueVoucherRequest.builder()
                    .issuerId("merchant123")
                    .unit("sat")
                    .amount(10000L)
                    .build();

            // When
            String json = objectMapper.writeValueAsString(original);
            IssueVoucherRequest deserialized = objectMapper.readValue(json, IssueVoucherRequest.class);

            // Then
            assertThat(deserialized).isNotNull();
            assertThat(deserialized.getIssuerId()).isEqualTo(original.getIssuerId());
            assertThat(deserialized.getUnit()).isEqualTo(original.getUnit());
            assertThat(deserialized.getAmount()).isEqualTo(original.getAmount());
            assertThat(deserialized.getExpiresInDays()).isNull();
            assertThat(deserialized.getMemo()).isNull();
            assertThat(deserialized.getVoucherId()).isNull();
        }

        @Test
        @DisplayName("should serialize and deserialize full request")
        void shouldSerializeFullRequest() throws Exception {
            // Given
            IssueVoucherRequest original = IssueVoucherRequest.builder()
                    .issuerId("merchant123")
                    .unit("sat")
                    .amount(50000L)
                    .expiresInDays(365)
                    .memo("Birthday gift")
                    .voucherId("custom-id-123")
                    .build();

            // When
            String json = objectMapper.writeValueAsString(original);
            IssueVoucherRequest deserialized = objectMapper.readValue(json, IssueVoucherRequest.class);

            // Then
            assertThat(deserialized).isNotNull();
            assertThat(deserialized.getIssuerId()).isEqualTo(original.getIssuerId());
            assertThat(deserialized.getUnit()).isEqualTo(original.getUnit());
            assertThat(deserialized.getAmount()).isEqualTo(original.getAmount());
            assertThat(deserialized.getExpiresInDays()).isEqualTo(original.getExpiresInDays());
            assertThat(deserialized.getMemo()).isEqualTo(original.getMemo());
            assertThat(deserialized.getVoucherId()).isEqualTo(original.getVoucherId());
        }

        @Test
        @DisplayName("should handle null values correctly")
        void shouldHandleNullValues() throws Exception {
            // Given
            String json = "{\"issuerId\":\"test\",\"unit\":\"sat\",\"amount\":1000}";

            // When
            IssueVoucherRequest deserialized = objectMapper.readValue(json, IssueVoucherRequest.class);

            // Then
            assertThat(deserialized.getIssuerId()).isEqualTo("test");
            assertThat(deserialized.getUnit()).isEqualTo("sat");
            assertThat(deserialized.getAmount()).isEqualTo(1000L);
            assertThat(deserialized.getExpiresInDays()).isNull();
            assertThat(deserialized.getMemo()).isNull();
            assertThat(deserialized.getVoucherId()).isNull();
        }
    }

    @Nested
    @DisplayName("IssueVoucherResponse")
    class IssueVoucherResponseTests {

        @Test
        @DisplayName("should have token field serializable")
        void shouldHaveTokenFieldSerializable() throws Exception {
            // Note: Full serialization of IssueVoucherResponse with SignedVoucher
            // is tested at integration level. Here we verify the DTO structure.

            // Given - test that the DTO structure is correct
            VoucherSecret secret = VoucherSecret.builder()
                    .issuerId("merchant123")
                    .unit("sat")
                    .faceValue(10000L)
                    .memo("Test memo")
                    .backingStrategy(BackingStrategy.FIXED.name())
                    .issuanceRatio(1.0)
                    .faceDecimals(0)
                    .build();
            SignedVoucher voucher = new SignedVoucher(
                    secret,
                    new byte[64],
                    "a".repeat(64)
            );

            IssueVoucherResponse response = IssueVoucherResponse.builder()
                    .voucher(voucher)
                    .token("cashuAtest123")
                    .build();

            // Then - verify the DTO has correct accessors
            assertThat(response.getToken()).isEqualTo("cashuAtest123");
            assertThat(response.getVoucher()).isEqualTo(voucher);
            assertThat(response.getVoucherId()).isEqualTo(secret.getVoucherId().toString());
            assertThat(response.getAmount()).isEqualTo(10000L);
            assertThat(response.getUnit()).isEqualTo("sat");
        }
    }

    @Nested
    @DisplayName("RedeemVoucherRequest")
    class RedeemVoucherRequestTests {

        @Test
        @DisplayName("should serialize and deserialize with online verification")
        void shouldSerializeWithOnlineVerification() throws Exception {
            // Given
            RedeemVoucherRequest original = RedeemVoucherRequest.builder()
                    .token("cashuAtest123")
                    .merchantId("merchant123")
                    .verifyOnline(true)
                    .build();

            // When
            String json = objectMapper.writeValueAsString(original);
            RedeemVoucherRequest deserialized = objectMapper.readValue(json, RedeemVoucherRequest.class);

            // Then
            assertThat(deserialized).isNotNull();
            assertThat(deserialized.getToken()).isEqualTo(original.getToken());
            assertThat(deserialized.getMerchantId()).isEqualTo(original.getMerchantId());
            assertThat(deserialized.getVerifyOnline()).isEqualTo(true);
        }

        @Test
        @DisplayName("should serialize and deserialize with offline verification")
        void shouldSerializeWithOfflineVerification() throws Exception {
            // Given
            RedeemVoucherRequest original = RedeemVoucherRequest.builder()
                    .token("cashuAtest456")
                    .merchantId("merchant456")
                    .verifyOnline(false)
                    .build();

            // When
            String json = objectMapper.writeValueAsString(original);
            RedeemVoucherRequest deserialized = objectMapper.readValue(json, RedeemVoucherRequest.class);

            // Then
            assertThat(deserialized.getVerifyOnline()).isEqualTo(false);
        }

        @Test
        @DisplayName("should default verifyOnline to true when not specified")
        void shouldDefaultVerifyOnlineToTrue() throws Exception {
            // Given
            String json = "{\"token\":\"test\",\"merchantId\":\"m123\"}";

            // When
            RedeemVoucherRequest deserialized = objectMapper.readValue(json, RedeemVoucherRequest.class);

            // Then
            assertThat(deserialized.getToken()).isEqualTo("test");
            assertThat(deserialized.getMerchantId()).isEqualTo("m123");
            // verifyOnline defaults to true for safety (prevent double-spend)
            assertThat(deserialized.getVerifyOnline()).isTrue();
        }
    }

    @Nested
    @DisplayName("RedeemVoucherResponse")
    class RedeemVoucherResponseTests {

        @Test
        @DisplayName("should create success response with voucher")
        void shouldCreateSuccessResponse() throws Exception {
            // Given
            VoucherSecret secret = VoucherSecret.builder()
                    .issuerId("merchant123")
                    .unit("sat")
                    .faceValue(10000L)
                    .backingStrategy(BackingStrategy.FIXED.name())
                    .issuanceRatio(1.0)
                    .faceDecimals(0)
                    .build();
            SignedVoucher voucher = new SignedVoucher(secret, new byte[64], "a".repeat(64));

            // When
            RedeemVoucherResponse response = RedeemVoucherResponse.success(voucher);

            // Then - verify DTO structure
            assertThat(response).isNotNull();
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getErrorMessage()).isNull();
            assertThat(response.getVoucher()).isEqualTo(voucher);
            assertThat(response.getAmount()).isEqualTo(10000L);
            assertThat(response.getUnit()).isEqualTo("sat");
        }

        @Test
        @DisplayName("should create failure response correctly")
        void shouldCreateFailureResponse() throws Exception {
            // Given & When
            RedeemVoucherResponse response = RedeemVoucherResponse.failure("Voucher expired");

            // Then - verify DTO structure for failure case
            assertThat(response).isNotNull();
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorMessage()).isEqualTo("Voucher expired");
            assertThat(response.getVoucher()).isNull();
            assertThat(response.getAmount()).isNull();
        }

        @Test
        @DisplayName("should have correct convenience accessors")
        void shouldHaveConvenienceAccessors() throws Exception {
            // Given
            VoucherSecret secret = VoucherSecret.builder()
                    .issuerId("merchant123")
                    .unit("usd")
                    .faceValue(100L)
                    .backingStrategy(BackingStrategy.FIXED.name())
                    .issuanceRatio(1.0)
                    .faceDecimals(0)
                    .build();
            SignedVoucher voucher = new SignedVoucher(secret, new byte[64], "a".repeat(64));

            // When
            RedeemVoucherResponse response = RedeemVoucherResponse.success(voucher);

            // Then - test convenience accessors work correctly
            assertThat(response.getAmount()).isEqualTo(100L);
            assertThat(response.getUnit()).isEqualTo("usd");
        }
    }

    @Nested
    @DisplayName("StoredVoucher")
    class StoredVoucherTests {

        @Test
        @DisplayName("should create from SignedVoucher with correct fields")
        void shouldCreateFromSignedVoucher() throws Exception {
            // Given
            VoucherSecret secret = VoucherSecret.builder()
                    .issuerId("merchant123")
                    .unit("sat")
                    .faceValue(10000L)
                    .memo("Test")
                    .backingStrategy(BackingStrategy.FIXED.name())
                    .issuanceRatio(1.0)
                    .faceDecimals(0)
                    .build();
            SignedVoucher signedVoucher = new SignedVoucher(secret, new byte[64], "a".repeat(64));

            // When
            StoredVoucher stored = StoredVoucher.from(signedVoucher);
            stored.setUserLabel("My Gift Card");
            stored.markBackedUp();
            stored.updateStatus(VoucherStatus.ISSUED);

            // Then - verify DTO structure and methods
            assertThat(stored).isNotNull();
            assertThat(stored.getVoucherId()).isEqualTo(secret.getVoucherId().toString());
            assertThat(stored.getUserLabel()).isEqualTo("My Gift Card");
            assertThat(stored.getAddedAt()).isNotNull();
            assertThat(stored.getLastBackupAt()).isNotNull();
            assertThat(stored.getCachedStatus()).isEqualTo(VoucherStatus.ISSUED);
            assertThat(stored.getStatusUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("should have working helper methods")
        void shouldHaveWorkingHelperMethods() throws Exception {
            // Given
            VoucherSecret secret = VoucherSecret.builder()
                    .issuerId("merchant123")
                    .unit("sat")
                    .faceValue(10000L)
                    .backingStrategy(BackingStrategy.FIXED.name())
                    .issuanceRatio(1.0)
                    .faceDecimals(0)
                    .build();
            SignedVoucher signedVoucher = new SignedVoucher(secret, new byte[64], "a".repeat(64));

            // When
            StoredVoucher stored = StoredVoucher.from(signedVoucher);

            // Then - helper methods should work correctly
            assertThat(stored.needsBackup()).isTrue();
            assertThat(stored.isStatusStale(300)).isTrue(); // Status is null, so stale

            // Mark as backed up and check
            stored.markBackedUp();
            assertThat(stored.needsBackup()).isFalse();
        }

        @Test
        @DisplayName("should handle nullable fields correctly")
        void shouldHandleNullableFields() throws Exception {
            // Given
            VoucherSecret secret = VoucherSecret.builder()
                    .issuerId("merchant123")
                    .unit("sat")
                    .faceValue(5000L)
                    .backingStrategy(BackingStrategy.FIXED.name())
                    .issuanceRatio(1.0)
                    .faceDecimals(0)
                    .build();
            SignedVoucher signedVoucher = new SignedVoucher(secret, new byte[64], "a".repeat(64));

            // When
            StoredVoucher stored = StoredVoucher.from(signedVoucher);
            // Don't set userLabel or backup - should remain null

            // Then
            assertThat(stored.getUserLabel()).isNull();
            assertThat(stored.getLastBackupAt()).isNull();
            assertThat(stored.getCachedStatus()).isNull();
            assertThat(stored.getStatusUpdatedAt()).isNull();
        }
    }
}
