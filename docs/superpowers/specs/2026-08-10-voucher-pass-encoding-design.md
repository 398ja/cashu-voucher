# Voucher Pass Encoding (pass.json)

**Date:** 2026-08-10
**Status:** Proposed
**Scope:** `cashu-voucher`

## Context

Vouchers today are represented by a `SignedVoucher` wrapping a NUT-10 `VoucherSecret`
(tag-based metadata + Schnorr issuer signature), with the Nostr NIP-33 ledger as the
source of truth for status. There is no defined representation for *displaying* a
voucher as a card in a wallet UI.

Rather than invent a bespoke display DTO, we adopt Apple's `pass.json` schema as the
encoding format for voucher display data. It is public, well documented, and already
solves the things a hand-rolled model gets wrong: a typed field model with
locale-aware date/number/currency formatting, lifecycle keys (`expirationDate`,
`voided`, `relevantDate`), machine-readable code definitions, and five pre-designed
pass styles that cover the pass types planned after vouchers.

We are adopting **the schema only**. Apple Wallet is not a target: imani ships its own
wallet, which renders the pass itself.

## Goals

- Encode a `SignedVoucher` as a `pass.json` document.
- Use a schema that generalises to future pass types (tickets, coupons, memberships)
  without redesign.
- Keep the voucher's cryptographic model untouched.

## Non-goals

Explicitly out of scope, and *not* deferred work — these are ruled out by the decision
to skip Apple Wallet:

- Apple Pass Type ID certificates, WWDR chain, PKCS#7 signing.
- The `.pkpass` ZIP container, `manifest.json`, and asset bundling.
- The PassKit pass-update web service, device registration, and APNs.
- Google Wallet API (`GenericClass`/`GenericObject`) and pass conversion.
- Changes to `cashu-lib`. `pass.json` is not a NUT, and `VoucherSecret` already
  carries every field the mapping needs.

## Decisions

**D1 — `pass.json` is a derived view, never authoritative, and never round-trips.**
`SignedVoucher` is the only source of truth. Nothing parses a pass back into a voucher.
This is what keeps the mapping safe to change and makes staleness impossible: a pass is
rendered on demand from current voucher state, so a balance change after partial
redemption needs no synchronisation, no re-signing, and no invalidation — just
re-rendering.

**D2 — New module `cashu-voucher-pass`, depending on `cashu-voucher-domain` only.**
Sibling of `cashu-voucher-nostr` in the parent POM.

Note the reason is narrower than the existing adapters'. `VoucherLedgerPort` and
`VoucherBackupPort` exist to isolate *I/O*. Pass rendering is a pure function with no
I/O, so it earns a module purely on dependency hygiene: `cashu-voucher-domain`
advertises zero infrastructure dependencies, and jPasskit types cannot live there
without breaking that claim.

**D3 — No `VoucherPassPort` in `cashu-voucher-app`.**
No application service needs to render a card; the caller (imani-wallet) invokes the
mapper directly. A port here would be ceremony over a single implementation with
nothing to abstract. If an app service later needs rendering, adding the port is
trivial and non-breaking.

**D4 — Use jPasskit (`de.brendamour:jpasskit:0.5.7`, Apache-2.0) as the object model
and serializer only.**
`PKPass` is a POJO tree that Jackson serializes to `pass.json`. The signing classes
(`PKFileBasedSigningUtil`, `PKSigningInformationUtil`) and template classes
(`PKPassTemplateFolder`, `PKPassTemplateInMemory`) are cleanly separate and must not be
imported. jPasskit's transitive dependencies are Jackson and Bouncy Castle, both
already managed in the parent POM.

*Fallback:* if the transitive tree proves unacceptable at implementation time, replace
with ~12 Jackson-annotated records. The mapper's public signature does not change.

**D5 — Fiat only.** Sat-denominated vouchers are out of scope. This is what makes
`currencyCode` usable directly and removes the need for `numberStyle` fallbacks.

