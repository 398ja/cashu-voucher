# Model B Vouchers Explained

This document explains the Model B voucher concept, why it exists, and how it differs from regular Cashu tokens.

## What is Model B?

Model B refers to a specific type of gift card voucher where **vouchers can only be redeemed at the issuing merchant**, not at the mint.

### Comparison: Model A vs Model B

| Aspect | Model A (Standard Cashu) | Model B (Voucher) |
|--------|--------------------------|-------------------|
| Redemption | Any mint | Issuing merchant only |
| Use case | Digital cash | Gift cards, store credit |
| Backing | Mint reserves | Merchant-specific |
| Fungibility | Fully fungible | Not fungible |
| Recovery | NUT-13 deterministic | Requires backup |

## Why Model B Exists

### Use Case: Gift Cards

When a coffee shop wants to issue gift cards:

```
Traditional Cashu Flow (Model A):
┌──────────┐    ┌──────────┐    ┌──────────┐
│ Customer │───▶│   Mint   │───▶│ Redeem   │
│ receives │    │          │    │ at mint  │
│ token    │    │          │    │          │
└──────────┘    └──────────┘    └──────────┘
                                     │
                                     ▼
                              Customer gets
                              Bitcoin back

Gift Card Flow (Model B):
┌──────────┐    ┌──────────┐    ┌──────────┐
│ Customer │───▶│ Merchant │───▶│ Redeem   │
│ receives │    │ (issuer) │    │ at shop  │
│ voucher  │    │          │    │          │
└──────────┘    └──────────┘    └──────────┘
                                     │
                                     ▼
                              Customer gets
                              goods/services
```

### Why Not Use Standard Cashu?

Standard Cashu tokens are:
1. **Redeemable at mint** - Customer could get Bitcoin instead of buying coffee
2. **Fungible** - Gift card value could be moved anywhere
3. **Not merchant-specific** - No way to restrict to issuing store

Model B vouchers solve these issues:
1. **Only redeemable at issuer** - Must be spent at the coffee shop
2. **Non-fungible** - Bound to specific merchant
3. **Merchant-controlled** - Shop controls the redemption

## How Model B is Enforced

### Issuer ID Binding

Every voucher contains an `issuerId` field:

```java
VoucherSecret secret = VoucherSecret.builder()
    .issuerId("my-coffee-shop")    // Bound to this merchant
    .unit("sat")
    .faceValue(10000L)
    // ...
    .build();
```

### Verification Check

When a merchant verifies a voucher, they check the issuer ID:

```java
// In MerchantVerificationService.verifyOffline()
String actualIssuerId = voucher.getSecret().getIssuerId();
if (!actualIssuerId.equals(expectedIssuerId)) {
    return VerificationResult.failure(
        "Voucher issued by '" + actualIssuerId +
        "' but expected issuer is '" + expectedIssuerId + "'"
    );
}
```

### Swap vs Redemption

It's important to distinguish between **swapping** and **redeeming**:

| Operation | Allowed? | Purpose |
|-----------|----------|---------|
| **Swap** (`/v1/swap`) | **Yes** | Exchange proofs for fresh proofs (same value) |
| **Redeem/Melt** | **No** | Convert voucher to Bitcoin (Model B blocks this) |

**Why swaps must be allowed:**
1. **Double-spend prevention**: When Carol receives a voucher from Alice, she must swap it to get fresh proofs (prevents Alice from double-spending)
2. **P2P transfers**: Users can send vouchers to each other
3. **Splitting**: Re-partition proofs into different denominations

**Model B enforcement happens at the APPLICATION layer** (merchant verification), not at the mint protocol layer. The mint only enforces cryptographic validity (BDHKE signatures), while the merchant application verifies the issuer ID matches.

## Technical Implementation

### Domain Layer Enforcement

The `VoucherSecret` class includes `issuerId` as a required field:

```java
public final class VoucherSecret {
    private final String issuerId;  // Cannot be null or blank

    private VoucherSecret(..., String issuerId, ...) {
        if (issuerId.isBlank()) {
            throw new IllegalArgumentException("Issuer ID cannot be blank");
        }
        this.issuerId = issuerId;
    }
}
```

### Signature Binding

The issuer ID is included in the signed data:

```java
// In toCanonicalBytes() - signed by issuer
map.put("issuerId", issuerId);
// ... other fields
return VoucherSerializationUtils.toCbor(map);
```

This means:
- Changing the issuer ID invalidates the signature
- Vouchers cannot be "re-assigned" to different merchants
- Cryptographic proof of original issuer

