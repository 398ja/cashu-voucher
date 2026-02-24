# Cashu Voucher

Gift card voucher system with Nostr storage for the Cashu ecash protocol.

## Overview

Cashu Voucher implements **Model B** gift card vouchers - vouchers that are spendable only at the issuing merchant, not redeemable at the mint. The system uses Nostr for both public ledger (NIP-33) and private backup (NIP-17 + NIP-44) storage.

### Key Features

- **Model B Implementation** - Vouchers only redeemable at issuing merchant
- **Nostr Storage** - Public ledger (NIP-33) and private backup (NIP-17 + NIP-44)
- **Schnorr Signatures** - BIP-340 secp256k1 cryptographic voucher verification
- **Offline Verification** - Merchants can verify without network access
- **Double-Spend Protection** - Nostr ledger prevents voucher reuse
- **Backing Strategies** - FIXED, MINIMAL, PROPORTIONAL for different use cases
- **Expiry Support** - Time-limited vouchers with automatic expiry checks
- **Merchant Metadata** - Custom business data attached to vouchers
- **Comprehensive Testing** - Extensive test coverage across all layers
- **Hexagonal Architecture** - Clean separation of concerns, pluggable adapters

## Architecture

This project follows **Hexagonal Architecture** (Ports & Adapters) with three modules:

```
┌─────────────────────────────────────────────────────────────────┐
│                    cashu-voucher-nostr                          │
│               (Nostr storage implementation)                    │
└──────────────────────────────┬──────────────────────────────────┘
                               │ implements ports
┌──────────────────────────────▼──────────────────────────────────┐
│                     cashu-voucher-app                           │
│            (Services, ports, DTOs)                              │
└──────────────────────────────┬──────────────────────────────────┘
                               │ uses domain
┌──────────────────────────────▼──────────────────────────────────┐
│                    cashu-voucher-domain                         │
│            (Pure business logic, no dependencies)               │
└─────────────────────────────────────────────────────────────────┘
```

- **cashu-voucher-domain** - Pure domain logic (VoucherSecret, SignedVoucher, validation)
- **cashu-voucher-app** - Application services and port interfaces
- **cashu-voucher-nostr** - Nostr infrastructure adapter (NIP-33, NIP-17)

## Quick Start

### Installation

Add the voucher modules to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>reposilite-releases</id>
        <url>https://maven.398ja.xyz/releases</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>xyz.tcheeric</groupId>
        <artifactId>cashu-voucher-domain</artifactId>
        <version>0.3.6</version>
    </dependency>
    <dependency>
        <groupId>xyz.tcheeric</groupId>
        <artifactId>cashu-voucher-app</artifactId>
        <version>0.3.6</version>
    </dependency>
    <dependency>
        <groupId>xyz.tcheeric</groupId>
        <artifactId>cashu-voucher-nostr</artifactId>
        <version>0.3.6</version>
    </dependency>
</dependencies>
```

### Issue a Voucher

```java
// Create voucher service
VoucherService voucherService = new VoucherService(
    ledgerPort,
    backupPort,
    issuerPrivateKey,
    issuerPublicKey
);

// Issue voucher
IssueVoucherRequest request = IssueVoucherRequest.builder()
    .issuerId("my-coffee-shop")
    .unit("sat")
    .amount(10000L)                          // 10,000 sats
    .expiresInDays(365)
    .memo("Coffee shop gift card")
    .backingStrategy(BackingStrategy.MINIMAL)
    .issuanceRatio(1.0)
    .faceDecimals(0)
    .build();

IssueVoucherResponse response = voucherService.issue(request);
SignedVoucher voucher = response.getVoucher();  // Voucher is signed and published to ledger

// To create a shareable token, use a wallet (e.g., cashu-client):
// The wallet swaps proofs at the mint and creates a TokenV4 (cashuB...) token
```

### Verify a Voucher (Merchant)

```java
// Create merchant verification service
MerchantVerificationService merchantService =
    new MerchantVerificationService(ledgerPort);

// Verify online (recommended - prevents double-spend)
VerificationResult result = merchantService.verifyOnline(
    voucher,
    "my-coffee-shop"
);

