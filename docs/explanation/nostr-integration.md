# Nostr Integration Explained

This document explains why Cashu Voucher uses Nostr, how the integration works, and the design decisions behind the implementation.

## Why Nostr?

Cashu Voucher needs two types of storage:

1. **Public Ledger**: Audit trail of voucher status (prevents double-spending)
2. **Private Backup**: Encrypted storage for voucher recovery

Nostr provides both with compelling benefits:

| Requirement | Nostr Solution | Benefit |
|-------------|----------------|---------|
| Public ledger | NIP-33 events | Decentralized, no single point of failure |
| Private backup | NIP-17 + NIP-44 | User-controlled, portable |
| Availability | Multiple relays | Redundancy without central server |
| Interoperability | Standard protocol | Works with any Nostr client |
| Privacy | Encryption options | User chooses what's private |

### Alternative Approaches

| Approach | Trade-offs |
|----------|------------|
| Central database | Single point of failure, requires trust |
| Blockchain | High cost, slow confirmation |
| IPFS | Good for content, poor for status updates |
| Cloud storage | Vendor lock-in, privacy concerns |

Nostr hits a sweet spot: decentralized, low-cost, good privacy, and an established ecosystem.

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           Cashu Voucher                                  │
│                                                                          │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │                   Application Layer                              │    │
│  │                                                                  │    │
│  │   VoucherLedgerPort              VoucherBackupPort               │    │
│  │   (interface)                    (interface)                     │    │
│  │                                                                  │    │
│  └──────────────┬──────────────────────────────────┬───────────────┘    │
│                 │                                   │                    │
│                 ▼                                   ▼                    │
│  ┌──────────────────────────────┐    ┌──────────────────────────────┐   │
│  │ NostrVoucherLedgerRepository │    │ NostrVoucherBackupRepository │   │
│  │                              │    │                              │   │
│  │ Kind 30078 (NIP-33)          │    │ Kind 4 (NIP-17 + NIP-44)     │   │
│  │ Public, replaceable          │    │ Private, encrypted           │   │
│  └──────────────┬───────────────┘    └──────────────┬───────────────┘   │
│                 │                                   │                    │
│                 └─────────────┬─────────────────────┘                    │
│                               │                                          │
│                               ▼                                          │
│                 ┌─────────────────────────┐                              │
│                 │   NostrClientAdapter    │                              │
│                 │                         │                              │
│                 │   Connection management │                              │
│                 │   Event publishing      │                              │
│                 │   Event querying        │                              │
│                 └───────────┬─────────────┘                              │
│                             │                                            │
└─────────────────────────────┼────────────────────────────────────────────┘
                              │
                              ▼
              ┌───────────────────────────────────┐
              │         Nostr Relays              │
              │                                   │
              │   ┌─────────┐   ┌─────────┐      │
              │   │Relay 1  │   │Relay 2  │ ...  │
              │   │damus.io │   │nos.lol  │      │
              │   └─────────┘   └─────────┘      │
              │                                   │
              └───────────────────────────────────┘
```

## Public Ledger (NIP-33)

### What is NIP-33?

NIP-33 defines **Parameterized Replaceable Events**. Key properties:
- Events with the same author + kind + d-tag value replace each other
- Only the most recent event (by `created_at`) is stored
- Perfect for status that changes over time

### Event Structure

```json
{
  "kind": 30078,
  "pubkey": "<issuer public key>",
  "created_at": 1735689600,
  "tags": [
    ["d", "voucher:abc-123-def"],
    ["status", "ISSUED"],
    ["amount", "10000"],
    ["unit", "sat"],
    ["expiry", "1767225600"]
  ],
  "content": "{\"voucher\": {...}, \"status\": \"ISSUED\"}",
  "sig": "<signature>"
}
```

### Tag Purposes

| Tag | Purpose |
|-----|---------|
| `d` | Makes event replaceable by voucher ID |
| `status` | Queryable status without parsing content |
| `amount` | Queryable face value |
| `unit` | Queryable currency |
| `expiry` | Queryable expiry timestamp |

### Status Update Flow

```
┌────────────────────────────────────────────────────────────────┐
│                    Status Update via NIP-33                    │
│                                                                │
│  Initial Publish:                                              │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ Event A (created_at: 1000)                               │ │
│  │ d-tag: "voucher:abc-123"                                 │ │
│  │ status: "ISSUED"                                         │ │
│  └──────────────────────────────────────────────────────────┘ │
│                              │                                 │
│                              ▼                                 │
│  Status Update:                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ Event B (created_at: 2000)                               │ │
│  │ d-tag: "voucher:abc-123"   (same d-tag)                  │ │
│  │ status: "REDEEMED"                                       │ │
│  └──────────────────────────────────────────────────────────┘ │
│                              │                                 │
│                              ▼                                 │
│  Relay keeps only Event B (newer created_at)                   │
│  Queries return: status = "REDEEMED"                           │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### Why Kind 30078?

- Range 30000-39999 is for parameterized replaceable events (NIP-33)
- 30078 is commonly used for arbitrary application data
- Avoids conflicts with other Nostr applications

## Private Backup (NIP-17 + NIP-44)

### What is NIP-17?

NIP-17 defines **Private Direct Messages**. Key properties:
- Kind 4 events for encrypted communication
- Only sender and recipient can read content
- Recipient specified in `p` tag

### What is NIP-44?

NIP-44 defines **Versioned Encryption**:
- ChaCha20-Poly1305 authenticated encryption
- X25519 key exchange (derived from secp256k1 keys)
- Version field for future-proofing

### Self-Addressed DM Pattern

For backup, we use a "self-DM" pattern:

