# VoucherService Reference

The `VoucherService` is the main application service that orchestrates voucher operations.

**Package:** `xyz.tcheeric.cashu.voucher.app`

**Module:** `cashu-voucher-app`

## Overview

`VoucherService` coordinates between the domain layer (voucher creation, signing, validation) and the infrastructure layer (storage via ports). It provides the primary business logic for:

- Issuing new vouchers
- Querying voucher status
- Updating voucher status
- Backup and restore operations

## Constructor

```java
public VoucherService(
    VoucherLedgerPort ledgerPort,          // Required: public ledger operations
    VoucherBackupPort backupPort,          // Required: private backup operations
    String mintIssuerPrivateKey,           // Required: hex-encoded ED25519 private key
    String mintIssuerPublicKey             // Required: hex-encoded ED25519 public key
)
```

### Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `ledgerPort` | `VoucherLedgerPort` | Port for public ledger operations |
| `backupPort` | `VoucherBackupPort` | Port for private backup operations |
| `mintIssuerPrivateKey` | `String` | Hex-encoded 32-byte ED25519 private key |
| `mintIssuerPublicKey` | `String` | Hex-encoded 32-byte ED25519 public key |

### Exceptions

- `NullPointerException` if any parameter is null
- `IllegalArgumentException` if keys are blank

## Methods

### issue

Issues a new voucher with automatic signing and ledger publishing.

```java
public IssueVoucherResponse issue(IssueVoucherRequest request)
```

**Process:**
1. Validates request parameters
2. Calculates expiry timestamp (if `expiresInDays` specified)
3. Creates `VoucherSecret` with provided parameters
4. Signs with issuer's private key
5. Publishes to ledger with `ISSUED` status
6. Serializes to Cashu token format
7. Returns response with voucher and token

**Parameters:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `issuerId` | `String` | Yes | Merchant identifier |
| `unit` | `String` | Yes | Currency unit |
| `amount` | `Long` | Yes | Face value (positive) |
| `expiresInDays` | `Integer` | No | Days until expiry |
| `memo` | `String` | No | Description |
| `voucherId` | `String` | No | Custom ID (auto-generated if not provided) |
| `backingStrategy` | `BackingStrategy` | Yes | Token backing approach |
| `issuanceRatio` | `double` | Yes | Face value per sat |
| `faceDecimals` | `int` | Yes | Currency decimal places |
| `merchantMetadata` | `Map<String, Object>` | No | Custom business data |

**Returns:** `IssueVoucherResponse`

| Field | Type | Description |
|-------|------|-------------|
| `voucher` | `SignedVoucher` | The signed voucher |
| `token` | `String` | Cashu token format |
| `getVoucherId()` | `String` | Convenience accessor |
| `getAmount()` | `Long` | Convenience accessor |
| `getUnit()` | `String` | Convenience accessor |

**Exceptions:**
- `IllegalArgumentException` for invalid request parameters
- `RuntimeException` if signing or ledger publishing fails

**Example:**
```java
IssueVoucherRequest request = IssueVoucherRequest.builder()
    .issuerId("coffee-shop")
    .unit("sat")
    .amount(10000L)
    .expiresInDays(365)
    .backingStrategy(BackingStrategy.MINIMAL)
    .issuanceRatio(1.0)
    .faceDecimals(0)
    .build();

IssueVoucherResponse response = voucherService.issue(request);
```

---

### queryStatus

Queries the current status of a voucher from the public ledger.

```java
public Optional<VoucherStatus> queryStatus(String voucherId)
```

**Parameters:**
- `voucherId` - Unique voucher identifier (non-blank)

**Returns:** `Optional<VoucherStatus>`
- `Optional.of(status)` if voucher found
- `Optional.empty()` if voucher not in ledger

**Exceptions:**
- `IllegalArgumentException` if voucherId is null or blank
- `RuntimeException` if ledger query fails

**Example:**
```java
Optional<VoucherStatus> status = voucherService.queryStatus("abc-123");
status.ifPresent(s -> System.out.println("Status: " + s));
```

---

### updateStatus

Updates the status of a voucher in the public ledger.

