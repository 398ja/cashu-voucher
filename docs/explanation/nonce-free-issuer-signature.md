# A voucher signature that survives a swap

This page explains why the issuer signature on a voucher stops covering the per-proof nonce, what it
covers instead, and why the change is a clean break with no compatibility path.

## The problem

Every Cashu swap replaces spent proofs with new ones, and each new proof must carry a secret that
differs from every input. Two byte-identical secrets hash to the same curve point `Y`, the mint keys
spent proofs on `Y`, and an output that reuses an input's secret is born already spent (mint error
11001). So receiving or splitting a voucher always gives each output a fresh NUT-10 `nonce`.

Until now the issuer signed the canonical bytes `[kind, data_hex, nonce, [tags]]`
([`VoucherCanonicalBytes`](../../cashu-voucher-domain/src/main/java/xyz/tcheeric/cashu/voucher/domain/VoucherCanonicalBytes.java)).
The nonce was inside the signature, so every fresh nonce broke it. Three things follow from that:

- **Only the issuer can move a voucher.** `OwnIssuanceVoucherResigner` in imani-gateway-customer
  re-signs a received voucher with the issuer's key, and does it only for vouchers that gateway
  issued. A voucher from any other issuer is received with a stale signature and refused on its next
  spend (`voucher_signature_invalid`, 90019).
- **The party that builds output secrets must hold the issuer key.** That is why the gateway builds
  them today, keeps the fresh proofs in its own float, and is custodial for every receive
  (imani-gateway-customer#133).
- **A lost receive response cannot be recovered by the wallet.** The wallet never held the outputs'
  secrets or blinding factors, so it cannot ask the mint to restore them (imani-wallet#123).

## The change

The issuer signs the **voucher**, not the **proof**. The canonical bytes become:

```
["<kind>","<data_hex>",[<tags>]]
```

These are the old bytes with the nonce element removed. Everything else stays: the kind, the voucher
id in `data`, and every tag except `issuer_sig` and `issuer_pubkey`. The tag values, the numeric
rendering and the tag order are unchanged. This is the only form that is signed or verified.

Any holder can now refresh the nonce on every output of a swap, and the signature still verifies.
Nobody needs the issuer's key to receive or split a voucher.

## What the nonce was protecting, and why dropping it is safe

The nonce is proof uniqueness, not voucher identity. Removing it from the signed bytes means one
issuer signature is valid for every proof that carries the same voucher. That has always been true in
practice: a voucher is issued as several proofs (one per denomination), all with the same metadata.

Double spending is not affected. The mint prevents it per proof, by `Y`, which is derived from the
whole secret, nonce included. A signature valid across nonces does not let anyone spend a proof
twice, and it does not let anyone mint value: new proofs only come from a swap that burns inputs of
equal amount (`validateVoucherSwapAmounts`), and only the mint can sign them.

What the signature still guarantees is exactly what a holder needs: this voucher, with this face
value, unit, expiry, ratio, warrant and merchant, was issued by this key.

## No compatibility path

Vouchers signed under the old bytes stop verifying, and are refused by the mint with
`voucher_signature_invalid` (90019). That is accepted: the stack has never run in production
(imani-deploy#60), so the only vouchers affected are test coupons on staging, which are issued again
after the deploy.

A compatibility path would cost a version marker in the signed bytes, a staged rollout (verifiers,
then issuers, then retiring the re-signer after the longest expiry), and a second verification form
kept alive for months. None of it protects real money here, so none of it is built. The same goes
for the older truncated-number form (`NumericTagForm.TRUNCATED_TO_LONG`), which exists only for
vouchers issued before a past fix. It is removed with its switch, and so is the wallet's
`legacyCanonical` fallback in `verifyVoucher`.

## What changes, and where

| Repository | Change |
| --- | --- |
| cashu-voucher | `VoucherCanonicalBytes` drops the nonce and the truncated form. `VoucherSignatureService.verify` has one path. |
| cashu-mint | Takes the new cashu-voucher. No code change: `VoucherSpendingCondition` already delegates to `VoucherSignatureService.verify`. |
| imani-wallet | `voucherCanonicalBytes` drops the nonce and the `legacy` flag. `verifyVoucher` has one path. `canonicalWire` for locked vouchers is rebuilt without the nonce. |
| imani-wallet-lib | `performSwap` refreshes the nonce and no longer calls a resigner. The `VoucherResigner` port is removed. |
| imani-gateway-customer | `OwnIssuanceVoucherResigner` and its wiring are deleted. |
| imani-bom | Pins the new versions. |

Every repository ships in one coordinated staging deploy. A verifier and an issuer on different
versions would refuse each other's vouchers, so none of them is deployed alone.

## How it is verified

- A shared test vector: one voucher secret, its canonical bytes, and a signature. The same vector
  is asserted in cashu-voucher (Java) and in imani-wallet (TypeScript), so the two implementations
  cannot drift apart.
- A swap test: a signed voucher, received with a fresh nonce on every output, still verifies, with
  no key but the issuer's ever used.
- A negative test for each covered field: changing the kind, the voucher id or any tag breaks the
  signature. Changing only the nonce does not.
- On staging: the post-deploy suite (T8, T9) sends and pays back vouchers across wallets, which now
  exercises a voucher received from an issuer other than the gateway.

## What this unlocks

With the signature independent of the nonce, the wallet can build its own output secrets on receive,
blind them, and send only the blinded outputs. The gateway forwards the swap and holds nothing, and a
lost response is recovered from the mint by NUT-09 restore of outputs the wallet derived under
NUT-13. That work is tracked in imani-gateway-customer#133 and imani-wallet#123.