### Verification Layers

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 1: Domain Validation                                  │
│   VoucherSecret requires non-blank issuerId                 │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 2: Mint Protocol (BDHKE)                              │
│   Standard cryptographic verification for swaps             │
│   (Vouchers treated same as regular proofs)                 │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 3: Signature Verification                             │
│   issuerId is part of signed canonical bytes                │
│   (Issuer's Schnorr signature proves authenticity)          │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│ Layer 4: Application Verification (Model B Enforcement)     │
│   MerchantVerificationService checks issuer match           │
│   Only the issuing merchant can redeem for goods/services   │
└─────────────────────────────────────────────────────────────┘
```

**Key point**: Model B enforcement happens at Layer 4 (application layer), not at the mint protocol layer. This allows vouchers to be transferred between users while ensuring only the merchant can accept them for redemption.

## Voucher Lifecycle

```
┌─────────────┐
│   ISSUED    │
│  (active)   │
└──────┬──────┘
       │
       │ Customer presents to merchant
       │
       ▼
┌─────────────────────────────────────────────────────────────┐
│                    Merchant Verification                    │
│                                                             │
│  1. Check issuer ID matches merchant ID                     │
│  2. Verify Schnorr signature                                │
│  3. Check expiry timestamp                                  │
│  4. Query ledger status (online verification)               │
│                                                             │
└───────────┬─────────────────────────────────┬───────────────┘
            │                                 │
            │ All checks pass                 │ Any check fails
            ▼                                 ▼
    ┌─────────────┐                   ┌─────────────┐
    │  REDEEMED   │                   │   REJECTED  │
    │ (terminal)  │                   │             │
    └─────────────┘                   └─────────────┘
```

## Security Considerations

### What Model B Prevents

1. **Cross-merchant redemption**: Voucher for Shop A can't be used at Shop B
2. **Mint redemption (melt)**: Vouchers can't be converted to Bitcoin at mint
3. **Value extraction**: Gift card value stays within merchant ecosystem

### What Model B Allows

1. **Swapping at mint**: Essential for double-spend prevention and P2P transfers
2. **P2P transfers**: Alice can send a voucher to Carol
3. **Splitting**: Vouchers can be split into smaller denominations (if backing strategy allows)

### What Model B Does NOT Prevent

1. **Voucher theft**: Stolen voucher can still be redeemed (use ledger for detection)
2. **Expiry bypass**: Ledger status can track expiry independently
3. **Merchant fraud**: Merchant could issue vouchers without backing

### Ledger Importance

The public ledger (NIP-33 in Nostr implementation) is critical for:
- **Double-spend prevention**: Check voucher not already redeemed
- **Revocation**: Issuer can mark vouchers as revoked
- **Audit trail**: Public record of voucher lifecycle

## Business Implications

### For Merchants

- Issue gift cards without giving away Bitcoin
- Control redemption at your store only
- Track voucher status via public ledger
- Revoke compromised vouchers

### For Customers

- Receive gift cards as Cashu tokens
- Store in any Cashu wallet
- Redeem at issuing merchant
- Need to backup (not recoverable from seed)

### For Platforms

- Facilitate gift card issuance
- Provide voucher management
- Integrate with existing Cashu infrastructure

## Example Scenarios

### Scenario 1: Coffee Shop Gift Card

```java
// Coffee shop issues €20 gift card
IssueVoucherRequest request = IssueVoucherRequest.builder()
    .issuerId("bean-scene-coffee")
    .unit("eur")
    .amount(2000L)           // €20.00 in cents
    .expiresInDays(365)
    .backingStrategy(BackingStrategy.MINIMAL)
    .issuanceRatio(0.01)
    .faceDecimals(2)
    .build();

// Customer tries to redeem at competing shop
VerificationResult result = competitor.verifyOffline(voucher, "rival-coffee");
// → FAILURE: "Voucher issued by 'bean-scene-coffee' but expected 'rival-coffee'"
```

### Scenario 2: Event Ticket

```java
// Concert venue issues ticket
IssueVoucherRequest ticket = IssueVoucherRequest.builder()
    .issuerId("rock-arena")
    .unit("usd")
    .amount(7500L)           // $75.00
    .expiresAt(concertDate)
    .backingStrategy(BackingStrategy.FIXED)  // Cannot split ticket
    .merchantMetadata(Map.of(
        "event", "summer-rock-fest",
        "seat", "A-42"
    ))
    .build();

// Only Rock Arena can verify and redeem this ticket
```

### Scenario 3: Store Credit

```java
// Retailer issues store credit after return
IssueVoucherRequest credit = IssueVoucherRequest.builder()
    .issuerId("mega-mart")
    .unit("usd")
    .amount(4999L)           // $49.99
    .expiresInDays(90)
    .backingStrategy(BackingStrategy.PROPORTIONAL)
    .merchantMetadata(Map.of(
        "originalOrder", "ORD-123456",
        "returnReason", "defective"
    ))
    .build();

// Customer can use store credit at any Mega Mart location
// But NOT at mint, NOT at other stores
```

## Related

- [Architecture Overview](./architecture.md)
- [Backing Strategies](./backing-strategies.md)
- [How to Verify Vouchers](../how-to/verify-voucher-as-merchant.md)
- [MerchantVerificationService Reference](../reference/merchant-verification-service.md)
