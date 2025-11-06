# Cashu Voucher

Gift card voucher system with Nostr storage for the Cashu ecash protocol.

## Overview

Cashu Voucher implements **Model B** gift card vouchers - vouchers that are spendable only at the issuing merchant, not redeemable at the mint. The system uses Nostr for both public ledger (NIP-33) and private backup (NIP-17 + NIP-44) storage.

### Key Features

✅ **Model B Implementation** - Vouchers only redeemable at issuing merchant
✅ **Nostr Storage** - Public ledger (NIP-33) and private backup (NIP-17 + NIP-44)
✅ **ED25519 Signatures** - Cryptographic voucher verification
✅ **Offline Verification** - Merchants can verify without network access
✅ **Double-Spend Protection** - Nostr ledger prevents voucher reuse
✅ **NUT-13 Integration** - Works with deterministic wallet recovery
✅ **Expiry Support** - Time-limited vouchers with automatic expiry checks
✅ **REST API** - HTTP endpoints for voucher operations
✅ **Comprehensive Testing** - 310+ tests with 80%+ code coverage
✅ **Hexagonal Architecture** - Clean separation of concerns, pluggable adapters

## Architecture

This project follows **Hexagonal Architecture** (Ports & Adapters) with three modules:

- **cashu-voucher-domain** - Pure domain logic (VoucherSecret, SignedVoucher, validation)
- **cashu-voucher-app** - Application services and port interfaces
- **cashu-voucher-nostr** - Nostr infrastructure adapter (NIP-33, NIP-17)

## Project Status

**Version**: 0.1.0 (in development)
**Status**: Phase 4 Complete - Mint Integration
**Progress**: 46/72 tasks complete (64%)
**Test Coverage**: 80%+ line coverage
**Tests Passing**: 310+ tests (domain, app, nostr, mint integration)

## Modules

### cashu-voucher-domain
Pure domain logic with zero infrastructure dependencies.

**Key Classes**:
- `VoucherSecret` - Gift card voucher secret (extends BaseKey, implements Secret)
- `SignedVoucher` - Voucher with ED25519 signature
- `VoucherSignatureService` - Sign and verify vouchers
- `VoucherValidator` - Validation logic (expiry, signature)
- `VoucherStatus` - Enum (ISSUED, REDEEMED, REVOKED, EXPIRED)

### cashu-voucher-app
Application services and port interfaces (infrastructure-agnostic).

**Key Classes**:
- `VoucherService` - Main voucher service (issue, query, backup, restore)
- `MerchantVerificationService` - Merchant-side verification (Model B)
- `VoucherLedgerPort` - Port interface for ledger operations
- `VoucherBackupPort` - Port interface for backup operations

### cashu-voucher-nostr
Nostr infrastructure adapter implementing port interfaces.

**Key Classes**:
- `NostrVoucherLedgerRepository` - NIP-33 ledger implementation
- `NostrVoucherBackupRepository` - NIP-17 + NIP-44 backup implementation
- `NostrClientAdapter` - Relay connection management
- `VoucherLedgerEvent` - NIP-33 event mapper
- `VoucherBackupPayload` - NIP-17 payload format

## Quick Start

### Installation

Add the voucher modules to your project:

```xml
<dependency>
    <groupId>xyz.tcheeric</groupId>
    <artifactId>cashu-voucher-domain</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>xyz.tcheeric</groupId>
    <artifactId>cashu-voucher-app</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>xyz.tcheeric</groupId>
    <artifactId>cashu-voucher-nostr</artifactId>
    <version>0.1.0</version>
</dependency>
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
    .amount(10000L)  // 10,000 sats
    .expiresInDays(365)
    .memo("Coffee shop gift card")
    .build();

IssueVoucherResponse response = voucherService.issue(request);
String token = response.getToken();  // cashuA...
```

### Verify a Voucher (Merchant)

```java
// Create merchant verification service
MerchantVerificationService merchantService =
    new MerchantVerificationService(ledgerPort);

// Verify offline (no network)
VerificationResult result = merchantService.verifyOffline(
    voucher,
    "my-coffee-shop"
);

if (result.isValid()) {
    // Accept voucher
} else {
    // Reject voucher
    System.err.println(result.getErrorMessage());
}
```

## Building

```bash
# Build all modules
mvn clean install

# Run unit tests only
mvn test

# Run integration tests
mvn verify -Pintegration-tests

# Generate code coverage report
mvn jacoco:report
```

## Testing

The project has comprehensive test coverage across all layers:

- **Domain Tests**: 126 tests (VoucherSecret, SignedVoucher, signatures)
- **App Tests**: 72 tests (VoucherService, MerchantVerification)
- **Nostr Tests**: 112 tests (NIP-33, NIP-17, NIP-44, Testcontainers)
- **Integration Tests**: 28+ tests (mint integration, E2E workflows)

See [TESTING.md](../cashu-mint/TESTING.md) for detailed testing guide.

## Dependencies

- **Java**: 21+
- **Maven**: 3.9+
- **cashu-lib**: 0.5.0
- **nostr-java**: 0.6.0
- **Bouncy Castle**: 1.78

## Documentation

### Implementation Plan
See [gift-card-plan-final-v2.md](../cashu-mint/project/gift-card-plan-final-v2.md) for the complete implementation plan with:
- 72 tasks across 6 phases
- Current progress: 46/72 (64% complete)
- Architecture decisions and rationale
- Testing strategy
- Timeline and milestones

### Testing Guide
See [TESTING.md](../cashu-mint/TESTING.md) for:
- How to run unit and integration tests
- Test naming conventions
- Maven profiles
- CI/CD integration

### API Documentation
- **Domain Layer**: JavaDoc in `cashu-voucher-domain/target/apidocs`
- **Application Layer**: JavaDoc in `cashu-voucher-app/target/apidocs`
- **Nostr Layer**: JavaDoc in `cashu-voucher-nostr/target/apidocs`

Generate JavaDoc:
```bash
mvn javadoc:javadoc
```

## Integration

### Mint Integration
```xml
<dependency>
    <groupId>xyz.tcheeric</groupId>
    <artifactId>cashu-voucher-domain</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>xyz.tcheeric</groupId>
    <artifactId>cashu-voucher-app</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>xyz.tcheeric</groupId>
    <artifactId>cashu-voucher-nostr</artifactId>
    <version>0.1.0</version>
    <scope>runtime</scope>
</dependency>
```

### Wallet Integration
Same dependencies as mint integration.

### CLI Integration
```xml
<dependency>
    <groupId>xyz.tcheeric</groupId>
    <artifactId>cashu-voucher-app</artifactId>
    <version>0.1.0</version>
</dependency>
```

## CLI Commands

### Wallet Commands
- `cashu voucher issue` - Issue a new voucher
- `cashu voucher list` - List all vouchers
- `cashu voucher show` - Show voucher details
- `cashu voucher backup` - Backup vouchers to Nostr
- `cashu voucher restore` - Restore vouchers from Nostr
- `cashu voucher status` - Check voucher status

### Merchant Commands
- `cashu merchant verify` - Verify voucher (offline/online)
- `cashu merchant redeem` - Redeem voucher

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
- **Kind**: 14 (private direct message)
- **Encryption**: NIP-44 (ChaCha20-Poly1305)
- **Purpose**: User's private voucher backup

## Roadmap

### ✅ Completed (Phases 0-4)
- [x] Project structure and CI/CD
- [x] Domain layer (VoucherSecret, SignedVoucher, validation)
- [x] Application layer (VoucherService, MerchantVerification)
- [x] Nostr layer (NIP-33 ledger, NIP-17 backup, NIP-44 encryption)
- [x] Mint integration (REST API, Model B enforcement)

### 🚧 In Progress
- [ ] Phase 5: Wallet & CLI (14 tasks)
  - Wallet voucher storage
  - CLI commands (issue, list, verify, redeem)
  - NUT-13 recovery integration

### 📋 Planned
- [ ] Phase 6: Testing & Documentation (12 tasks)
  - End-to-end tests
  - Performance benchmarks
  - User guides
  - v0.1.0 release

## License

[License TBD]

## Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Write tests for your changes
4. Ensure all tests pass (`mvn verify`)
5. Submit a pull request

## Support

For questions or issues:
- Review the [implementation plan](../cashu-mint/project/gift-card-plan-final-v2.md)
- Check the [testing guide](../cashu-mint/TESTING.md)
- Open an issue on GitHub

## Acknowledgments

- **Cashu Protocol**: https://github.com/cashubtc/nuts
- **Nostr Protocol**: https://github.com/nostr-protocol/nips
- **NUT-13**: Deterministic secrets specification
- **NIP-33**: Parameterized replaceable events
- **NIP-17**: Private direct messages
- **NIP-44**: Encrypted payloads (ChaCha20-Poly1305)

---

**Last Updated**: 2025-11-06
**Version**: 0.1.0 (in development)
**Architecture**: Hexagonal (Ports & Adapters)
**Test Coverage**: 80%+ line coverage
**Tests**: 310+ passing
**Status**: Phase 4 complete (64% overall)
