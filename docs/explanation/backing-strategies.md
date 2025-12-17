# Backing Strategies Explained

This document explains the three backing strategies available for Cashu Vouchers and when to use each one.

## What is a Backing Strategy?

A backing strategy determines:
1. How many satoshis back the voucher token
2. Whether the voucher can be split
3. The granularity of splits (if allowed)

The merchant chooses the strategy at issuance based on the voucher's intended use case.

## The Three Strategies

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        B A C K I N G   S T R A T E G I E S              │
├──────────────────┬──────────────────┬───────────────────────────────────┤
│      FIXED       │     MINIMAL      │          PROPORTIONAL             │
├──────────────────┼──────────────────┼───────────────────────────────────┤
│ Token: 1 sat     │ Token: 10 sats   │ Token: 1 sat per cent             │
│ Splittable: No   │ Splittable: Yes  │ Splittable: Yes                   │
│ Granularity: N/A │ Granularity: Low │ Granularity: High                 │
│                  │                  │                                   │
│ Use: Tickets     │ Use: Gift cards  │ Use: Split payments               │
└──────────────────┴──────────────────┴───────────────────────────────────┘
```

## FIXED Strategy

**Use for:** Tickets, event passes, single-use vouchers

### Characteristics

- **Token amount:** Constant (configurable, default 1 sat)
- **Splittable:** No
- **Redemption:** Whole voucher only

### When to Use

Choose FIXED when:
- The voucher represents a discrete item (ticket, pass, license)
- Partial redemption doesn't make sense
- You want to prevent splitting for business reasons

### Example

```java
// Concert ticket - cannot be split
IssueVoucherRequest ticket = IssueVoucherRequest.builder()
    .issuerId("concert-venue")
    .unit("usd")
    .amount(7500L)                     // $75.00
    .backingStrategy(BackingStrategy.FIXED)
    .issuanceRatio(0.01)               // $0.01 per sat
    .faceDecimals(2)
    .memo("General admission - Summer Festival")
    .build();
```

### Split Behavior

```java
BackingStrategy.FIXED.isSplittable();          // false
BackingStrategy.FIXED.hasFineGrainedSplits();  // false

// Attempting to split a FIXED voucher should fail
// (enforced by wallet/merchant implementation)
```

### Diagram

```
┌─────────────────────────────────────────┐
│           FIXED VOUCHER                 │
│                                         │
│  ┌─────────────────────────────────┐   │
│  │     Face Value: $75.00          │   │
│  │     Backing: 1 sat              │   │
│  │     Split: NOT ALLOWED          │   │
│  └─────────────────────────────────┘   │
│                                         │
│  Redemption: $75.00 or nothing         │
│                                         │
└─────────────────────────────────────────┘
```

---

## MINIMAL Strategy

**Use for:** Gift cards, loyalty points, store credit

### Characteristics

- **Token amount:** Constant (configurable, default 10 sats)
- **Splittable:** Yes
- **Granularity:** Coarse (limited split points)

### When to Use

Choose MINIMAL when:
- Occasional splits are needed (buy $30 item with $50 card)
- Fine-grained splitting isn't required
- You want to minimize on-chain footprint

### Example

```java
// Coffee shop gift card - occasional splits OK
IssueVoucherRequest giftCard = IssueVoucherRequest.builder()
    .issuerId("coffee-shop")
    .unit("sat")
    .amount(50000L)                    // 50,000 sats
    .backingStrategy(BackingStrategy.MINIMAL)
    .issuanceRatio(1.0)                // 1 sat = 1 sat
    .faceDecimals(0)
    .expiresInDays(365)
    .memo("Coffee shop gift card")
    .build();
```

### Split Behavior

```java
BackingStrategy.MINIMAL.isSplittable();          // true
BackingStrategy.MINIMAL.hasFineGrainedSplits();  // false

// Can split, but with limited precision
// E.g., 50,000 sat voucher backed by 10 sats
// Split granularity: ~5,000 sats per sat of backing
```

### Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                    MINIMAL VOUCHER                              │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │     Face Value: 50,000 sats                             │   │
│  │     Backing: 10 sats                                    │   │
│  │     Split: ALLOWED (coarse)                             │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  Possible splits:                                               │
│                                                                 │
│  ┌──────────────┐    ┌──────────────┐                          │
│  │   30,000     │ +  │   20,000     │ = 50,000 sats            │
│  │   (6 sats)   │    │   (4 sats)   │                          │
│  └──────────────┘    └──────────────┘                          │
│                                                                 │
│  Limited precision: 5,000 sats per backing sat                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## PROPORTIONAL Strategy

**Use for:** Split payments, group gifts, precise divisions

### Characteristics

- **Token amount:** Proportional to face value
- **Splittable:** Yes
- **Granularity:** Fine (cent-level for fiat, sat-level for Bitcoin)

### When to Use

Choose PROPORTIONAL when:
- Precise splits are needed (splitting dinner bill)
- Users expect exact amounts
- Face value maps directly to backing

### Example

```java
// Restaurant gift for group - needs precise splits
IssueVoucherRequest groupGift = IssueVoucherRequest.builder()
    .issuerId("fine-dining")
    .unit("eur")
    .amount(10000L)                    // €100.00 in cents
    .backingStrategy(BackingStrategy.PROPORTIONAL)
    .issuanceRatio(0.01)               // €0.01 per sat
    .faceDecimals(2)
    .memo("Group dinner gift")
    .build();

