# Voucher ledger v2: encrypted, pseudonymous voucher status

Status: implemented in cashu-voucher 0.18.0 (imani-gateway-customer#310, step 2).
Shared by Java (cashu-voucher, imani-gateway-customer, imani-gateway-portal) and
TypeScript (imani-wallet). The bytes below are a wire contract: both sides replay
`cashu-voucher-domain/src/test/resources/voucher-ledger-v2-vectors.json`.

## Why

Ledger v1 published every voucher in plaintext (kind 30078, `d = voucher:<voucherId>`),
naming the stall by its real key, with its memo, face value and expiry, to public relays
(imani-gateway-customer#310). v2 keeps a public ledger, but:

- everything about the voucher, its status included, is encrypted;
- the event is signed by a key used for that one voucher only, so events cannot be
  grouped by stall or by publisher;
- the address (`d`) is unlinkable to the voucher, its id, its stall or any proof.

The same event is the encrypted relay copy of the voucher that cashu-voucher#64 asks for.

## Who can read

Anyone who knows the voucher's **reference** `ref`. That is:

- every holder of a proof (the voucher id and issuer are inside every proof);
- the issuing stall (it knows its own voucher ids);
- the portal (it knows its merchants' voucher ids);
- an auditor the stall gives `ref` to, one voucher at a time.

The relay and the public see ciphertext of a bucketed size at an unlinkable address.

**Consequence: a voucher id is the key to its ledger entry.** Voucher ids must never be
published in plaintext (relay tags, public URLs, logs shipped off-host).

## Derivations

All hashes are SHA-256. `HMAC(key, msg)` is HMAC-SHA256. `||` is byte concatenation.
`hex` is lowercase. `US` is the single byte 0x1f.

| Name | Definition |
|---|---|
| `ref` (32 bytes) | `SHA256(UTF8("imani-voucher-ref-v1:" + issuerId + ":" + voucherId))` |
| `k` (32 bytes) | `HMAC(UTF8("imani-voucher-key-v1"), ref)` |
| `d` (64 hex) | `hex(SHA256(UTF8("imani-voucher-locator-v1") \|\| ref))` |
| event key `sk_v` | `HMAC(root, UTF8("imani-voucher-ledger-v1") \|\| ref)`, as a secp256k1 scalar |
| `root` (stall) | `ledgerSk = SHA256(UTF8("imani-ledger-key-v1:") \|\| stallSecretKey)` |
| `root` (platform) | the same formula over the platform voucher-signing identity's secret key |

- `issuerId` is lowercased (ASCII only) and must be 1..256 visible ASCII characters
  (0x21..0x7e). `voucherId` must be a UUID and is written in its canonical lowercase form.
  Anything else is refused, so Java and TypeScript can never derive different `ref`s.
- `k` is `HKDF-Extract(salt = "imani-voucher-key-v1", ikm = ref)`, the same construction
  NIP-44 v2 uses for its conversation key. It is used directly as a NIP-44 v2 conversation
  key. NIP-44 v2 is used exactly as specified (version byte 2, random 32-byte nonce,
  ChaCha20, HMAC-SHA256, its own padding); only the conversation key is ours. Java and
  TypeScript implementations must pass the official NIP-44 test vectors
  (`nip44.vectors.json`, paulmillr/nip44).
- `sk_v` must be in `[1, n-1]`; otherwise derivation fails (probability about 2^-128).
- `root` is only ever an HMAC key. It never signs a voucher-ledger event: signing with
  `ledgerSk` would group every voucher under the stall's public `ledgerPub`.
- A key derived from `ref` alone would let any holder overwrite the event, so `sk_v` comes
  from a secret. Forged events at the same `d` under other keys are ignored by readers
  because authority comes from the inner signature, below.

## The event

| Field | Value |
|---|---|
| `kind` | 30078 (addressable, NIP-01) |
| `pubkey` | x-only public key of `sk_v` |
| `tags` | `[["d", d]]`, plus `["expiration", E]` (NIP-40) when the voucher expires |
| `content` | `NIP44v2.encrypt(envelopeJson, conversationKey = k)` |
| `created_at` | publish time |

`E = ceil(expiresAt / 86400) * 86400 + 30 * 86400`: the voucher's expiry rounded up to a
UTC day, plus a 30-day grace, so relays drop the event once the voucher is dead and the
tag reveals the expiry only to the day. A voucher with no expiry gets no tag.

No other tag. No `p`, no amount, no unit, no status in clear.

### Envelope (the NIP-44 plaintext)

```json
{"body":"<string>","sig":"<128 hex>","signer":"<64 hex>","pad":"<'0' * n>"}
```

- `body` is a JSON document **carried as a string**, so its exact bytes are what is signed
  and no JSON canonicalisation is needed in either language.
- `sig` is a BIP-340 signature by `signer` (x-only) over
  `SHA256(UTF8("imani-voucher-ledger-v2") || US || UTF8(d) || US || UTF8(hex(SHA256(UTF8(body)))))`.
- `pad` is zeros, so the serialised envelope is exactly the smallest bucket in
  `1024, 2048, 4096, 8192, 16384, 32768, 65535` bytes (UTF-8) that fits. An envelope that
  does not fit 65535 bytes is refused. The envelope is serialised with keys in the order
  above and no whitespace, so the length is predictable.
- **JSON writing rule** (envelope and body), so Java and TypeScript writers produce the same
  bytes: no whitespace; in strings escape exactly `"` as `\"`, `\` as `\\`, U+0008 `\b`,
  U+0009 `\t`, U+000A `\n`, U+000C `\f`, U+000D `\r`, and every other U+0000..U+001F as
  `\u00xx` with lowercase hex; write everything else (including `/`, U+007F and non-ASCII)
  as itself in UTF-8. A string containing a lone surrogate is refused. This is what
  `JSON.stringify` produces for well-formed strings.
- **Readers** parse with a strict JSON parser that refuses duplicate keys, and verify `sig`
  over the `body` string exactly as received, so reading never depends on the writing rule.

### Body

```json
{"v":2,"ref":"<64 hex>","status":"ISSUED","seq":1,
 "voucher":{"voucherId":"…","issuerId":"…","issuerPublicKey":"…","unit":"…",
            "faceValue":1000,"faceDecimals":2,"expiresAt":1790000000,"memo":"…",
            "backingStrategy":"MINIMAL","issuanceRatio":"0.5",
            "merchantMetadata":"…","issuanceWarrant":"…"},
 "doc":"<hex of the voucher document>"}
```

- `v` is 2. `ref` is `hex(ref)`.
- `status` is `ISSUED`, `REDEEMED` or `REVOKED`. `EXPIRED` is never published: anyone holding
  `ref` can read the expiry.
- `seq` is 1 for `ISSUED` and 2 for `REDEEMED` and `REVOKED`.
- Keys are written in exactly the order shown. Unknown keys are refused by readers.
- `voucher.voucherId` and `voucher.issuerId` are required, and are written exactly as they
  went into `ref` (lowercase). The other fields are optional and omitted when unknown or
  empty. `faceValue`, `faceDecimals` and `expiresAt` are JSON integers (no sign for
  non-negative values, no exponent) within the JavaScript safe range, `|n| <= 2^53 - 1`,
  so every reader holds them exactly; writers and readers refuse anything outside it.
  `issuanceRatio` is a string in the canonical spelling
  the voucher signs (`VoucherSecret.canonicalIssuanceRatio`: `"2"`, `"0.5"`), so no reader
  has to format a floating-point number. `merchantMetadata` and `issuanceWarrant` are the
  JSON strings the voucher carries, verbatim.
- `doc` is optional: the voucher document of cashu-voucher#64 (for an unlocked voucher, its
  signed CBOR blob), hex. It is what lets a wallet restore a by-reference token.
- Terminal credentials (`merchantMetadata.terminal == true`) are never published.

## Publishers

| Status | Published by | `sk_v` root | Inner `signer` |
|---|---|---|---|
| `ISSUED` | imani-gateway-customer, at every issuance (stall Sell included) | platform | the platform voucher-signing key (`voucher.issuerPublicKey`) |
| `REDEEMED` | the stall's device (imani-wallet), from the same step as its kind-7377 attestation | stall | the stall's key (`voucher.issuerId`) |
| `REVOKED` | not in this version (there is no stall-side revoke yet) | stall | the stall's key |

Republishing is idempotent: same address, same plaintext.

## Reading

1. Fetch `{"kinds":[30078], "#d":[d]}`. Several events may come back (one per publisher).
2. For each event: decrypt with `k` (an event that fails to decrypt is ignored), parse the
   envelope, recompute the statement and verify `sig` against `signer`.
3. Accept the event only if all hold:
   - the event's own NIP-01 id and signature verify, and the event is kind 30078 with exactly
     one `d` tag equal to `d`;
   - `body.v == 2`, `body.ref == hex(ref)`, and
     `ref(body.voucher.issuerId, body.voucher.voucherId) == ref`;
   - `body.seq` matches `body.status` (1 for `ISSUED`, 2 otherwise);
   - `signer` is authorised for `body.status`:
     - `ISSUED`: `signer == issuerId` or `signer` is in the reader's trusted issuer keys;
     - `REDEEMED`, `REVOKED`: `signer == issuerId` only;
   - when `doc` is present it hashes, parses and verifies as the voucher's document
     (otherwise only `doc` is dropped, not the event).
4. The status is the accepted event with the highest `seq`; on a tie, `REVOKED` beats
   `REDEEMED` beats `ISSUED`. Voucher fields come from the accepted `ISSUED` event when there
   is one.

Anyone who knows `ref` can publish many events at the same `d` under throwaway outer keys.
Each is refused by the inner signature, but each costs the reader a fetch, so readers must not
rely on a small relay `limit` (a flood could push the genuine events out of the result).

Terminal detection fails closed: non-empty `merchantMetadata` that is not one strictly
parseable JSON object, or whose `terminal` is `true` or `"true"`, is never published.

`issuerId` is x-only hex for stalls. A reader that cannot parse it as a key accepts only
trusted-key signers. The event's outer author (`sk_v`) is never trusted for anything: it
only makes the address replaceable by its publisher.

## Clarifications pinned by the vectors

These follow from the rules above. They are written out because two implementations could
otherwise read them differently.

- `voucherId` is accepted only in the 8-4-4-4-12 hex layout, either case. Forms Java's
  `UUID.fromString` would also accept (no hyphens, short groups, braces) are refused.
- A reader refuses a body whose `voucher.voucherId` or `voucher.issuerId` is not already in its
  normalised (lowercase) form. Bodies are written that way, so this only affects forgeries.
- Readers refuse a JSON value of the wrong type (`faceValue` as a string, a fractional or
  out-of-int64 number, `doc` not a string) and refuse trailing data after the JSON object.
- A `signer` or `sig` that is not lowercase hex of the exact length, or a `pad` containing
  anything but `0`, makes the envelope malformed.
- `doc` is verified as an unlocked-voucher CBOR blob: it must decode, name the same `voucherId`
  and `issuerId`, and its issuer signature must verify. Anything else drops `doc` only.
- Locked (P2PK) vouchers carry no `doc` and no `issuanceWarrant`.
- When several accepted `ISSUED` events exist, voucher fields come from the first one that
  carries a verified `doc`, else the first `ISSUED` event.
- The reason names in the vector file (`UNAUTHORISED_SIGNER`, `SEQ_MISMATCH`, ...) are the
  first check that fails, in this order: address, decrypt, envelope shape (duplicate and unknown
  keys included), inner signature, body shape, version, ref, status, seq, signer. A TypeScript
  reader needs only to refuse the same events; matching the reason is recommended.

## Known limit of content-derived encryption

Anyone who can guess the whole input of `ref` can confirm the guess. `ref` covers the
voucher id, a random UUID (122 bits), so it is not guessable. Never derive a key this way
from guessable data.