**D6 — Output is a `PKPass` object.** Serialization is the caller's `ObjectMapper`
one-liner. No wrapper class, no file writing, no `String`-returning convenience method.

## Module layout

```
cashu-voucher/
├── cashu-voucher-domain     (unchanged)
├── cashu-voucher-app        (unchanged)
├── cashu-voucher-nostr      (unchanged)
└── cashu-voucher-pass       (new)
    └── xyz/tcheeric/cashu/voucher/pass/
        └── VoucherPassMapper.java
```

One class:

```java
public final class VoucherPassMapper {
    private VoucherPassMapper() {}

    /** Renders with {@code voided: false}. */
    public static PKPass toPass(SignedVoucher voucher);

    /** Renders with {@code voided} derived from ledger status. */
    public static PKPass toPass(SignedVoucher voucher, VoucherStatus status);
}
```

No interface, no `PassRenderer<T>` abstraction. When a second pass type arrives, the
reusable pieces (currency handling, branding resolution) are extracted then, against
two real callers rather than one imagined.

## Mapping

`SignedVoucher` → `pass.json`, style `PKStoreCard` (expressed in jPasskit as
`PKGenericPass.builder().passType(PKPassType.PKStoreCard)`).

| pass.json key | Source | Notes |
|---|---|---|
| `formatVersion` | constant `1` | |
| `passTypeIdentifier` | constant `xyz.tcheeric.voucher` | Schema-required, semantically meaningless without Apple |
| `teamIdentifier` | constant `imani` | As above |
| `serialNumber` | `secret.getVoucherId()` | |
| `organizationName` | `merchant_metadata.name` | Falls back to `secret.getIssuerId()` |
| `description` | `secret.getMemo()` | Falls back to `"Gift Card"` |
| `logoText` | `merchant_metadata.name` | Omitted when absent |
| `backgroundColor` / `foregroundColor` | `merchant_metadata.bgColor` / `.fgColor` | Defaults below |
| `expirationDate` | `secret.getExpiresAt()` | Epoch seconds → ISO 8601; omitted when null |
| `voided` | `VoucherStatus` | `true` for `REDEEMED` and `REVOKED` |
| `primaryFields[balance]` | `face_value` + `face_decimals` + `unit` | See currency rules |
| `auxiliaryFields[expires]` | `secret.getExpiresAt()` | `dateStyle: PKDateStyleMedium` |
| `backFields` | voucher id, issuer id, `issuer_pubkey`, terms | See below |
| `barcodes[0]` | `"voucher:" + secret.getVoucherId()` | See barcode rules |
| `userInfo` | `{"voucherId": "<uuid>"}` | Links the card to proofs the wallet already holds |

`backFields` carries, in order: `voucherId`, `issuer` (issuer id), `issuerKey`
(`issuer_pubkey`, full hex), and `terms` — a fixed string stating the Model B
constraint that the voucher is redeemable only with the issuing merchant.

The following voucher tags are deliberately **not** mapped: `issuer_sig`,
`backing_strategy`, `issuance_ratio`. They are verification and accounting concerns
with no display meaning, and `issuer_sig` in particular should not be surfaced in a UI
where it invites treatment as a credential.

`VoucherStatus` is not on `SignedVoucher`; it comes from the ledger. `toPass` therefore
sets `voided` from an overload taking status, defaulting to `false` when the caller has
not resolved it.

## Currency rules

```java
Currency ccy = Currency.getInstance(secret.getUnit().toUpperCase(Locale.ROOT));
BigDecimal amount = BigDecimal.valueOf(secret.getFaceValue(), secret.getFaceDecimals());
```

- `Currency.getInstance` validates the ISO 4217 code and throws
  `IllegalArgumentException` on an unknown one. No lookup table.
- `BigDecimal.valueOf(long, int)` is mandatory — `double` must never appear on this
  path. `faceValue=5000, faceDecimals=2` → exactly `50.00`.
