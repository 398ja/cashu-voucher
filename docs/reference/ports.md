# Ports Reference

This reference documents the port interfaces in the `cashu-voucher-app` module. Ports define what the application needs from infrastructure without specifying how it's implemented.

## VoucherLedgerPort

Interface for voucher public ledger operations.

**Package:** `xyz.tcheeric.cashu.voucher.app.ports`

**Purpose:** Public audit trail of voucher status, double-spend prevention.

### Methods

#### publish

Publishes a voucher to the public ledger.

```java
void publish(SignedVoucher voucher, VoucherStatus status)
```

**Parameters:**
- `voucher` - Signed voucher to publish
- `status` - Initial status (typically `ISSUED`)

**Behavior:**
- Creates a new ledger entry for the voucher
- If voucher exists: behavior depends on implementation
  - NIP-33 (Nostr): Replaces previous entry
  - SQL: May update or throw exception

**Exceptions:**
- `IllegalArgumentException` if parameters invalid
- `RuntimeException` if publishing fails

---

#### queryStatus

Queries the current status of a voucher.

```java
Optional<VoucherStatus> queryStatus(String voucherId)
```

**Parameters:**
- `voucherId` - Unique voucher identifier (non-blank)

**Returns:**
- `Optional.of(status)` if voucher found
- `Optional.empty()` if not found

**Exceptions:**
- `IllegalArgumentException` if voucherId is invalid
- `RuntimeException` if query fails

---

#### updateStatus

Updates the status of an existing voucher.

```java
void updateStatus(String voucherId, VoucherStatus newStatus)
```

**Parameters:**
- `voucherId` - Unique voucher identifier (non-blank)
- `newStatus` - New status to set

**Common transitions:**
- `ISSUED` → `REDEEMED`
- `ISSUED` → `REVOKED`
- `ISSUED` → `EXPIRED`

**Exceptions:**
- `IllegalArgumentException` if parameters invalid
- `RuntimeException` if update fails or voucher not found

---

#### exists

Checks if a voucher exists in the ledger.

```java
default boolean exists(String voucherId)
```

**Default implementation:** `queryStatus(voucherId).isPresent()`

**Parameters:**
- `voucherId` - Unique voucher identifier

**Returns:** `true` if voucher exists

---

#### queryVoucher

Retrieves full voucher details from the ledger.

```java
default Optional<SignedVoucher> queryVoucher(String voucherId)
```

**Default implementation:** Throws `UnsupportedOperationException`

**Parameters:**
- `voucherId` - Unique voucher identifier

**Returns:** Complete voucher with status, or empty if not found

**Note:** Optional operation; not all implementations support full retrieval.

---

### Implementations

| Class | Module | Storage |
|-------|--------|---------|
| `NostrVoucherLedgerRepository` | cashu-voucher-nostr | NIP-33 events |
| `InMemoryLedgerPort` | (test) | ConcurrentHashMap |

---

## VoucherBackupPort

Interface for private voucher backup operations.

**Package:** `xyz.tcheeric.cashu.voucher.app.ports`

**Purpose:** Private user storage for voucher recovery.

### Methods

#### backup

Backs up vouchers to private user storage.

```java
void backup(List<SignedVoucher> vouchers, String userPrivateKey)
```

**Parameters:**
- `vouchers` - List of vouchers to backup (can be empty)
- `userPrivateKey` - User's private key for encryption

**Behavior:**
- Empty list: No-op or minimal write
- Non-empty: Encrypts and stores vouchers
- Incremental: May append to or replace previous backups

**Key format:** Implementation-dependent (typically Nostr private key)

**Exceptions:**
- `IllegalArgumentException` if parameters invalid
- `RuntimeException` if backup fails

---

#### restore

Restores vouchers from private user storage.

```java
List<SignedVoucher> restore(String userPrivateKey)
```

**Parameters:**
- `userPrivateKey` - User's private key for decryption

**Returns:** List of restored vouchers (never null, may be empty)

**Behavior:**
- Retrieves all backups associated with the key
- Decrypts each backup
- Merges and deduplicates by voucher ID
- Returns all vouchers found

**Exceptions:**
- `IllegalArgumentException` if userPrivateKey invalid
- `RuntimeException` if restore fails

---

#### hasBackups

Checks if backups exist for the user.

```java
default boolean hasBackups(String userPrivateKey)
```

