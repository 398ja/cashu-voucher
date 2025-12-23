# Nostr Integration Tutorial

This tutorial shows you how to integrate Cashu Voucher with Nostr for decentralized voucher storage and backup. You will learn to set up the Nostr adapters for both the public ledger and private backups.

## Prerequisites

- Completed the [Getting Started](./getting-started.md) tutorial
- Completed the [Issuing Vouchers with Service](./issuing-vouchers-with-service.md) tutorial
- Understanding of Nostr basics (relays, events, keys)

## What You Will Learn

1. Understanding Nostr's role in Cashu Voucher
2. Setting up Nostr relay connections
3. Using the public ledger (NIP-33)
4. Using private backups (NIP-17 + NIP-44)
5. Multi-relay configuration

## Overview: Why Nostr?

Cashu Voucher uses Nostr for two distinct purposes:

| Function | Event Kind | NIP | Purpose |
|----------|------------|-----|---------|
| Public Ledger | 30078 | NIP-33 | Audit trail, double-spend prevention |
| Private Backup | 4 | NIP-17 + NIP-44 | Encrypted user voucher storage |

**Benefits of Nostr:**
- Decentralized (no single point of failure)
- User-controlled (your keys, your data)
- Interoperable (standard protocol)
- Privacy options (encrypted DMs for backups)

## Step 1: Add the Nostr Module

Ensure you have the Nostr adapter dependency:

```xml
<dependency>
    <groupId>xyz.tcheeric</groupId>
    <artifactId>cashu-voucher-nostr</artifactId>
    <version>0.3.6</version>
</dependency>
```

## Step 2: Configure Relay Connections

Create a `NostrClientAdapter` to manage relay connections:

```java
import xyz.tcheeric.cashu.voucher.nostr.NostrClientAdapter;
import xyz.tcheeric.cashu.voucher.nostr.config.NostrRelayConfig;

import java.util.List;

public class NostrSetup {
    public static NostrClientAdapter createClient() {
        // Configure relays
        List<String> relayUrls = List.of(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.band"
        );

        // Create configuration
        NostrRelayConfig config = NostrRelayConfig.builder()
            .relayUrls(relayUrls)
            .publishTimeoutMs(10000)   // 10 seconds
            .queryTimeoutMs(15000)     // 15 seconds
            .build();

        // Create adapter
        NostrClientAdapter client = new NostrClientAdapter(config);

        // Connect to relays
        client.connect();

        System.out.println("Connected to " + client.getConnectedRelayCount() + " relays");
        return client;
    }
}
```

## Step 3: Set Up the Public Ledger

The `NostrVoucherLedgerRepository` implements `VoucherLedgerPort` using NIP-33:

```java
import xyz.tcheeric.cashu.voucher.nostr.NostrVoucherLedgerRepository;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;

public class LedgerSetup {
    public static VoucherLedgerPort createLedger(NostrClientAdapter client) {
        // Create ledger repository
        NostrVoucherLedgerRepository ledger = new NostrVoucherLedgerRepository(
            client,
            10000,   // publishTimeoutMs
            15000    // queryTimeoutMs
        );

        return ledger;
    }
}
```

### How the Ledger Works

When you publish a voucher:

1. **Event Creation**: A NIP-33 event (kind 30078) is created
2. **d-tag**: `["d", "voucher:<voucherId>"]` - makes it replaceable
3. **Status Tag**: `["status", "ISSUED"]` - queryable status
4. **Multi-relay Publish**: Event sent to all connected relays

```
┌────────────────────────────────────────────────────────┐
│ NIP-33 Event (Kind 30078)                              │
├────────────────────────────────────────────────────────┤
│ tags: [                                                │
│   ["d", "voucher:abc-123-def"],                        │
│   ["status", "ISSUED"],                                │
│   ["amount", "10000"],                                 │
│   ["unit", "sat"],                                     │
│   ["expiry", "1735689600"]                             │
│ ]                                                      │
│ content: { serialized SignedVoucher }                  │
└────────────────────────────────────────────────────────┘
```

When the status changes, a new event with the same d-tag replaces the old one (NIP-33 behavior).

## Step 4: Set Up Private Backups

The `NostrVoucherBackupRepository` implements `VoucherBackupPort` using NIP-17 + NIP-44:

```java
import xyz.tcheeric.cashu.voucher.nostr.NostrVoucherBackupRepository;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherBackupPort;

public class BackupSetup {
    public static VoucherBackupPort createBackup(NostrClientAdapter client) {
        // Create backup repository
        NostrVoucherBackupRepository backup = new NostrVoucherBackupRepository(
            client,
            10000,   // publishTimeoutMs
            15000    // queryTimeoutMs
        );

        return backup;
    }
}
```

### How Backup Works

When you backup vouchers:

1. **Serialization**: Vouchers serialized to JSON
2. **Encryption**: NIP-44 encrypts with user's private key
3. **Self-DM**: Kind 4 event sent to user's own pubkey
4. **Storage**: Relays store the encrypted message

```
┌────────────────────────────────────────────────────────┐
│ NIP-17 Event (Kind 4)                                  │
├────────────────────────────────────────────────────────┤
│ pubkey: <user's pubkey>                                │
│ tags: [                                                │
│   ["p", "<user's pubkey>"]   (self-addressed)          │
│ ]                                                      │
│ content: <NIP-44 encrypted voucher data>               │
└────────────────────────────────────────────────────────┘
```