- `pass.json`'s `currencyCode` formatting expects **major units**, so the scaling is
  required for correctness, not presentation.
- **Cross-check `faceDecimals` against `ccy.getDefaultFractionDigits()`, warn on
  mismatch, and scale by `faceDecimals` regardless.** JPY is 0; KWD and BHD are 3. A
  mismatch means the voucher was minted inconsistently, but `face_decimals` is what the
  issuer signed and the rest of the stack treats as valid — a display mapper is the
  wrong layer to overrule it. The `WARN` surfaces the bad minting without a cosmetic
  concern being able to break rendering.
- `unit` is lowercase by Cashu convention (`eur`); ISO 4217 is uppercase. Uppercase
  with `Locale.ROOT` to avoid the Turkish dotless-i trap.

The balance field is `{key: "balance", label: "BALANCE", value: <BigDecimal>,
currencyCode: <ISO 4217>}`. Verify at implementation time that jPasskit serializes
`BigDecimal` in plain notation, not scientific.

## Merchant branding contract

`merchant_metadata` is a free-form JSON string tag. The mapper reads four optional
keys and tolerates anything else:

| Key | Type | Default when absent |
|---|---|---|
| `name` | string | `issuer_id` |
| `logoUrl` | string | omitted |
| `bgColor` | `rgb(r,g,b)` string | `rgb(20,20,20)` |
| `fgColor` | `rgb(r,g,b)` string | `rgb(255,255,255)` |

No schema enforcement, no new NUT-10 tags, no validation beyond parseability.
Malformed or absent `merchant_metadata` yields defaults and a `WARN` log — it must
never fail rendering, since branding is cosmetic and the voucher is still valid
without it.

`logoUrl` is carried through for the renderer to fetch. The mapper performs no I/O.

## Barcode

```json
"barcodes": [{
  "format": "PKBarcodeFormatQR",
  "message": "voucher:<uuid>",
  "messageEncoding": "utf-8",
  "altText": "<uuid>"
}]
```

- Emit the `barcodes` **array** only. The singular `barcode` key is the deprecated
  pre-iOS 9 form; nothing in our stack reads it.
- `message` is `voucher:` + the voucher UUID. The ledger is the source of truth for
  status, so a scanner needs only an identifier to look up. A prefixed UUID is ~44
  ASCII characters against a QR limit of ~2,953 bytes — capacity is not a
  consideration.
- `altText` renders the bare UUID as human-readable text beneath the code (no prefix —
  it is for a human to key in), so a cashier can enter it when a scanner fails. This is
  a real failure mode for gift cards and the fallback is free.
- `utf-8` rather than Apple's `iso-8859-1` convention, because we write the reader and
  are not constrained by third-party decoders.

### Why a prefix, and why not the wallet's existing QR

The imani wallet already renders a voucher QR (`voucher/js/vouchers.js::renderShareQr`),
and the pass barcode is deliberately **not** the same payload.

That QR is a *share/transfer* code: it carries the raw Cashu token, rendered as an
animated multi-frame NUT-16 BC-UR sequence, falling back to a static token QR and then
to a receive URL. A peer scans it and receives the bearer value.

The pass barcode is a *redemption* code: a merchant scans an identifier and resolves it
against the ledger. Two reasons they must stay distinct:

1. **The share QR gives away the money.** If the pass carried the same payload, a
   merchant scanning a customer's card at the till would receive the whole token rather
   than redeem against it.
2. **`pass.json` barcodes are static.** Entries in the `barcodes` array are alternate
   *formats* of a single message, not different messages — the schema has no animated
   or multi-frame concept. The existence of `AnimatedQrRenderer` is proof that tokens
   routinely exceed static QR capacity, so a token in a pass barcode is not a trade-off
   but an impossibility.

