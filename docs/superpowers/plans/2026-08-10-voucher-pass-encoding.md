# Voucher Pass Encoding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `cashu-voucher-pass` module that maps a `SignedVoucher` to an Apple `pass.json` document, so vouchers can be rendered as store cards by imani's own wallet.

**Architecture:** A small Jackson-annotated record tree (`PassJson`) modelling the subset of `pass.json` we emit, plus one pure static mapper (`VoucherPassMapper`) turning a `SignedVoucher` and caller-supplied `MerchantBranding` into it. No I/O, no ports, no Nostr dependency. The pass is a derived view of the voucher and never round-trips back.

**Tech Stack:** Java 21, Maven, Jackson (already managed by the parent), JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-10-voucher-pass-encoding-design.md`

## Revision note — jPasskit was dropped

The spec's D4 chose `de.brendamour:jpasskit` as the object model, with a stated fallback: *"if the transitive tree proves unacceptable at implementation time, replace with hand-rolled Jackson-annotated records; `VoucherPassMapper`'s public signature would not change."*

The tree was measured and is unacceptable. jPasskit 0.5.7 pulls ~25 transitive artifacts including **`com.eatthepath:pushy`** (an APNs push client) and **12 Netty artifacts**, Guava, commons-codec/io/lang3, `bcpkix`/`bcutil` 1.84 against the parent's pinned `bcprov` 1.78, and `jackson-datatype-jsr310:2.21.3` skewed against the managed Jackson 2.17.0.

Pulling an APNs client and Netty into a module whose spec explicitly rules out the pass update web service is precisely what the fallback exists for. **This plan takes the fallback.** We own a four-record model, add no dependency, and the mapper's inputs are unchanged. The return type becomes `PassJson` instead of `PKPass` — a spec amendment to D6.

A side benefit: because we control the model, `expirationDate` is an ISO-8601 `String` rather than an `Instant`, so no Jackson java-time module is needed anywhere.

## Global Constraints

- Java 21; parent POM `xyz.tcheeric:cashu-voucher:0.8.0` manages all dependency versions — child POMs declare no `<version>` on dependencies.
- **The module's only non-test dependencies are `cashu-voucher-domain`, Jackson annotations, and slf4j.** Never add jPasskit, Nostr, HTTP, or filesystem dependencies.
- Money is `BigDecimal` built with `BigDecimal.valueOf(long, int)`. `double` must never appear on the money path.
- The mapper performs no I/O. Image URLs are passed through as strings.
- Tests use JUnit 5 + AssertJ with `@DisplayName`, matching `cashu-voucher-domain` conventions. Test classes are package-private.
- Constants: `passTypeIdentifier` = `xyz.tcheeric.voucher`, `teamIdentifier` = `imani`. Both are schema-required but semantically meaningless — we target no Apple infrastructure.
- Never enforce Apple's image pixel dimensions in the mapper.
- **Test command:** always pass `-Dsurefire.failIfNoSpecifiedTests=false` alongside `-Dtest=` when using `-am`. Without it Maven fails in `cashu-voucher-domain`, which has no matching test, and you get a misleading failure instead of the real one.

## File Structure

| File | Responsibility |
|---|---|
| `pom.xml` (modify) | Register the module + manage its version |
| `cashu-voucher-pass/pom.xml` (create) | Module dependencies |
| `.../voucher/pass/PassJson.java` (create) | The `pass.json` model — records only, no logic |
| `.../voucher/pass/MerchantBranding.java` (create) | Branding carrier, all fields nullable |
| `.../voucher/pass/VoucherPassMapper.java` (create) | The mapping; only public entry point |
| `.../voucher/pass/PassJsonTest.java` (create) | Serialisation shape |
| `.../voucher/pass/VoucherPassMapperTest.java` (create) | All mapping behaviour |

Package root: `xyz.tcheeric.cashu.voucher.pass`.

---

### Task 1: Module scaffold, PassJson model, MerchantBranding

**Files:**
- Modify: `pom.xml`
- Create: `cashu-voucher-pass/pom.xml`
- Create: `cashu-voucher-pass/src/main/java/xyz/tcheeric/cashu/voucher/pass/PassJson.java`
- Create: `cashu-voucher-pass/src/main/java/xyz/tcheeric/cashu/voucher/pass/MerchantBranding.java`
- Test: `cashu-voucher-pass/src/test/java/xyz/tcheeric/cashu/voucher/pass/PassJsonTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `PassJson` record with components `formatVersion` (int), `passTypeIdentifier`, `teamIdentifier`, `serialNumber`, `description`, `organizationName`, `logoText`, `backgroundColor`, `foregroundColor`, `labelColor`, `expirationDate` (String, ISO-8601), `voided` (boolean), `storeCard` (`PassJson.StoreCard`), `barcodes` (`List<PassJson.Barcode>`), `userInfo` (`Map<String,Object>`); nested records `StoreCard(primaryFields, auxiliaryFields, backFields)` each `List<Field>`, `Field(key, label, value, currencyCode, dateStyle)` with static `Field.of(key, label, value)`, and `Barcode(format, message, messageEncoding, altText)`. Also `MerchantBranding` record with accessors `organizationName()`, `logoUrl()`, `bannerUrl()`, `storeDescription()`, `backgroundColor()`, `foregroundColor()` — all `String`, all nullable — plus `static MerchantBranding empty()`.

