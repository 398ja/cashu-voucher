# How to Issue a Voucher

This guide shows you how to issue vouchers for different use cases, including configuring backing strategies, expiry, and metadata.

## Basic Voucher Issuance

Issue a simple voucher with default settings:

```java
IssueVoucherRequest request = IssueVoucherRequest.builder()
    .issuerId("my-merchant-id")
    .unit("sat")
    .amount(10000L)
    .backingStrategy(BackingStrategy.MINIMAL)
    .issuanceRatio(1.0)
    .faceDecimals(0)
    .build();

IssueVoucherResponse response = voucherService.issue(request);
```

## Issue with Expiry

Set a voucher to expire after a certain number of days:

```java
IssueVoucherRequest request = IssueVoucherRequest.builder()
    .issuerId("my-merchant-id")
    .unit("sat")
    .amount(10000L)
    .expiresInDays(365)              // Expires in 1 year
    .backingStrategy(BackingStrategy.MINIMAL)
    .issuanceRatio(1.0)
    .faceDecimals(0)
    .memo("Valid for one year")
    .build();
```

## Issue with Fiat Currency

Issue vouchers denominated in fiat currency:

```java
// Euro voucher: €50.00
IssueVoucherRequest euroRequest = IssueVoucherRequest.builder()
    .issuerId("eu-merchant")
    .unit("eur")
    .amount(5000L)                   // 5000 cents = €50.00
    .faceDecimals(2)                 // 2 decimal places for euros
    .issuanceRatio(0.01)             // €0.01 per sat
    .backingStrategy(BackingStrategy.PROPORTIONAL)
    .build();

// USD voucher: $25.00
IssueVoucherRequest usdRequest = IssueVoucherRequest.builder()
    .issuerId("us-merchant")
    .unit("usd")
    .amount(2500L)                   // 2500 cents = $25.00
    .faceDecimals(2)
    .issuanceRatio(0.01)
    .backingStrategy(BackingStrategy.PROPORTIONAL)
    .build();
```

## Choose the Right Backing Strategy

### FIXED - Non-Splittable

Use for tickets, event passes, or single-use vouchers:

```java
// Event ticket - cannot be split
IssueVoucherRequest ticketRequest = IssueVoucherRequest.builder()
    .issuerId("concert-venue")
    .unit("usd")
    .amount(7500L)                   // $75.00
    .backingStrategy(BackingStrategy.FIXED)  // Cannot split
    .issuanceRatio(0.01)
    .faceDecimals(2)
    .memo("Concert admission - General")
    .build();
```

### MINIMAL - Occasional Splits

Use for gift cards where splits are occasional:

```java
// Gift card - can split occasionally
IssueVoucherRequest giftCardRequest = IssueVoucherRequest.builder()
    .issuerId("coffee-shop")
    .unit("sat")
    .amount(50000L)
    .backingStrategy(BackingStrategy.MINIMAL)  // Coarse splits
    .issuanceRatio(1.0)
    .faceDecimals(0)
    .memo("Coffee shop gift card")
    .build();
```

### PROPORTIONAL - Fine-Grained Splits

Use for shared payments or group gifts:

```java
// Group gift - supports precise splits
IssueVoucherRequest groupGiftRequest = IssueVoucherRequest.builder()
    .issuerId("restaurant")
    .unit("eur")
    .amount(10000L)                  // €100.00
    .backingStrategy(BackingStrategy.PROPORTIONAL)  // Fine splits
    .issuanceRatio(0.01)
    .faceDecimals(2)
    .memo("Group dinner gift")
    .build();
```

## Issue with Custom Voucher ID

Specify a custom voucher ID (useful for testing or external systems):

```java
IssueVoucherRequest request = IssueVoucherRequest.builder()
    .voucherId("custom-voucher-12345")  // Custom ID
    .issuerId("my-merchant")
    .unit("sat")
    .amount(10000L)
    .backingStrategy(BackingStrategy.MINIMAL)
    .issuanceRatio(1.0)
    .faceDecimals(0)
    .build();
```

## Issue with Merchant Metadata

Add custom business data to the voucher:

```java
Map<String, Object> metadata = new LinkedHashMap<>();
metadata.put("purchaseOrderId", "PO-2024-001");
metadata.put("customerTier", "gold");
metadata.put("campaign", "holiday-2024");

IssueVoucherRequest request = IssueVoucherRequest.builder()
    .issuerId("my-merchant")
    .unit("sat")
    .amount(10000L)
    .backingStrategy(BackingStrategy.MINIMAL)
    .issuanceRatio(1.0)
    .faceDecimals(0)
    .merchantMetadata(metadata)
    .build();
```

## Handle Issuance Response

Extract useful information from the response:

```java
IssueVoucherResponse response = voucherService.issue(request);

// Get the signed voucher
SignedVoucher voucher = response.getVoucher();

// Get the Cashu token (for sharing)
String token = response.getToken();

// Convenience methods
String voucherId = response.getVoucherId();
Long amount = response.getAmount();
String unit = response.getUnit();

System.out.println("Voucher ID: " + voucherId);
System.out.println("Amount: " + amount + " " + unit);
System.out.println("Token: " + token);
```

## Validate Before Issuing

Check parameters before creating:

```java
public void validateRequest(IssueVoucherRequest request) {
    if (request.getAmount() <= 0) {
        throw new IllegalArgumentException("Amount must be positive");
    }
    if (request.getAmount() > 1_000_000_000L) {
        throw new IllegalArgumentException("Amount exceeds maximum");
    }
    if (request.getExpiresInDays() != null && request.getExpiresInDays() <= 0) {
        throw new IllegalArgumentException("Expiry must be positive");
    }
}
```

## Bulk Issuance

Issue multiple vouchers efficiently:

```java
public List<IssueVoucherResponse> issueBatch(
        String issuerId,
        int count,
        long amountEach
) {
    List<IssueVoucherResponse> responses = new ArrayList<>();

    for (int i = 0; i < count; i++) {
        IssueVoucherRequest request = IssueVoucherRequest.builder()
            .issuerId(issuerId)
            .unit("sat")
            .amount(amountEach)
            .backingStrategy(BackingStrategy.MINIMAL)
            .issuanceRatio(1.0)
            .faceDecimals(0)
            .memo("Batch voucher " + (i + 1) + "/" + count)
            .build();

        responses.add(voucherService.issue(request));
    }

    return responses;
}
```

## Error Handling

Handle common issuance errors:

```java
try {
    IssueVoucherResponse response = voucherService.issue(request);
} catch (IllegalArgumentException e) {
    // Invalid request parameters
    System.err.println("Invalid request: " + e.getMessage());
} catch (RuntimeException e) {
    // Ledger publish failed
    if (e.getMessage().contains("ledger")) {
        System.err.println("Failed to publish to ledger: " + e.getMessage());
    } else {
        throw e;
    }
}
```

## Related

- [Backing Strategies Explained](../explanation/backing-strategies.md)
- [VoucherService Reference](../reference/voucher-service.md)
- [Verify Voucher as Merchant](./verify-voucher-as-merchant.md)
