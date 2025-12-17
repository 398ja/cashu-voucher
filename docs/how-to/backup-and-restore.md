# How to Backup and Restore Vouchers

This guide explains how to backup vouchers to secure storage and restore them after wallet loss or device change. Since vouchers are non-deterministic, backup is essential for recovery.

## Why Backup is Critical

Unlike Bitcoin HD wallets or NUT-13 deterministic secrets, Cashu Voucher secrets **cannot be regenerated from a seed phrase**. If you lose your wallet without a backup, your vouchers are lost forever.

```
┌─────────────────────────────────────────────────────────────┐
│  NUT-13 Deterministic:    Seed → Derive → Same Secrets      │
│  Cashu Voucher:           Random → Must Backup → Restore    │
└─────────────────────────────────────────────────────────────┘
```

## Basic Backup

Backup vouchers using `VoucherService`:

```java
// Your user's Nostr private key (for encryption)
String userPrivateKey = "your-nostr-private-key-hex";

// Vouchers to backup
List<SignedVoucher> vouchers = wallet.getAllVouchers();

// Backup
voucherService.backup(vouchers, userPrivateKey);
System.out.println("Backed up " + vouchers.size() + " vouchers");
```

## Basic Restore

Restore vouchers from backup:

```java
String userPrivateKey = "your-nostr-private-key-hex";

List<SignedVoucher> restored = voucherService.restore(userPrivateKey);
System.out.println("Restored " + restored.size() + " vouchers");

// Add to wallet
for (SignedVoucher voucher : restored) {
    wallet.addVoucher(voucher);
}
```

## Incremental Backup Strategy

Backup new vouchers incrementally instead of all vouchers each time:

```java
public class IncrementalBackupManager {
    private final VoucherService voucherService;
    private final Set<String> backedUpIds = new HashSet<>();

    public void backupNewVouchers(List<SignedVoucher> allVouchers, String userKey) {
        // Find vouchers not yet backed up
        List<SignedVoucher> newVouchers = allVouchers.stream()
            .filter(v -> !backedUpIds.contains(v.getSecret().getVoucherId()))
            .toList();

        if (newVouchers.isEmpty()) {
            return; // Nothing new to backup
        }

        // Backup new vouchers
        voucherService.backup(newVouchers, userKey);

        // Track backed up IDs
        newVouchers.forEach(v ->
            backedUpIds.add(v.getSecret().getVoucherId()));

        System.out.println("Backed up " + newVouchers.size() + " new vouchers");
    }
}
```

## Automatic Backup After Issuance

Backup immediately when vouchers are received:

```java
public SignedVoucher receiveVoucher(String token, String userPrivateKey) {
    // Parse and validate token
    SignedVoucher voucher = parseToken(token);

    // Verify signature
    if (!voucher.verify()) {
        throw new IllegalArgumentException("Invalid voucher signature");
    }

    // Add to wallet
    wallet.addVoucher(voucher);

    // Immediately backup
    voucherService.backup(List.of(voucher), userPrivateKey);

    return voucher;
}
```

## Merge Restored with Existing

Handle conflicts when restoring to a wallet that already has vouchers:

```java
public List<SignedVoucher> restoreAndMerge(String userPrivateKey) {
    // Get existing vouchers
    Map<String, SignedVoucher> existing = wallet.getAllVouchers().stream()
        .collect(Collectors.toMap(
            v -> v.getSecret().getVoucherId(),
            v -> v
        ));

    // Restore from backup
    List<SignedVoucher> restored = voucherService.restore(userPrivateKey);

    // Merge (backup has newer data for conflicts)
    List<SignedVoucher> newVouchers = new ArrayList<>();
    for (SignedVoucher voucher : restored) {
        String id = voucher.getSecret().getVoucherId();
        if (!existing.containsKey(id)) {
            wallet.addVoucher(voucher);
            newVouchers.add(voucher);
        }
    }

    return newVouchers;
}
```

## Check Voucher Status After Restore

Restored vouchers may have been redeemed. Verify status:

```java
public List<SignedVoucher> restoreAndFilterActive(String userPrivateKey) {
    List<SignedVoucher> restored = voucherService.restore(userPrivateKey);
    List<SignedVoucher> active = new ArrayList<>();

    for (SignedVoucher voucher : restored) {
        String id = voucher.getSecret().getVoucherId();

        // Check current status in ledger
        Optional<VoucherStatus> status = voucherService.queryStatus(id);

        if (status.isEmpty()) {
            System.out.println("Voucher " + id + " not found in ledger (may be old)");
            continue;
        }

        switch (status.get()) {
            case ISSUED -> {
                active.add(voucher);
                System.out.println("Voucher " + id + " is active");
            }
            case REDEEMED -> System.out.println("Voucher " + id + " already redeemed");
            case REVOKED -> System.out.println("Voucher " + id + " was revoked");
            case EXPIRED -> System.out.println("Voucher " + id + " has expired");
        }
    }

    return active;
}
```

