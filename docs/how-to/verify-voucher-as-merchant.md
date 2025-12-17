# How to Verify and Redeem Vouchers as a Merchant

This guide shows merchants how to verify incoming vouchers and process redemptions securely. It covers both offline and online verification modes.

## Prerequisites

- A configured `MerchantVerificationService`
- Access to the voucher ledger (for online verification)
- Your merchant ID matching the voucher's issuer ID

## Set Up the Verification Service

```java
import xyz.tcheeric.cashu.voucher.app.MerchantVerificationService;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;

// Create service with ledger access
VoucherLedgerPort ledger = // ... your ledger implementation
MerchantVerificationService verificationService = new MerchantVerificationService(ledger);
```

## Verify Offline (No Network)

Offline verification checks signature and expiry without network access:

```java
SignedVoucher voucher = // ... received from customer
String myMerchantId = "my-coffee-shop";

MerchantVerificationService.VerificationResult result =
    verificationService.verifyOffline(voucher, myMerchantId);

if (result.isValid()) {
    System.out.println("Voucher is valid (offline check)");
} else {
    System.out.println("Voucher rejected: " + result.getErrorMessage());
}
```

**Use offline verification when:**
- Temporary network outage
- Quick preliminary check
- Testing or development

**Limitations:**
- Cannot detect double-spending
- Cannot verify ledger status

## Verify Online (Recommended)

Online verification adds ledger status checking:

```java
MerchantVerificationService.VerificationResult result =
    verificationService.verifyOnline(voucher, myMerchantId);

if (result.isValid()) {
    System.out.println("Voucher is valid and not redeemed");
    // Proceed with redemption
} else {
    System.out.println("Voucher rejected: " + result.getErrorMessage());
    // Handle specific errors
    for (String error : result.getErrors()) {
        System.out.println("  - " + error);
    }
}
```

**Online verification checks:**
1. Issuer ID matches your merchant ID (Model B)
2. Signature is cryptographically valid
3. Voucher has not expired
4. Ledger status is `ISSUED` (not `REDEEMED`, `REVOKED`, or `EXPIRED`)

## Redeem a Voucher

Use the `redeem()` method to verify and mark as redeemed atomically:

```java
RedeemVoucherRequest request = RedeemVoucherRequest.builder()
    .token(tokenFromCustomer)
    .merchantId("my-coffee-shop")
    .verifyOnline(true)          // Use online verification
    .build();

RedeemVoucherResponse response = verificationService.redeem(request, voucher);

if (response.isSuccess()) {
    System.out.println("Redemption successful!");
    System.out.println("Amount: " + response.getAmount() + " " + response.getUnit());

    // Process payment...
} else {
    System.out.println("Redemption failed: " + response.getErrorMessage());
}
```

## Handle Common Verification Errors

```java
MerchantVerificationService.VerificationResult result =
    verificationService.verifyOnline(voucher, myMerchantId);

if (!result.isValid()) {
    String error = result.getErrorMessage();

    if (error.contains("already redeemed")) {
        // Double-spend attempt
        System.err.println("ALERT: Double-spend attempt detected!");
        logSecurityEvent(voucher, "double-spend");

    } else if (error.contains("issued by")) {
        // Wrong merchant (Model B violation)
        System.err.println("Voucher is for a different merchant");

    } else if (error.contains("expired")) {
        // Time limit exceeded
        System.err.println("Voucher has expired");

    } else if (error.contains("revoked")) {
        // Cancelled by issuer
        System.err.println("Voucher was cancelled");

    } else if (error.contains("signature")) {
        // Tampered or fake voucher
        System.err.println("ALERT: Invalid signature - possible tampering!");
        logSecurityEvent(voucher, "invalid-signature");

    } else {
        // Other error
        System.err.println("Verification failed: " + error);
    }
}
```

## Mark as Redeemed Manually

If you need to separate verification from redemption:

