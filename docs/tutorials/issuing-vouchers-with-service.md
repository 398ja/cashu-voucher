# Issuing Vouchers with VoucherService

This tutorial builds on the [Getting Started](./getting-started.md) guide to show you how to use the `VoucherService` for complete voucher issuance with ledger integration.

## Prerequisites

- Completed the [Getting Started](./getting-started.md) tutorial
- Understanding of hexagonal architecture (optional but helpful)

## What You Will Learn

1. Setting up the VoucherService with ports
2. Creating mock ports for development
3. Issuing vouchers with automatic ledger publishing
4. Querying voucher status
5. Backup and restore basics

## Step 1: Understanding the Architecture

The `VoucherService` orchestrates voucher operations using two ports:

```
┌─────────────────────────────────────────────────────────────┐
│                    VoucherService                           │
│                                                             │
│  issue()  queryStatus()  updateStatus()  backup()  restore()│
└─────────────────┬────────────────────────────┬──────────────┘
                  │                            │
                  ▼                            ▼
       ┌──────────────────┐         ┌──────────────────┐
       │ VoucherLedgerPort│         │ VoucherBackupPort│
       │   (interface)    │         │   (interface)    │
       └────────┬─────────┘         └────────┬─────────┘
                │                            │
                ▼                            ▼
       ┌──────────────────┐         ┌──────────────────┐
       │ Nostr/SQL/Memory │         │ Nostr/Cloud/File │
       │  (adapter)       │         │  (adapter)       │
       └──────────────────┘         └──────────────────┘
```

Ports are interfaces that define what the service needs. Adapters are implementations.

## Step 2: Create Mock Ports for Development

For development and testing, create simple in-memory implementations:

```java
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherBackupPort;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// In-memory ledger for development
public class InMemoryLedgerPort implements VoucherLedgerPort {
    private final Map<String, VoucherStatus> statusMap = new ConcurrentHashMap<>();
    private final Map<String, SignedVoucher> voucherMap = new ConcurrentHashMap<>();

    @Override
    public void publish(SignedVoucher voucher, VoucherStatus status) {
        String id = voucher.getSecret().getVoucherId();
        voucherMap.put(id, voucher);
        statusMap.put(id, status);
        System.out.println("Published voucher: " + id + " with status: " + status);
    }

    @Override
    public Optional<VoucherStatus> queryStatus(String voucherId) {
        return Optional.ofNullable(statusMap.get(voucherId));
    }

    @Override
    public void updateStatus(String voucherId, VoucherStatus newStatus) {
        if (!statusMap.containsKey(voucherId)) {
            throw new RuntimeException("Voucher not found: " + voucherId);
        }
        statusMap.put(voucherId, newStatus);
        System.out.println("Updated voucher " + voucherId + " to status: " + newStatus);
    }

    @Override
    public Optional<SignedVoucher> queryVoucher(String voucherId) {
        return Optional.ofNullable(voucherMap.get(voucherId));
    }
}

// In-memory backup for development
public class InMemoryBackupPort implements VoucherBackupPort {
    private final Map<String, List<SignedVoucher>> backups = new ConcurrentHashMap<>();

    @Override
    public void backup(List<SignedVoucher> vouchers, String userPrivateKey) {
        backups.computeIfAbsent(userPrivateKey, k -> new ArrayList<>())
               .addAll(vouchers);
        System.out.println("Backed up " + vouchers.size() + " vouchers");
    }

    @Override
    public List<SignedVoucher> restore(String userPrivateKey) {
        return new ArrayList<>(backups.getOrDefault(userPrivateKey, List.of()));
    }
}
```

## Step 3: Initialize VoucherService

Create the service with your ports and issuer keys:

```java
import xyz.tcheeric.cashu.voucher.app.VoucherService;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherRequest;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherResponse;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;

public class VoucherServiceDemo {
    public static void main(String[] args) {
        // Create ports (use Nostr implementations in production)
        VoucherLedgerPort ledgerPort = new InMemoryLedgerPort();
        VoucherBackupPort backupPort = new InMemoryBackupPort();

        // Your issuer keys
        String privateKey = "your-64-char-hex-private-key";
        String publicKey = "your-64-char-hex-public-key";

        // Create service
        VoucherService voucherService = new VoucherService(
            ledgerPort,
            backupPort,
            privateKey,
            publicKey
        );

        System.out.println("VoucherService initialized!");
    }
}
```

## Step 4: Issue a Voucher

Use `IssueVoucherRequest` to specify voucher parameters:

```java
// Create issuance request
IssueVoucherRequest request = IssueVoucherRequest.builder()
    .issuerId("my-coffee-shop")        // Your merchant identifier
    .unit("sat")                        // Currency unit
    .amount(10000L)                     // Face value: 10,000 sats
    .expiresInDays(365)                 // Optional: expires in 1 year
    .memo("Holiday gift card")          // Optional: description
    .backingStrategy(BackingStrategy.MINIMAL)  // Allows occasional splits
    .issuanceRatio(1.0)                 // 1 sat = 1 unit of face value
    .faceDecimals(0)                    // No decimal places
    .build();

// Issue the voucher
IssueVoucherResponse response = voucherService.issue(request);

// Get results
SignedVoucher voucher = response.getVoucher();

System.out.println("Voucher issued!");
System.out.println("  ID:     " + response.getVoucherId());
System.out.println("  Amount: " + response.getAmount() + " " + response.getUnit());
```

The `issue()` method automatically:
1. Creates the `VoucherSecret`
2. Signs it with your private key
3. Publishes to the ledger with `ISSUED` status
4. Returns the signed voucher

> **Note:** To create a shareable token (`cashuB...` format), use a wallet
> implementation (e.g., cashu-client) that can swap proofs at the mint.

## Step 5: Query Voucher Status

Check a voucher's current status:

```java
String voucherId = response.getVoucherId();

Optional<VoucherStatus> status = voucherService.queryStatus(voucherId);

if (status.isPresent()) {
    System.out.println("Voucher " + voucherId + " status: " + status.get());

    switch (status.get()) {
        case ISSUED -> System.out.println("  Ready for redemption");
        case REDEEMED -> System.out.println("  Already used");
        case REVOKED -> System.out.println("  Cancelled by issuer");
        case EXPIRED -> System.out.println("  Time limit exceeded");
    }
} else {
    System.out.println("Voucher not found in ledger");
}
```

## Step 6: Update Voucher Status

Mark vouchers as redeemed, revoked, or expired:

```java
// Mark as redeemed (after merchant accepts payment)
voucherService.updateStatus(voucherId, VoucherStatus.REDEEMED);

// Or revoke (if issuer cancels)
// voucherService.updateStatus(voucherId, VoucherStatus.REVOKED);
```

**Note:** Status updates are recorded in the public ledger, preventing double-spending.

## Step 7: Backup and Restore

Vouchers are non-deterministic and must be backed up:

```java
// User's Nostr private key for encryption
String userPrivateKey = "user-nostr-private-key-hex";

// Backup vouchers
List<SignedVoucher> vouchersToBackup = List.of(response.getVoucher());
voucherService.backup(vouchersToBackup, userPrivateKey);

// Later: restore from backup
List<SignedVoucher> restoredVouchers = voucherService.restore(userPrivateKey);
System.out.println("Restored " + restoredVouchers.size() + " vouchers");
```

## Complete Example

Here's a full working example:

```java
import xyz.tcheeric.cashu.voucher.app.VoucherService;
import xyz.tcheeric.cashu.voucher.app.dto.*;
import xyz.tcheeric.cashu.voucher.domain.*;

public class CompleteVoucherExample {
    public static void main(String[] args) {
        // Setup
        VoucherLedgerPort ledger = new InMemoryLedgerPort();
        VoucherBackupPort backup = new InMemoryBackupPort();
        String privKey = "a1b2c3...";  // Your real key
        String pubKey = "d4e5f6...";   // Your real key

        VoucherService service = new VoucherService(ledger, backup, privKey, pubKey);

        // Issue
        IssueVoucherRequest request = IssueVoucherRequest.builder()
            .issuerId("demo-merchant")
            .unit("sat")
            .amount(5000L)
            .expiresInDays(30)
            .backingStrategy(BackingStrategy.MINIMAL)
            .issuanceRatio(1.0)
            .faceDecimals(0)
            .build();

        IssueVoucherResponse response = service.issue(request);
        String voucherId = response.getVoucherId();

        // Query
        VoucherStatus status = service.queryStatus(voucherId)
            .orElseThrow(() -> new RuntimeException("Not found"));
        System.out.println("Initial status: " + status);

        // Backup
        String userKey = "user-key-hex";
        service.backup(List.of(response.getVoucher()), userKey);

        // Simulate redemption
        service.updateStatus(voucherId, VoucherStatus.REDEEMED);
        System.out.println("Final status: " + service.queryStatus(voucherId).get());
    }
}
```

## What's Next?

You now understand the core `VoucherService` workflow. Continue with:

- [Merchant Verification](../how-to/verify-voucher-as-merchant.md) - Verify and redeem vouchers
- [Nostr Integration](./nostr-integration.md) - Use real Nostr storage
- [VoucherService Reference](../reference/voucher-service.md) - Full API documentation

## Key Points

- `VoucherService` orchestrates the complete voucher lifecycle
- Ports (`VoucherLedgerPort`, `VoucherBackupPort`) abstract storage
- Use in-memory ports for development, Nostr ports for production
- `issue()` creates, signs, and publishes in one step
- Always backup vouchers - they cannot be recovered from a seed phrase