**Default implementation:** `!restore(userPrivateKey).isEmpty()`

**Note:** Override with efficient check if possible.

---

#### deleteBackups

Deletes all backups for the user.

```java
default void deleteBackups(String userPrivateKey)
```

**Default implementation:** Throws `UnsupportedOperationException`

**Note:** Optional operation; not supported by immutable storage (e.g., Nostr).

---

### Implementations

| Class | Module | Storage |
|-------|--------|---------|
| `NostrVoucherBackupRepository` | cashu-voucher-nostr | NIP-17 + NIP-44 |
| `InMemoryBackupPort` | (test) | Map<String, List> |

---

## Design Principles

### Hexagonal Architecture

```
          ┌─────────────────────────────────────────┐
          │         Application Layer               │
          │                                         │
          │  ┌─────────────────┐  ┌──────────────┐  │
          │  │ VoucherService  │  │ Merchant...  │  │
          │  └────────┬────────┘  └──────┬───────┘  │
          │           │                  │          │
          │           ▼                  ▼          │
          │  ┌─────────────────────────────────┐    │
          │  │     P O R T S  (interfaces)     │    │
          │  │                                 │    │
          │  │  VoucherLedgerPort              │    │
          │  │  VoucherBackupPort              │    │
          │  └─────────────────────────────────┘    │
          └───────────────┬─────────────────────────┘
                          │
          ┌───────────────┴───────────────┐
          │                               │
          ▼                               ▼
┌─────────────────────┐       ┌─────────────────────┐
│   Nostr Adapters    │       │   Custom Adapters   │
│                     │       │                     │
│ NostrVoucherLedger  │       │ SqlVoucherLedger    │
│ NostrVoucherBackup  │       │ S3VoucherBackup     │
└─────────────────────┘       └─────────────────────┘
```

### Port Characteristics

| Principle | Description |
|-----------|-------------|
| Infrastructure-agnostic | No dependencies on Nostr, SQL, cloud, etc. |
| Pluggable | Easy to swap implementations |
| Testable | Mock with in-memory implementations |
| Minimal surface | Only essential operations |

---

## Nostr Implementations

### NostrVoucherLedgerRepository

**Event Structure (NIP-33):**
```
Kind: 30078 (parameterized replaceable)
Tags:
  ["d", "voucher:<voucherId>"]   -- Makes event replaceable
  ["status", "ISSUED"]           -- Queryable status
  ["amount", "10000"]            -- Face value
  ["unit", "sat"]                -- Currency
  ["expiry", "1735689600"]       -- Optional expiry timestamp
Content: JSON serialized SignedVoucher + status
```

**Behavior:**
- Publishing creates/replaces NIP-33 event
- Status updates create new event (same d-tag)
- Queries filter by d-tag prefix

### NostrVoucherBackupRepository

**Event Structure (NIP-17):**
```
Kind: 4 (encrypted DM)
pubkey: <user's public key>
Tags:
  ["p", "<user's public key>"]   -- Self-addressed
Content: NIP-44 encrypted JSON array of SignedVouchers
```

**Behavior:**
- Backup encrypts with user's private key (NIP-44)
- Restore queries all kind 4 events to self
- Merges multiple backups, deduplicates by voucher ID

---

## Testing with Mocks

```java
// Simple mock implementation
class MockLedgerPort implements VoucherLedgerPort {
    private final Map<String, VoucherStatus> statuses = new HashMap<>();

    @Override
    public void publish(SignedVoucher v, VoucherStatus s) {
        statuses.put(v.getSecret().getVoucherId(), s);
    }

    @Override
    public Optional<VoucherStatus> queryStatus(String id) {
        return Optional.ofNullable(statuses.get(id));
    }

    @Override
    public void updateStatus(String id, VoucherStatus s) {
        statuses.put(id, s);
    }
}

// Usage in tests
@Test
void testIssue() {
    VoucherLedgerPort mockLedger = new MockLedgerPort();
    VoucherBackupPort mockBackup = mock(VoucherBackupPort.class);

    VoucherService service = new VoucherService(
        mockLedger, mockBackup, privateKey, publicKey
    );

    // ... test
}
```

---

## Related

- [Implement Custom Adapter](../how-to/implement-custom-adapter.md)
- [VoucherService Reference](./voucher-service.md)
- [Nostr Adapters Reference](./nostr-adapters.md)
- [Architecture Overview](../explanation/architecture.md)