Only the user with the matching private key can decrypt and restore their vouchers.

## Step 5: Complete Integration

Putting it all together:

```java
import xyz.tcheeric.cashu.voucher.app.VoucherService;
import xyz.tcheeric.cashu.voucher.app.dto.*;
import xyz.tcheeric.cashu.voucher.domain.*;
import xyz.tcheeric.cashu.voucher.nostr.*;

import java.util.List;

public class NostrVoucherDemo {
    public static void main(String[] args) {
        // 1. Set up Nostr client
        List<String> relays = List.of(
            "wss://relay.damus.io",
            "wss://nos.lol"
        );

        NostrRelayConfig config = NostrRelayConfig.builder()
            .relayUrls(relays)
            .publishTimeoutMs(10000)
            .queryTimeoutMs(15000)
            .build();

        NostrClientAdapter client = new NostrClientAdapter(config);
        client.connect();

        // 2. Create ports
        VoucherLedgerPort ledger = new NostrVoucherLedgerRepository(client, 10000, 15000);
        VoucherBackupPort backup = new NostrVoucherBackupRepository(client, 10000, 15000);

        // 3. Issuer keys (merchant)
        String issuerPrivateKey = "merchant-private-key-hex";
        String issuerPublicKey = "merchant-public-key-hex";

        // 4. Create VoucherService
        VoucherService service = new VoucherService(
            ledger,
            backup,
            issuerPrivateKey,
            issuerPublicKey
        );

        // 5. Issue a voucher
        IssueVoucherRequest request = IssueVoucherRequest.builder()
            .issuerId("nostr-demo-merchant")
            .unit("sat")
            .amount(10000L)
            .expiresInDays(365)
            .backingStrategy(BackingStrategy.MINIMAL)
            .issuanceRatio(1.0)
            .faceDecimals(0)
            .memo("Nostr-backed gift card")
            .build();

        IssueVoucherResponse response = service.issue(request);
        System.out.println("Issued voucher: " + response.getVoucherId());
        System.out.println("Published to " + client.getConnectedRelayCount() + " relays");

        // 6. Query status from Nostr
        VoucherStatus status = service.queryStatus(response.getVoucherId())
            .orElseThrow(() -> new RuntimeException("Voucher not found in ledger"));
        System.out.println("Ledger status: " + status);

        // 7. Backup to Nostr (user's key)
        String userPrivateKey = "user-nostr-private-key-hex";
        service.backup(List.of(response.getVoucher()), userPrivateKey);
        System.out.println("Backed up to Nostr");

        // 8. Restore from Nostr
        List<SignedVoucher> restored = service.restore(userPrivateKey);
        System.out.println("Restored " + restored.size() + " vouchers from backup");

        // 9. Clean up
        client.disconnect();
    }
}
```

## Multi-Relay Redundancy

For production, use multiple relays for redundancy:

```java
List<String> productionRelays = List.of(
    // High-reliability relays
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://relay.nostr.band",
    "wss://nostr.wine",

    // Regional relays (optional)
    "wss://relay.nostr.info",
    "wss://relay.snort.social"
);
```

**Best Practices:**
- Use at least 3 relays for redundancy
- Include geographically diverse relays
- Monitor relay health and availability
- Consider running your own relay for critical operations

## Error Handling

Handle Nostr-specific errors:

```java
import xyz.tcheeric.cashu.voucher.nostr.VoucherNostrException;

try {
    VoucherStatus status = service.queryStatus(voucherId)
        .orElse(null);
} catch (VoucherNostrException e) {
    // Nostr-specific error (network, timeout, relay error)
    System.err.println("Nostr error: " + e.getMessage());
} catch (RuntimeException e) {
    // General error
    System.err.println("Error: " + e.getMessage());
}
```

## Testing with Local Relay

For development, use a local Nostr relay:

```java
// Using Testcontainers (in tests)
@Testcontainers
class NostrIntegrationTest {

    @Container
    static GenericContainer<?> relay = new GenericContainer<>("scsibug/nostr-rs-relay:latest")
        .withExposedPorts(8080);

    @Test
    void testWithLocalRelay() {
        String relayUrl = "ws://localhost:" + relay.getMappedPort(8080);

        NostrRelayConfig config = NostrRelayConfig.builder()
            .relayUrls(List.of(relayUrl))
            .build();

        // ... test code
    }
}
```

## What's Next?

You now have full Nostr integration. Continue with:

- [Merchant Verification](../how-to/verify-voucher-as-merchant.md) - Accept vouchers
- [Backup Strategies](../how-to/backup-and-restore.md) - Best practices for backup
- [Nostr Adapter Reference](../reference/nostr-adapters.md) - Full API documentation

## Key Points

- Use `NostrVoucherLedgerRepository` for the public ledger (NIP-33)
- Use `NostrVoucherBackupRepository` for private backups (NIP-17 + NIP-44)
- Configure multiple relays for redundancy
- The ledger is public; backups are encrypted
- Handle network errors gracefully
- Test with local relays during development
