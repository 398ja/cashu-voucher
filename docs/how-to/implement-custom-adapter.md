# How to Implement Custom Storage Adapters

This guide shows you how to implement custom storage adapters for the voucher ledger and backup ports. Use this when you need storage other than Nostr (e.g., SQL database, cloud storage, IPFS).

## Understanding Ports and Adapters

Cashu Voucher uses **hexagonal architecture**. Ports define what the application needs; adapters provide implementations:

```
┌──────────────────────────────────────────────────────────────┐
│                    Application Layer                          │
│                                                               │
│   VoucherService    MerchantVerificationService               │
│         │                      │                              │
│         ▼                      ▼                              │
│  ┌─────────────────┐   ┌─────────────────┐                   │
│  │VoucherLedgerPort│   │VoucherBackupPort│  ← Ports          │
│  │   (interface)   │   │   (interface)   │                   │
│  └────────┬────────┘   └────────┬────────┘                   │
└───────────┼─────────────────────┼────────────────────────────┘
            │                     │
            ▼                     ▼
┌───────────────────┐   ┌───────────────────┐
│ NostrLedgerRepo   │   │ NostrBackupRepo   │  ← Adapters
│ SQLLedgerRepo     │   │ S3BackupRepo      │
│ IPFSLedgerRepo    │   │ FileBackupRepo    │
└───────────────────┘   └───────────────────┘
```

## Implement VoucherLedgerPort

The ledger port handles the public audit trail. Here's how to implement it with SQL:

```java
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import javax.sql.DataSource;
import java.sql.*;
import java.util.Optional;

public class SqlVoucherLedgerRepository implements VoucherLedgerPort {

    private final DataSource dataSource;

    public SqlVoucherLedgerRepository(DataSource dataSource) {
        this.dataSource = dataSource;
        initializeSchema();
    }

    private void initializeSchema() {
        String sql = """
            CREATE TABLE IF NOT EXISTS voucher_ledger (
                voucher_id VARCHAR(64) PRIMARY KEY,
                status VARCHAR(20) NOT NULL,
                voucher_data TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize schema", e);
        }
    }

    @Override
    public void publish(SignedVoucher voucher, VoucherStatus status) {
        String sql = """
            INSERT INTO voucher_ledger (voucher_id, status, voucher_data)
            VALUES (?, ?, ?)
            ON CONFLICT (voucher_id) DO UPDATE SET
                status = EXCLUDED.status,
                voucher_data = EXCLUDED.voucher_data,
                updated_at = CURRENT_TIMESTAMP
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, voucher.getSecret().getVoucherId());
            stmt.setString(2, status.name());
            stmt.setString(3, serializeVoucher(voucher));
            stmt.executeUpdate();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to publish voucher", e);
        }
    }

    @Override
    public Optional<VoucherStatus> queryStatus(String voucherId) {
        String sql = "SELECT status FROM voucher_ledger WHERE voucher_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, voucherId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return Optional.of(VoucherStatus.valueOf(rs.getString("status")));
            }
            return Optional.empty();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to query status", e);
        }
    }

    @Override
    public void updateStatus(String voucherId, VoucherStatus newStatus) {
        String sql = """
            UPDATE voucher_ledger
            SET status = ?, updated_at = CURRENT_TIMESTAMP
            WHERE voucher_id = ?
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, newStatus.name());
            stmt.setString(2, voucherId);
            int rows = stmt.executeUpdate();

            if (rows == 0) {
                throw new RuntimeException("Voucher not found: " + voucherId);
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to update status", e);
        }
    }

    @Override
    public Optional<SignedVoucher> queryVoucher(String voucherId) {
        String sql = "SELECT voucher_data FROM voucher_ledger WHERE voucher_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, voucherId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return Optional.of(deserializeVoucher(rs.getString("voucher_data")));
            }
            return Optional.empty();

        } catch (SQLException e) {
            throw new RuntimeException("Failed to query voucher", e);
        }
    }

    private String serializeVoucher(SignedVoucher voucher) {
        // Implement JSON serialization
        // Use Jackson ObjectMapper
        return "{}"; // placeholder
    }

    private SignedVoucher deserializeVoucher(String json) {
        // Implement JSON deserialization
        return null; // placeholder
    }
}
```

## Implement VoucherBackupPort

The backup port handles private user storage. Here's an S3 implementation:

```java
import xyz.tcheeric.cashu.voucher.app.ports.VoucherBackupPort;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.core.sync.RequestBody;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.List;

public class S3VoucherBackupRepository implements VoucherBackupPort {

    private final S3Client s3Client;
    private final String bucketName;

    public S3VoucherBackupRepository(S3Client s3Client, String bucketName) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
    }

    @Override
    public void backup(List<SignedVoucher> vouchers, String userPrivateKey) {
        if (vouchers.isEmpty()) {
            return;
        }

        // Derive encryption key from user's private key
        byte[] encryptionKey = deriveKey(userPrivateKey);

        // Serialize vouchers
        String json = serializeVouchers(vouchers);

        // Encrypt
        byte[] encrypted = encrypt(json.getBytes(), encryptionKey);

        // Generate object key based on user's public key
        String objectKey = "backups/" + derivePublicKey(userPrivateKey) + "/vouchers.enc";

        // Upload to S3
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build(),
            RequestBody.fromBytes(encrypted)
        );
    }

    @Override
    public List<SignedVoucher> restore(String userPrivateKey) {
        // Derive keys
        byte[] encryptionKey = deriveKey(userPrivateKey);
        String objectKey = "backups/" + derivePublicKey(userPrivateKey) + "/vouchers.enc";

        try {
            // Download from S3
            byte[] encrypted = s3Client.getObjectAsBytes(
                GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build()
            ).asByteArray();

            // Decrypt
            byte[] decrypted = decrypt(encrypted, encryptionKey);

            // Deserialize
            return deserializeVouchers(new String(decrypted));

        } catch (NoSuchKeyException e) {
            return List.of(); // No backup found
        }
    }

    @Override
    public boolean hasBackups(String userPrivateKey) {
        String objectKey = "backups/" + derivePublicKey(userPrivateKey) + "/vouchers.enc";

        try {
            s3Client.headObject(
                HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build()
            );
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public void deleteBackups(String userPrivateKey) {
        String objectKey = "backups/" + derivePublicKey(userPrivateKey) + "/vouchers.enc";

        s3Client.deleteObject(
            DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build()
        );
    }

    // Helper methods (implement properly)
    private byte[] deriveKey(String privateKey) { /* ... */ }
    private String derivePublicKey(String privateKey) { /* ... */ }
    private byte[] encrypt(byte[] data, byte[] key) { /* ... */ }
    private byte[] decrypt(byte[] data, byte[] key) { /* ... */ }
    private String serializeVouchers(List<SignedVoucher> vouchers) { /* ... */ }
    private List<SignedVoucher> deserializeVouchers(String json) { /* ... */ }
}
```