```java
public void updateStatus(String voucherId, VoucherStatus newStatus)
```

**Parameters:**
- `voucherId` - Unique voucher identifier (non-blank)
- `newStatus` - New status to set

**Common Transitions:**
- `ISSUED` → `REDEEMED` (merchant accepts)
- `ISSUED` → `REVOKED` (issuer cancels)
- `ISSUED` → `EXPIRED` (time-based)

**Exceptions:**
- `IllegalArgumentException` if parameters are invalid
- `RuntimeException` if ledger update fails

**Example:**
```java
voucherService.updateStatus("abc-123", VoucherStatus.REDEEMED);
```

---

### backup

Backs up vouchers to private user storage.

```java
public void backup(List<SignedVoucher> vouchers, String userPrivateKey)
```

**Parameters:**
- `vouchers` - List of vouchers to backup (can be empty)
- `userPrivateKey` - User's private key for encryption (non-blank)

**Behavior:**
- Empty list: Returns immediately, no action
- Non-empty list: Delegates to `VoucherBackupPort.backup()`

**Exceptions:**
- `IllegalArgumentException` if userPrivateKey is null or blank
- `RuntimeException` if backup fails

**Example:**
```java
List<SignedVoucher> vouchers = List.of(response.getVoucher());
voucherService.backup(vouchers, userPrivateKey);
```

---

### restore

Restores vouchers from private user storage.

```java
public List<SignedVoucher> restore(String userPrivateKey)
```

**Parameters:**
- `userPrivateKey` - User's private key for decryption (non-blank)

**Returns:** List of restored vouchers (never null, may be empty)

**Exceptions:**
- `IllegalArgumentException` if userPrivateKey is null or blank
- `RuntimeException` if restore fails

**Example:**
```java
List<SignedVoucher> restored = voucherService.restore(userPrivateKey);
```

---

### exists

Checks if a voucher exists in the public ledger.

```java
public boolean exists(String voucherId)
```

**Parameters:**
- `voucherId` - Unique voucher identifier (non-blank)

**Returns:** `true` if voucher exists in ledger, `false` otherwise

**Exceptions:**
- `IllegalArgumentException` if voucherId is null or blank
- `RuntimeException` if check fails

**Example:**
```java
if (voucherService.exists("abc-123")) {
    System.out.println("Voucher found in ledger");
}
```

---

## Logging

`VoucherService` uses SLF4J for logging:

| Level | Events |
|-------|--------|
| INFO | Issuance, status updates, backup/restore operations |
| DEBUG | Detailed flow (expiry calculation, voucher creation, signing) |
| WARN | Token serialization placeholder usage |
| ERROR | Failures in ledger operations, backup, restore |

**Log Fields:**
- `voucherId` - Voucher identifier
- `issuerId` - Merchant identifier
- `unit` - Currency unit
- `amount` - Face value

---

## Thread Safety

`VoucherService` is **stateless** and thread-safe. All state is managed by the injected ports.

---

## Dependencies

```
┌──────────────────────────────────────────────────────────────┐
│                     VoucherService                           │
└───────────┬─────────────────────────────────┬────────────────┘
            │                                 │
            ▼                                 ▼
    ┌───────────────┐                 ┌───────────────┐
    │VoucherLedger- │                 │VoucherBackup- │
    │     Port      │                 │     Port      │
    └───────────────┘                 └───────────────┘
            │                                 │
            ▼                                 ▼
    ┌───────────────┐                 ┌───────────────┐
    │Domain Entities│                 │Domain Entities│
    │VoucherSecret  │                 │SignedVoucher  │
    │SignedVoucher  │                 │               │
    │VoucherStatus  │                 │               │
    └───────────────┘                 └───────────────┘
```

---

## Related

- [IssueVoucherRequest/Response](./dto.md)
- [VoucherLedgerPort](./ports.md#voucherledgerport)
- [VoucherBackupPort](./ports.md#voucherbackupport)
- [Domain Entities](./domain-entities.md)
- [Issuing Vouchers Tutorial](../tutorials/issuing-vouchers-with-service.md)