**Note on prior work:** an earlier attempt at this task left uncommitted edits adding jPasskit to `pom.xml` and `cashu-voucher-pass/pom.xml`, and created `MerchantBranding.java` + `MerchantBrandingTest.java`. **Remove every jPasskit reference** — the `jpasskit.version` property, both dependencyManagement entries for it, and the module dependency. Keep `MerchantBranding.java` and its test if they match this brief. Delete `MerchantBrandingTest.java` only if you fold its assertions elsewhere; otherwise keep it as-is.

- [ ] **Step 1: Register the module in the parent POM**

In `pom.xml`, add to `<modules>` after `cashu-voucher-app`:

```xml
        <module>cashu-voucher-pass</module>
```

Add to `<dependencyManagement><dependencies>` after the `cashu-voucher-app` entry:

```xml
            <dependency>
                <groupId>xyz.tcheeric</groupId>
                <artifactId>cashu-voucher-pass</artifactId>
                <version>${project.version}</version>
            </dependency>
```

Add **no** `jpasskit.version` property and **no** jpasskit dependency anywhere. If a previous attempt added them, remove them.

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

        <!-- Annotations only. Serialization is the caller's ObjectMapper. -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
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

Create `cashu-voucher-pass/src/test/java/xyz/tcheeric/cashu/voucher/pass/PassJsonTest.java`:

```java
package xyz.tcheeric.cashu.voucher.pass;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PassJson")
class PassJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("omits null fields so the document stays schema-clean")
    void omitsNullFields() throws Exception {
        PassJson pass = new PassJson(
                1, "xyz.tcheeric.voucher", "imani", "serial-1", "A card", "Corner Cafe",
                null, null, null, null, null, false,
                new PassJson.StoreCard(List.of(), null, null), null, null);

        JsonNode json = mapper.valueToTree(pass);

        assertThat(json.has("logoText")).isFalse();
        assertThat(json.has("backgroundColor")).isFalse();
        assertThat(json.has("expirationDate")).isFalse();
        assertThat(json.has("barcodes")).isFalse();
        assertThat(json.has("userInfo")).isFalse();
        assertThat(json.path("storeCard").has("auxiliaryFields")).isFalse();
        // voided is a primitive and is always emitted; false is valid pass.json
        assertThat(json.path("voided").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("serialises a currency field as a JSON number with its code")
    void serialisesCurrencyField() throws Exception {
        PassJson.Field balance =
                new PassJson.Field("balance", "BALANCE", new BigDecimal("50.00"), "EUR", null);

        JsonNode json = mapper.valueToTree(balance);

        assertThat(json.path("value").isNumber()).isTrue();
        assertThat(json.path("value").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(json.path("currencyCode").asText()).isEqualTo("EUR");
        assertThat(json.has("dateStyle")).isFalse();
    }

    @Test
    @DisplayName("Field.of leaves currencyCode and dateStyle unset")
    void fieldOfLeavesOptionalsUnset() {
        PassJson.Field field = PassJson.Field.of("issuer", "Issuer", "corner-cafe");

        assertThat(field.key()).isEqualTo("issuer");
        assertThat(field.label()).isEqualTo("Issuer");
        assertThat(field.value()).isEqualTo("corner-cafe");
        assertThat(field.currencyCode()).isNull();
        assertThat(field.dateStyle()).isNull();
    }

    @Test
    @DisplayName("serialises nested pass structure with the expected key names")
    void serialisesNestedStructure() throws Exception {
        PassJson pass = new PassJson(
                1, "xyz.tcheeric.voucher", "imani", "serial-1", "A card", "Corner Cafe",
                "Corner Cafe", "rgb(20,20,20)", "rgb(255,255,255)", "rgb(255,255,255)",
                "2030-01-01T00:00:00Z", true,
                new PassJson.StoreCard(
                        List.of(PassJson.Field.of("balance", "BALANCE", 1)),
                        List.of(PassJson.Field.of("expires", "EXPIRES", "x")),
                        List.of(PassJson.Field.of("issuer", "Issuer", "corner-cafe"))),
                List.of(new PassJson.Barcode("PKBarcodeFormatQR", "voucher:x", "UTF-8", "x")),
                Map.of("voucherId", "x"));

        JsonNode json = mapper.valueToTree(pass);

        assertThat(json.path("formatVersion").asInt()).isEqualTo(1);
        assertThat(json.path("voided").asBoolean()).isTrue();
        assertThat(json.path("expirationDate").asText()).isEqualTo("2030-01-01T00:00:00Z");
        assertThat(json.path("storeCard").path("primaryFields")).hasSize(1);
        assertThat(json.path("storeCard").path("backFields").get(0).path("key").asText())
                .isEqualTo("issuer");
        assertThat(json.path("barcodes").get(0).path("format").asText())
                .isEqualTo("PKBarcodeFormatQR");
        assertThat(json.path("userInfo").path("voucherId").asText()).isEqualTo("x");
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=PassJsonTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `PassJson` does not exist.

- [ ] **Step 5: Write PassJson**

Create `cashu-voucher-pass/src/main/java/xyz/tcheeric/cashu/voucher/pass/PassJson.java`:

```java
package xyz.tcheeric.cashu.voucher.pass;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * The subset of Apple's {@code pass.json} schema this project emits.
 *
 * <p>We adopt the schema, not the platform: there is no certificate, no
 * {@code .pkpass} container, and no pass update web service. Apple Wallet never
 * renders these documents — imani's own wallet does. {@code passTypeIdentifier} and
 * {@code teamIdentifier} are schema-required and carry no meaning here.
 *
 * <p>Modelled by hand rather than via jPasskit: that library drags an APNs push
 * client and Netty into the dependency tree, which contradicts this module's design.
 *
 * <p>Serialisation is the caller's — this module declares only Jackson annotations.
 * Nulls are omitted so optional keys stay absent rather than appearing as
 * {@code null}, which Apple's schema does not permit.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PassJson(
        int formatVersion,
        String passTypeIdentifier,
        String teamIdentifier,
        String serialNumber,
        String description,
        String organizationName,
        String logoText,
        String backgroundColor,
        String foregroundColor,
        String labelColor,
        /** ISO-8601 instant, or null. A String so no Jackson java-time module is needed. */
        String expirationDate,
        boolean voided,
        StoreCard storeCard,
        List<Barcode> barcodes,
        Map<String, Object> userInfo
) {

    /** Field groups for a store card. Absent groups stay null rather than empty. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StoreCard(
            List<Field> primaryFields,
            List<Field> auxiliaryFields,
            List<Field> backFields
    ) {
    }

    /**
     * One displayed field.
     *
     * <p>{@code currencyCode} and {@code dateStyle} are mutually exclusive — a field is
     * a currency or a date, never both.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Field(
            String key,
            String label,
            Object value,
            String currencyCode,
            String dateStyle
    ) {
        /**
         * A plain field with no currency or date formatting.
         *
         * @param key field key
         * @param label displayed label
         * @param value displayed value
         * @return the field
         */
        public static Field of(String key, String label, Object value) {
            return new Field(key, label, value, null, null);
        }
    }

    /** A machine-readable code. {@code altText} is the human-readable fallback. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Barcode(
            String format,
            String message,
            String messageEncoding,
            String altText
    ) {
    }
}
```

- [ ] **Step 6: Write MerchantBranding**

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

If a `MerchantBrandingTest.java` from the earlier attempt exists and passes against this record, keep it.

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. `PassJsonTest` 4/4, plus `MerchantBrandingTest` if you kept it.

- [ ] **Step 8: Confirm the dependency tree is clean**

Run: `mvn -pl cashu-voucher-pass dependency:tree`

Expected compile-scope dependencies: `cashu-voucher-domain` (and its own tree), `jackson-annotations`, `slf4j-api`. **There must be no `jpasskit`, no `pushy`, no `netty`, no `guava`, and no `bcpkix`/`bcutil`.** If any appear, a jPasskit reference survived somewhere — find and remove it.

- [ ] **Step 9: Commit**

```bash
git add pom.xml cashu-voucher-pass/pom.xml cashu-voucher-pass/src
git commit -m "feat(pass): add cashu-voucher-pass module with the pass.json model"
```

Stage nothing else. `cashu-voucher-domain/dependency-reduced-pom.xml` and any untracked file under `cashu-voucher-domain/src/test` belong to unrelated work — leave them.

---

### Task 2: Minimal valid pass — identity, store card, back fields

**Files:**
- Create: `cashu-voucher-pass/src/main/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapper.java`
- Test: `cashu-voucher-pass/src/test/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapperTest.java`

**Interfaces:**
- Consumes: `PassJson`, `MerchantBranding` from Task 1.
- Produces: `static PassJson VoucherPassMapper.toPass(SignedVoucher voucher, MerchantBranding branding)`. Throws `NullPointerException` on a null voucher; a null `branding` is legal. Later tasks add a three-argument overload taking `VoucherStatus`.

The signed test voucher built here is reused by every later task — keep the helper stable.

- [ ] **Step 1: Write the failing test**

Create `cashu-voucher-pass/src/test/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapperTest.java`:

```java
package xyz.tcheeric.cashu.voucher.pass;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VoucherPassMapper")
class VoucherPassMapperTest {

    private static String issuerPrivateKeyHex;
    static String issuerPublicKeyHex;

    static final String ISSUER_ID = "corner-cafe";
    static final String VOUCHER_ID = "11111111-2222-3333-4444-555555555555";

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
                .voucherId(UUID.fromString(VOUCHER_ID))
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

    /** Finds a field by key, failing the test if absent. */
    static PassJson.Field field(List<PassJson.Field> fields, String key) {
        return fields.stream()
                .filter(f -> key.equals(f.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No field with key '" + key + "' in " + fields));
    }

    @Test
    @DisplayName("produces a store card with all required identity fields")
    void producesStoreCard() {
        PassJson pass = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, null, "Gift card"), MerchantBranding.empty());

        assertThat(pass.formatVersion()).isEqualTo(1);
        assertThat(pass.passTypeIdentifier()).isEqualTo("xyz.tcheeric.voucher");
        assertThat(pass.teamIdentifier()).isEqualTo("imani");
        assertThat(pass.serialNumber()).isEqualTo(VOUCHER_ID);
        assertThat(pass.description()).isEqualTo("Gift card");
        assertThat(pass.organizationName()).isEqualTo(ISSUER_ID);
        assertThat(pass.storeCard()).isNotNull();
    }

    @Test
    @DisplayName("back fields carry voucher id, issuer, issuer key and terms")
    void backFieldsCarryProvenance() {
        List<PassJson.Field> back = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, null, "Gift card"), MerchantBranding.empty())
                .storeCard().backFields();

        assertThat(field(back, "voucherId").value()).isEqualTo(VOUCHER_ID);
        assertThat(field(back, "issuer").value()).isEqualTo(ISSUER_ID);
        assertThat(field(back, "issuerKey").value()).isEqualTo(issuerPublicKeyHex);
        assertThat(field(back, "terms").value().toString()).contains("issuing merchant");
    }

    @Test
    @DisplayName("does not expose the issuer signature anywhere")
    void doesNotExposeSignature() {
        SignedVoucher v = voucher("eur", 5000L, 2, null, "Gift card");

        List<PassJson.Field> back = VoucherPassMapper.toPass(v, MerchantBranding.empty())
                .storeCard().backFields();

        assertThat(back).extracting(PassJson.Field::key).doesNotContain("issuerSig");
        assertThat(back).extracting(f -> String.valueOf(f.value()))
                .doesNotContain(v.getSecret().getIssuerSignature());
    }

    @Test
    @DisplayName("rejects a null voucher")
    void rejectsNullVoucher() {
        assertThatThrownBy(() -> VoucherPassMapper.toPass(null, MerchantBranding.empty()))
                .isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `VoucherPassMapper` does not exist.

- [ ] **Step 3: Write the implementation**

Create `cashu-voucher-pass/src/main/java/xyz/tcheeric/cashu/voucher/pass/VoucherPassMapper.java`:

```java
package xyz.tcheeric.cashu.voucher.pass;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;

import java.util.List;

/**
 * Maps a {@link SignedVoucher} to a {@link PassJson} document.
 *
 * <p>The pass is a <em>derived view</em>: the signed voucher is the only source of
 * truth, and nothing parses a pass back into a voucher. Rendering is a pure function,
 * so a balance change after partial redemption needs no synchronisation — re-render.
 *
 * <p>Apple Wallet is not a target. We adopt the {@code pass.json} schema only; there
 * is no certificate, no {@code .pkpass} container, and no pass update web service.
 */
@Slf4j
public final class VoucherPassMapper {

    static final int FORMAT_VERSION = 1;

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
     * @return the pass document
     */
    public static PassJson toPass(@NonNull SignedVoucher voucher, MerchantBranding branding) {
        MerchantBranding b = branding != null ? branding : MerchantBranding.empty();
        VoucherSecret secret = voucher.getSecret();

        PassJson.StoreCard storeCard = new PassJson.StoreCard(
                null,
                null,
                backFields(secret));

        return new PassJson(
                FORMAT_VERSION,
                PASS_TYPE_IDENTIFIER,
                TEAM_IDENTIFIER,
                String.valueOf(secret.getVoucherId()),
                description(secret, b),
                organizationName(secret, b),
                null,
                null,
                null,
                null,
                null,
                false,
                storeCard,
                null,
                null);
    }

    private static String description(VoucherSecret secret, MerchantBranding b) {
        if (isPresent(secret.getMemo())) {
            return secret.getMemo();
        }
        if (isPresent(b.storeDescription())) {
            return b.storeDescription();
        }
        return DEFAULT_DESCRIPTION;
    }

    private static String organizationName(VoucherSecret secret, MerchantBranding b) {
        return isPresent(b.organizationName()) ? b.organizationName() : secret.getIssuerId();
    }

    /**
     * Provenance shown on the back of the card.
     *
     * <p>Deliberately excludes {@code issuer_sig}: surfacing a signature in a UI
     * invites treatment as a credential. {@code backing_strategy}, {@code issuance_ratio}
     * and {@code merchant_metadata} are excluded too — none has display meaning.
     */
    private static List<PassJson.Field> backFields(VoucherSecret secret) {
        return List.of(
                PassJson.Field.of("voucherId", "Voucher ID", String.valueOf(secret.getVoucherId())),
                PassJson.Field.of("issuer", "Issuer", secret.getIssuerId()),
                PassJson.Field.of("issuerKey", "Issuer Public Key", secret.getIssuerPublicKey()),
                PassJson.Field.of("terms", "Terms", TERMS));
    }

    static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 4 tests.

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
- Consumes: `toPass(SignedVoucher, MerchantBranding)` from Task 2.
- Produces: `storeCard().primaryFields()` contains one field keyed `balance` whose `value()` is a `BigDecimal` in major units and whose `currencyCode()` is uppercase ISO 4217. `toPass` now throws `IllegalArgumentException` for an unknown unit or a negative face value.

- [ ] **Step 1: Write the failing test**

Add to `VoucherPassMapperTest`, with imports:

```java
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThatCode;
```

```java
    private static PassJson.Field balanceOf(String unit, long faceValue, int faceDecimals) {
        return field(VoucherPassMapper.toPass(
                voucher(unit, faceValue, faceDecimals, null, "Gift card"),
                MerchantBranding.empty()).storeCard().primaryFields(), "balance");
    }

    @Test
    @DisplayName("scales the balance into major units with the currency code")
    void scalesBalance() {
        PassJson.Field balance = balanceOf("eur", 5000L, 2);

        assertThat(balance.value()).isEqualTo(new BigDecimal("50.00"));
        assertThat(balance.currencyCode()).isEqualTo("EUR");
        assertThat(balance.label()).isEqualTo("BALANCE");
    }

    @Test
    @DisplayName("handles zero-decimal and three-decimal currencies")
    void handlesOtherCurrencyScales() {
        assertThat(balanceOf("jpy", 5000L, 0).value()).isEqualTo(new BigDecimal("5000"));
        assertThat(balanceOf("jpy", 5000L, 0).currencyCode()).isEqualTo("JPY");
        assertThat(balanceOf("kwd", 5000L, 3).value()).isEqualTo(new BigDecimal("5.000"));
        assertThat(balanceOf("kwd", 5000L, 3).currencyCode()).isEqualTo("KWD");
    }

    @Test
    @DisplayName("trusts face_decimals when it disagrees with the currency default")
    void trustsFaceDecimalsOverCurrencyDefault() {
        // JPY defaults to 0 fraction digits; the voucher says 2. face_decimals wins.
        assertThat(balanceOf("jpy", 5000L, 2).value()).isEqualTo(new BigDecimal("50.00"));
    }

    @Test
    @DisplayName("rejects an unknown currency unit")
    void rejectsUnknownUnit() {
        assertThatThrownBy(() -> VoucherPassMapper.toPass(
                voucher("zzz", 5000L, 2, null, "Gift card"), MerchantBranding.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO 4217");
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
    @DisplayName("accepts an already-uppercase unit")
    void acceptsUppercaseUnit() {
        assertThatCode(() -> VoucherPassMapper.toPass(
                voucher("EUR", 5000L, 2, null, "Gift card"), MerchantBranding.empty()))
                .doesNotThrowAnyException();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `No field with key 'balance'` (primaryFields is null), and the rejection tests fail because nothing throws.

- [ ] **Step 3: Write the implementation**

In `VoucherPassMapper.java`, add imports:

```java
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
```

In `toPass`, replace the `storeCard` construction with:

```java
        PassJson.StoreCard storeCard = new PassJson.StoreCard(
                List.of(balanceField(secret)),
                null,
                backFields(secret));
```

Add these methods:

```java
    /**
     * The balance, as a {@link BigDecimal} in major units with an ISO 4217 code.
     *
     * <p>{@code pass.json} formats currency in major units, so the scaling is required
     * for correctness, not presentation. A {@code BigDecimal} keeps the scale exact and
     * serialises as a JSON number.
     */
    private static PassJson.Field balanceField(VoucherSecret secret) {
        Currency currency = currency(secret.getUnit());
        long faceValue = faceValue(secret);
        int decimals = faceDecimals(secret, currency);

        return new PassJson.Field(
                "balance",
                "BALANCE",
                BigDecimal.valueOf(faceValue, decimals),
                currency.getCurrencyCode(),
                null);
    }

    /**
     * Validates the unit as ISO 4217. {@code Currency.getInstance} throws on an
     * unknown code, so no lookup table is needed.
     *
     * <p>Cashu writes units lowercase; ISO 4217 is uppercase. {@code Locale.ROOT}
     * avoids the Turkish dotless-i trap.
     */
    private static Currency currency(String unit) {
        if (!isPresent(unit)) {
            throw new IllegalArgumentException("Voucher has no unit; expected an ISO 4217 code");
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

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 10 tests. One `face_decimals_mismatch` warning from `trustsFaceDecimalsOverCurrencyDefault`.

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
- Produces: `static PassJson toPass(SignedVoucher, MerchantBranding, VoucherStatus)`. The two-argument form delegates with a null status. When `expires_at` is set the pass gains an ISO-8601 `expirationDate()` and an auxiliary field keyed `expires` with `dateStyle` `PKDateStyleMedium`.

- [ ] **Step 1: Write the failing test**

Add to `VoucherPassMapperTest`, with import:

```java
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;
```

```java
    private static final long EXPIRES_AT = 1893456000L; // 2030-01-01T00:00:00Z

    @Test
    @DisplayName("sets an ISO-8601 expirationDate and an auxiliary expiry field")
    void setsExpiry() {
        PassJson pass = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, EXPIRES_AT, "Gift card"), MerchantBranding.empty());

        assertThat(pass.expirationDate()).isEqualTo("2030-01-01T00:00:00Z");

        PassJson.Field expires = field(pass.storeCard().auxiliaryFields(), "expires");
        assertThat(expires.value()).isEqualTo("2030-01-01T00:00:00Z");
        assertThat(expires.dateStyle()).isEqualTo("PKDateStyleMedium");
        assertThat(expires.currencyCode()).isNull();
    }

    @Test
    @DisplayName("omits expiry entirely when the voucher has none")
    void omitsExpiryWhenAbsent() {
        PassJson pass = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, null, "Gift card"), MerchantBranding.empty());

        assertThat(pass.expirationDate()).isNull();
        assertThat(pass.storeCard().auxiliaryFields()).isNull();
    }

    @Test
    @DisplayName("voids the pass for redeemed and revoked vouchers only")
    void voidsForTerminalStatuses() {
        SignedVoucher v = voucher("eur", 5000L, 2, null, "Gift card");

        assertThat(VoucherPassMapper.toPass(v, MerchantBranding.empty(), VoucherStatus.REDEEMED)
                .voided()).isTrue();
        assertThat(VoucherPassMapper.toPass(v, MerchantBranding.empty(), VoucherStatus.REVOKED)
                .voided()).isTrue();
        assertThat(VoucherPassMapper.toPass(v, MerchantBranding.empty(), VoucherStatus.ISSUED)
                .voided()).isFalse();
    }

    @Test
    @DisplayName("defaults to not voided when no status is supplied")
    void defaultsToNotVoided() {
        assertThat(VoucherPassMapper.toPass(voucher("eur", 5000L, 2, null, "Gift card"),
                MerchantBranding.empty()).voided()).isFalse();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, no three-argument `toPass`.

- [ ] **Step 3: Write the implementation**

In `VoucherPassMapper.java`, add imports:

```java
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
```

Add the constant beside the others:

```java
    static final String DATE_STYLE_MEDIUM = "PKDateStyleMedium";
```

Replace the existing `toPass` method with:

```java
    /**
     * Renders a voucher as a store card, not voided.
     *
     * @param voucher the signed voucher; must not be null
     * @param branding merchant branding, or null for defaults
     * @return the pass document
     */
    public static PassJson toPass(@NonNull SignedVoucher voucher, MerchantBranding branding) {
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
     * @return the pass document
     */
    public static PassJson toPass(@NonNull SignedVoucher voucher, MerchantBranding branding,
                                  VoucherStatus status) {
        MerchantBranding b = branding != null ? branding : MerchantBranding.empty();
        VoucherSecret secret = voucher.getSecret();
        String expirationDate = expirationDate(secret);

        PassJson.StoreCard storeCard = new PassJson.StoreCard(
                List.of(balanceField(secret)),
                expirationDate == null ? null : List.of(expiryField(expirationDate)),
                backFields(secret));

        return new PassJson(
                FORMAT_VERSION,
                PASS_TYPE_IDENTIFIER,
                TEAM_IDENTIFIER,
                String.valueOf(secret.getVoucherId()),
                description(secret, b),
                organizationName(secret, b),
                null,
                null,
                null,
                null,
                expirationDate,
                isVoided(status),
                storeCard,
                null,
                null);
    }

    /** Epoch seconds to an ISO-8601 instant, or null when the voucher never expires. */
    private static String expirationDate(VoucherSecret secret) {
        Long expiresAt = secret.getExpiresAt();
        return expiresAt == null
                ? null
                : DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(expiresAt));
    }

    private static PassJson.Field expiryField(String expirationDate) {
        return new PassJson.Field("expires", "EXPIRES", expirationDate, null, DATE_STYLE_MEDIUM);
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

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest -Dsurefire.failIfNoSpecifiedTests=false`
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
- Produces: the pass gains `backgroundColor()`, `foregroundColor()`, `labelColor()`, `logoText()`, and a `userInfo()` map containing `voucherId` always, plus `logoUrl` and `stripUrl` when branding supplies them.

`pass.json` has no image fields — Apple carries images as files inside the `.pkpass` bundle. We emit JSON only, so URLs go in `userInfo`, the app-private dictionary our renderer reads.

- [ ] **Step 1: Write the failing test**

Add to `VoucherPassMapperTest`, with import:

```java
import java.util.Map;
```

```java
    static final MerchantBranding FULL_BRANDING = new MerchantBranding(
            "Corner Cafe",
            "https://blossom.example/logo.png",
            "https://blossom.example/banner.png",
            "Best coffee in town",
            "rgb(10,20,30)",
            "rgb(240,240,240)");

    @Test
    @DisplayName("applies merchant branding")
    void appliesBranding() {
        PassJson pass = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, null, "Gift card"), FULL_BRANDING);

        assertThat(pass.organizationName()).isEqualTo("Corner Cafe");
        assertThat(pass.logoText()).isEqualTo("Corner Cafe");
        assertThat(pass.backgroundColor()).isEqualTo("rgb(10,20,30)");
        assertThat(pass.foregroundColor()).isEqualTo("rgb(240,240,240)");
        assertThat(pass.labelColor()).isEqualTo("rgb(240,240,240)");
    }

    @Test
    @DisplayName("puts image URLs in userInfo alongside the voucher id")
    void putsImageUrlsInUserInfo() {
        Map<String, Object> userInfo = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, null, "Gift card"), FULL_BRANDING).userInfo();

        assertThat(userInfo)
                .containsEntry("voucherId", VOUCHER_ID)
                .containsEntry("logoUrl", "https://blossom.example/logo.png")
                .containsEntry("stripUrl", "https://blossom.example/banner.png");
    }

    @Test
    @DisplayName("omits absent image URLs rather than storing nulls")
    void omitsAbsentImageUrls() {
        Map<String, Object> userInfo = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, null, "Gift card"), MerchantBranding.empty()).userInfo();

        assertThat(userInfo).containsOnlyKeys("voucherId");
    }

    @Test
    @DisplayName("falls back to defaults for null and empty branding")
    void fallsBackForMissingBranding() {
        SignedVoucher v = voucher("eur", 5000L, 2, null, null);

        for (MerchantBranding branding : new MerchantBranding[]{null, MerchantBranding.empty()}) {
            PassJson pass = VoucherPassMapper.toPass(v, branding);

            assertThat(pass.organizationName()).isEqualTo(ISSUER_ID);
            assertThat(pass.description()).isEqualTo("Gift Card");
            assertThat(pass.backgroundColor()).isEqualTo("rgb(20,20,20)");
            assertThat(pass.foregroundColor()).isEqualTo("rgb(255,255,255)");
            assertThat(pass.logoText()).isNull();
        }
    }

    @Test
    @DisplayName("uses storeDescription when the voucher has no memo")
    void usesStoreDescriptionWhenNoMemo() {
        assertThat(VoucherPassMapper.toPass(voucher("eur", 5000L, 2, null, null), FULL_BRANDING)
                .description()).isEqualTo("Best coffee in town");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `logoText()`, the colours and `userInfo()` are all null.

- [ ] **Step 3: Write the implementation**

In `VoucherPassMapper.java`, add imports:

```java
import java.util.LinkedHashMap;
import java.util.Map;
```

Add constants beside the others:

```java
    static final String DEFAULT_BACKGROUND_COLOR = "rgb(20,20,20)";
    static final String DEFAULT_FOREGROUND_COLOR = "rgb(255,255,255)";
```

In the three-argument `toPass`, replace the four `null` arguments for `logoText`, `backgroundColor`, `foregroundColor` and `labelColor`, and the trailing `null` for `userInfo`, so the constructor call reads:

```java
        return new PassJson(
                FORMAT_VERSION,
                PASS_TYPE_IDENTIFIER,
                TEAM_IDENTIFIER,
                String.valueOf(secret.getVoucherId()),
                description(secret, b),
                organizationName(secret, b),
                b.organizationName(),
                orDefault(b.backgroundColor(), DEFAULT_BACKGROUND_COLOR),
                orDefault(b.foregroundColor(), DEFAULT_FOREGROUND_COLOR),
                orDefault(b.foregroundColor(), DEFAULT_FOREGROUND_COLOR),
                expirationDate,
                isVoided(status),
                storeCard,
                null,
                userInfo(secret, b));
```

Add these methods:

```java
    private static String orDefault(String value, String fallback) {
        return isPresent(value) ? value : fallback;
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
        if (isPresent(b.logoUrl())) {
            userInfo.put("logoUrl", b.logoUrl());
        }
        if (isPresent(b.bannerUrl())) {
            userInfo.put("stripUrl", b.bannerUrl());
        }
        return Map.copyOf(userInfo);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest -Dsurefire.failIfNoSpecifiedTests=false`
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
- Produces: `barcodes()` holds exactly one `PassJson.Barcode` — QR format, message `voucher:<uuid>`, `UTF-8`, `altText` the bare UUID. This completes `VoucherPassMapper`; nothing further depends on it.

The `voucher:` prefix exists because every payload imani's scanner accepts is prefix-discriminated (`packages/imani-qr/src/detector/patterns.ts`): `vreqa`, `cashua`/`cashub`, `npub1`, `ur:bytes/`. A bare UUID would fall through to `UNKNOWN`.

This barcode is **not** the wallet's existing share QR, which carries the raw token as an animated NUT-16 sequence. That one hands over bearer value; this one is a redemption identifier. They must stay distinct.

- [ ] **Step 1: Write the failing test**

Add to `VoucherPassMapperTest`, with imports:

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
```

```java
    @Test
    @DisplayName("emits exactly one prefixed QR barcode with a bare-UUID altText")
    void emitsPrefixedQrBarcode() {
        PassJson pass = VoucherPassMapper.toPass(
                voucher("eur", 5000L, 2, null, "Gift card"), MerchantBranding.empty());

        assertThat(pass.barcodes()).hasSize(1);
        PassJson.Barcode barcode = pass.barcodes().get(0);

        assertThat(barcode.format()).isEqualTo("PKBarcodeFormatQR");
        assertThat(barcode.message()).isEqualTo("voucher:" + VOUCHER_ID);
        assertThat(barcode.messageEncoding()).isEqualTo("UTF-8");
        assertThat(barcode.altText()).isEqualTo(VOUCHER_ID).doesNotStartWith("voucher:");
    }

    @Test
    @DisplayName("serialises to the expected pass.json document")
    void serialisesToGoldenDocument() {
        SignedVoucher v = voucher("eur", 5000L, 2, EXPIRES_AT, "Gift card");
        PassJson pass = VoucherPassMapper.toPass(v, FULL_BRANDING, VoucherStatus.ISSUED);

        JsonNode json = new ObjectMapper().valueToTree(pass);

        assertThat(json.path("formatVersion").asInt()).isEqualTo(1);
        assertThat(json.path("passTypeIdentifier").asText()).isEqualTo("xyz.tcheeric.voucher");
        assertThat(json.path("teamIdentifier").asText()).isEqualTo("imani");
        assertThat(json.path("serialNumber").asText()).isEqualTo(VOUCHER_ID);
        assertThat(json.path("organizationName").asText()).isEqualTo("Corner Cafe");
        assertThat(json.path("description").asText()).isEqualTo("Gift card");
        assertThat(json.path("backgroundColor").asText()).isEqualTo("rgb(10,20,30)");
        assertThat(json.path("expirationDate").asText()).isEqualTo("2030-01-01T00:00:00Z");
        assertThat(json.path("voided").asBoolean()).isFalse();

        JsonNode balance = json.path("storeCard").path("primaryFields").get(0);
        assertThat(balance.path("key").asText()).isEqualTo("balance");
        assertThat(balance.path("currencyCode").asText()).isEqualTo("EUR");
        assertThat(balance.path("value").isNumber()).isTrue();
        assertThat(balance.path("value").decimalValue()).isEqualByComparingTo("50.00");

        JsonNode expires = json.path("storeCard").path("auxiliaryFields").get(0);
        assertThat(expires.path("dateStyle").asText()).isEqualTo("PKDateStyleMedium");
        assertThat(expires.has("currencyCode")).isFalse();

        assertThat(json.path("storeCard").path("backFields")).hasSize(4);

        JsonNode barcode = json.path("barcodes").get(0);
        assertThat(barcode.path("format").asText()).isEqualTo("PKBarcodeFormatQR");
        assertThat(barcode.path("message").asText()).isEqualTo("voucher:" + VOUCHER_ID);
        assertThat(barcode.path("altText").asText()).isEqualTo(VOUCHER_ID);

        assertThat(json.path("userInfo").path("stripUrl").asText())
                .isEqualTo("https://blossom.example/banner.png");

        // the issuer signature must never reach the document
        assertThat(json.toString()).doesNotContain(v.getSecret().getIssuerSignature());
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `barcodes()` is null.

- [ ] **Step 3: Write the implementation**

In `VoucherPassMapper.java`, add the constants:

```java
    /**
     * Prefix matching imani-qr's discriminated payload taxonomy. A bare UUID would
     * fall through to {@code UNKNOWN} in the wallet's scanner.
     */
    static final String BARCODE_PREFIX = "voucher:";

    static final String BARCODE_FORMAT_QR = "PKBarcodeFormatQR";
```

In the three-argument `toPass`, replace the `null` argument for `barcodes` with `List.of(barcode(secret))`.

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
    private static PassJson.Barcode barcode(VoucherSecret secret) {
        String voucherId = String.valueOf(secret.getVoucherId());
        return new PassJson.Barcode(
                BARCODE_FORMAT_QR,
                BARCODE_PREFIX + voucherId,
                StandardCharsets.UTF_8.name(),
                voucherId);
    }
```

Add the import:

```java
import java.nio.charset.StandardCharsets;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl cashu-voucher-pass -am test -Dtest=VoucherPassMapperTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 21 tests.

- [ ] **Step 5: Run the whole build**

Run: `mvn -q verify -DskipITs`
Expected: BUILD SUCCESS across all four modules.

If pre-existing failures appear in `cashu-voucher-domain` or `cashu-voucher-nostr` that your changes could not have caused (for example integration tests needing a live Nostr relay, which the README documents as expected), report them as a concern rather than fixing them — they are outside this task.

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

3. **Spec amendment to D4/D6**: the spec still names jPasskit as the object model and `PKPass` as the return type. Both are superseded by this plan's revision note. Update the spec when the branch lands.
