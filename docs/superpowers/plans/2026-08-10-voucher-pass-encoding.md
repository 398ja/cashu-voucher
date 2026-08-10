# Voucher Pass Encoding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `cashu-voucher-pass` module that maps a `SignedVoucher` to an Apple `pass.json` document, so vouchers can be rendered as store cards by imani's own wallet.

**Architecture:** One pure static mapper, `VoucherPassMapper`, turning a `SignedVoucher` plus caller-supplied `MerchantBranding` into a jPasskit `PKPass`. No I/O, no ports, no Nostr dependency — the caller resolves branding and serialises the result. The pass is a derived view of the voucher and never round-trips back.

**Tech Stack:** Java 21, Maven, jPasskit 0.5.7 (object model only, never its signing classes), JUnit 5, AssertJ, Jackson.

**Spec:** `docs/superpowers/specs/2026-08-10-voucher-pass-encoding-design.md`

## Global Constraints

- Java 21; parent POM `xyz.tcheeric:cashu-voucher:0.8.0` manages all dependency versions — child POMs declare no `<version>`.
- Module depends on `cashu-voucher-domain` only. **Never** add a Nostr, HTTP, or filesystem dependency to this module.
- **Never** import `de.brendamour.jpasskit.signing.*` or `de.brendamour.jpasskit.passes.PKPassTemplate*`. Object model only.
- Money is `BigDecimal` built with `BigDecimal.valueOf(long, int)`. `double` must never appear on the money path.
- The mapper performs no I/O. Image URLs are passed through as strings.
- Tests use JUnit 5 + AssertJ with `@DisplayName`, matching `cashu-voucher-domain` conventions. Test classes are package-private.
- Constants: `passTypeIdentifier` = `xyz.tcheeric.voucher`, `teamIdentifier` = `imani`. Both are schema-required by jPasskit validation but semantically meaningless — we target no Apple infrastructure.
- Never enforce Apple's image pixel dimensions in the mapper.

## Verified jPasskit API (0.5.7)

Confirmed against source at `drallgood/jpasskit`. Use exactly these names.

```java
PKPass.builder()                              // returns PKPassBuilder
  .serialNumber(String) .passTypeIdentifier(String) .teamIdentifier(String)
  .description(String) .organizationName(String) .logoText(String)
  .formatVersion(int) .voided(boolean)
  .backgroundColor(String) .foregroundColor(String) .labelColor(String)
  .expirationDate(Instant)                    // Date overload is deprecated
  .userInfo(Map<String, Object>)
  .barcodeBuilder(PKBarcodeBuilder)
  .pass(PKGenericPassBuilder)
  .getValidationErrors() -> List<String>      // ON THE BUILDER, not on PKPass
  .build() -> PKPass

PKGenericPass.builder()                       // returns PKGenericPassBuilder
  .passType(PKPassType.PKStoreCard)
  .primaryFieldBuilder(PKFieldBuilder) .auxiliaryFieldBuilder(..) .backFieldBuilder(..)

PKField.builder()                             // returns PKFieldBuilder
  .key(String) .label(String)
  .value(String) | .value(BigDecimal) | .value(Instant)   // overloaded
  .currencyCode(String) .dateStyle(PKDateStyle)

PKBarcode.builder()                           // returns PKBarcodeBuilder
  .format(PKBarcodeFormat.PKBarcodeFormatQR) .message(String)
  .messageEncoding(Charset) .altText(String)
```

**Two constraints jPasskit enforces that shape the code:**

1. `PKFieldBuilder.checkCurrencyValueIsNumeric` — a field with `currencyCode` set **must** have a numeric value (`Integer`, `Float`, `Long`, `Double`, `BigDecimal`). A `String` value fails validation. So the balance is a `BigDecimal`, not a formatted string.
2. `PKPassBuilder.checkRequiredFields` — requires `serialNumber`, `passTypeIdentifier`, `teamIdentifier`, `description`, `organizationName`, and `formatVersion != 0`. A pass missing any of these is invalid, which is why Task 2 sets them all before anything else is added.

jPasskit's transitive dependencies are Jackson (already managed in the parent) and `org.apache.commons.lang3`. Task 1 confirms this.

---

## File Structure

| File | Responsibility |
|---|---|
| `pom.xml` (modify) | Register `cashu-voucher-pass` module + manage its version and jPasskit's |
| `cashu-voucher-pass/pom.xml` (create) | Module dependencies |
| `.../voucher/pass/MerchantBranding.java` (create) | Immutable branding carrier, all fields nullable |
| `.../voucher/pass/VoucherPassMapper.java` (create) | The whole mapping; only public entry point |
| `.../voucher/pass/MerchantBrandingTest.java` (create) | Record defaults |
| `.../voucher/pass/VoucherPassMapperTest.java` (create) | All mapping behaviour |

Package root: `xyz.tcheeric.cashu.voucher.pass`.

---

### Task 1: Module scaffold and MerchantBranding

**Files:**
- Modify: `pom.xml` (add `<module>`, dependencyManagement entries, `jpasskit.version` property)
- Create: `cashu-voucher-pass/pom.xml`
- Create: `cashu-voucher-pass/src/main/java/xyz/tcheeric/cashu/voucher/pass/MerchantBranding.java`
- Test: `cashu-voucher-pass/src/test/java/xyz/tcheeric/cashu/voucher/pass/MerchantBrandingTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `MerchantBranding` record with accessors `organizationName()`, `logoUrl()`, `bannerUrl()`, `storeDescription()`, `backgroundColor()`, `foregroundColor()`, all `String` and all nullable; plus `static MerchantBranding empty()` returning an instance with every field null.

- [ ] **Step 1: Add the module and dependency management to the parent POM**

In `pom.xml`, add to `<modules>` after `cashu-voucher-app`:

```xml
        <module>cashu-voucher-pass</module>
