# How to Generate a Payment Request

This guide shows how to generate NUT-18V payment requests for accepting voucher payments at point-of-sale or online checkout.

## Basic Payment Request

Generate a simple payment request with a fixed amount:

```java
GeneratePaymentRequestDTO request = GeneratePaymentRequestDTO.builder()
    .issuerId("my-merchant-id")
    .amount(5000)
    .unit("sat")
    .description("Coffee purchase")
    .build();

GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(request);
String qrContent = response.getEncodedRequest();  // vreqA...
```

## Open-Amount Request

Generate a request that accepts any amount (e.g., for tips or donations):

```java
GeneratePaymentRequestDTO request = GeneratePaymentRequestDTO.builder()
    .issuerId("my-merchant-id")
    .description("Tip jar - any amount welcome")
    .build();

// Customer chooses the amount in their wallet
```

## Single-Use Payment Request

For one-time payments, mark the request as single-use:

```java
GeneratePaymentRequestDTO request = GeneratePaymentRequestDTO.builder()
    .issuerId("my-merchant-id")
    .paymentId("order-12345")        // Correlate with your order system
    .amount(15000)
    .unit("sat")
    .singleUse(true)                 // Prevent replay attacks
    .description("Order #12345")
    .build();
```

## With HTTP Callback

Add an HTTP POST callback for server-to-server payment notification:

```java
GeneratePaymentRequestDTO request = GeneratePaymentRequestDTO.builder()
    .issuerId("my-merchant-id")
    .amount(10000)
    .unit("sat")
    .callbackUrl("https://myshop.com/api/voucher-payment")
    .description("Online checkout")
    .build();

// When customer pays, their wallet POSTs the payment payload to your callback URL
```

## With Nostr Transport

Accept payments via Nostr NIP-17 encrypted direct messages:

```java
GeneratePaymentRequestDTO request = GeneratePaymentRequestDTO.builder()
    .issuerId("my-merchant-id")
    .amount(5000)
    .unit("sat")
    .nostrNprofile("nprofile1...")   // Your Nostr nprofile
    .description("Pay via Nostr")
    .build();
```

## Multiple Transports

Combine multiple transport methods for flexibility:

```java
GeneratePaymentRequestDTO request = GeneratePaymentRequestDTO.builder()
    .issuerId("my-merchant-id")
    .amount(5000)
    .unit("sat")
    .includeMerchantTransport(true)                    // Merchant transport
    .callbackUrl("https://myshop.com/api/payment")     // HTTP POST
    .nostrNprofile("nprofile1...")                     // Nostr NIP-17
    .description("Multiple payment options")
    .build();
```

## Restrict Accepted Mints

Only accept vouchers from specific mints:

```java
GeneratePaymentRequestDTO request = GeneratePaymentRequestDTO.builder()
    .issuerId("my-merchant-id")
    .amount(10000)
    .unit("sat")
    .mints(List.of(
        "https://mint.example.com",
        "https://trusted-mint.org"
    ))
    .description("Trusted mints only")
    .build();
```

## Offline Verification

Enable offline verification for environments without network access:

```java
GeneratePaymentRequestDTO request = GeneratePaymentRequestDTO.builder()
    .issuerId("my-merchant-id")
    .amount(5000)
    .unit("sat")
    .offlineVerification(true)       // Request DLEQ proofs for offline verification
    .description("Offline POS payment")
    .build();
```

## Clickable URI Format

Generate a clickable URI for web links or deep links:

```java
GeneratePaymentRequestDTO request = GeneratePaymentRequestDTO.builder()
    .issuerId("my-merchant-id")
    .amount(5000)
    .unit("sat")
    .clickable(true)                 // Generates cashu:vreqA... format
    .build();

String uri = response.getEncodedRequest();  // cashu:vreqA...
// Can be used as href in web pages
```

## Display as QR Code

The encoded request can be displayed as a QR code:

```java
GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(request);
String qrContent = response.getEncodedRequest();

// Use any QR code library to generate the image
// Example with ZXing:
// QRCodeWriter writer = new QRCodeWriter();
// BitMatrix matrix = writer.encode(qrContent, BarcodeFormat.QR_CODE, 300, 300);
```

## Handle Response

Extract information from the response:

```java
GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(request);

// The encoded request string (for QR code)
String encodedRequest = response.getEncodedRequest();

// Access the underlying request object
VoucherPaymentRequest paymentRequest = response.getPaymentRequest();
String paymentId = paymentRequest.getPaymentId();
String issuerId = paymentRequest.getIssuerId();
Integer amount = paymentRequest.getAmount();
```

## Process Incoming Payment

When a customer pays, validate and process the payment payload:

```java
// Customer's wallet sends a VoucherPaymentPayload
VoucherPaymentPayload payload = ...; // Received via callback or transport

// Validate the payment
MerchantVerificationService.PaymentValidationResult result =
    merchantService.validatePaymentPayload(payload, originalRequest);

if (result.isValid()) {
    // Process the payment
    MerchantVerificationService.PaymentProcessingResult processed =
        merchantService.processPaymentPayload(payload, originalRequest);

    if (processed.isSuccess()) {
        // Payment accepted - fulfill the order
        System.out.println("Payment received: " + processed.getMessage());
    }
} else {
    // Payment rejected
    System.out.println("Payment rejected: " + result.getReason());
}
```

## Error Handling

Handle common errors when generating payment requests:

```java
try {
    GeneratePaymentRequestResponse response = voucherService.generatePaymentRequest(request);
} catch (IllegalArgumentException e) {
    // Invalid request parameters
    // - Missing issuerId
    // - Amount specified without unit
    System.err.println("Invalid request: " + e.getMessage());
}
```

## See Also

- [How to Issue a Voucher](issue-voucher.md)
- [How to Verify a Voucher as Merchant](verify-voucher-as-merchant.md)
- [NUT-18V Payment Request Specification](https://github.com/cashubtc/nuts/blob/main/18.md)
