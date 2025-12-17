# Architecture Overview

This document explains the architectural decisions and patterns used in Cashu Voucher.

## Hexagonal Architecture

Cashu Voucher follows **Hexagonal Architecture** (also known as Ports & Adapters), a pattern that isolates business logic from infrastructure concerns.

### Core Principles

1. **Domain at the center**: Business rules have no external dependencies
2. **Ports define boundaries**: Interfaces describe what the application needs
3. **Adapters implement ports**: Infrastructure code plugs in through ports
4. **Direction of dependencies**: Everything depends inward, never outward

### Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────────┐
│                      Infrastructure Layer                            │
│                                                                      │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐  │
│  │  Nostr Relay    │    │   SQL Database  │    │   Cloud Storage │  │
│  │  Connections    │    │                 │    │                 │  │
│  └────────┬────────┘    └────────┬────────┘    └────────┬────────┘  │
│           │                      │                      │           │
│           ▼                      ▼                      ▼           │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐  │
│  │ Nostr Ledger    │    │ SQL Ledger      │    │ S3 Backup       │  │
│  │ Repository      │    │ Repository      │    │ Repository      │  │
│  │ (adapter)       │    │ (adapter)       │    │ (adapter)       │  │
│  └────────┬────────┘    └────────┬────────┘    └────────┬────────┘  │
└───────────┼─────────────────────┼─────────────────────┼─────────────┘
            │                      │                      │
            └──────────────────────┼──────────────────────┘
                                   │
┌──────────────────────────────────┼───────────────────────────────────┐
│                         Application Layer                            │
│                                  │                                   │
│  ┌───────────────────────────────┴───────────────────────────────┐  │
│  │                         P O R T S                              │  │
│  │                                                                │  │
│  │   VoucherLedgerPort              VoucherBackupPort             │  │
│  │   (interface)                    (interface)                   │  │
│  │                                                                │  │
│  └───────────────────────────────┬───────────────────────────────┘  │
│                                  │                                   │
│                                  ▼                                   │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                      A P P   S E R V I C E S                   │  │
│  │                                                                │  │
│  │   VoucherService              MerchantVerificationService      │  │
│  │                                                                │  │
│  │   - issue()                   - verifyOffline()                │  │
│  │   - queryStatus()             - verifyOnline()                 │  │
│  │   - updateStatus()            - redeem()                       │  │
│  │   - backup()                  - markRedeemed()                 │  │
│  │   - restore()                                                  │  │
│  │                                                                │  │
│  └───────────────────────────────┬───────────────────────────────┘  │
└──────────────────────────────────┼───────────────────────────────────┘
                                   │
┌──────────────────────────────────┼───────────────────────────────────┐
│                          Domain Layer                                │
│                                  │                                   │
│                                  ▼                                   │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                      D O M A I N   M O D E L                   │  │
│  │                                                                │  │
│  │   VoucherSecret           SignedVoucher          VoucherStatus │  │
│  │                                                                │  │
│  │   BackingStrategy         VoucherValidator                     │  │
│  │                                                                │  │
│  │   VoucherSignatureService  VoucherSerializationUtils           │  │
│  │                                                                │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                      │
│  No dependencies on infrastructure. Pure business logic.             │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

## Module Structure

### cashu-voucher-domain

**Purpose:** Pure domain logic with zero infrastructure dependencies.

**Contents:**
- Value objects (`VoucherSecret`, `SignedVoucher`)
- Enumerations (`VoucherStatus`, `BackingStrategy`)
- Domain services (`VoucherSignatureService`, `VoucherValidator`)
- Serialization utilities (`VoucherSerializationUtils`)

**Dependencies:** Only cashu-lib interfaces, Bouncy Castle (crypto)

**Key Decisions:**
- Immutable entities prevent accidental modification
- Canonical CBOR serialization ensures deterministic signing
- Model B constraint embedded in domain (issuerId)

### cashu-voucher-app

**Purpose:** Application services and port interfaces.

**Contents:**
- Application services (`VoucherService`, `MerchantVerificationService`)
- Port interfaces (`VoucherLedgerPort`, `VoucherBackupPort`)
- DTOs (`IssueVoucherRequest`, `IssueVoucherResponse`, etc.)

**Dependencies:** cashu-voucher-domain

**Key Decisions:**
- Services are stateless and orchestrate domain operations
- Ports define contracts, not implementations
- DTOs separate API concerns from domain entities

### cashu-voucher-nostr

**Purpose:** Nostr infrastructure adapter.

**Contents:**
- Port implementations (`NostrVoucherLedgerRepository`, `NostrVoucherBackupRepository`)
- Nostr client (`NostrClientAdapter`)
- Event helpers (`VoucherLedgerEvent`, `VoucherBackupPayload`)

**Dependencies:** cashu-voucher-domain, cashu-voucher-app, nostr-java

**Key Decisions:**
- Implements application ports with Nostr
- NIP-33 for public ledger (replaceable events)
- NIP-17 + NIP-44 for private backup (encrypted DMs)

## Dependency Flow

```
External Libraries
       ↑
       │ depends on
       │
┌──────┴──────┐
│   nostr     │ ← Infrastructure adapters depend on external libs
└──────┬──────┘
       │ implements
       ▼
┌─────────────┐
│    app      │ ← Application defines ports (interfaces)
└──────┬──────┘
       │ depends on
       ▼
┌─────────────┐
│   domain    │ ← Domain has minimal dependencies
└─────────────┘
```