```

Add to `<properties>` after `<jackson.version>`:

```xml
        <jpasskit.version>0.5.7</jpasskit.version>
```

Add to `<dependencyManagement><dependencies>` after the `cashu-voucher-app` entry:

```xml
            <dependency>
                <groupId>xyz.tcheeric</groupId>
                <artifactId>cashu-voucher-pass</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>de.brendamour</groupId>
                <artifactId>jpasskit</artifactId>
                <version>${jpasskit.version}</version>
            </dependency>
```

- [ ] **Step 2: Create the module POM**

Create `cashu-voucher-pass/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>xyz.tcheeric</groupId>
        <artifactId>cashu-voucher</artifactId>
        <version>0.8.0</version>
    </parent>

    <artifactId>cashu-voucher-pass</artifactId>
    <packaging>jar</packaging>

    <name>Cashu Voucher Pass</name>
    <description>Maps signed vouchers to Apple pass.json documents (schema only, no Apple Wallet integration)</description>

    <dependencies>
        <dependency>
            <groupId>xyz.tcheeric</groupId>
            <artifactId>cashu-voucher-domain</artifactId>
        </dependency>

        <!-- pass.json object model. Signing and template classes are never used. -->
        <dependency>
            <groupId>de.brendamour</groupId>
            <artifactId>jpasskit</artifactId>
        </dependency>

        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: Write the failing test**

Create `cashu-voucher-pass/src/test/java/xyz/tcheeric/cashu/voucher/pass/MerchantBrandingTest.java`:

```java
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
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=MerchantBrandingTest`
Expected: FAIL — compilation error, `MerchantBranding` does not exist.

- [ ] **Step 5: Write the implementation**

Create `cashu-voucher-pass/src/main/java/xyz/tcheeric/cashu/voucher/pass/MerchantBranding.java`:

```java
package xyz.tcheeric.cashu.voucher.pass;

/**
 * Merchant branding resolved at render time and passed to {@link VoucherPassMapper}.
 *
 * <p>Branding is deliberately <em>not</em> read from the voucher. Branding is mutable
 * and a signed voucher is not, so a value baked into the signed secret at issuance
 * would leave every outstanding voucher showing stale branding or needing re-issuance.
 *
 * <p>The caller populates this from the merchant's existing Nostr identity, served by
 * {@code GET /api/v1/merchant/bootstrap}:
 * <ul>
 *   <li>{@code organizationName} — kind-0 {@code name}</li>
 *   <li>{@code logoUrl} — kind-0 {@code picture}</li>
 *   <li>{@code bannerUrl} — kind-0 {@code banner}</li>
 *   <li>{@code storeDescription} — kind-30078 {@code d=imani:merchant}</li>
 * </ul>
 *
 * <p>Do not use {@code businessName} from the merchant profile — possa-merchant is
 * removing it. kind-0 {@code name} is the live field.
 *
 * <p>Every field is nullable; {@link VoucherPassMapper} defaults each one.
 */
public record MerchantBranding(
        String organizationName,
        String logoUrl,
        String bannerUrl,
        String storeDescription,
        String backgroundColor,
        String foregroundColor
) {

    private static final MerchantBranding EMPTY =
            new MerchantBranding(null, null, null, null, null, null);

    /**
     * Branding with no values set. Equivalent to passing {@code null} to the mapper.
     *
     * @return a branding instance with every field null
     */
    public static MerchantBranding empty() {
        return EMPTY;
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=MerchantBrandingTest`
Expected: PASS, 2 tests.

- [ ] **Step 7: Confirm jPasskit's transitive dependencies**

Run: `mvn -q -pl cashu-voucher-pass dependency:tree -Dincludes=de.brendamour:jpasskit`

Expected: jPasskit pulls Jackson (already managed by the parent) and `org.apache.commons.lang3:commons-lang3`. Both are benign.

If the tree instead shows something heavy or conflicting — a second Jackson major version, a logging binding, or a servlet/HTTP stack — stop and report it. The spec's stated fallback is to drop jPasskit and hand-roll roughly twelve Jackson-annotated records; `VoucherPassMapper`'s public signature would not change. Do not silently add exclusions.

- [ ] **Step 8: Commit**

```bash
git add pom.xml cashu-voucher-pass/pom.xml \
  cashu-voucher-pass/src/main/java/xyz/tcheeric/cashu/voucher/pass/MerchantBranding.java \
  cashu-voucher-pass/src/test/java/xyz/tcheeric/cashu/voucher/pass/MerchantBrandingTest.java
git commit -m "feat(pass): add cashu-voucher-pass module and MerchantBranding"
```

---

### Task 2: Minimal valid pass — identity, store card, back fields

**Files:**
- Create: `cashu-voucher-pass/src/main/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapper.java`
- Test: `cashu-voucher-pass/src/test/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapperTest.java`

**Interfaces:**
- Consumes: `MerchantBranding` from Task 1.
- Produces: `static PKPass VoucherPassMapper.toPass(SignedVoucher voucher, MerchantBranding branding)`. Throws `NullPointerException` on a null voucher; a null `branding` is legal. Later tasks add a three-argument overload taking `VoucherStatus`.

The signed test voucher built here is reused by every later task — keep the helper stable.

- [ ] **Step 1: Write the failing test**

Create `cashu-voucher-pass/src/test/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapperTest.java`:

```java
package xyz.tcheeric.cashu.voucher.pass;

import de.brendamour.jpasskit.PKField;
import de.brendamour.jpasskit.PKPass;
import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("VoucherPassMapper")
class VoucherPassMapperTest {

    private static String issuerPrivateKeyHex;
    private static String issuerPublicKeyHex;

    static final String ISSUER_ID = "corner-cafe";

    @BeforeAll
    static void setupKeys() {
        byte[] privateKeyBytes = Schnorr.generatePrivateKey();
        byte[] publicKeyBytes = Schnorr.genPubKey(privateKeyBytes);
        issuerPrivateKeyHex = Hex.toHexString(privateKeyBytes);
        issuerPublicKeyHex = Hex.toHexString(publicKeyBytes);
    }

    /**
     * Builds a signed voucher. Reused by every test in this class.
     *
     * @param unit ISO 4217 code, lowercase per Cashu convention
     * @param faceValue value in the smallest unit
     * @param faceDecimals decimal places
     * @param expiresAt epoch seconds, or null
     * @param memo description, or null
     */
    static SignedVoucher voucher(String unit, long faceValue, int faceDecimals,
                                 Long expiresAt, String memo) {
        VoucherSecret secret = VoucherSecret.builder()
                .voucherId(UUID.fromString("11111111-2222-3333-4444-555555555555"))
                .issuerId(ISSUER_ID)
                .unit(unit)
                .faceValue(faceValue)
                .faceDecimals(faceDecimals)
                .expiresAt(expiresAt)
                .memo(memo)
                .build();
        // createSigned signs, sets the issuer_sig and issuer_pubkey tags, and wraps.
        // Calling sign() alone returns the bytes without setting the tags, and the
        // SignedVoucher constructor would then reject the secret as unsigned.
        return VoucherSignatureService.createSigned(secret, issuerPrivateKeyHex, issuerPublicKeyHex);
    }

    /** Finds a field by key in a list, failing the test if absent. */
    static PKField field(List<PKField> fields, String key) {
        return fields.stream()
                .filter(f -> key.equals(f.getKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No field with key '" + key + "' in " + fields));
    }

    @Test
    @DisplayName("produces a valid store card with all required identity fields")
    void producesValidStoreCard() {
        SignedVoucher v = voucher("eur", 5000L, 2, null, "Gift card");

        PKPass pass = VoucherPassMapper.toPass(v, MerchantBranding.empty());

        assertThat(pass.getFormatVersion()).isEqualTo(1);
        assertThat(pass.getPassTypeIdentifier()).isEqualTo("xyz.tcheeric.voucher");
        assertThat(pass.getTeamIdentifier()).isEqualTo("imani");
        assertThat(pass.getSerialNumber()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(pass.getDescription()).isEqualTo("Gift card");
        assertThat(pass.getOrganizationName()).isEqualTo(ISSUER_ID);
        assertThat(pass.getStoreCard()).isNotNull();
        assertThat(pass.getGeneric()).isNull();
        assertThat(pass.getCoupon()).isNull();
    }

    @Test
    @DisplayName("back fields carry voucher id, issuer, issuer key and terms")
    void backFieldsCarryProvenance() {
        SignedVoucher v = voucher("eur", 5000L, 2, null, "Gift card");

        List<PKField> back = VoucherPassMapper.toPass(v, MerchantBranding.empty())
                .getStoreCard().getBackFields();

        assertThat(field(back, "voucherId").getValue())
                .isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(field(back, "issuer").getValue()).isEqualTo(ISSUER_ID);
        assertThat(field(back, "issuerKey").getValue()).isEqualTo(issuerPublicKeyHex);
        assertThat(field(back, "terms").getValue().toString())
                .contains("issuing merchant");
    }

    @Test
    @DisplayName("does not expose the issuer signature")
    void doesNotExposeSignature() {
        SignedVoucher v = voucher("eur", 5000L, 2, null, "Gift card");

        List<PKField> back = VoucherPassMapper.toPass(v, MerchantBranding.empty())
                .getStoreCard().getBackFields();

        assertThat(back).extracting(PKField::getKey).doesNotContain("issuerSig");
        assertThat(back).extracting(f -> String.valueOf(f.getValue()))
                .noneMatch(value -> value.equals(v.getSecret().getIssuerSignature()));
    }

    @Test
    @DisplayName("rejects a null voucher")
    void rejectsNullVoucher() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> VoucherPassMapper.toPass(null, MerchantBranding.empty()))
                .isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest`
Expected: FAIL — compilation error, `VoucherPassMapper` does not exist.

- [ ] **Step 3: Write the implementation**

Create `cashu-voucher-pass/src/main/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapper.java`:

