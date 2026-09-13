# Nearby sync protocol (v2)

This describes what two SplitFree phones do when they meet over Google Nearby Connections, what
"up to date" means, and what the design deliberately does not promise. The implementation lives in
`app/src/main/java/com/splitfree/sync/nearby/`; the previous audit is in
the local-only `docs/nearby-audit-2026-09-12/` (not tracked).

## Components

```
NearbySyncScreen
    │ observes
NearbySyncViewModel            discovery / advertising, status text only
    │
NearbySessionCoordinator       one consumer of transport callbacks; owns sessions; forwards
    ├── PeerSession            per-connection state machine: auth → scope → reconcile
    ├── ReconciliationStore    Room-backed inventory, ingest (via EventProcessor), deliveries
    └── NearbyTransport        NearbySync (Google Nearby Connections, P2P_CLUSTER)
```

Everything a session does runs behind one lock in the coordinator, including timers. A session's
coroutines are cancelled on close, so a timeout from an old connection cannot act on a new session
that reuses the same endpoint id.

Nearby ingress and relay ingress use the same `EventProcessor`; nearby passes
`IngestionContext.RECONCILIATION`, which lifts the age gate and the in-memory rate counters (the
session bounds admission instead) while keeping every signature, membership, decryption and payload
check.

## Framing

Every payload is `[0x7F][type][UTF-8 JSON]`. `0x7F` is outside the legacy `0x01..0x04` range so a v1
peer is recognised and closed with `unsupported_version` instead of being half-understood. Frames are
at most 16 KiB; inventories are paged (≤ 256 entries and ≤ 16 KiB per page); records are chunked
(≤ 12,000 characters per chunk, ≤ 64 chunks). These limits are far inside the pinned
`ConnectionsClient.MAX_BYTES_DATA_SIZE` (1,047,552 bytes) and are asserted by tests.

| Type | Message | Purpose |
|---|---|---|
| 0x01 | `Hello` | version, capabilities, pubkey, fresh nonce, Nearby connection role |
| 0x02 | `Auth` | Schnorr signature over the channel-bound transcript |
| 0x03 | `OpenGroup` | the one group this session may sync (+ optional signed self-join) |
| 0x04 | `OpenGroupResult` | ok, or `unauthorized` (unknown and unauthorized are indistinguishable) |
| 0x05 | `InventoryPage` | one page of a snapshot: event ids and envelope ids with recipients |
| 0x06 | `Want` | ids the consumer wants from that snapshot (≤ 16 outstanding) |
| 0x07 | `Record` | one chunk of a record; `parts = 0` means "no longer available" |
| 0x08 | `Result` | `APPLIED`, `ALREADY_APPLIED`, `DEFERRED`, `REJECTED`, `BUSY`, `CARRIED` |
| 0x09 | `ReconcileResult` | consumer finished the snapshot; counts and unresolved |
| 0x0A | `Close` | explicit terminal reason |

## Authentication

Both phones send `Hello`. The initiator is the outgoing side of the Nearby connection; if both sides
report the same role (simultaneous connection requests) the lexically smaller pubkey initiates. Both
then sign:

```
SHA-256( "splitfree-nearby-auth-v2" || 0x00 || version || role
       || initiatorPubkey || responderPubkey || initiatorNonce || responderNonce
       || SHA-256(rawAuthenticationToken) || SHA-256(sorted capabilities) )
```

`rawAuthenticationToken` is Nearby's per-connection token, identical on both ends. A signature
lifted from one connection therefore fails on any other, which is what defeats a relay between two
separate connections. Nonces are generated once per session; duplicate `Hello`/`Auth` frames are
idempotent; a conflicting one closes the session. Nothing about any group is sent before both
signatures verify, and `OpenGroup` is only sent if the peer is a member (or creator) of the selected
group. A freshly invited member may attach its signed self-join `group_meta` so it is not blocked by
a phone that has not yet seen its join.

## Reconciliation

After the group opens the protocol is symmetric. Each side advertises its inventory as a numbered
snapshot; the other side asks for what it lacks, applies each record and answers with a `Result`, then
sends `ReconcileResult`. Inventories list:

- `e` entries: ledger and control events with a third-party-verifiable signature, applied or still
  pending (a rotation this phone cannot apply yet is exactly what the next phone may be waiting
  for). Rows that only hold a gift-wrap rumor (`seal:` signature) are not offered, because a peer
  could not verify them; rows whose effect was permanently rejected here are not offered either.
- `d` entries: recipient-encrypted envelopes (gift wraps, per-member key rotations) this phone holds,
  with the recipient pubkey and, when known, the inner event id.

Key rotations and other control records are advertised first. A record refused as undecryptable
before its key arrived is requested once more in the same snapshot after a control record applies,
and rows deferred on a missing epoch are re-driven (`EventProcessor.retryDeferred`).

A consumer wants: unknown verifiable events; a signed original for an event it only holds as a rumor
(the row is upgraded in place, never duplicated); envelopes addressed to itself; and envelopes for
other current members. Courier storage is bounded (512 envelopes / 4 MiB per group, 30-day retention
for carried, 90 for authored) by evicting the oldest carried envelope when a new one arrives, never by
refusing it: a full cache must not stop a key from propagating.

