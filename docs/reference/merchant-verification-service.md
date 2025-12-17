# MerchantVerificationService Reference

Service for merchant-side voucher verification and redemption (Model B).

**Package:** `xyz.tcheeric.cashu.voucher.app`

**Module:** `cashu-voucher-app`

## Overview

In Model B, vouchers can **only** be redeemed at the issuing merchant, not at the mint. `MerchantVerificationService` provides both offline and online verification capabilities for merchants to validate incoming vouchers.

## Constructor

```java
public MerchantVerificationService(VoucherLedgerPort ledgerPort)
```

**Parameters:**
- `ledgerPort` - Port for ledger operations (required)

**Exceptions:**
- `NullPointerException` if ledgerPort is null

## Methods

### verifyOffline

Verifies a voucher without network access (signature and expiry only).

```java
public VerificationResult verifyOffline(
    SignedVoucher voucher,
    String expectedIssuerId
)
```

**Checks performed:**
1. Issuer ID matches `expectedIssuerId` (Model B constraint)
2. ED25519 signature is cryptographically valid
3. Voucher has not expired

**Parameters:**
- `voucher` - The signed voucher to verify
- `expectedIssuerId` - Your merchant ID (non-blank)

**Returns:** `VerificationResult`

**Exceptions:**
- `IllegalArgumentException` if expectedIssuerId is blank

**Limitations:**
- Cannot detect double-spending
- Cannot verify ledger status

**Use cases:**
- Temporary network outage
- Quick preliminary check
- Testing environments

**Example:**
```java
VerificationResult result = service.verifyOffline(voucher, "my-merchant-id");
if (result.isValid()) {
    // Accept (with risk of double-spend)
}
```

---

### verifyOnline

Verifies a voucher with full ledger status checking (recommended for production).

```java
public VerificationResult verifyOnline(
    SignedVoucher voucher,
    String expectedIssuerId
)
```

**Checks performed:**
1. All offline checks (issuer, signature, expiry)
2. Voucher exists in public ledger
3. Status is `ISSUED` (not `REDEEMED`, `REVOKED`, or `EXPIRED`)

**Parameters:**
- `voucher` - The signed voucher to verify
- `expectedIssuerId` - Your merchant ID (non-blank)

**Returns:** `VerificationResult`

**Exceptions:**
- `IllegalArgumentException` if expectedIssuerId is blank

**Error detection:**
- Double-spend: Status is `REDEEMED`
- Cancelled: Status is `REVOKED`
- Time limit: Status is `EXPIRED`
- Unknown: Voucher not in ledger

**Example:**
```java
VerificationResult result = service.verifyOnline(voucher, "my-merchant-id");
if (result.isValid()) {
    // Safe to accept
    service.markRedeemed(voucher.getSecret().getVoucherId());
}
```

---

### markRedeemed

Marks a voucher as redeemed in the ledger.

```java
public void markRedeemed(String voucherId)
```

**Parameters:**
- `voucherId` - Voucher ID to mark as redeemed (non-blank)

**Behavior:**
- Updates ledger status to `REDEEMED`
- Prevents future redemption (terminal state)

**Exceptions:**
- `IllegalArgumentException` if voucherId is blank
- `RuntimeException` if ledger update fails

**Important:** Call this after verifying and accepting payment.

**Example:**
```java
service.markRedeemed("abc-123-def");
```

---

### redeem

Combines verification and redemption in one operation.

```java
public RedeemVoucherResponse redeem(
    RedeemVoucherRequest request,
    SignedVoucher voucher
)
```

**Process:**
1. Verifies online or offline based on `request.getVerifyOnline()`
2. If valid, marks voucher as `REDEEMED`
3. Returns success or failure response

**Parameters:**

`RedeemVoucherRequest` fields:
| Field | Type | Description |
|-------|------|-------------|
| `token` | `String` | Original token (for reference) |
| `merchantId` | `String` | Your merchant ID |
| `verifyOnline` | `Boolean` | true (default) for online verification |