```java
package xyz.tcheeric.cashu.voucher.pass;

import de.brendamour.jpasskit.PKField;
import de.brendamour.jpasskit.PKPass;
import de.brendamour.jpasskit.PKPassBuilder;
import de.brendamour.jpasskit.enums.PKPassType;
import de.brendamour.jpasskit.passes.PKGenericPass;
import de.brendamour.jpasskit.passes.PKGenericPassBuilder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;

import java.util.List;

/**
 * Maps a {@link SignedVoucher} to an Apple {@code pass.json} document.
 *
 * <p>The pass is a <em>derived view</em>: the signed voucher is the only source of
 * truth, and nothing parses a pass back into a voucher. Rendering is a pure function,
 * so a balance change after partial redemption needs no synchronisation — re-render.
 *
 * <p>Apple Wallet is not a target. We adopt the {@code pass.json} schema only; there
 * is no certificate, no {@code .pkpass} container, and no pass update web service.
 * {@code passTypeIdentifier} and {@code teamIdentifier} are schema-required constants
 * with no meaning here.
 */
@Slf4j
public final class VoucherPassMapper {

    /** Schema-required, semantically meaningless — we target no Apple infrastructure. */
    static final String PASS_TYPE_IDENTIFIER = "xyz.tcheeric.voucher";

    /** Schema-required, semantically meaningless — we target no Apple infrastructure. */
    static final String TEAM_IDENTIFIER = "imani";

    static final String DEFAULT_DESCRIPTION = "Gift Card";

    static final String TERMS =
            "Redeemable only with the issuing merchant. Not redeemable at the mint.";

    private VoucherPassMapper() {
    }

    /**
     * Renders a voucher as a store card.
     *
     * @param voucher the signed voucher; must not be null
     * @param branding merchant branding, or null for defaults
     * @return the pass
     */
    public static PKPass toPass(@NonNull SignedVoucher voucher, MerchantBranding branding) {
        MerchantBranding b = branding != null ? branding : MerchantBranding.empty();
        VoucherSecret secret = voucher.getSecret();

        PKGenericPassBuilder storeCard = PKGenericPass.builder()
                .passType(PKPassType.PKStoreCard);

        backFields(secret).forEach(storeCard::backField);

        PKPassBuilder pass = PKPass.builder()
                .formatVersion(1)
                .passTypeIdentifier(PASS_TYPE_IDENTIFIER)
                .teamIdentifier(TEAM_IDENTIFIER)
                .serialNumber(String.valueOf(secret.getVoucherId()))
                .description(description(secret, b))
                .organizationName(organizationName(secret, b))
                .pass(storeCard);

        return build(pass);
    }

    private static String description(VoucherSecret secret, MerchantBranding b) {
        if (secret.getMemo() != null && !secret.getMemo().isBlank()) {
            return secret.getMemo();
        }
        if (b.storeDescription() != null && !b.storeDescription().isBlank()) {
            return b.storeDescription();
        }
        return DEFAULT_DESCRIPTION;
    }

    private static String organizationName(VoucherSecret secret, MerchantBranding b) {
        if (b.organizationName() != null && !b.organizationName().isBlank()) {
            return b.organizationName();
        }
        return secret.getIssuerId();
    }

    /**
     * Provenance shown on the back of the card.
     *
     * <p>Deliberately excludes {@code issuer_sig}: surfacing a signature in a UI
     * invites treatment as a credential. {@code backing_strategy}, {@code issuance_ratio}
     * and {@code merchant_metadata} are excluded too — none has display meaning.
     */
    private static List<PKField> backFields(VoucherSecret secret) {
        return List.of(
                PKField.builder().key("voucherId").label("Voucher ID")
                        .value(String.valueOf(secret.getVoucherId())).build(),
                PKField.builder().key("issuer").label("Issuer")
                        .value(secret.getIssuerId()).build(),
                PKField.builder().key("issuerKey").label("Issuer Public Key")
                        .value(secret.getIssuerPublicKey()).build(),
                PKField.builder().key("terms").label("Terms")
                        .value(TERMS).build());
    }

    /**
     * Builds, surfacing jPasskit's own validation as a log line.
     *
     * <p>Validation lives on the builder, not on {@link PKPass}, so it must be read
     * before {@code build()}. Errors are logged rather than thrown: they indicate a
     * mapper bug, and the tests assert on structure directly.
     */
    private static PKPass build(PKPassBuilder pass) {
        List<String> errors = pass.getValidationErrors();
        if (!errors.isEmpty()) {
            log.warn("voucher_pass build validation_errors={}", errors);
        }
        return pass.build();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest`
Expected: PASS, 4 tests. No `validation_errors` warning in the output.

- [ ] **Step 5: Commit**

```bash
git add cashu-voucher-pass/src
git commit -m "feat(pass): map voucher identity and back fields to a store card"
```

---

### Task 3: Balance field and currency rules

**Files:**
- Modify: `cashu-voucher-pass/src/main/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapper.java`
- Test: `cashu-voucher-pass/src/test/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapperTest.java`

**Interfaces:**
- Consumes: `VoucherPassMapper.toPass(SignedVoucher, MerchantBranding)` from Task 2.
- Produces: the store card gains a primary field with key `balance`, a `BigDecimal` value in major units and an uppercase ISO 4217 `currencyCode`. `toPass` now throws `IllegalArgumentException` for an unknown unit or a negative face value.

- [ ] **Step 1: Write the failing test**

Add to `VoucherPassMapperTest`, and add these imports to the file:

```java
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
```

```java
    @Test
    @DisplayName("scales the balance into major units with the currency code")
    void scalesBalance() {
        PKField balance = field(
                VoucherPassMapper.toPass(voucher("eur", 5000L, 2, null, "Gift card"),
                        MerchantBranding.empty()).getStoreCard().getPrimaryFields(),
                "balance");

        assertThat(balance.getValue()).isEqualTo(new BigDecimal("50.00"));
        assertThat(balance.getCurrencyCode()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("handles zero-decimal and three-decimal currencies")
    void handlesOtherCurrencyScales() {
        PKField jpy = field(
                VoucherPassMapper.toPass(voucher("jpy", 5000L, 0, null, "Gift card"),
                        MerchantBranding.empty()).getStoreCard().getPrimaryFields(),
                "balance");
        assertThat(jpy.getValue()).isEqualTo(new BigDecimal("5000"));
        assertThat(jpy.getCurrencyCode()).isEqualTo("JPY");

        PKField kwd = field(
                VoucherPassMapper.toPass(voucher("kwd", 5000L, 3, null, "Gift card"),
                        MerchantBranding.empty()).getStoreCard().getPrimaryFields(),
                "balance");
        assertThat(kwd.getValue()).isEqualTo(new BigDecimal("5.000"));
        assertThat(kwd.getCurrencyCode()).isEqualTo("KWD");
    }

    @Test
    @DisplayName("trusts face_decimals when it disagrees with the currency default")
    void trustsFaceDecimalsOverCurrencyDefault() {
        // JPY defaults to 0 fraction digits; the voucher says 2. face_decimals wins.
        PKField balance = field(
                VoucherPassMapper.toPass(voucher("jpy", 5000L, 2, null, "Gift card"),
                        MerchantBranding.empty()).getStoreCard().getPrimaryFields(),
                "balance");

        assertThat(balance.getValue()).isEqualTo(new BigDecimal("50.00"));
    }

    @Test
    @DisplayName("rejects an unknown currency unit")
    void rejectsUnknownUnit() {
        assertThatThrownBy(() -> VoucherPassMapper.toPass(
                voucher("xyz", 5000L, 2, null, "Gift card"), MerchantBranding.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a negative face value")
    void rejectsNegativeFaceValue() {
        assertThatThrownBy(() -> VoucherPassMapper.toPass(
                voucher("eur", -1L, 2, null, "Gift card"), MerchantBranding.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("accepts an uppercase unit")
    void acceptsUppercaseUnit() {
        assertThatCode(() -> VoucherPassMapper.toPass(
                voucher("EUR", 5000L, 2, null, "Gift card"), MerchantBranding.empty()))
                .doesNotThrowAnyException();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest`