## In-Memory Implementation (Testing)

For unit tests, use simple in-memory implementations:

```java
public class InMemoryVoucherLedgerPort implements VoucherLedgerPort {
    private final Map<String, VoucherStatus> statusMap = new ConcurrentHashMap<>();
    private final Map<String, SignedVoucher> voucherMap = new ConcurrentHashMap<>();

    @Override
    public void publish(SignedVoucher voucher, VoucherStatus status) {
        String id = voucher.getSecret().getVoucherId();
        voucherMap.put(id, voucher);
        statusMap.put(id, status);
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
    }

    @Override
    public Optional<SignedVoucher> queryVoucher(String voucherId) {
        return Optional.ofNullable(voucherMap.get(voucherId));
    }

    // Test helper methods
    public void clear() {
        statusMap.clear();
        voucherMap.clear();
    }

    public int size() {
        return statusMap.size();
    }
}
```

## Combining Multiple Adapters

Use a composite adapter for redundancy:

```java
public class CompositeVoucherLedgerPort implements VoucherLedgerPort {
    private final List<VoucherLedgerPort> adapters;

    public CompositeVoucherLedgerPort(VoucherLedgerPort... adapters) {
        this.adapters = List.of(adapters);
    }

    @Override
    public void publish(SignedVoucher voucher, VoucherStatus status) {
        // Publish to all adapters
        for (VoucherLedgerPort adapter : adapters) {
            try {
                adapter.publish(voucher, status);
            } catch (Exception e) {
                // Log but continue to other adapters
                System.err.println("Publish failed on adapter: " + e.getMessage());
            }
        }
    }

    @Override
    public Optional<VoucherStatus> queryStatus(String voucherId) {
        // Query from first successful adapter
        for (VoucherLedgerPort adapter : adapters) {
            try {
                Optional<VoucherStatus> status = adapter.queryStatus(voucherId);
                if (status.isPresent()) {
                    return status;
                }
            } catch (Exception e) {
                // Try next adapter
            }
        }
        return Optional.empty();
    }

    @Override
    public void updateStatus(String voucherId, VoucherStatus newStatus) {
        // Update all adapters
        for (VoucherLedgerPort adapter : adapters) {
            try {
                adapter.updateStatus(voucherId, newStatus);
            } catch (Exception e) {
                System.err.println("Update failed on adapter: " + e.getMessage());
            }
        }
    }
}
```

## Using Custom Adapters

Wire your custom adapters into the service:

```java
// Create custom adapters
DataSource dataSource = createDataSource();
VoucherLedgerPort sqlLedger = new SqlVoucherLedgerRepository(dataSource);

S3Client s3Client = S3Client.create();
VoucherBackupPort s3Backup = new S3VoucherBackupRepository(s3Client, "my-bucket");

// Or use composite
VoucherLedgerPort compositeLedger = new CompositeVoucherLedgerPort(
    sqlLedger,
    nostrLedger
);

// Create service
VoucherService service = new VoucherService(
    compositeLedger,
    s3Backup,
    issuerPrivateKey,
    issuerPublicKey
);
```

## Testing Custom Adapters

Write thorough tests for your adapters:

```java
class SqlVoucherLedgerRepositoryTest {

    private SqlVoucherLedgerRepository repo;
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = createTestDataSource(); // H2 in-memory
        repo = new SqlVoucherLedgerRepository(dataSource);
    }

    @Test
    void publishAndQuery() {
        SignedVoucher voucher = createTestVoucher();

        repo.publish(voucher, VoucherStatus.ISSUED);

        Optional<VoucherStatus> status = repo.queryStatus(voucher.getSecret().getVoucherId());

        assertThat(status).isPresent();
        assertThat(status.get()).isEqualTo(VoucherStatus.ISSUED);
    }

    @Test
    void updateStatus() {
        SignedVoucher voucher = createTestVoucher();
        repo.publish(voucher, VoucherStatus.ISSUED);

        repo.updateStatus(voucher.getSecret().getVoucherId(), VoucherStatus.REDEEMED);

        assertThat(repo.queryStatus(voucher.getSecret().getVoucherId()))
            .contains(VoucherStatus.REDEEMED);
    }

    @Test
    void queryNonExistent() {
        assertThat(repo.queryStatus("non-existent")).isEmpty();
    }
}
```

## Related

- [Ports Reference](../reference/ports.md)
- [Architecture Overview](../explanation/architecture.md)
- [Nostr Integration](../tutorials/nostr-integration.md)