**Returns:** `RedeemVoucherResponse`

| Field | Type | Description |
|-------|------|-------------|
| `success` | `boolean` | True if redemption succeeded |
| `voucher` | `SignedVoucher` | The voucher (if success) |
| `errorMessage` | `String` | Error details (if failure) |
| `getAmount()` | `Long` | Convenience accessor |
| `getUnit()` | `String` | Convenience accessor |
| `getVoucherId()` | `String` | Convenience accessor |

**Example:**
```java
RedeemVoucherRequest request = RedeemVoucherRequest.builder()
    .token(token)
    .merchantId("my-merchant")
    .verifyOnline(true)
    .build();

RedeemVoucherResponse response = service.redeem(request, voucher);
if (response.isSuccess()) {
    System.out.println("Redeemed: " + response.getAmount());
}
```

---

## VerificationResult

Inner class representing verification outcome.

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `valid` | `boolean` | True if verification passed |
| `errors` | `List<String>` | Error messages (empty if valid) |

### Static Factory Methods

```java
// Success
VerificationResult.success()

// Single error
VerificationResult.failure(String error)

// Multiple errors
VerificationResult.failure(List<String> errors)
```

### Instance Methods

| Method | Returns | Description |
|--------|---------|-------------|
| `isValid()` | `boolean` | True if no errors |
| `getErrors()` | `List<String>` | Unmodifiable error list |
| `getErrorMessage()` | `String` | Errors joined by "; " |

---

## Error Messages

| Error | Cause | Action |
|-------|-------|--------|
| "Voucher issued by 'X' but expected issuer is 'Y'" | Wrong merchant | Reject |
| "Invalid voucher signature" | Tampered or fake | Reject, alert |
| "Voucher has expired" | Time limit exceeded | Reject |
| "Voucher not found in public ledger" | Unknown voucher | Reject |
| "Voucher already redeemed (double-spend attempt detected)" | Previously used | Reject, alert |
| "Voucher has been revoked by issuer" | Cancelled | Reject |
| "Failed to query voucher status from ledger" | Network error | Retry or fallback |

---

## Verification Flow

```
┌──────────────────────────────────────────────────────────────┐
│                        verifyOnline()                        │
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
              ┌───────────────────────────────┐
              │     1. Check issuer ID        │
              │   (Model B: must match)       │
              └───────────────┬───────────────┘
                              │
                              ▼
              ┌───────────────────────────────┐
              │   2. Verify ED25519 signature │
              └───────────────┬───────────────┘
                              │
                              ▼
              ┌───────────────────────────────┐
              │     3. Check expiry time      │
              └───────────────┬───────────────┘
                              │
                              ▼
              ┌───────────────────────────────┐
              │  4. Query ledger for status   │
              └───────────────┬───────────────┘
                              │
              ┌───────────────┴───────────────┐
              │                               │
              ▼                               ▼
      ┌─────────────┐               ┌─────────────────┐
      │ ISSUED      │               │ REDEEMED/       │
      │ → SUCCESS   │               │ REVOKED/EXPIRED │
      └─────────────┘               │ → FAILURE       │
                                    └─────────────────┘
```

---

## Logging

| Level | Events |
|-------|--------|
| INFO | Online verification start, redemption processing, mark redeemed |
| DEBUG | Offline verification, issuer check, ledger status |
| WARN | Issuer mismatch, domain validation failure, offline redemption warning |
| ERROR | Unknown ledger status, query failures |

**Log Fields:**
- `voucherId` - Voucher identifier
- `expectedIssuer` - Your merchant ID
- `errors` - Error count

---

## Thread Safety

`MerchantVerificationService` is **stateless** and thread-safe.

---

## Related

- [How to Verify Vouchers](../how-to/verify-voucher-as-merchant.md)
- [VoucherLedgerPort](./ports.md#voucherledgerport)
- [Model B Vouchers](../explanation/model-b-vouchers.md)
- [Domain Entities](./domain-entities.md)
