# Nostr Adapters Reference

This reference documents the Nostr infrastructure adapters in the `cashu-voucher-nostr` module.

## Module Overview

**Package:** `xyz.tcheeric.cashu.voucher.nostr`

**Module:** `cashu-voucher-nostr`

**Dependencies:** cashu-voucher-domain, cashu-voucher-app, nostr-java

## NostrVoucherLedgerRepository

Implements `VoucherLedgerPort` using NIP-33 parameterized replaceable events.

### Constructor

```java
public NostrVoucherLedgerRepository(
    NostrClientAdapter client,
    long publishTimeoutMs,
    long queryTimeoutMs
)
```

**Parameters:**
- `client` - Connected Nostr client adapter
- `publishTimeoutMs` - Timeout for publish operations (milliseconds)
- `queryTimeoutMs` - Timeout for query operations (milliseconds)

### Event Format

**Event Kind:** 30078 (NIP-33 parameterized replaceable)

**Tags:**
```json
[
  ["d", "voucher:abc-123-def"],     // d-tag for replaceability
  ["status", "ISSUED"],              // Queryable status
  ["amount", "10000"],               // Face value
  ["unit", "sat"],                   // Currency unit
  ["expiry", "1735689600"]           // Optional expiry timestamp
]
```

**Content:** JSON-serialized `VoucherContent`:
```json
{
  "voucher": { /* SignedVoucher fields */ },
  "status": "ISSUED"
}
```

### Behavior

**publish():**
1. Creates `VoucherLedgerEvent` from voucher and status
2. Publishes to all connected relays
3. Waits for at least one confirmation (up to `publishTimeoutMs`)

**queryStatus():**
1. Creates REQ filter for d-tag `voucher:<voucherId>`
2. Queries all connected relays
3. Returns most recent event's status

**updateStatus():**
1. Queries current voucher data
2. Creates new event with updated status
3. NIP-33 automatically replaces previous event (same d-tag)

### Example

```java
NostrClientAdapter client = new NostrClientAdapter(config);
client.connect();

NostrVoucherLedgerRepository ledger = new NostrVoucherLedgerRepository(
    client,
    10000,  // 10 second publish timeout
    15000   // 15 second query timeout
);

ledger.publish(voucher, VoucherStatus.ISSUED);
```

---

## NostrVoucherBackupRepository

Implements `VoucherBackupPort` using NIP-17 private direct messages with NIP-44 encryption.

### Constructor

```java
public NostrVoucherBackupRepository(
    NostrClientAdapter client,
    long publishTimeoutMs,
    long queryTimeoutMs
)
```

**Parameters:**
- `client` - Connected Nostr client adapter
- `publishTimeoutMs` - Timeout for publish operations (milliseconds)
- `queryTimeoutMs` - Timeout for query operations (milliseconds)

### Event Format

**Event Kind:** 4 (Encrypted Direct Message)

**Tags:**
```json
[
  ["p", "user-public-key-hex"]   // Recipient (self for backup)
]
```

**Content:** NIP-44 encrypted payload:
```json
{
  "vouchers": [
    { /* SignedVoucher 1 */ },
    { /* SignedVoucher 2 */ }
  ],
  "timestamp": 1735689600
}
```

### Encryption (NIP-44)

- **Algorithm:** ChaCha20-Poly1305
- **Key derivation:** Shared secret from sender private key + recipient public key
- **Versioned:** Uses NIP-44 v2 format

### Behavior

**backup():**
1. Creates `VoucherBackupPayload` from voucher list
2. Encrypts with NIP-44 using user's private key
3. Creates kind 4 event addressed to user's public key (self-DM)
4. Publishes to all connected relays

**restore():**
1. Queries all kind 4 events where user is recipient
2. Filters for valid backup events
3. Decrypts each using NIP-44
4. Extracts vouchers from all backups
5. Deduplicates by voucher ID (latest timestamp wins)
6. Returns merged list

### Example

```java
NostrVoucherBackupRepository backup = new NostrVoucherBackupRepository(
    client,
    10000,  // 10 second publish timeout
    15000   // 15 second query timeout
);

// Backup
backup.backup(vouchers, userPrivateKey);

// Restore
List<SignedVoucher> restored = backup.restore(userPrivateKey);
```

---

## NostrClientAdapter

Manages Nostr relay connections and event operations.

### Constructor

```java
public NostrClientAdapter(NostrRelayConfig config)
```

### Configuration

```java
NostrRelayConfig config = NostrRelayConfig.builder()
    .relayUrls(List.of(
        "wss://relay.damus.io",
        "wss://nos.lol"
    ))
    .publishTimeoutMs(10000)
    .queryTimeoutMs(15000)
    .build();
```

### Methods

#### connect

```java
public void connect()
```

Connects to all configured relays in parallel. Non-blocking for individual failures.

#### disconnect

```java
public void disconnect()
```

Closes all relay connections.

#### publishEvent

