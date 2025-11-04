# Cashu Voucher

Gift card voucher system with Nostr storage for the Cashu ecash protocol.

## Overview

Cashu Voucher implements **Model B** gift card vouchers - vouchers that are spendable only at the issuing merchant, not redeemable at the mint. The system uses Nostr for both public ledger (NIP-33) and private backup (NIP-17 + NIP-44) storage.

## Architecture

This project follows **Hexagonal Architecture** (Ports & Adapters) with three modules:

- **cashu-voucher-domain** - Pure domain logic (VoucherSecret, SignedVoucher, validation)
- **cashu-voucher-app** - Application services and port interfaces
- **cashu-voucher-nostr** - Nostr infrastructure adapter (NIP-33, NIP-17)

## Project Status

**Version**: 0.1.0 (in development)
**Status**: Phase 0 - Project Bootstrap
**Progress**: 4/72 tasks complete (6%)

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

## Building

```bash
# Build all modules
mvn clean install

# Run tests
mvn test

# Generate code coverage report
mvn jacoco:report
```

## Dependencies

- **Java**: 21+
- **Maven**: 3.9+
- **cashu-lib**: 0.5.0
- **nostr-java**: 0.6.0
- **Bouncy Castle**: 1.78

## Documentation

See the `project/` directory in cashu-lib for comprehensive documentation:

- `gift-card-plan-final-v2.md` - Implementation plan
- `voucher-architecture-diagrams.md` - Architecture diagrams
- `voucher-test-plan.md` - Test specifications
- `voucher-deployment-guide.md` - Deployment guide
- `voucher-api-specification.md` - API reference

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

## License

[License TBD]

## Contributing

[Contributing guidelines TBD]

## Support

For questions or issues, please refer to the project documentation or open an issue.

---

**Last Updated**: 2025-11-04
**Architecture**: Hexagonal (Ports & Adapters)
**NUT-13 Integration**: Compatible with deterministic secret recovery