## Backup to Multiple Relays

When using Nostr, ensure backups go to multiple relays:

```java
// Configure multiple relays for redundancy
List<String> relays = List.of(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://relay.nostr.band",
    "wss://nostr.wine"
);

NostrRelayConfig config = NostrRelayConfig.builder()
    .relayUrls(relays)
    .publishTimeoutMs(15000)
    .build();

// Backups will be published to all connected relays
```

## Scheduled Backup

Run backups on a schedule:

```java
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ScheduledBackupService {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final VoucherService voucherService;
    private final Wallet wallet;
    private final String userPrivateKey;

    public void startScheduledBackup(long intervalMinutes) {
        scheduler.scheduleAtFixedRate(
            this::runBackup,
            0,
            intervalMinutes,
            TimeUnit.MINUTES
        );
    }

    private void runBackup() {
        try {
            List<SignedVoucher> vouchers = wallet.getAllVouchers();
            if (!vouchers.isEmpty()) {
                voucherService.backup(vouchers, userPrivateKey);
                System.out.println("Scheduled backup completed: " + vouchers.size() + " vouchers");
            }
        } catch (Exception e) {
            System.err.println("Backup failed: " + e.getMessage());
        }
    }

    public void stop() {
        scheduler.shutdown();
    }
}
```

## Export and Import (Alternative)

For file-based backup:

```java
import com.fasterxml.jackson.databind.ObjectMapper;

public class FileBackupService {
    private final ObjectMapper mapper = new ObjectMapper();

    public void exportToFile(List<SignedVoucher> vouchers, Path filePath) throws IOException {
        String json = mapper.writeValueAsString(vouchers);
        Files.writeString(filePath, json);
        System.out.println("Exported " + vouchers.size() + " vouchers to " + filePath);
    }

    public List<SignedVoucher> importFromFile(Path filePath) throws IOException {
        String json = Files.readString(filePath);
        return mapper.readValue(json, new TypeReference<List<SignedVoucher>>() {});
    }
}
```

**Warning:** File backups are not encrypted by default. Consider encrypting the file.

## Recovery Scenarios

### Scenario 1: New Device

```java
// On new device with same Nostr key
String userPrivateKey = "your-nostr-private-key-hex";

// Restore all vouchers
List<SignedVoucher> vouchers = voucherService.restore(userPrivateKey);

// Filter to active only
List<SignedVoucher> active = filterActiveVouchers(vouchers);

// Initialize new wallet
wallet.addVouchers(active);
```

### Scenario 2: Corrupted Wallet

```java
// Clear corrupted data
wallet.clear();

// Restore from backup
List<SignedVoucher> restored = voucherService.restore(userPrivateKey);

// Re-verify all vouchers
for (SignedVoucher voucher : restored) {
    if (voucher.isValid()) {
        wallet.addVoucher(voucher);
    }
}
```

### Scenario 3: Multiple Backups

```java
// If you have backups from different keys
List<String> allKeys = List.of(
    "old-key-hex",
    "current-key-hex"
);

Set<String> seenIds = new HashSet<>();
List<SignedVoucher> allVouchers = new ArrayList<>();

for (String key : allKeys) {
    List<SignedVoucher> restored = voucherService.restore(key);
    for (SignedVoucher voucher : restored) {
        String id = voucher.getSecret().getVoucherId();
        if (!seenIds.contains(id)) {
            seenIds.add(id);
            allVouchers.add(voucher);
        }
    }
}
```

## Best Practices

1. **Backup immediately after receiving** - Don't wait for scheduled backup
2. **Use multiple relays** - Redundancy prevents data loss
3. **Verify restored vouchers** - Check signature and ledger status
4. **Keep your Nostr key safe** - It's the key to your backups
5. **Test restore periodically** - Ensure backups are working
6. **Don't store private keys with vouchers** - Separate concerns

## Related

- [Nostr Integration Tutorial](../tutorials/nostr-integration.md)
- [VoucherBackupPort Reference](../reference/ports.md#voucherbackupport)
- [Model B Vouchers Explained](../explanation/model-b-vouchers.md)