Expected: FAIL — `No field with key 'balance'`, and the rejection tests fail because nothing throws.

- [ ] **Step 3: Write the implementation**

In `VoucherPassMapper.java`, add imports:

```java
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
```

Add the balance field to the store card in `toPass`, immediately before the `backFields` line:

```java
        storeCard.primaryFieldBuilder(balanceField(secret));
```

Add these methods:

```java
    /**
     * The balance, as a {@link BigDecimal} in major units with an ISO 4217 code.
     *
     * <p>jPasskit requires a numeric value whenever {@code currencyCode} is set, and
     * {@code pass.json} formats currency in major units — so the scaling is required
     * for correctness, not presentation.
     */
    private static PKField balanceField(VoucherSecret secret) {
        Currency currency = currency(secret.getUnit());
        long faceValue = faceValue(secret);
        int decimals = faceDecimals(secret, currency);

        return PKField.builder()
                .key("balance")
                .label("BALANCE")
                .value(BigDecimal.valueOf(faceValue, decimals))
                .currencyCode(currency.getCurrencyCode())
                .build();
    }

    /**
     * Validates the unit as ISO 4217. {@code Currency.getInstance} throws on an
     * unknown code, so no lookup table is needed.
     *
     * <p>Cashu writes units lowercase; ISO 4217 is uppercase. {@code Locale.ROOT}
     * avoids the Turkish dotless-i trap.
     */
    private static Currency currency(String unit) {
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("Voucher has no unit");
        }
        try {
            return Currency.getInstance(unit.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid ISO 4217 currency: " + unit, e);
        }
    }

    private static long faceValue(VoucherSecret secret) {
        Long faceValue = secret.getFaceValue();
        if (faceValue == null) {
            throw new IllegalArgumentException("Voucher has no face value");
        }
        if (faceValue < 0) {
            throw new IllegalArgumentException("Voucher face value is negative: " + faceValue);
        }
        return faceValue;
    }

    /**
     * Warns when {@code face_decimals} disagrees with the currency, then trusts
     * {@code face_decimals} anyway.
     *
     * <p>It is what the issuer signed and what the rest of the stack accepts as valid;
     * a display mapper is the wrong layer to overrule it. The warning surfaces bad
     * minting without letting a cosmetic concern break rendering.
     */
    private static int faceDecimals(VoucherSecret secret, Currency currency) {
        int decimals = secret.getFaceDecimals();
        int expected = currency.getDefaultFractionDigits();
        if (expected >= 0 && decimals != expected) {
            log.warn("voucher_pass face_decimals_mismatch currency={} declared={} expected={}",
                    currency.getCurrencyCode(), decimals, expected);
        }
        return decimals;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest`
Expected: PASS, 10 tests. One `face_decimals_mismatch` warning appears, from `trustsFaceDecimalsOverCurrencyDefault`.

- [ ] **Step 5: Commit**

```bash
git add cashu-voucher-pass/src
git commit -m "feat(pass): render the balance with ISO 4217 currency scaling"
```

---

### Task 4: Expiry and voided

**Files:**
- Modify: `cashu-voucher-pass/src/main/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapper.java`
- Test: `cashu-voucher-pass/src/test/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapperTest.java`

**Interfaces:**
- Consumes: `toPass(SignedVoucher, MerchantBranding)` from Tasks 2–3.
- Produces: `static PKPass toPass(SignedVoucher, MerchantBranding, VoucherStatus)`. The two-argument form delegates with `voided = false`. When `expires_at` is set, the pass gains `expirationDate` and an auxiliary field keyed `expires`.

- [ ] **Step 1: Write the failing test**

Add to `VoucherPassMapperTest`, with imports:

```java
import de.brendamour.jpasskit.enums.PKDateStyle;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;
import java.time.Instant;
```