```
┌────────────────────────────────────────────────────────────────┐
│                    Self-Addressed Backup                       │
│                                                                │
│  User A wants to backup vouchers:                              │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ Kind: 4 (encrypted DM)                                   │ │
│  │ pubkey: <User A's public key>                            │ │
│  │ tags: [["p", "<User A's public key>"]]  ← To self        │ │
│  │ content: <NIP-44 encrypted voucher data>                 │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
│  Only User A can decrypt (shared secret with self)             │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

### Encryption Flow

```
Backup:
┌──────────────┐     ┌─────────────────┐     ┌──────────────────┐
│   Vouchers   │────▶│ Serialize JSON  │────▶│ NIP-44 Encrypt   │
│   (domain)   │     │                 │     │ (user's key)     │
└──────────────┘     └─────────────────┘     └────────┬─────────┘
                                                      │
                                                      ▼
                                             ┌──────────────────┐
                                             │ Kind 4 Event     │
                                             │ (to self)        │
                                             └────────┬─────────┘
                                                      │
                                                      ▼
                                             ┌──────────────────┐
                                             │ Publish to       │
                                             │ Nostr Relays     │
                                             └──────────────────┘

Restore:
┌──────────────┐     ┌─────────────────┐     ┌──────────────────┐
│ Query Kind 4 │────▶│ NIP-44 Decrypt  │────▶│ Deserialize JSON │
│ Events       │     │ (user's key)    │     │                  │
└──────────────┘     └─────────────────┘     └────────┬─────────┘
                                                      │
                                                      ▼
                                             ┌──────────────────┐
                                             │   Vouchers       │
                                             │   (restored)     │
                                             └──────────────────┘
```

## Multi-Relay Strategy

### Why Multiple Relays?

- **Redundancy**: If one relay goes down, others have your data
- **Availability**: Better uptime than any single relay
- **Performance**: Query from multiple sources
- **Censorship resistance**: No single relay can block you

### Publish Strategy

```java
// Publish to ALL connected relays
for (Relay relay : connectedRelays) {
    try {
        relay.publish(event);
    } catch (Exception e) {
        // Log but continue to other relays
    }
}

// Success if at least one relay confirms
```

### Query Strategy

```java
// Query from ALL relays, deduplicate
Set<GenericEvent> allEvents = new HashSet<>();
for (Relay relay : connectedRelays) {
    List<GenericEvent> events = relay.query(filter);
    allEvents.addAll(events);  // Dedupe by event ID
}
return new ArrayList<>(allEvents);
```

### Recommended Configuration

```java
List<String> relays = List.of(
    "wss://relay.damus.io",      // High reliability
    "wss://nos.lol",             // Popular
    "wss://relay.nostr.band",    // Good uptime
    "wss://nostr.wine"           // Reliable
);

// At least 3 relays for redundancy
// Geographically diverse preferred
```

## Data Privacy

### Public Ledger Privacy

The ledger is **intentionally public**:
- Voucher status visible to anyone
- Enables third-party verification
- Auditable by merchants and customers
- Does NOT contain private keys

**What's exposed:**
- Voucher ID
- Issuer ID (merchant)
- Face value and unit
- Status (ISSUED, REDEEMED, etc.)
- Expiry timestamp

**What's protected:**
- User identity (no link to user unless they reveal it)
- Private keys (never published)
- Other vouchers (not linked)

### Backup Privacy

Backups are **encrypted and private**:
- NIP-44 encryption (ChaCha20-Poly1305)
- Only user's private key can decrypt
- Content looks like random bytes to relays

**What's exposed:**
- Event exists (kind 4 to self)
- Approximate size
- Timestamp

**What's protected:**
- Voucher contents
- Voucher IDs
- Any user data

## Recovery Scenarios

### Scenario 1: New Device

```
User loses phone, gets new one:

1. Enter Nostr private key (nsec...)
2. Connect to relays
3. Query all kind 4 events to self
4. Decrypt with private key
5. Vouchers restored!
```

### Scenario 2: Relay Failure

```
Primary relay goes offline:

1. Other relays still have data
2. Query succeeds from remaining relays
3. No data loss
4. Can publish to add new relay later
```

### Scenario 3: Corrupted Backup

```
One relay has corrupted data:

1. Query all relays
2. Deduplicate by event ID
3. Valid events from other relays used
4. Corrupted event ignored
```

## Limitations and Considerations

### Relay Reliability

- Free relays may have downtime
- Consider paid relays for critical applications
- Self-host a relay for maximum control

### Data Persistence

- Relays may prune old events
- Republish periodically if concerned
- Consider multiple backup methods

### Key Management

- Nostr key = access to backups
- Lose key = lose ability to decrypt
- Consider key backup separately

### Network Dependency

- Requires internet for backup/restore
- Offline verification still works (domain layer)
- Online verification requires relay access

## Implementation Notes

### Event Deduplication

Events are deduplicated by ID (hash of event):
```java
Set<String> seenIds = new HashSet<>();
List<GenericEvent> deduplicated = events.stream()
    .filter(e -> seenIds.add(e.getId()))
    .toList();
```

### Timeout Handling

```java
// Configurable timeouts
NostrRelayConfig config = NostrRelayConfig.builder()
    .publishTimeoutMs(10000)   // 10 seconds for publish
    .queryTimeoutMs(15000)     // 15 seconds for query
    .build();
```

### Error Recovery

```java
try {
    ledgerPort.publish(voucher, status);
} catch (VoucherNostrException e) {
    // Retry with backoff
    // Or queue for later
}
```

## Related

- [Nostr Integration Tutorial](../tutorials/nostr-integration.md)
- [Nostr Adapters Reference](../reference/nostr-adapters.md)
- [Backup and Restore Guide](../how-to/backup-and-restore.md)
- [Architecture Overview](./architecture.md)