The `voucher:` prefix exists because every payload the wallet's scanner accepts is
prefix-discriminated (`packages/imani-qr/src/detector/patterns.ts`): `vreqa` for
payment requests, `cashua`/`cashub` for tokens, `npub1`, `ur:bytes/` for animated
fragments, with `cashu:` and `nostr:` as the established URI-style prefixes. A bare
UUID would fall through to `UNKNOWN`, and is the shape most likely to collide with
something else later. `voucher:<uuid>` follows the existing house style and routes
unambiguously.

### External dependency (outside this spec's scope)

Recognising the new payload requires a change in **imani-apps**, not in
`cashu-voucher`:

- a `QrType.VOUCHER_REDEEM` entry with pattern `/^voucher:/i` in
  `packages/imani-qr/src/detector/patterns.ts` and its `TYPE_DESCRIPTIONS`,
- a corresponding handler registered alongside `TokenHandler` in
  `packages/imani-qr/src/handlers/HandlerRegistry.ts`.

Until that lands, passes render correctly and the QR is readable by any generic
scanner, but the imani wallet's own scanner will classify it as `UNKNOWN`. Tracked
separately; it does not block this module.

## Validation and errors

`toPass` throws `IllegalArgumentException` when:

- `unit` is not a valid ISO 4217 code,
- `faceValue` is negative.

It does **not** throw for absent or malformed `merchant_metadata`, absent `memo`,
absent `expiresAt`, or a `faceDecimals` that disagrees with the currency's default
fraction digits — all have defined fallbacks or, in the last case, a `WARN` and the
issuer's own value.

The mapper rejects only what makes rendering impossible. Everything the stack already
accepts as a valid voucher renders, because a display concern must not be able to
withhold a card the ledger considers good.

`toPass` does not verify the issuer signature. `SignedVoucher` already guarantees a
signature and public key are present at construction, and verification is a separate
concern (`VoucherValidator`, `MerchantVerificationService`) that a display mapper must
not silently duplicate.

## Testing

Unit tests only — the mapper is a pure function with no I/O to stub.

1. **Golden pass.json** — a fully populated fiat voucher maps to an expected JSON
   document, compared as a parsed tree rather than a string.
2. **Currency scaling** — `(5000, 2, "eur")` → `50.00`; `(5000, 0, "jpy")` → `5000`;
   `(5000, 3, "kwd")` → `5.000`.
3. **Currency rejection** — an unknown unit throws; a negative `faceValue` throws.
4. **Decimals mismatch** — `(5000, 2, "jpy")` renders `50.00` and does not throw,
   trusting `face_decimals` over the currency default.
5. **Branding fallbacks** — absent, empty, and malformed `merchant_metadata` each
   yield defaults without throwing.
6. **Optional fields** — absent `expiresAt` omits both `expirationDate` and the
   auxiliary field; absent `memo` falls back.
7. **Voided** — `REDEEMED` and `REVOKED` set `voided: true`; `ISSUED` does not.
8. **Barcode** — `message` is `voucher:` + the UUID, `altText` is the bare UUID
   without the prefix, and exactly one entry is emitted under `barcodes`.

## Assumptions to confirm

1. **The `merchant_metadata` key names above** (`name`, `logoUrl`, `bgColor`,
   `fgColor`) were proposed during design and adopted as defaults rather than
   explicitly ratified. If imani-merchant already emits a different shape, the mapper
   follows it. Cheap to change; does not block implementation.

## Consequences

- The mapper is a pure function, so partial redemption needs no pass lifecycle at all
  — re-render and the card is current.
- No bearer secret is placed in any rendered artifact; the pass carries only a voucher
  identifier, and the token stays wherever the wallet keeps tokens.
- Adopting `pass.json` means future pass types start from Apple's field semantics for
  `coupon`, `eventTicket`, `boardingPass`, and `generic` rather than a fresh design.
- If Apple Wallet ever becomes a target, the gap is a signing step and an asset
  bundle over an unchanged mapper.
