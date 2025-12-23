# Domain Entities Reference

This reference documents the core domain entities in the `cashu-voucher-domain` module.

## VoucherSecret

The core value object representing a gift card voucher.

**Package:** `xyz.tcheeric.cashu.voucher.domain`

**Implements:** `Secret` (from cashu-lib)

### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `voucherId` | `String` | Yes | Unique identifier (UUID format) |
| `issuerId` | `String` | Yes | Merchant identifier |
| `unit` | `String` | Yes | Currency unit (e.g., "sat", "eur", "usd") |
| `faceValue` | `long` | Yes | Amount in smallest unit (must be positive) |
| `expiresAt` | `Long` | No | Unix epoch seconds for expiry |
| `memo` | `String` | No | Human-readable description |
| `backingStrategy` | `BackingStrategy` | Yes | Token backing approach |
| `issuanceRatio` | `double` | Yes | Face value per sat (must be positive) |
| `faceDecimals` | `int` | Yes | Decimal places for currency (non-negative) |
| `merchantMetadata` | `Map<String, Object>` | No | Custom business data |

### Factory Methods

```java
// Auto-generate voucher ID
VoucherSecret create(
    String issuerId,
    String unit,
    long faceValue,
    Long expiresAt,
    String memo,
    BackingStrategy backingStrategy,
    double issuanceRatio,
    int faceDecimals,
    Map<String, Object> merchantMetadata
)

// Specify voucher ID
VoucherSecret create(
    String voucherId,
    String issuerId,
    String unit,
    long faceValue,
    Long expiresAt,
    String memo,
    BackingStrategy backingStrategy,
    double issuanceRatio,
    int faceDecimals,
    Map<String, Object> merchantMetadata
)

// Builder pattern
VoucherSecret.Builder builder()
```

### Instance Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `toCanonicalBytes()` | `byte[]` | CBOR-encoded bytes for signing |
| `toHexString()` | `String` | Hex-encoded canonical bytes |
| `toBytes()` | `byte[]` | Alias for `toCanonicalBytes()` |
| `getData()` | `byte[]` | Alias for `toCanonicalBytes()` |
| `isExpired()` | `boolean` | True if current time > expiresAt |
| `isValid()` | `boolean` | True if not expired |
| `toStringWithMetadata()` | `String` | Detailed string representation |

### Serialization

**CBOR (Canonical):** Fields ordered alphabetically:
1. backingStrategy
2. expiresAt (if present)
3. faceDecimals
4. faceValue
5. issuanceRatio
6. issuerId
7. memo (if present)
8. merchantMetadata (if not empty)
9. unit
10. voucherId

**JSON:** Uses `VoucherSecretSerializer` to serialize as hex string.

### Validation

Throws `IllegalArgumentException` for:
- `faceValue <= 0`
- Blank `voucherId`, `issuerId`, or `unit`
- `expiresAt <= 0` (when provided)
- `issuanceRatio <= 0`
- `faceDecimals < 0`

---

## SignedVoucher

A voucher with a BIP-340 Schnorr cryptographic signature.

**Package:** `xyz.tcheeric.cashu.voucher.domain`

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `secret` | `VoucherSecret` | The voucher data |
| `issuerSignature` | `byte[]` | Schnorr signature (64 bytes) |
| `issuerPublicKey` | `String` | Hex-encoded x-only public key |

### Constructor

```java
SignedVoucher(
    VoucherSecret secret,
    byte[] issuerSignature,    // Must be 64 bytes
    String issuerPublicKey     // Hex string
)
```

### Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `verify()` | `boolean` | Validates Schnorr signature |
| `isExpired()` | `boolean` | Delegates to secret.isExpired() |
| `isValid()` | `boolean` | True if signature valid AND not expired |
| `getIssuerSignature()` | `byte[]` | Defensive copy of signature |
| `toStringWithMetadata()` | `String` | Detailed string representation |

### Validation

Throws `IllegalArgumentException` for:
- `issuerSignature.length != 64`
- Blank `issuerPublicKey`

---

## VoucherStatus

Enumeration of voucher lifecycle states.

**Package:** `xyz.tcheeric.cashu.voucher.domain`

### Values

| Status | Terminal | Can Redeem | Description |
|--------|----------|------------|-------------|
| `ISSUED` | No | Yes | Active, ready for redemption |
| `REDEEMED` | Yes | No | Successfully used |
| `REVOKED` | Yes | No | Cancelled by issuer |
| `EXPIRED` | Yes | No | Time limit exceeded |

### Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `isTerminal()` | `boolean` | True for REDEEMED, REVOKED, EXPIRED |
| `canBeRedeemed()` | `boolean` | True only for ISSUED |
| `getDescription()` | `String` | Human-readable description |

