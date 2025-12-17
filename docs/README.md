# Cashu Voucher Documentation

Welcome to the Cashu Voucher documentation. This library implements **Model B** gift card vouchers for the Cashu ecash protocol with Nostr-based storage.

## Quick Links

- [Getting Started Tutorial](./tutorials/getting-started.md) - Your first voucher in 10 minutes
- [Architecture Overview](./explanation/architecture.md) - Understand the design
- [API Reference](./reference/voucher-service.md) - Complete method documentation

## Documentation Structure

This documentation follows the [Diátaxis framework](https://diataxis.fr/), organized into four categories:

### Tutorials (Learning-Oriented)

Step-by-step guides to get you started:

| Tutorial | Description |
|----------|-------------|
| [Getting Started](./tutorials/getting-started.md) | Create your first voucher |
| [Issuing with VoucherService](./tutorials/issuing-vouchers-with-service.md) | Use the application layer |
| [Nostr Integration](./tutorials/nostr-integration.md) | Set up Nostr storage |

### How-To Guides (Task-Oriented)

Practical guides for specific tasks:

| Guide | Description |
|-------|-------------|
| [Issue a Voucher](./how-to/issue-voucher.md) | Different issuance scenarios |
| [Verify as Merchant](./how-to/verify-voucher-as-merchant.md) | Verify and redeem vouchers |
| [Backup and Restore](./how-to/backup-and-restore.md) | Protect your vouchers |
| [Implement Custom Adapters](./how-to/implement-custom-adapter.md) | SQL, S3, or other storage |

### Reference (Information-Oriented)

Technical specifications and API documentation:

| Reference | Description |
|-----------|-------------|
| [Domain Entities](./reference/domain-entities.md) | VoucherSecret, SignedVoucher, etc. |
| [VoucherService](./reference/voucher-service.md) | Main application service |
| [MerchantVerificationService](./reference/merchant-verification-service.md) | Merchant verification |
| [Ports](./reference/ports.md) | VoucherLedgerPort, VoucherBackupPort |
| [Nostr Adapters](./reference/nostr-adapters.md) | Nostr implementation details |
| [DTOs](./reference/dto.md) | Request/response objects |

### Explanations (Understanding-Oriented)

Background and design decisions:

| Topic | Description |
|-------|-------------|
| [Architecture Overview](./explanation/architecture.md) | Hexagonal architecture, modules |
| [Model B Vouchers](./explanation/model-b-vouchers.md) | Why merchant-only redemption |
| [Backing Strategies](./explanation/backing-strategies.md) | FIXED, MINIMAL, PROPORTIONAL |
| [Nostr Integration](./explanation/nostr-integration.md) | Why Nostr, how it works |

## Key Concepts

### Model B Vouchers

Cashu Voucher implements **Model B** - vouchers that are only redeemable at the issuing merchant, not at the mint. This enables:

- Gift cards tied to specific stores
- Event tickets bound to venues
- Store credit that stays within the ecosystem

[Learn more about Model B →](./explanation/model-b-vouchers.md)

### Hexagonal Architecture

The library follows hexagonal architecture with three modules:

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

[Learn more about the architecture →](./explanation/architecture.md)

### Nostr Storage

Vouchers use Nostr for two purposes:

- **Public Ledger (NIP-33)**: Status tracking, double-spend prevention
- **Private Backup (NIP-17 + NIP-44)**: Encrypted user voucher storage

[Learn more about Nostr integration →](./explanation/nostr-integration.md)

## Installation

Add to your `pom.xml`:

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
        <version>0.3.0</version>
    </dependency>
    <dependency>
        <groupId>xyz.tcheeric</groupId>
        <artifactId>cashu-voucher-app</artifactId>
        <version>0.3.0</version>
    </dependency>
    <dependency>
        <groupId>xyz.tcheeric</groupId>
        <artifactId>cashu-voucher-nostr</artifactId>
        <version>0.3.0</version>
    </dependency>
</dependencies>
```

## Quick Example

```java
// Issue a voucher
IssueVoucherRequest request = IssueVoucherRequest.builder()
    .issuerId("my-coffee-shop")
    .unit("sat")
    .amount(10000L)
    .backingStrategy(BackingStrategy.MINIMAL)
    .issuanceRatio(1.0)
    .faceDecimals(0)
    .build();

IssueVoucherResponse response = voucherService.issue(request);
String token = response.getToken();  // Share with customer

// Verify as merchant
VerificationResult result = merchantService.verifyOnline(voucher, "my-coffee-shop");
if (result.isValid()) {
    merchantService.markRedeemed(voucher.getSecret().getVoucherId());
}
```

## Requirements

- Java 21+
- Maven 3.9+

## Getting Help

- Check the [tutorials](./tutorials/) for step-by-step guides
- Browse the [reference](./reference/) for API details
- Read the [explanations](./explanation/) for design background

## Project Links

- [GitHub Repository](https://github.com/cashu-voucher/cashu-voucher)
- [Project README](../README.md)
- [AGENTS.md](../AGENTS.md) - Development guidelines
- [Cashu Protocol](https://github.com/cashubtc/nuts)
- [Nostr Protocol](https://github.com/nostr-protocol/nips)

---

*Documentation version: 0.3.0*