```java
    @Test
    @DisplayName("sets expirationDate and an auxiliary expiry field")
    void setsExpiry() {
        long expiresAt = 1893456000L; // 2030-01-01T00:00:00Z
        PKPass pass = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, expiresAt, "Gift card"), MerchantBranding.empty());

        assertThat(pass.getExpirationDate()).isEqualTo(Instant.ofEpochSecond(expiresAt));

        PKField expires = field(pass.getStoreCard().getAuxiliaryFields(), "expires");
        assertThat(expires.getValue()).isEqualTo(Instant.ofEpochSecond(expiresAt));
        assertThat(expires.getDateStyle()).isEqualTo(PKDateStyle.PKDateStyleMedium);
    }

    @Test
    @DisplayName("omits expiry entirely when the voucher has none")
    void omitsExpiryWhenAbsent() {
        PKPass pass = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, null, "Gift card"), MerchantBranding.empty());

        assertThat(pass.getExpirationDate()).isNull();
        assertThat(pass.getStoreCard().getAuxiliaryFields())
                .extracting(PKField::getKey).doesNotContain("expires");
    }

    @Test
    @DisplayName("voids the pass for redeemed and revoked vouchers only")
    void voidsForTerminalStatuses() {
        SignedVoucher v = voucher("eur", 5000L, 2, null, "Gift card");

        assertThat(VoucherPassMapper.toPass(v, MerchantBranding.empty(), VoucherStatus.REDEEMED)
                .isVoided()).isTrue();
        assertThat(VoucherPassMapper.toPass(v, MerchantBranding.empty(), VoucherStatus.REVOKED)
                .isVoided()).isTrue();
        assertThat(VoucherPassMapper.toPass(v, MerchantBranding.empty(), VoucherStatus.ISSUED)
                .isVoided()).isFalse();
    }

    @Test
    @DisplayName("defaults to not voided when no status is supplied")
    void defaultsToNotVoided() {
        assertThat(VoucherPassMapper.toPass(voucher("eur", 5000L, 2, null, "Gift card"),
                MerchantBranding.empty()).isVoided()).isFalse();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest`
Expected: FAIL — compilation error, no three-argument `toPass`.

- [ ] **Step 3: Write the implementation**

In `VoucherPassMapper.java`, add imports:

```java
import de.brendamour.jpasskit.enums.PKDateStyle;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;
import java.time.Instant;
```

Replace the existing `toPass` method with:

```java
    /**
     * Renders a voucher as a store card, not voided.
     *
     * @param voucher the signed voucher; must not be null
     * @param branding merchant branding, or null for defaults
     * @return the pass
     */
    public static PKPass toPass(@NonNull SignedVoucher voucher, MerchantBranding branding) {
        return toPass(voucher, branding, null);
    }

    /**
     * Renders a voucher as a store card, voided according to ledger status.
     *
     * <p>{@link VoucherStatus} is not carried on {@link SignedVoucher} — it comes from
     * the ledger — so the caller resolves it. A null status means not voided.
     *
     * @param voucher the signed voucher; must not be null
     * @param branding merchant branding, or null for defaults
     * @param status ledger status, or null
     * @return the pass
     */
    public static PKPass toPass(@NonNull SignedVoucher voucher, MerchantBranding branding,
                                VoucherStatus status) {
        MerchantBranding b = branding != null ? branding : MerchantBranding.empty();
        VoucherSecret secret = voucher.getSecret();

        PKGenericPassBuilder storeCard = PKGenericPass.builder()
                .passType(PKPassType.PKStoreCard);

        storeCard.primaryFieldBuilder(balanceField(secret));

        Instant expiresAt = expiresAt(secret);
        if (expiresAt != null) {
            storeCard.auxiliaryFieldBuilder(PKField.builder()
                    .key("expires")
                    .label("EXPIRES")
                    .value(expiresAt)
                    .dateStyle(PKDateStyle.PKDateStyleMedium));
        }

        backFields(secret).forEach(storeCard::backField);

        PKPassBuilder pass = PKPass.builder()
                .formatVersion(1)
                .passTypeIdentifier(PASS_TYPE_IDENTIFIER)
                .teamIdentifier(TEAM_IDENTIFIER)
                .serialNumber(String.valueOf(secret.getVoucherId()))
                .description(description(secret, b))
                .organizationName(organizationName(secret, b))
                .voided(isVoided(status))
                .pass(storeCard);

        if (expiresAt != null) {
            pass.expirationDate(expiresAt);
        }

        return build(pass);
    }

    private static Instant expiresAt(VoucherSecret secret) {
        Long expiresAt = secret.getExpiresAt();
        return expiresAt != null ? Instant.ofEpochSecond(expiresAt) : null;
    }

    /**
     * A pass is voided once the voucher can no longer be spent. {@code EXPIRED} is
     * excluded deliberately: {@code expirationDate} already communicates it, and
     * renderers grey the card out on that alone.
     */
    private static boolean isVoided(VoucherStatus status) {
        return status == VoucherStatus.REDEEMED || status == VoucherStatus.REVOKED;
    }
```

