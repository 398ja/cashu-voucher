# Getting Started with Cashu Voucher

This tutorial guides you through setting up and using the Cashu Voucher library for the first time. By the end, you will have issued your first voucher and understood the basic workflow.

## Prerequisites

Before starting, ensure you have:

- **Java 21** or later (Temurin recommended)
- **Maven 3.9+** for building
- Basic familiarity with Java and Maven

## What You Will Learn

1. Adding Cashu Voucher to your project
2. Understanding the module structure
3. Issuing your first voucher
4. Verifying a voucher

## Step 1: Add Dependencies

Add the Cashu Voucher modules to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>reposilite-releases</id>
        <name>398ja Maven Repository</name>
        <url>https://maven.398ja.xyz/releases</url>
    </repository>
</repositories>

<dependencies>
    <!-- Domain layer - pure business logic -->
    <dependency>
        <groupId>xyz.tcheeric</groupId>
        <artifactId>cashu-voucher-domain</artifactId>
        <version>0.3.6</version>
    </dependency>

    <!-- Application layer - services and ports -->
    <dependency>
        <groupId>xyz.tcheeric</groupId>
        <artifactId>cashu-voucher-app</artifactId>
        <version>0.3.6</version>
    </dependency>

    <!-- Nostr adapter - optional, for Nostr-based storage -->
    <dependency>
        <groupId>xyz.tcheeric</groupId>
        <artifactId>cashu-voucher-nostr</artifactId>
        <version>0.3.6</version>
    </dependency>
</dependencies>
```

## Step 2: Understand the Modules

Cashu Voucher follows **Hexagonal Architecture** with three modules:

| Module | Purpose | Dependencies |
|--------|---------|--------------|
| `cashu-voucher-domain` | Pure domain logic, entities, validation | None (standalone) |
| `cashu-voucher-app` | Application services, port interfaces | Domain |
| `cashu-voucher-nostr` | Nostr storage implementation | Domain, App |

For basic usage, you need `domain` and `app`. Add `nostr` if you want Nostr-based voucher storage.

## Step 3: Generate Issuer Keys

Vouchers require secp256k1 keys for Schnorr signing (BIP-340, same as Nostr). Generate a key pair:

```java
import nostr.crypto.schnorr.Schnorr;
import org.bouncycastle.util.encoders.Hex;

public class KeyGenerator {
    public static void main(String[] args) {
        // Generate secp256k1 key pair (Schnorr/Nostr compatible)
        byte[] privateKeyBytes = Schnorr.generatePrivateKey();
        byte[] publicKeyBytes = Schnorr.genPubKey(privateKeyBytes);

        String privateKeyHex = Hex.toHexString(privateKeyBytes);
        String publicKeyHex = Hex.toHexString(publicKeyBytes);

        System.out.println("Private Key: " + privateKeyHex);
        System.out.println("Public Key:  " + publicKeyHex);
    }
}
```

**Important:** Store your private key securely. Never commit it to version control.

## Step 4: Create a Simple Voucher

Now let's create your first voucher using the domain layer directly:

```java
import xyz.tcheeric.cashu.voucher.domain.*;

public class FirstVoucher {
    public static void main(String[] args) {
        // Your issuer keys (from Step 3)
        String privateKey = "your-64-char-hex-private-key";
        String publicKey = "your-64-char-hex-public-key";

        // Create a voucher secret
        VoucherSecret secret = VoucherSecret.builder()
            .issuerId("my-coffee-shop")          // Your merchant ID
            .unit("sat")                          // Currency unit
            .faceValue(10000L)                    // 10,000 satoshis
            .backingStrategy(BackingStrategy.MINIMAL)
            .issuanceRatio(1.0)                   // 1 sat = 1 unit
            .faceDecimals(0)                      // No decimals for sats
            .memo("Welcome gift card")            // Optional description
            .build();

        System.out.println("Created voucher: " + secret.getVoucherId());
        System.out.println("Face value: " + secret.getFaceValue() + " " + secret.getUnit());

        // Sign the voucher
        SignedVoucher signedVoucher = VoucherSignatureService.createSigned(
            secret,
            privateKey,
            publicKey
        );

        System.out.println("Voucher signed: " + signedVoucher.verify());
    }
}
```

## Step 5: Verify the Voucher

Verification ensures the voucher is authentic and hasn't been tampered with:

```java
// Verify signature
boolean signatureValid = signedVoucher.verify();
System.out.println("Signature valid: " + signatureValid);

// Check if expired
boolean expired = signedVoucher.isExpired();
System.out.println("Expired: " + expired);

// Full validation (signature + expiry)
boolean fullyValid = signedVoucher.isValid();
System.out.println("Fully valid: " + fullyValid);

// Use the VoucherValidator for detailed results
VoucherValidator.ValidationResult result = VoucherValidator.validate(signedVoucher);
if (!result.isValid()) {
    System.out.println("Validation errors: " + result.getErrorMessage());
}
```

## Step 6: Add Expiry (Optional)

Vouchers can have an expiry date:

```java
import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Expires in 365 days
Long expiresAt = Instant.now()
    .plus(365, ChronoUnit.DAYS)
    .getEpochSecond();

VoucherSecret secret = VoucherSecret.builder()
    .issuerId("my-coffee-shop")
    .unit("sat")
    .faceValue(10000L)
    .expiresAt(expiresAt)                    // Set expiry
    .backingStrategy(BackingStrategy.MINIMAL)
    .issuanceRatio(1.0)
    .faceDecimals(0)
    .build();

System.out.println("Voucher expires: " +
    Instant.ofEpochSecond(secret.getExpiresAt()));
```

## What's Next?

Congratulations! You've created and verified your first voucher. Next steps:

- [Issue Vouchers Using VoucherService](./issuing-vouchers-with-service.md) - Use the application layer for full workflow
- [Model B Explained](../explanation/model-b-vouchers.md) - Understand why vouchers are merchant-only
- [Backing Strategies](../explanation/backing-strategies.md) - Choose the right strategy for your use case
- [Nostr Integration](./nostr-integration.md) - Set up Nostr-based storage

## Troubleshooting

### "Invalid signature length"

Schnorr signatures must be exactly 64 bytes. Ensure your keys are properly formatted as hex strings (64 characters for 32 bytes).

### "Face value must be positive"

Voucher amounts must be greater than zero. Check your `faceValue` parameter.

### "Issuer ID cannot be blank"

Every voucher must have a non-empty issuer ID. This identifies the merchant who can redeem the voucher.

## Summary

In this tutorial, you learned:

1. How to add Cashu Voucher dependencies
2. The three-module architecture (domain, app, nostr)
3. Generating secp256k1/Schnorr key pairs
4. Creating a `VoucherSecret` with the builder pattern
5. Signing vouchers with `VoucherSignatureService`
6. Validating vouchers with `VoucherValidator`

The domain layer provides the foundation. For production use, continue to the [VoucherService tutorial](./issuing-vouchers-with-service.md) to learn about the application layer with ledger integration.
