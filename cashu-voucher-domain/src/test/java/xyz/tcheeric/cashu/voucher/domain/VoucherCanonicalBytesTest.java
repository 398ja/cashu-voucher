package xyz.tcheeric.cashu.voucher.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the exact bytes an issuer signature commits to.
 *
 * <p>These bytes are a wire contract shared with every verifier, including the offline
 * TypeScript wallet. A change to them invalidates every voucher signature already issued, so
 * this test asserts the literal expected string rather than recomputing it: a test that
 * re-derives the bytes agrees with any change it shares, which is exactly how the regression
 * these tests now guard against reached a release.
 */
class VoucherCanonicalBytesTest {

    private static final UUID VOUCHER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String NONCE = "0000000000000000000000000000000000000000000000000000000000aa";
    private static final String VOUCHER_ID_HEX =
            "31323365343536372d653839622d313264332d613435362d343236363134313734303030";

    private static VoucherSecret.Builder secret() {
        return VoucherSecret.builder()
                .voucherId(VOUCHER_ID)
                .nonce(NONCE)
                .issuerId("test-issuer")
                .unit("sat")
                .faceValue(5000L)
                .expiresAt(1893456000L)
                .memo("golden")
                .faceDecimals(2);
    }

    private static String canonical(VoucherSecret voucher) {
        return new String(VoucherCanonicalBytes.of(voucher), StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("numeric tags")
    class NumericTags {

        /**
         * The whole point of #15: face_value, expires_at and face_decimals must be written as
         * bare JSON numbers. cashu-lib models tag values as String, so nothing about the
         * runtime type distinguishes them and only the tag key can.
         */
        @Test
        @DisplayName("writes numeric tags unquoted even though tag values are Strings")
        void writesNumericTagsUnquoted() {
            assertThat(canonical(secret().build()))
                    .contains("[\"face_value\",5000]")
                    .contains("[\"expires_at\",1893456000]")
                    .contains("[\"face_decimals\",2]")
                    .doesNotContain("\"5000\"")
                    .doesNotContain("\"1893456000\"");
        }

        /** Non-numeric tags keep their quotes, so the two cases are genuinely distinguished. */
        @Test
        @DisplayName("writes non-numeric tags quoted")
        void writesNonNumericTagsQuoted() {
            assertThat(canonical(secret().build()))
                    .contains("[\"unit\",\"sat\"]")
                    .contains("[\"memo\",\"golden\"]");
        }

        /** A fractional ratio keeps its fraction; truncating it once left the ratio unsigned. */
        @Test
        @DisplayName("preserves a fractional issuance_ratio")
        void preservesFractionalRatio() {
            assertThat(canonical(secret().issuanceRatio(0.05611672278338945).build()))
                    .contains("[\"issuance_ratio\",0.05611672278338945]");
        }

        /** An integral double must not gain a decimal point, or 1.0 and 1 sign differently. */
        @Test
        @DisplayName("writes an integral issuance_ratio as a long")
        void writesIntegralRatioAsLong() {
            assertThat(canonical(secret().issuanceRatio(2.0).build()))
                    .contains("[\"issuance_ratio\",2]");
        }
    }

    @Nested
    @DisplayName("structure")
    class Structure {

        /**
         * The exact preimage, asserted literally. If this string has to change, every voucher
         * already signed stops verifying, so the failure should be loud and deliberate.
         */
        @Test
        @DisplayName("matches the pinned canonical form byte for byte")
        void matchesPinnedForm() {
            String expected = "[\"VOUCHER\",\"" + VOUCHER_ID_HEX + "\",\"" + NONCE + "\","
                    + "[[\"issuer\",\"test-issuer\"],"
                    + "[\"unit\",\"sat\"],"
                    + "[\"face_value\",5000],"
                    + "[\"expires_at\",1893456000],"
                    + "[\"memo\",\"golden\"],"
                    + "[\"face_decimals\",2]]]";

            assertThat(canonical(secret().build())).isEqualTo(expected);
        }

        /** The signature cannot cover itself, so those tags are excluded from the preimage. */
        @Test
        @DisplayName("excludes the tags added after signing")
        void excludesSignatureTags() {
            VoucherSecret voucher = secret().build();
            voucher.setIssuerSignature("aa".repeat(64));
            voucher.setIssuerPublicKey("bb".repeat(32));

            assertThat(canonical(voucher))
                    .doesNotContain("issuer_sig")
                    .doesNotContain("issuer_pubkey");
        }

        /** Adding the signature must not disturb the bytes it was computed over. */
        @Test
        @DisplayName("is unchanged by attaching the signature")
        void isUnchangedByAttachingSignature() {
            VoucherSecret unsigned = secret().build();
            String before = canonical(unsigned);

            unsigned.setIssuerSignature("aa".repeat(64));
            unsigned.setIssuerPublicKey("bb".repeat(32));

            assertThat(canonical(unsigned)).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("legacy form")
    class LegacyForm {

        /**
         * The compatibility path for vouchers signed before the ratio was bound. It must stay
         * reproducible, because those vouchers cannot be re-signed.
         */
        @Test
        @DisplayName("truncates a fractional ratio to zero")
        void truncatesFractionalRatio() {
            byte[] legacy = VoucherCanonicalBytes.of(
                    secret().issuanceRatio(0.05611672278338945).build(),
                    VoucherCanonicalBytes.NumericTagForm.TRUNCATED_TO_LONG);

            assertThat(new String(legacy, StandardCharsets.UTF_8))
                    .contains("[\"issuance_ratio\",0]");
        }

        /** The two forms must genuinely differ, or the fallback verifies nothing distinct. */
        @Test
        @DisplayName("differs from the current form for a fractional ratio")
        void differsFromCurrentForm() {
            VoucherSecret voucher = secret().issuanceRatio(0.05611672278338945).build();

            assertThat(VoucherCanonicalBytes.of(voucher, VoucherCanonicalBytes.NumericTagForm.TRUNCATED_TO_LONG))
                    .isNotEqualTo(VoucherCanonicalBytes.of(voucher));
        }
    }
}