Note that `PKFieldBuilder` rejects a field having both `currencyCode` and `dateStyle`, so the balance and expiry must stay separate fields. They are.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest`
Expected: PASS, 14 tests.

- [ ] **Step 5: Commit**

```bash
git add cashu-voucher-pass/src
git commit -m "feat(pass): add expiry and voided status to the pass"
```

---

### Task 5: Branding — colours, logo text, image URLs

**Files:**
- Modify: `cashu-voucher-pass/src/main/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapper.java`
- Test: `cashu-voucher-pass/src/test/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapperTest.java`

**Interfaces:**
- Consumes: `toPass(SignedVoucher, MerchantBranding, VoucherStatus)` from Task 4.
- Produces: the pass gains `backgroundColor`, `foregroundColor`, `labelColor`, `logoText`, and a `userInfo` map containing `voucherId` always, plus `logoUrl` and `stripUrl` when branding supplies them.

`pass.json` has no image fields — Apple carries images as files inside the `.pkpass` bundle. We emit JSON only, so URLs go in `userInfo`, the app-private dictionary our renderer reads.

- [ ] **Step 1: Write the failing test**

Add to `VoucherPassMapperTest`, with import:

```java
import java.util.Map;
```

```java
    private static final MerchantBranding FULL_BRANDING = new MerchantBranding(
            "Corner Cafe",
            "https://blossom.example/logo.png",
            "https://blossom.example/banner.png",
            "Best coffee in town",
            "rgb(10,20,30)",
            "rgb(240,240,240)");

    @Test
    @DisplayName("applies merchant branding")
    void appliesBranding() {
        PKPass pass = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, null, "Gift card"), FULL_BRANDING);

        assertThat(pass.getOrganizationName()).isEqualTo("Corner Cafe");
        assertThat(pass.getLogoText()).isEqualTo("Corner Cafe");
        assertThat(pass.getBackgroundColor()).isEqualTo("rgb(10,20,30)");
        assertThat(pass.getForegroundColor()).isEqualTo("rgb(240,240,240)");
        assertThat(pass.getLabelColor()).isEqualTo("rgb(240,240,240)");
    }

    @Test
    @DisplayName("puts image URLs in userInfo alongside the voucher id")
    void putsImageUrlsInUserInfo() {
        Map<String, Object> userInfo = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, null, "Gift card"), FULL_BRANDING).getUserInfo();

        assertThat(userInfo)
                .containsEntry("voucherId", "11111111-2222-3333-4444-555555555555")
                .containsEntry("logoUrl", "https://blossom.example/logo.png")
                .containsEntry("stripUrl", "https://blossom.example/banner.png");
    }

    @Test
    @DisplayName("omits absent image URLs rather than storing nulls")
    void omitsAbsentImageUrls() {
        Map<String, Object> userInfo = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, null, "Gift card"), MerchantBranding.empty()).getUserInfo();

        assertThat(userInfo).containsOnlyKeys("voucherId");
    }

    @Test
    @DisplayName("falls back to defaults for null and empty branding")
    void fallsBackForMissingBranding() {
        SignedVoucher v = voucher("eur", 5000L, 2, null, null);

        for (MerchantBranding branding : new MerchantBranding[]{null, MerchantBranding.empty()}) {
            PKPass pass = VoucherPassMapper.toPass(v, branding);

            assertThat(pass.getOrganizationName()).isEqualTo(ISSUER_ID);
            assertThat(pass.getDescription()).isEqualTo("Gift Card");
            assertThat(pass.getBackgroundColor()).isEqualTo("rgb(20,20,20)");
            assertThat(pass.getForegroundColor()).isEqualTo("rgb(255,255,255)");
            assertThat(pass.getLogoText()).isNull();
        }
    }

    @Test
    @DisplayName("uses storeDescription when the voucher has no memo")
    void usesStoreDescriptionWhenNoMemo() {
        PKPass pass = VoucherPassMapper.toPass(voucher("eur", 5000L, 2, null, null), FULL_BRANDING);

        assertThat(pass.getDescription()).isEqualTo("Best coffee in town");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest`
Expected: FAIL — `getLogoText()`, colours and `getUserInfo()` are all null.

- [ ] **Step 3: Write the implementation**

In `VoucherPassMapper.java`, add imports:

```java
import java.util.LinkedHashMap;
import java.util.Map;
```

Add constants beside the existing ones:

```java
    static final String DEFAULT_BACKGROUND_COLOR = "rgb(20,20,20)";
    static final String DEFAULT_FOREGROUND_COLOR = "rgb(255,255,255)";
```

In `toPass`, chain onto the `PKPassBuilder` immediately after `.voided(isVoided(status))`:

```java
                .logoText(b.organizationName())
                .backgroundColor(orDefault(b.backgroundColor(), DEFAULT_BACKGROUND_COLOR))
                .foregroundColor(orDefault(b.foregroundColor(), DEFAULT_FOREGROUND_COLOR))
                .labelColor(orDefault(b.foregroundColor(), DEFAULT_FOREGROUND_COLOR))
                .userInfo(userInfo(secret, b))
```

Add these methods:

```java
    private static String orDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    /**
     * The app-private dictionary our renderer reads.
     *
     * <p>{@code pass.json} has no image fields — Apple carries images as files inside
     * the {@code .pkpass} bundle, referenced by filename convention. We emit JSON only,
     * so branding URLs live here. Absent URLs are omitted rather than stored as nulls.
     *
     * <p>{@code voucherId} links the card to whichever proofs the wallet already holds.
     * No bearer secret is ever placed in a pass.
     */
    private static Map<String, Object> userInfo(VoucherSecret secret, MerchantBranding b) {
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("voucherId", String.valueOf(secret.getVoucherId()));
        if (b.logoUrl() != null && !b.logoUrl().isBlank()) {
            userInfo.put("logoUrl", b.logoUrl());
        }
        if (b.bannerUrl() != null && !b.bannerUrl().isBlank()) {
            userInfo.put("stripUrl", b.bannerUrl());
        }
        return Map.copyOf(userInfo);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest`
Expected: PASS, 19 tests.

- [ ] **Step 5: Commit**

```bash
git add cashu-voucher-pass/src
git commit -m "feat(pass): apply merchant branding and carry image URLs in userInfo"
```

---

### Task 6: Barcode and the golden document

**Files:**
- Modify: `cashu-voucher-pass/src/main/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapper.java`
- Test: `cashu-voucher-pass/src/test/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapperTest.java`

**Interfaces:**
- Consumes: everything from Tasks 2–5.
- Produces: exactly one entry in `barcodes`, QR format, message `voucher:<uuid>`, UTF-8, `altText` the bare UUID. This completes `VoucherPassMapper`; nothing further depends on it.

The `voucher:` prefix exists because every payload imani's scanner accepts is prefix-discriminated (`packages/imani-qr/src/detector/patterns.ts`): `vreqa`, `cashua`/`cashub`, `npub1`, `ur:bytes/`. A bare UUID would fall through to `UNKNOWN`.

This barcode is **not** the wallet's existing share QR, which carries the raw token as an animated NUT-16 sequence. That one hands over bearer value; this one is a redemption identifier. They must stay distinct.

- [ ] **Step 1: Write the failing test**

Add to `VoucherPassMapperTest`, with imports:

```java
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.brendamour.jpasskit.PKBarcode;
import de.brendamour.jpasskit.enums.PKBarcodeFormat;
```

```java
    @Test
    @DisplayName("emits exactly one prefixed QR barcode with a bare-UUID altText")
    void emitsPrefixedQrBarcode() {
        PKPass pass = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, null, "Gift card"), MerchantBranding.empty());

        assertThat(pass.getBarcodes()).hasSize(1);
        PKBarcode barcode = pass.getBarcodes().get(0);

        assertThat(barcode.getFormat()).isEqualTo(PKBarcodeFormat.PKBarcodeFormatQR);
        assertThat(barcode.getMessage())
                .isEqualTo("voucher:11111111-2222-3333-4444-555555555555");
        assertThat(barcode.getMessageEncoding()).isEqualTo("UTF-8");
        assertThat(barcode.getAltText())
                .isEqualTo("11111111-2222-3333-4444-555555555555")
                .doesNotStartWith("voucher:");
    }

    @Test
    @DisplayName("serialises to the expected pass.json document")
    void serialisesToGoldenDocument() throws Exception {
        SignedVoucher v = voucher("eur", 5000L, 2, 1893456000L, "Gift card");
        PKPass pass = VoucherPassMapper.toPass(v, FULL_BRANDING, VoucherStatus.ISSUED);

        ObjectMapper mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        JsonNode json = mapper.valueToTree(pass);

        assertThat(json.path("formatVersion").asInt()).isEqualTo(1);
        assertThat(json.path("serialNumber").asText())
                .isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(json.path("organizationName").asText()).isEqualTo("Corner Cafe");
        assertThat(json.path("description").asText()).isEqualTo("Gift card");
        assertThat(json.path("backgroundColor").asText()).isEqualTo("rgb(10,20,30)");

        // storeCard is present; the other four styles are not
        assertThat(json.has("storeCard")).isTrue();
        assertThat(json.has("generic")).isFalse();
        assertThat(json.has("coupon")).isFalse();
        assertThat(json.has("eventTicket")).isFalse();
        assertThat(json.has("boardingPass")).isFalse();

        JsonNode balance = json.path("storeCard").path("primaryFields").get(0);
        assertThat(balance.path("key").asText()).isEqualTo("balance");
        assertThat(balance.path("currencyCode").asText()).isEqualTo("EUR");
        assertThat(balance.path("value").decimalValue()).isEqualByComparingTo("50.00");

        JsonNode barcode = json.path("barcodes").get(0);
        assertThat(barcode.path("format").asText()).isEqualTo("PKBarcodeFormatQR");
        assertThat(barcode.path("message").asText())
                .isEqualTo("voucher:11111111-2222-3333-4444-555555555555");

        assertThat(json.path("userInfo").path("stripUrl").asText())
                .isEqualTo("https://blossom.example/banner.png");

        // the signature must never reach the document
        assertThat(json.toString()).doesNotContain(v.getSecret().getIssuerSignature());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest`
Expected: FAIL — `getBarcodes()` is empty.

- [ ] **Step 3: Write the implementation**

In `VoucherPassMapper.java`, add imports:

```java
import de.brendamour.jpasskit.PKBarcode;
import de.brendamour.jpasskit.PKBarcodeBuilder;
import de.brendamour.jpasskit.enums.PKBarcodeFormat;
import java.nio.charset.StandardCharsets;
```

Add the constant:

```java
    /**
     * Prefix matching imani-qr's discriminated payload taxonomy. A bare UUID would
     * fall through to {@code UNKNOWN} in the wallet's scanner.
     */
    static final String BARCODE_PREFIX = "voucher:";
```

In `toPass`, chain onto the `PKPassBuilder` after `.userInfo(...)`:

```java
                .barcodeBuilder(barcode(secret))
```

Add the method:

```java
    /**
     * A redemption code, not a transfer code.
     *
     * <p>The wallet's share QR carries the raw token as an animated NUT-16 sequence and
     * hands over bearer value. This one carries an identifier the merchant resolves
     * against the ledger. Conflating them would let a merchant scanning a customer's
     * card receive the whole token instead of redeeming against it.
     *
     * <p>{@code altText} is the bare UUID — it exists for a cashier to key in when a
     * scanner fails, so it carries no prefix.
     */
    private static PKBarcodeBuilder barcode(VoucherSecret secret) {
        String voucherId = String.valueOf(secret.getVoucherId());
        return PKBarcode.builder()
                .format(PKBarcodeFormat.PKBarcodeFormatQR)
                .message(BARCODE_PREFIX + voucherId)
                .messageEncoding(StandardCharsets.UTF_8)
                .altText(voucherId);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest`
Expected: PASS, 21 tests.

- [ ] **Step 5: Run the whole build**

Run: `mvn -q verify`
Expected: BUILD SUCCESS. All four modules compile and their tests pass.

- [ ] **Step 6: Commit**

```bash
git add cashu-voucher-pass/src
git commit -m "feat(pass): emit the voucher: prefixed QR barcode"
```

---

## Follow-up work, deliberately out of scope

Neither blocks this module.

1. **imani-qr scanner support** (imani-apps): a `QrType.VOUCHER_REDEEM` entry with pattern `/^voucher:/i` in `packages/imani-qr/src/detector/patterns.ts` plus `TYPE_DESCRIPTIONS`, and a handler registered in `packages/imani-qr/src/handlers/HandlerRegistry.ts`. Until it lands the QR is readable by any generic scanner but classifies as `UNKNOWN` in imani's own.

2. **Brand colours** (possa-merchant): neither the kind-0 profile nor the kind-30078 `imani:merchant` event carries `backgroundColor`/`foregroundColor`, so every pass renders with the defaults. This also bears on legibility — the banner-as-strip image sits behind the balance field, so the renderer needs a scrim or a guaranteed-contrast foreground colour.