---

## BackingStrategy

Enumeration for token backing and split capability.

**Package:** `xyz.tcheeric.cashu.voucher.domain`

### Values

| Strategy | Splittable | Fine-Grained | Use Case |
|----------|------------|--------------|----------|
| `FIXED` | No | No | Tickets, event passes |
| `MINIMAL` | Yes | No | Gift cards |
| `PROPORTIONAL` | Yes | Yes | Split payments |

### Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `isSplittable()` | `boolean` | True for MINIMAL, PROPORTIONAL |
| `hasFineGrainedSplits()` | `boolean` | True only for PROPORTIONAL |

---

## VoucherSignatureService

Utility class for BIP-340 Schnorr signature operations (secp256k1).

**Package:** `xyz.tcheeric.cashu.voucher.domain`

### Static Methods

```java
// Sign a voucher
byte[] sign(VoucherSecret secret, String privateKeyHex)

// Verify signature
boolean verify(VoucherSecret secret, byte[] signature, String publicKeyHex)

// Create signed voucher in one step
SignedVoucher createSigned(
    VoucherSecret secret,
    String privateKeyHex,
    String publicKeyHex
)
```

### Key Format

- **Private Key:** Hex-encoded, 64 characters (32 bytes) - secp256k1 scalar
- **Public Key:** Hex-encoded, 64 characters (32 bytes) - x-only secp256k1 point
- **Signature:** 64 bytes (BIP-340 Schnorr standard)

---

## VoucherValidator

Utility class for comprehensive voucher validation.

**Package:** `xyz.tcheeric.cashu.voucher.domain`

### Static Methods

```java
// Full validation (signature + expiry)
ValidationResult validate(SignedVoucher voucher)

// Validate with expected issuer
ValidationResult validateWithIssuer(SignedVoucher voucher, String expectedIssuerId)

// Signature only
ValidationResult validateSignatureOnly(SignedVoucher voucher)

// Expiry only
ValidationResult validateExpiryOnly(SignedVoucher voucher)
```

### ValidationResult

| Field/Method | Type | Description |
|--------------|------|-------------|
| `isValid()` | `boolean` | True if no errors |
| `getErrors()` | `List<String>` | List of error messages |
| `getErrorMessage()` | `String` | Errors joined by "; " |

---

## VoucherSerializationUtils

Utility class for CBOR serialization.

**Package:** `xyz.tcheeric.cashu.voucher.domain.util`

### Static Methods

```java
// Serialize map to CBOR
byte[] toCbor(Map<String, Object> map)

// Deserialize CBOR to map
Map<String, Object> fromCbor(byte[] bytes)
```

---

## Class Diagram

```
┌────────────────────────────────────────┐
│           <<interface>>                │
│              Secret                    │
└───────────────┬────────────────────────┘
                │
                │ implements
                ▼
┌────────────────────────────────────────┐
│           VoucherSecret                │
├────────────────────────────────────────┤
│ - voucherId: String                    │
│ - issuerId: String                     │
│ - unit: String                         │
│ - faceValue: long                      │
│ - expiresAt: Long                      │
│ - memo: String                         │
│ - backingStrategy: BackingStrategy     │
│ - issuanceRatio: double                │
│ - faceDecimals: int                    │
│ - merchantMetadata: Map                │
├────────────────────────────────────────┤
│ + toCanonicalBytes(): byte[]           │
│ + isExpired(): boolean                 │
│ + isValid(): boolean                   │
└───────────────┬────────────────────────┘
                │
                │ contains
                ▼
┌────────────────────────────────────────┐
│          SignedVoucher                 │
├────────────────────────────────────────┤
│ - secret: VoucherSecret                │
│ - issuerSignature: byte[]              │
│ - issuerPublicKey: String              │
├────────────────────────────────────────┤
│ + verify(): boolean                    │
│ + isExpired(): boolean                 │
│ + isValid(): boolean                   │
└────────────────────────────────────────┘

┌──────────────────┐    ┌──────────────────┐
│  <<enum>>        │    │  <<enum>>        │
│ VoucherStatus    │    │ BackingStrategy  │
├──────────────────┤    ├──────────────────┤
│ ISSUED           │    │ FIXED            │
│ REDEEMED         │    │ MINIMAL          │
│ REVOKED          │    │ PROPORTIONAL     │
│ EXPIRED          │    │                  │
└──────────────────┘    └──────────────────┘
```

## Related

- [VoucherService Reference](./voucher-service.md)
- [Ports Reference](./ports.md)
- [Getting Started Tutorial](../tutorials/getting-started.md)