if (result.isValid()) {
    // Accept voucher and mark as redeemed
    merchantService.markRedeemed(voucher.getSecret().getVoucherId());
} else {
    // Reject voucher
    System.err.println(result.getErrorMessage());
}
```

## Documentation

Comprehensive documentation is available in the [`docs/`](./docs/) directory, organized by the [Diátaxis framework](https://diataxis.fr/):

### Tutorials (Learning)
- [Getting Started](./docs/tutorials/getting-started.md) - Your first voucher
- [Issuing with VoucherService](./docs/tutorials/issuing-vouchers-with-service.md) - Application layer usage
- [Nostr Integration](./docs/tutorials/nostr-integration.md) - Set up Nostr storage

### How-To Guides (Tasks)
- [Issue a Voucher](./docs/how-to/issue-voucher.md) - Different issuance scenarios
- [Verify as Merchant](./docs/how-to/verify-voucher-as-merchant.md) - Verification and redemption
- [Backup and Restore](./docs/how-to/backup-and-restore.md) - Protect your vouchers
- [Implement Custom Adapters](./docs/how-to/implement-custom-adapter.md) - SQL, S3, or other storage

### Reference (API)
- [Domain Entities](./docs/reference/domain-entities.md) - VoucherSecret, SignedVoucher, etc.
- [VoucherService](./docs/reference/voucher-service.md) - Main service API
- [MerchantVerificationService](./docs/reference/merchant-verification-service.md) - Merchant verification
- [Ports](./docs/reference/ports.md) - VoucherLedgerPort, VoucherBackupPort
- [Nostr Adapters](./docs/reference/nostr-adapters.md) - Nostr implementation
- [DTOs](./docs/reference/dto.md) - Request/response objects

### Explanations (Concepts)
- [Architecture Overview](./docs/explanation/architecture.md) - Hexagonal architecture
- [Model B Vouchers](./docs/explanation/model-b-vouchers.md) - Why merchant-only redemption
- [Backing Strategies](./docs/explanation/backing-strategies.md) - FIXED, MINIMAL, PROPORTIONAL
- [Nostr Integration](./docs/explanation/nostr-integration.md) - Why and how Nostr works

## Modules

### cashu-voucher-domain

Pure domain logic with zero infrastructure dependencies.

| Class | Description |
|-------|-------------|
| `VoucherSecret` | Gift card voucher secret (extends BaseKey, implements Secret) |
| `SignedVoucher` | Voucher with Schnorr signature |
| `VoucherSignatureService` | Sign and verify vouchers |
| `VoucherValidator` | Validation logic (expiry, signature) |
| `VoucherStatus` | Enum: ISSUED, REDEEMED, REVOKED, EXPIRED |
| `BackingStrategy` | Enum: FIXED, MINIMAL, PROPORTIONAL |

### cashu-voucher-app

Application services and port interfaces (infrastructure-agnostic).

| Class | Description |
|-------|-------------|
| `VoucherService` | Main voucher service (issue, query, backup, restore) |
| `MerchantVerificationService` | Merchant-side verification (Model B) |
| `VoucherLedgerPort` | Port interface for ledger operations |
| `VoucherBackupPort` | Port interface for backup operations |

### cashu-voucher-nostr

Nostr infrastructure adapter implementing port interfaces.

| Class | Description |
|-------|-------------|
| `NostrVoucherLedgerRepository` | NIP-33 ledger implementation |
| `NostrVoucherBackupRepository` | NIP-17 + NIP-44 backup implementation |
| `NostrClientAdapter` | Relay connection management |
| `VoucherLedgerEvent` | NIP-33 event mapper |
| `VoucherBackupPayload` | NIP-17 payload format |

## Building

```bash
# Build all modules
mvn -q verify

# Module build with dependencies
mvn -q -pl cashu-voucher-app -am verify

# Generate code coverage report
mvn -q jacoco:report
```

## Testing

The project has comprehensive test coverage across all layers:

| Layer | Tests | Coverage |
|-------|-------|----------|
| Domain | 126+ | VoucherSecret, SignedVoucher, signatures |
| App | 72+ | VoucherService, MerchantVerification |
| Nostr | 112+ | NIP-33, NIP-17, NIP-44, Testcontainers |

## Dependencies

| Dependency | Version |
|------------|---------|
| Java | 21+ |
| Maven | 3.9+ |
| cashu-lib | 0.9.1 |
| nostr-java | 1.1.0 |
| Bouncy Castle | 1.78 |
| Jackson | 2.17.0 |

## Model B Implementation

Vouchers are **NOT redeemable at the mint**. When a voucher is presented in a swap or melt operation:

```
POST /v1/swap
{
  "proofs": [{ "secret": "<VoucherSecret>", ... }]
}

→ 400 Bad Request
{
  "error": "VOUCHER_NOT_ACCEPTED",
  "message": "Vouchers cannot be redeemed at mint (Model B). Please redeem with issuing merchant."
}
```

## Nostr Storage

### Public Ledger (NIP-33)
- **Kind**: 30078 (parameterized replaceable event)
- **Tag**: `["d", "voucher:<voucherId>"]`
- **Purpose**: Public audit trail of voucher status

### Private Backup (NIP-17 + NIP-44)
- **Kind**: 4 (encrypted direct message)
- **Encryption**: NIP-44 (ChaCha20-Poly1305)
- **Purpose**: User's private voucher backup

## Backing Strategies

| Strategy | Splittable | Use Case |
|----------|------------|----------|
| FIXED | No | Tickets, event passes |
| MINIMAL | Yes (coarse) | Gift cards, store credit |
| PROPORTIONAL | Yes (fine) | Split payments, group gifts |

## TODO

### Integration Tests

The following integration tests fail without live Nostr relay connections:

| Test Class | Tests | Issue |
|------------|-------|-------|
| `NostrClientAdapterIntegrationTest` | 5 | Requires live Nostr relay |
| `VoucherNostrIT` | 6 | End-to-end relay integration |

**Planned fixes:**
- [ ] Add `@Tag("integration")` to skip in CI builds
- [ ] Configure Testcontainers with local Nostr relay (e.g., `nostr-rs-relay`)
- [ ] Add CI workflow for integration tests with relay container

**Note:** All unit tests pass. Integration test failures are expected in isolated environments.

## Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Write tests for your changes
4. Ensure all tests pass (`mvn -q verify`)
5. Follow [Conventional Commits](https://www.conventionalcommits.org/)
6. Submit a pull request

See [AGENTS.md](./AGENTS.md) for detailed development guidelines.

## Acknowledgments

- [Cashu Protocol](https://github.com/cashubtc/nuts) - NUT specifications
- [Nostr Protocol](https://github.com/nostr-protocol/nips) - NIP specifications
- NUT-13: Deterministic secrets
- NIP-33: Parameterized replaceable events
- NIP-17: Private direct messages
- NIP-44: Encrypted payloads