Acknowledged baseline: the provider only treats an id as known to the peer once a `ReconcileResult`
for a snapshot that carried it has come back. Every later snapshot resends whatever is still
unacknowledged, so a page lost in transit (a failed send) is simply sent again after the 30 s
silence; it can never turn into an empty delta that both sides mistake for "done". A page that
arrives out of order is ignored, not treated as a violation, for the same reason.

Completion: a session is **up to date** only when both snapshots are consumed, every wanted record
has a terminal `Result`, nothing was `REJECTED`, `BUSY` or unresolved, and **neither side holds
pending rows**. The pending count is read from durable storage (`applyState = PENDING`), so work left
over from an earlier session or a process restart counts, and is re-driven when the screen opens and
when a group opens; the peer's count travels in its `ReconcileResult`. Either side pending gives
**waiting for a key or earlier update** on both. Failures give **incomplete** with a count. A dropped
transport gives **interrupted** with the durable progress kept: a reconnect authenticates afresh and
only transfers what is still missing. `CARRIED` is never shown as delivery to the recipient.

Retries: a record with no terminal result after 30 s of silence is re-requested once, then counted
unresolved. A lost `ReconcileResult` or page is recovered by re-advertising the unacknowledged delta
(twice at most). Duplicates are harmless everywhere.

## Forwarding

The author of a gift-wrapped event keeps every per-recipient envelope (`deliveries` table). A phone
that receives an envelope for another member stores it opaque and offers it onward; the recipient
opens it and verifies the original author's seal. The courier never sees the content and is never
trusted as the author. Per-member key rotation events carry a `p` tag; a phone that cannot decrypt
one carries it the same way, so a removed member's rotation reaches everyone without exposing the new
key under the old shared key.

An envelope addressed to this phone is opened by the same `EventProcessor` as relay traffic, with the
session's group as the expected group: a wrap whose inner event belongs to another group the phone
happens to know is rejected before anything is read or written, so a session authenticated for G
cannot mutate H.

While the Nearby screen is open, any change to the offerable inventory (a ledger row applied from a
peer or a relay, or an envelope that appeared without a new row, such as history re-wrapped for a new
member) is re-advertised to every other connected peer, so an A–B–C chain propagates without a second
button press. Records are identified by immutable ids, and a peer is never offered an id it has
already acknowledged, so cycles converge.

## Ledger rules that changed with this protocol

- **Expense identity** is `(group, original author, uuid)`. A correction or deletion only affects the
  original by the same author; two authors using one uuid are two expenses; editing resolves to the
  editor's own entry.
- **Three orderings for group state**, each with its own clock, never mixed:
  - *Rotations* are ordered by epoch. Applying one installs the epoch and the roster it defines in a
    single statement and never touches the creator's metadata watermark.
  - *Creator metas* are ordered by `(created_at, event id)`. A meta sealed under an epoch older than
    the group's current one predates a rotation: it still updates name, relays and description, but
    may not touch the roster. So a rotation delivered late cannot block a newer rename, and a stale
    pre-rotation meta cannot re-add the removed member, in any arrival order.
  - *A member's own join and display name* are ordered by that member's `(created_at, event id)`
    clock. A creator meta older than a member's rename keeps the member's name. A self-join is any
    meta sealed under the current key whose roster adds nobody but its author, so two members
    joining concurrently, or a joiner unaware of a recent rename, are admitted in either order.
  - Key revocation (an identity swap) has no epoch and rides the creator watermark, raised to the
    revocation's own clock rather than to the wall clock.
- **Rotation is resumable.** The key for epoch N+1 is generated once and immutable; an interrupted
  removal reuses it, so every member ends up with the same key however many attempts it took.
  Envelopes are published (durably queued) before the local transition; at start-up the creator
  recognises a stored key for N+1 with published envelopes and finishes the transition.
- **Stored vs applied**: control events are stored pending until their effect lands (`applyState`).
  A missing dependency (epoch gap, a member whose join has not arrived) or a transient failure
  keeps the row pending and retried; an effect that can never apply on this device (key material it
  cannot open, conflicting epoch key) parks the row as failed. A receipt of `APPLIED` for a control
  record therefore means its durable effect landed. Only applied rows enter balances.
- **Historical authors** are admitted during reconciliation if the record decrypts under an epoch
  older than the one that removed them.

## Platform

- Android 12 and below: fine and coarse location are requested in the same prompt.
- Android 17 (target 37): `ACCESS_LOCAL_NETWORK` is declared and requested for the Wi-Fi LAN path;
  Nearby still uses Bluetooth if it is denied.
- Nearby sync is foreground only. Leaving the screen, or the activity stopping (Home, lock screen,
  app switch), closes every session and stops advertising; durable records and envelopes remain for
  the next session. `ON_PAUSE` is deliberately not used: the system consent dialog Nearby shows on a
  first connection pauses the activity mid-handshake.

## Known limits

- A phone holding only an unsigned rumor cannot manufacture the envelope another member is missing;
  recovery needs the author or a surviving original envelope. Such records stay visibly unresolved.
- A removed member holding an old key could backdate an event under that key. Admission of
  pre-removal history relies on the epoch, not on a creator-signed checkpoint; that checkpoint is the
  documented follow-up before any claim of tamper-proof historical membership.
- The Nearby SDK reports diagnostics to Google under the device's Usage & diagnostics setting.
- JVM tests cover the protocol and the Room-backed pipeline end to end over an in-memory transport;
  radio behaviour (transport selection, permission prompts, OEM differences) still needs the
  three-phone matrix in the audit report before the mesh claim is made in release notes.
