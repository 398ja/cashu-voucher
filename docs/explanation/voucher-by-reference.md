# A voucher stored once per token, referenced from each proof

Status: readers and carriers implemented (cashu-voucher#64, phase A). Producers stay on
the inline form until every reader ships (phase B).

## The problem

Every proof of an unlocked voucher carries the whole signed voucher in its NUT-10
`data`. A typical 7 EUR coupon has 7 proofs, and 91% of its 13 KB token is the same
813-byte voucher repeated seven times (cashu-voucher#64). That breaks NIP-17 size
limits, inflates every request and every stored row, and grows with every field added.

## The two forms

| | Form | Unlocked `VOUCHER` proof secret |
|---|---|---|
| v1 | inline | `["VOUCHER",{"nonce":N,"data":hex(D),"tags":[]}]` |
| v2 | by reference | `["VOUCHER",{"nonce":N,"data":hex(SHA256(D)),"tags":[]}]` |

`D` is the **voucher document**: the signed CBOR blob that a v1 proof carries in `data`
today, byte for byte (keys `voucherId`, `issuerId`, `unit`, `faceValue`, `expiresAt`,
`memo`, `backingStrategy`, `issuanceRatio`, `faceDecimals`, `merchantMetadata`,
`issuanceWarrant` when present, `nonce`, `issuerSignature`, `issuerPublicKey`).

- **Lossless.** A v2 proof and a v1 proof of the same voucher resolve to the same
  document, so every check that runs on v1 runs unchanged on v2: issuer signature,
  issuer binding, expiry, canonical numeric tags, warrants, the redemption cap.
- **Binding.** `SHA256(D)` is inside the secret, so it is inside
  `Y = hash_to_curve(secret)` and under the mint's signature. A verifier checks
  `SHA256(D) == data` and then verifies `D` exactly as it verifies a v1 blob. The document
  cannot be swapped for another.
- **Discriminator.** An unlocked `VOUCHER` whose `data` is exactly 32 bytes is by reference.
  This cannot misread a real v1 proof: a document always contains a 36-character voucher id,
  so it is longer than 32 bytes. Either reading is fully verified, so choosing the "wrong"
  path can only lead to a refusal, never to a skipped check.
- **No new trust.** The document travels with the proofs. No server or relay is needed to
  read or verify a voucher.

`P2PK_VOUCHER` (locked) proofs are unchanged in this version. See "Locked vouchers" below.

## The rule

**Wherever proofs go, the documents they reference go too, once per document.**

### Token (`cashuB`, NUT-00 v4)

One new top-level key, written last:

```
{ "t": [...], "d": memo, "m": mint, "u": unit, "v": { "<hex SHA256(D)>": <bstr D>, ... } }
```

- Keys are 64 lowercase hex characters. Values are CBOR byte strings.
- Omitted when no proof is by reference. Never emitted empty.
- **A decoder refuses** a token when a key is not `hex(SHA256(value))`, a value is empty
  or larger than 65,536 bytes, or there are more entries than proofs.
- A by-reference proof whose document is missing is **incomplete**: it decodes, but every
  verifier reports it unverifiable. It is never treated as plain sats.
- Entries no proof references are dropped on re-encode.
- `cashuA` (v3, JSON) cannot carry documents. A token holding a by-reference proof is
  only ever encoded as `cashuB`.

### Mint requests (`POST /v1/swap`, `POST /v1/melt`)

```json
{ "inputs": [...], "outputs": [...], "vouchers": { "<hex SHA256(D)>": "<hex D>" } }
```

- Optional. Omitted when no input is by reference.
- The mint refuses the request when a key is not `hex(SHA256(value))`, a value is empty or
  larger than 65,536 bytes, there are more entries than inputs, or a by-reference input's
  document is missing. Entries no input references are ignored.
- **The mint stays stateless.** It verifies from the documents in the request and stores
  nothing new. It sees exactly what it sees today: the full voucher is in every v1 secret.
- Outputs are blinded, so the mint cannot check that a new proof references the right
  document. It cannot check that today either; the binding is checked when the proof is
  spent.

### NUT-18 payment payloads

`PaymentPayload` and `VoucherPaymentPayload` gain the same optional `vouchers` map.

### Storage

Wallets keep each document once, keyed by its hash, next to the proofs that reference it.

## Ids that are derived from content

- `VoucherFingerprint` is computed from the resolved voucher (id, issuer, signature), so it is
  the same for both forms.
- A token's own id (`sha256(token)`, the wallet's `token_id`, spec 020's DM `voucher_id`) is
  a hash of the token string, so a v2 token has a different id from a v1 token. Both are
  new tokens; nothing in circulation changes.

## Migration

1. **Phase A (this change): readers and carriers.** Everything that reads or forwards proofs
   accepts both forms and carries documents: cashu-lib (token, requests, payloads),
   cashu-voucher (resolution and verification), cashu-mint (swap, melt, redemption cap),
   imani-wallet (decode, verify, swap, send, change). Nothing produces v2 yet.
2. **Phase B: the remaining readers**, then the producers behind a flag: imani-wallet-lib
   (token codec, swaps), imani-gateway-customer (issuance, bundle sends, burns, the Cashlink
   and split routes), imani-gateway-core, imani-gateway-portal. A producer emits v2 only once
   every reader in its path is on phase A or later.
3. v1 proofs keep verifying for as long as they live. There is no conversion: changing `data`
   changes `Y`, so an existing proof cannot be compacted. Proofs move to v2 when a v2-aware
   wallet mints new outputs for them in a swap.

Wallets that only know v1 cannot read v2 proofs and must be updated before producers switch.

## Answers to the open questions on #64

1. **Unknown token keys.** imani-wallet decodes tokens with its own CBOR code, not cashu-ts,
   and keeps `v`. A third-party decoder that drops `v` makes by-reference proofs unverifiable,
   which is refused, never spent as plain sats. Vouchers by reference work in Imani wallets.
2. **The nonce under the issuer signature.** It stays signed for locked vouchers. The nonce is
   what limits mint-valid signed locked proofs to the ones the issuer created: the mint
   balances sats but never sees output secrets, so without it a lock holder could mint
   unlimited copies of a signed locked voucher for a few sats each (cashu-voucher#47, closed
   for this reason). So locked splits keep needing fresh issuer signatures
   (imani-gateway-core#113). Unlocked vouchers never signed their wrapper nonce, which is why
   their document is identical in every proof and can be referenced.
3. **Outputs.** No regression, as above.

## Locked vouchers (not in this version)

A locked proof's issuer signature covers its own nonce, so its signed bytes differ per proof.
A by-reference locked form would move the voucher tags into a document and keep, per proof,
`data` = lock key, the nonce, the P2PK tags and one `voucher_doc` tag holding
`hex(SHA256(document))`, with the issuer signature over that reduced secret. The signature
still covers the nonce, and through the hash, the whole voucher. It needs a new canonical form
shared by every re-signer, so it is left for a later change. Locked tokens are mostly
single-proof (terminal credentials, licences, link locks), where it gains nothing.

## Relation to the encrypted ledger (imani-gateway-customer#310)

The ledger v2 event is addressed by `ref = SHA256("imani-voucher-ref-v1:" ‖ issuerId ‖ ":" ‖
voucherId)`, not by `SHA256(D)`: a locked voucher has no single document, and the stall and
the portal know voucher ids, not documents. The ledger body may carry `D` (`doc`), so anyone
who knows a voucher's id and issuer can recover its document.