```java
// Step 1: Verify
VerificationResult result = verificationService.verifyOnline(voucher, merchantId);
if (!result.isValid()) {
    return; // Reject
}

// Step 2: Process payment
processPayment(voucher.getSecret().getFaceValue());

// Step 3: Mark as redeemed (IMPORTANT: do this after payment succeeds)
verificationService.markRedeemed(voucher.getSecret().getVoucherId());
```

## Verify Without Redeeming

Check voucher validity without consuming it:

```java
// Just check validity - don't redeem
VerificationResult result = verificationService.verifyOnline(voucher, merchantId);

if (result.isValid()) {
    System.out.println("Voucher is valid");
    System.out.println("Value: " + voucher.getSecret().getFaceValue());
    System.out.println("Unit: " + voucher.getSecret().getUnit());
    System.out.println("Expires: " + voucher.getSecret().getExpiresAt());
    // Customer can still use it later
}
```

## Check Specific Validations

Use `VoucherValidator` for granular checks:

```java
import xyz.tcheeric.cashu.voucher.domain.VoucherValidator;

// Signature only
ValidationResult sigResult = VoucherValidator.validateSignatureOnly(voucher);

// Expiry only
ValidationResult expiryResult = VoucherValidator.validateExpiryOnly(voucher);

// With specific issuer
ValidationResult issuerResult = VoucherValidator.validateWithIssuer(voucher, "expected-issuer");
```

## Offline Fallback Pattern

Handle network failures gracefully:

```java
public VerificationResult verifyWithFallback(SignedVoucher voucher, String merchantId) {
    try {
        // Try online first
        return verificationService.verifyOnline(voucher, merchantId);
    } catch (RuntimeException e) {
        if (isNetworkError(e)) {
            // Fall back to offline
            System.out.println("Network unavailable, using offline verification");
            VerificationResult offline = verificationService.verifyOffline(voucher, merchantId);

            if (offline.isValid()) {
                // Queue for later online verification
                queueForLaterVerification(voucher);
            }
            return offline;
        }
        throw e;
    }
}

private boolean isNetworkError(Exception e) {
    return e.getMessage().contains("timeout") ||
           e.getMessage().contains("connection") ||
           e.getMessage().contains("network");
}
```

## POS Integration Example

Complete point-of-sale integration:

```java
public class POSVoucherHandler {
    private final MerchantVerificationService verification;
    private final String merchantId;

    public POSVoucherHandler(VoucherLedgerPort ledger, String merchantId) {
        this.verification = new MerchantVerificationService(ledger);
        this.merchantId = merchantId;
    }

    public PaymentResult processVoucherPayment(String token, long amountDue) {
        // 1. Parse token to voucher
        SignedVoucher voucher = parseToken(token);

        // 2. Verify
        VerificationResult result = verification.verifyOnline(voucher, merchantId);
        if (!result.isValid()) {
            return PaymentResult.rejected(result.getErrorMessage());
        }

        // 3. Check amount
        long voucherAmount = voucher.getSecret().getFaceValue();
        if (voucherAmount < amountDue) {
            return PaymentResult.insufficientFunds(voucherAmount, amountDue);
        }

        // 4. Mark redeemed
        try {
            verification.markRedeemed(voucher.getSecret().getVoucherId());
        } catch (Exception e) {
            return PaymentResult.error("Failed to record redemption");
        }

        // 5. Calculate change
        long change = voucherAmount - amountDue;

        return PaymentResult.success(voucherAmount, change);
    }
}
```

## Security Best Practices

1. **Always verify online in production** - Prevents double-spending
2. **Log all verification attempts** - Audit trail for disputes
3. **Alert on double-spend attempts** - May indicate fraud
4. **Verify signature before anything else** - Reject tampered vouchers early
5. **Check issuer ID matches** - Model B requires exact match

## Related

- [Model B Vouchers Explained](../explanation/model-b-vouchers.md)
- [MerchantVerificationService Reference](../reference/merchant-verification-service.md)
- [Issue Voucher](./issue-voucher.md)