```java
public boolean publishEvent(GenericEvent event, long timeoutMs)
```

Publishes an event to all connected relays.

**Parameters:**
- `event` - Nostr event to publish
- `timeoutMs` - Timeout for confirmations

**Returns:** `true` if at least one relay confirms

#### queryEvents

```java
public List<GenericEvent> queryEvents(
    String subscriptionId,
    long timeoutMs
)
```

Queries events from all connected relays.

**Parameters:**
- `subscriptionId` - Subscription identifier
- `timeoutMs` - Query timeout

**Returns:** Deduplicated list of events

#### Status Methods

```java
public boolean isConnected()
public int getConnectedRelayCount()
public List<String> getConnectedRelays()
```

---

## VoucherLedgerEvent

Helper class for NIP-33 voucher events.

**Package:** `xyz.tcheeric.cashu.voucher.nostr.events`

### Static Methods

```java
// Create event from voucher
VoucherLedgerEvent fromVoucher(SignedVoucher voucher, VoucherStatus status)

// Check if event is valid voucher event
boolean isValidVoucherEvent(GenericEvent event)
```

### Instance Methods

```java
// Extract voucher from event
SignedVoucher toVoucher()

// Get status from event
VoucherStatus getStatus()

// Get voucher ID from d-tag
String getVoucherId()
```

---

## VoucherBackupPayload

Helper class for NIP-17 backup events.

**Package:** `xyz.tcheeric.cashu.voucher.nostr.events`

### Static Methods

```java
// Create backup event
GenericEvent createBackupEvent(
    List<SignedVoucher> vouchers,
    String userPrivateKey,
    String userPublicKey
)

// Check if event is valid backup
boolean isValidBackupEvent(GenericEvent event)

// Extract vouchers from event
List<SignedVoucher> extractVouchers(
    GenericEvent event,
    String userPrivateKey,
    String userPublicKey
)
```

---

## VoucherEventPayloadMapper

Maps between domain entities and event payloads.

**Package:** `xyz.tcheeric.cashu.voucher.nostr.events`

### Static Methods

```java
// Domain to payload
VoucherPayload toPayload(SignedVoucher voucher)

// Payload to domain
SignedVoucher fromPayload(VoucherPayload payload)
```

---

## VoucherNostrException

Custom exception for Nostr-related errors.

**Package:** `xyz.tcheeric.cashu.voucher.nostr`

### Constructors

```java
VoucherNostrException(String message)
VoucherNostrException(String message, Throwable cause)
```

### Common Causes

- Network connectivity issues
- Relay timeout
- Encryption/decryption failure
- Invalid event format
- Serialization error

---

## NostrRelayConfig

Configuration holder for relay settings.

**Package:** `xyz.tcheeric.cashu.voucher.nostr.config`

### Builder

```java
NostrRelayConfig config = NostrRelayConfig.builder()
    .relayUrls(List.of("wss://relay.damus.io"))
    .publishTimeoutMs(10000)
    .queryTimeoutMs(15000)
    .build();
```

### Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `relayUrls` | `List<String>` | Required | WebSocket relay URLs |
| `publishTimeoutMs` | `long` | 10000 | Publish operation timeout |
| `queryTimeoutMs` | `long` | 15000 | Query operation timeout |

---

## NIP Reference

| NIP | Usage | Description |
|-----|-------|-------------|
| NIP-01 | Events | Basic event format |
| NIP-04 | Kind 4 | Encrypted direct messages |
| NIP-17 | Backup | Private DM protocol |
| NIP-33 | Ledger | Parameterized replaceable events |
| NIP-44 | Encryption | Versioned encryption (v2) |

### NIP-33 Event Replacement

NIP-33 events with the same:
- `pubkey` (author)
- `kind` (30078)
- `d` tag value

Are replaced by newer events (based on `created_at`). This ensures:
- Single source of truth per voucher
- Status updates replace previous status
- No duplicate entries

### NIP-44 Encryption

ChaCha20-Poly1305 authenticated encryption:
- **Key exchange:** X25519 (from secp256k1 keys)
- **Cipher:** ChaCha20-Poly1305 AEAD
- **Nonce:** 12 bytes, randomly generated
- **Version:** 2 (current)

---

## Relay Selection

### Recommended Relays

```java
List<String> productionRelays = List.of(
    "wss://relay.damus.io",      // Popular, reliable
    "wss://nos.lol",             // Popular
    "wss://relay.nostr.band",    // Good uptime
    "wss://nostr.wine",          // Reliable
    "wss://relay.snort.social"   // Popular client relay
);
```

### Considerations

- **Redundancy:** Use 3+ relays
- **Geography:** Include diverse locations
- **Reliability:** Monitor uptime
- **Paid relays:** Consider for critical applications

---

## Related

- [Nostr Integration Tutorial](../tutorials/nostr-integration.md)
- [Ports Reference](./ports.md)
- [Backup and Restore](../how-to/backup-and-restore.md)
