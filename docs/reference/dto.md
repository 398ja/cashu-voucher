# Data Transfer Objects Reference

This reference documents the DTOs (Data Transfer Objects) in the `cashu-voucher-app` module used for API boundaries.

## IssueVoucherRequest

Request DTO for voucher issuance.

**Package:** `xyz.tcheeric.cashu.voucher.app.dto`

### Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `voucherId` | `String` | No | Custom voucher ID (auto-generated if not provided) |
| `issuerId` | `String` | Yes | Merchant/issuer identifier |
| `unit` | `String` | Yes | Currency unit (e.g., "sat", "eur", "usd") |
| `amount` | `Long` | Yes | Face value in smallest unit (must be positive) |
| `expiresInDays` | `Integer` | No | Days until expiry |
| `memo` | `String` | No | Human-readable description |
| `backingStrategy` | `BackingStrategy` | Yes | Token backing approach |
| `issuanceRatio` | `double` | Yes | Face value per sat |
| `faceDecimals` | `int` | Yes | Decimal places for currency |
| `merchantMetadata` | `Map<String, Object>` | No | Custom business data |

### Builder Pattern

```java
IssueVoucherRequest request = IssueVoucherRequest.builder()
    .issuerId("coffee-shop")
    .unit("sat")
    .amount(10000L)
    .expiresInDays(365)
    .memo("Holiday gift card")
    .backingStrategy(BackingStrategy.MINIMAL)
    .issuanceRatio(1.0)
    .faceDecimals(0)
    .merchantMetadata(Map.of("campaign", "holiday-2024"))
    .build();
```

### Validation

The `VoucherService` validates:
- `issuerId` must not be null or blank
- `unit` must not be null or blank
- `amount` must be positive
- `expiresInDays` must be positive (if provided)

---

## IssueVoucherResponse

Response DTO from voucher issuance.

**Package:** `xyz.tcheeric.cashu.voucher.app.dto`

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `voucher` | `SignedVoucher` | The issued and signed voucher |
| `token` | `String` | Cashu token format string |

### Convenience Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `getVoucherId()` | `String` | `voucher.getSecret().getVoucherId()` |
| `getAmount()` | `Long` | `voucher.getSecret().getFaceValue()` |
| `getUnit()` | `String` | `voucher.getSecret().getUnit()` |

### Builder Pattern

```java
IssueVoucherResponse response = IssueVoucherResponse.builder()
    .voucher(signedVoucher)
    .token("cashuA...")
    .build();
```

---

## RedeemVoucherRequest

Request DTO for voucher redemption.

**Package:** `xyz.tcheeric.cashu.voucher.app.dto`

### Fields

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `token` | `String` | Yes | - | Original Cashu token |
| `merchantId` | `String` | Yes | - | Merchant identifier |
| `verifyOnline` | `Boolean` | No | `true` | Use online verification |

### Builder Pattern

```java
RedeemVoucherRequest request = RedeemVoucherRequest.builder()
    .token("cashuA...")
    .merchantId("coffee-shop")
    .verifyOnline(true)
    .build();
```

### Verification Modes

| Mode | When to Use |
|------|-------------|
| `verifyOnline=true` | Production (prevents double-spend) |
| `verifyOnline=false` | Offline fallback (no double-spend protection) |

---

## RedeemVoucherResponse

Response DTO from voucher redemption.

**Package:** `xyz.tcheeric.cashu.voucher.app.dto`

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `success` | `boolean` | True if redemption succeeded |
| `voucher` | `SignedVoucher` | The redeemed voucher (if success) |
| `errorMessage` | `String` | Error details (if failure) |

### Static Factory Methods

```java
// Success
RedeemVoucherResponse.success(SignedVoucher voucher)

// Failure
RedeemVoucherResponse.failure(String errorMessage)
```

### Convenience Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `getAmount()` | `Long` | Face value (null if failure) |
| `getUnit()` | `String` | Currency unit (null if failure) |
| `getVoucherId()` | `String` | Voucher ID (null if failure) |

### Usage Example

```java
RedeemVoucherResponse response = merchantService.redeem(request, voucher);

if (response.isSuccess()) {
    System.out.println("Redeemed: " + response.getAmount() + " " + response.getUnit());
    // Process payment
} else {
    System.err.println("Failed: " + response.getErrorMessage());
}
```

---

## StoredVoucher

Extended voucher DTO with storage metadata.

**Package:** `xyz.tcheeric.cashu.voucher.app.dto`

### Fields

| Field | Type | Description |
|-------|------|-------------|
| (inherited) | `SignedVoucher` | Base voucher data |
| `addedAt` | `Instant` | When added to wallet |
| `lastBackupAt` | `Instant` | Last backup timestamp |
| `status` | `VoucherStatus` | Cached status |

### Purpose

Used for wallet storage to track:
- When vouchers were added
- Backup status (for incremental backup)
- Cached status (reduce ledger queries)

### Example

```java
StoredVoucher stored = StoredVoucher.builder()
    .voucher(signedVoucher)
    .addedAt(Instant.now())
    .status(VoucherStatus.ISSUED)
    .build();

// After backup
stored = stored.withLastBackupAt(Instant.now());
```

---

## JSON Serialization

All DTOs use Jackson for JSON serialization.

### IssueVoucherRequest Example

```json
{
  "issuerId": "coffee-shop",
  "unit": "sat",
  "amount": 10000,
  "expiresInDays": 365,
  "memo": "Holiday gift card",
  "backingStrategy": "MINIMAL",
  "issuanceRatio": 1.0,
  "faceDecimals": 0,
  "merchantMetadata": {
    "campaign": "holiday-2024"
  }
}
```

### IssueVoucherResponse Example

```json
{
  "voucher": {
    "secret": { /* VoucherSecret fields */ },
    "issuerSignature": "base64-encoded-signature",
    "issuerPublicKey": "hex-encoded-public-key"
  },
  "token": "cashuA..."
}
```

### RedeemVoucherRequest Example

```json
{
  "token": "cashuA...",
  "merchantId": "coffee-shop",
  "verifyOnline": true
}
```

### RedeemVoucherResponse Example (Success)

```json
{
  "success": true,
  "voucher": { /* SignedVoucher */ },
  "errorMessage": null
}
```

### RedeemVoucherResponse Example (Failure)

```json
{
  "success": false,
  "voucher": null,
  "errorMessage": "Voucher already redeemed"
}
```

---

## Related

- [VoucherService Reference](./voucher-service.md)
- [MerchantVerificationService Reference](./merchant-verification-service.md)
- [Domain Entities Reference](./domain-entities.md)