// Backed by 10,000 sats (€0.01 per sat × 10,000 cents)
```

### Split Behavior

```java
BackingStrategy.PROPORTIONAL.isSplittable();          // true
BackingStrategy.PROPORTIONAL.hasFineGrainedSplits();  // true

// Can split with cent-level precision
// E.g., €100.00 voucher backed by 10,000 sats
// Split to exactly €37.42 + €62.58
```

### Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                  PROPORTIONAL VOUCHER                           │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │     Face Value: €100.00                                 │   │
│  │     Backing: 10,000 sats (1 sat per cent)               │   │
│  │     Split: ALLOWED (fine-grained)                       │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  Precise splits:                                                │
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │   €37.42     │  │   €28.17     │  │   €34.41     │          │
│  │  (3742 sats) │  │  (2817 sats) │  │  (3441 sats) │          │
│  └──────────────┘  └──────────────┘  └──────────────┘          │
│                                                                 │
│  Cent-level precision: 1 sat = €0.01                           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Strategy Selection Guide

### Decision Tree

```
                    ┌───────────────────────────┐
                    │ Does the voucher represent│
                    │ a discrete item?          │
                    └─────────────┬─────────────┘
                                  │
                    ┌─────────────┴─────────────┐
                    │                           │
                    ▼                           ▼
              ┌─────────┐                 ┌──────────┐
              │   YES   │                 │    NO    │
              │         │                 │          │
              │ → FIXED │                 │          │
              └─────────┘                 └────┬─────┘
                                              │
                          ┌───────────────────┴───────────────────┐
                          │ Do users need precise splits?         │
                          └───────────────────┬───────────────────┘
                                              │
                          ┌───────────────────┴───────────────────┐
                          │                                       │
                          ▼                                       ▼
                    ┌───────────┐                         ┌───────────────┐
                    │    YES    │                         │      NO       │
                    │           │                         │               │
                    │ → PROPOR- │                         │ → MINIMAL     │
                    │   TIONAL  │                         │               │
                    └───────────┘                         └───────────────┘
```

### Quick Reference

| Scenario | Strategy | Reason |
|----------|----------|--------|
| Concert ticket | FIXED | Cannot attend half a concert |
| Gym membership | FIXED | Access is binary |
| $50 gift card | MINIMAL | Occasional splits, simple |
| Store credit | MINIMAL | Typical gift card usage |
| Group dinner | PROPORTIONAL | Split bill precisely |
| Shared expense | PROPORTIONAL | Each person pays exact share |
| Refund voucher | PROPORTIONAL | Exact amount matters |

---

## Issuance Ratio

The `issuanceRatio` works with backing strategy to determine token amounts.

### Formula

```
Token Amount = Face Value × Backing Factor / Issuance Ratio

Where:
- Face Value: Amount in smallest currency unit (cents, satoshis)
- Backing Factor: Strategy-dependent multiplier
- Issuance Ratio: Face value per satoshi
```

### Examples

**Satoshi voucher (1:1):**
```java
.unit("sat")
.faceValue(10000L)       // 10,000 sats
.issuanceRatio(1.0)      // 1 sat = 1 sat face value
// MINIMAL: backed by ~10 sats
// PROPORTIONAL: backed by 10,000 sats
```

**Euro voucher:**
```java
.unit("eur")
.faceValue(5000L)        // €50.00 (5000 cents)
.faceDecimals(2)
.issuanceRatio(0.01)     // €0.01 per sat
// PROPORTIONAL: backed by 5,000 sats (€50 ÷ €0.01/sat)
```

**USD voucher:**
```java
.unit("usd")
.faceValue(2500L)        // $25.00 (2500 cents)
.faceDecimals(2)
.issuanceRatio(0.01)     // $0.01 per sat
// PROPORTIONAL: backed by 2,500 sats
```

---

## Implementation Details

### BackingStrategy Enum

```java
public enum BackingStrategy {
    FIXED,
    MINIMAL,
    PROPORTIONAL;

    public boolean isSplittable() {
        return this != FIXED;
    }

    public boolean hasFineGrainedSplits() {
        return this == PROPORTIONAL;
    }
}
```

### Serialization

Strategy is serialized to CBOR as its name:

```java
map.put("backingStrategy", backingStrategy.name());
// → "FIXED", "MINIMAL", or "PROPORTIONAL"
```

This is part of the signed canonical bytes, so:
- Cannot change strategy after signing
- Different strategies produce different signatures

---

## Trade-offs

| Strategy | Pros | Cons |
|----------|------|------|
| FIXED | Simple, low overhead | No flexibility |
| MINIMAL | Balanced, efficient | Imprecise splits |
| PROPORTIONAL | Precise, flexible | Higher token amounts |

---

## Related

- [Model B Vouchers](./model-b-vouchers.md)
- [How to Issue Voucher](../how-to/issue-voucher.md)
- [Domain Entities Reference](../reference/domain-entities.md)