**Key insight:** Dependencies flow inward. Domain knows nothing about Nostr, SQL, or HTTP.

## Why Hexagonal Architecture?

### Testability

```java
// Unit test with mock ports
@Test
void testIssue() {
    VoucherLedgerPort mockLedger = mock(VoucherLedgerPort.class);
    VoucherBackupPort mockBackup = mock(VoucherBackupPort.class);

    VoucherService service = new VoucherService(
        mockLedger, mockBackup, privateKey, publicKey
    );

    // Test business logic without real infrastructure
    IssueVoucherResponse response = service.issue(request);

    verify(mockLedger).publish(any(), eq(VoucherStatus.ISSUED));
}
```

### Pluggability

```java
// Production with Nostr
VoucherLedgerPort ledger = new NostrVoucherLedgerRepository(client, 10000, 15000);

// Development with in-memory
VoucherLedgerPort ledger = new InMemoryLedgerPort();

// Future: SQL database
VoucherLedgerPort ledger = new SqlVoucherLedgerRepository(dataSource);

// Same service code works with any adapter
VoucherService service = new VoucherService(ledger, backup, keys...);
```

### Separation of Concerns

| Layer | Responsibility |
|-------|---------------|
| Domain | Business rules, validation, crypto |
| Application | Use case orchestration, workflows |
| Infrastructure | Storage, network, external systems |

## Design Patterns

### Builder Pattern

Used for complex object construction:

```java
VoucherSecret secret = VoucherSecret.builder()
    .issuerId("merchant")
    .unit("sat")
    .faceValue(10000L)
    .backingStrategy(BackingStrategy.MINIMAL)
    .issuanceRatio(1.0)
    .faceDecimals(0)
    .build();
```

### Factory Methods

Static methods for object creation:

```java
// Auto-generate ID
VoucherSecret.create(issuerId, unit, faceValue, ...);

// Specify ID
VoucherSecret.create(voucherId, issuerId, unit, faceValue, ...);

// Result factories
VerificationResult.success();
VerificationResult.failure("error message");
```

### Strategy Pattern

`BackingStrategy` enum encapsulates backing behavior:

```java
public enum BackingStrategy {
    FIXED,       // Non-splittable
    MINIMAL,     // Coarse splits
    PROPORTIONAL // Fine splits
}

// Usage
if (strategy.isSplittable()) {
    // Allow split
}
```

### Port/Adapter Pattern

Core of hexagonal architecture:

```java
// Port (interface in app layer)
public interface VoucherLedgerPort {
    void publish(SignedVoucher voucher, VoucherStatus status);
    Optional<VoucherStatus> queryStatus(String voucherId);
}

// Adapter (implementation in nostr layer)
public class NostrVoucherLedgerRepository implements VoucherLedgerPort {
    @Override
    public void publish(SignedVoucher voucher, VoucherStatus status) {
        // NIP-33 implementation
    }
}
```

## Data Flow

### Issuance Flow

```
┌──────────┐    ┌──────────────┐    ┌──────────────┐    ┌─────────────┐
│  Client  │───▶│VoucherService│───▶│VoucherSecret │───▶│SignedVoucher│
└──────────┘    └──────┬───────┘    └──────────────┘    └──────┬──────┘
                       │                                        │
                       │                                        ▼
                       │                               ┌─────────────────┐
                       │                               │VoucherLedgerPort│
                       │                               └────────┬────────┘
                       │                                        │
                       │                                        ▼
                       │                               ┌─────────────────┐
                       └───────────────────────────────│  Nostr Relay    │
                                                       └─────────────────┘
```

### Verification Flow

```
┌──────────┐    ┌─────────────────────┐    ┌──────────────────┐
│ Merchant │───▶│MerchantVerification │───▶│  VoucherValidator │
└──────────┘    │     Service         │    └──────────────────┘
                └──────────┬──────────┘
                           │
                           ▼
                  ┌─────────────────┐
                  │VoucherLedgerPort│
                  └────────┬────────┘
                           │
                           ▼
                  ┌─────────────────┐
                  │  Query Status   │
                  │  (NIP-33 event) │
                  └─────────────────┘
```

## Concurrency Model

- **Domain entities:** Immutable, thread-safe by design
- **Application services:** Stateless, thread-safe
- **Nostr adapters:** Internal synchronization (ConcurrentHashMap)

All state lives in the ports/adapters or external systems.

## Error Handling

| Layer | Strategy |
|-------|----------|
| Domain | `IllegalArgumentException` for invalid input |
| Application | Wrap exceptions, return result types |
| Infrastructure | Wrap in `RuntimeException` or `VoucherNostrException` |

```java
// Domain: fail fast
if (faceValue <= 0) {
    throw new IllegalArgumentException("Face value must be positive");
}

// Application: wrap and rethrow
try {
    ledgerPort.publish(voucher, status);
} catch (Exception e) {
    throw new RuntimeException("Failed to publish voucher", e);
}

// Verification: return result type
if (!voucher.verify()) {
    return VerificationResult.failure("Invalid signature");
}
```

## Related

- [Model B Vouchers](./model-b-vouchers.md)
- [Nostr Integration](./nostr-integration.md)
- [Backing Strategies](./backing-strategies.md)
- [Implement Custom Adapter](../how-to/implement-custom-adapter.md)
