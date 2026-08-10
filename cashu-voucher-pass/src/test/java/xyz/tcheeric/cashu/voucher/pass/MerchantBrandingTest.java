package xyz.tcheeric.cashu.voucher.pass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MerchantBranding")
class MerchantBrandingTest {

    @Test
    @DisplayName("empty() has every field null")
    void emptyHasAllFieldsNull() {
        MerchantBranding branding = MerchantBranding.empty();

        assertThat(branding.organizationName()).isNull();
        assertThat(branding.logoUrl()).isNull();
        assertThat(branding.bannerUrl()).isNull();
        assertThat(branding.storeDescription()).isNull();
        assertThat(branding.backgroundColor()).isNull();
        assertThat(branding.foregroundColor()).isNull();
    }

    @Test
    @DisplayName("retains the values it is given")
    void retainsValues() {
        MerchantBranding branding = new MerchantBranding(
                "Corner Cafe", "https://blossom.example/logo.png", "https://blossom.example/banner.png",
                "Best coffee in town", "rgb(10,20,30)", "rgb(240,240,240)");

        assertThat(branding.organizationName()).isEqualTo("Corner Cafe");
        assertThat(branding.logoUrl()).isEqualTo("https://blossom.example/logo.png");
        assertThat(branding.bannerUrl()).isEqualTo("https://blossom.example/banner.png");
        assertThat(branding.storeDescription()).isEqualTo("Best coffee in town");
        assertThat(branding.backgroundColor()).isEqualTo("rgb(10,20,30)");
        assertThat(branding.foregroundColor()).isEqualTo("rgb(240,240,240)");
    }
}
