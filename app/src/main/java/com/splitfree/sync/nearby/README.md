# Nearby sync protocol

SplitFree's foreground, group-scoped synchronization over Google Nearby Connections.
This guide describes the implemented **v3** protocol, including recovery behavior and limits.

[Project overview](../../../../../../../../README.md) · [Nostr and relay sync](../../data/nostr/README.md) · [Privacy](../../../../../../../../PRIVACY.md)

## At a glance

| Property | Behavior |
| --- | --- |
| Transport | Google Nearby Connections, `P2P_CLUSTER`; the SDK selects Bluetooth, BLE, or Wi-Fi. |
| Scope | One authorized group per peer session. |
| Authentication | Mutual Schnorr signatures; channel binding uses the SDK token when supplied. See the missing-token limit below. |
| Data | Signed ledger/control events and recipient-encrypted envelopes. |
| Recovery | Reconnect, authenticate again, and reconcile against durable stored records. |
| Lifecycle | Runs while the Nearby screen is open and the activity is foregrounded. |
| Completion | The current exchange has no reported missing, failed, or pending work; see [completion limits](#known-limits). |

## Contents

- [Components](#components)
- [Framing](#framing)
- [Authentication](#authentication)
- [Reconciliation](#reconciliation)
- [Completion and retries](#completion-and-retries)
- [Forwarding](#forwarding)
- [Ledger rules that changed with this protocol](#ledger-rules-that-changed-with-this-protocol)
- [Platform](#platform)
- [Known limits](#known-limits)
- [Testing](#testing)

## Components

```text
NearbySyncScreen / NearbySyncViewModel
    │ discovery, advertising, and observable status
    ▼
NearbySessionCoordinator
    ├── PeerSession               auth → group scope → reconciliation
    ├── ReconciliationStore       inventory, ingestion, retained records
    └── NearbyTransport           Google Nearby Connections adapter
```

| Component | Responsibility |
| --- | --- |
| [NearbySessionCoordinator](NearbySessionCoordinator.kt) | Owns peer sessions, serializes callbacks/timers, and observes durable inventory changes. |
| [PeerSession](PeerSession.kt) | Implements authentication, group authorization, snapshots, receipts, retries, and session status. |
| [NearbyWire](NearbyWire.kt) | Encodes/decodes frames and defines message types and bounds. |
| [NearbyAuth](NearbyAuth.kt) | Builds and verifies role-specific authentication transcripts. |
| [ReconciliationStore](ReconciliationStore.kt) | Defines group-scoped inventory and ingestion operations. |
| [RoomReconciliationStore](RoomReconciliationStore.kt) | Implements storage, evidence upgrades, dependency recovery, and envelope carriage. |
| [NearbyTransport](NearbyTransport.kt) / [NearbySync](../../data/ble/NearbySync.kt) | Adapts transport callbacks and byte payloads. |
| [NearbySessionState](NearbySessionState.kt) | Defines observer-facing phases and transfer counters. |

**Concurrency and ingestion**

- Session work and watchdog callbacks run under one coordinator mutex.
- Closing a session cancels its coroutines; stale timers cannot act on a replacement session.
- Relay and Nearby ingress share [`EventProcessor`](../event/EventProcessor.kt).
- Nearby uses `IngestionContext.RECONCILIATION`: historical age and in-memory rate gates are relaxed; signature, membership, decryption, and payload checks remain.

## Framing

```text
[0x7F][message type: 1 byte][UTF-8 JSON body]
```

- `0x7F` distinguishes current framing from legacy v1 type prefixes `0x01..0x04`.
- Recognized legacy peers close with `unsupported_version`.
- `Hello.v` must match **3**; v2 framing alone does not establish compatibility.
- Decoding validates serialization; `PeerSession` checks field values and message ordering.

### Bounds

| Limit | Value | Enforced by |
| --- | --- | --- |
| Incoming encoded frame | 16 KiB, including header | `NearbyWire.decode` |
| Inventory page | 256 entries and 16 KiB encoded | `NearbyWire.paginate` |
| Inventory snapshot | 200,000 entries | `PeerSession` |
| Record chunk | 12,000 characters | `NearbyWire.chunk` and receive validation |
| Chunks per record | 64 | Chunking and receive validation |
| Requested records in flight | 16 per peer | Request batching / `Want` validation |
| Partially assembled records | 4 per peer | Receive validation, then `BUSY` |
| Dependency-shaped rejections retained | 4,096 per session | `PeerSession` retry collection |

> **Bytes and characters differ:** chunking limits characters. UTF-8 encoding and JSON escaping can increase byte size; `encode` and `chunk` do not themselves enforce the 16 KiB encoded-frame limit.

### Messages

| Type | Message | Purpose |
| --- | --- | --- |
| `0x01` | `Hello` | Protocol version, capabilities, public key, nonce, and connection role. |
| `0x02` | `Auth` | Schnorr signature over the role-specific transcript. |
| `0x03` | `OpenGroup` | Requested group and optional signed self-join. |
| `0x04` | `OpenGroupResult` | Accepted or `unauthorized`; unknown groups are not distinguished from unauthorized ones. |
| `0x05` | `InventoryPage` | Numbered inventory page with event, delivery, and held-record entries; includes pending count. |
| `0x06` | `Want` | IDs requested from the advertised snapshot. |
| `0x07` | `Record` | A record chunk; `parts = 0` means unavailable. |
| `0x08` | `Result` | Per-record processing receipt. |
| `0x09` | `ReconcileResult` | Snapshot completion report, including unresolved, pending, and held counts. |
| `0x0A` | `Close` | Terminal reason. |

## Authentication

### Handshake

1. Both phones send `Hello` with a fresh 32-byte nonce.
2. The outgoing connection side becomes initiator. If roles match, the lexically smaller public key initiates.
3. Both sides agree on the intersection of offered capabilities.
4. Each signs the transcript for its own role and verifies the peer's signature.
5. The initiator requests one group; membership checks gate group opening.

### Transcript

```text
SHA-256(
    UTF8("splitfree-nearby-auth-v2") || 0x00
    || version:1 || signer-role:1
    || initiator-pubkey:32 || responder-pubkey:32
    || initiator-nonce:32 || responder-nonce:32
    || SHA-256(rawAuthenticationToken)
    || SHA-256(UTF8(sorted-capabilities-joined-by-commas))
)
```

- The domain string retains `v2`; the version byte is the current protocol version, **3**.
- Signer roles are `0x01` for initiator and `0x02` for responder.
- Channel-token binding prevents a signature from one connection authenticating a different connection.
- `NearbySync` forwards the token captured at connection initiation. Its callback model permits a missing token.
- `PeerSession` does not reject a missing token solely for absence; `NearbyAuth` hashes empty bytes instead. Identity proof remains, but **channel binding is absent**.
- This is a source-level fallback, not evidence that the SDK normally omits tokens or that a device exploit has been reproduced.
- Identical duplicate `Hello` / `Auth` messages are idempotent; conflicting ones close the session.
- No group details are sent before mutual authentication.

### Group authorization

- `OpenGroup` is sent only to a known member or creator of the selected group.
- A newly invited member may attach a signed self-join `group_meta` so the other phone can admit it before checking membership.
- Group-scoped messages recheck local identity and both memberships while the session is open.
- A group-scoped session cannot apply a received envelope to a different group the phone happens to know.

## Reconciliation

Both peers act as provider and consumer after the group opens.

```text
InventoryPage → Want → Record → Result → ReconcileResult
     ↑                                       │
     └──── changed / unacknowledged data ────┘
```

### Inventory entry kinds

| Kind | Content | How the consumer uses it |
| --- | --- | --- |
| `e` | Ledger/control event with a third-party-verifiable signature; applied or pending, but not failed. | Requests unknown events or a signed original that upgrades a rumor-only row. |
| `d` | Available recipient-encrypted envelope, with recipient and optional inner-event ID. | Requests envelopes for itself or eligible carriage for current members. |
| `h` | Applied record held only as a gift-wrap rumor (`seal:` evidence). | Counts missing records, but never requests or serves these entries. |

**Ordering and evidence**

- Control events and key-rotation envelopes are advertised before ordinary ledger data.
- A signed original upgrades a rumor row in place; it does not duplicate the ledger effect.
- Evidence is acknowledged by **entry kind + ID**, so upgrading a held rumor to a signed event creates a new offerable entry.
- Rumor-only `group_meta` and `key_revocation` records participate in held-record accounting.
- Rumor-only per-recipient `key_rotation` records are excluded: another recipient's copy has a different event ID.

### Dependency recovery

| Missing input | Recovery |
| --- | --- |
| Original expense for a correction/delete | Store pending and retry through `EventProcessor.retryDeferred` when the original arrives. |
| Group-key epoch or author join | Retry after a control record applies or local storage changes. |
| Previously deferred durable work | Retry on activation, group opening, relevant changes, and application recovery. |
| Malformed or unauthorized record | Reject; do not classify as a dependency retry. |

- Dependency-shaped rejections are retained for the session, within the bounded retry collection.
- Retries can request an ID from the provider's current inventory even if it was previously acknowledged.
- Pending rows stay out of balances until their effects apply.

### Snapshot acknowledgments

- A provider advances its acknowledged baseline only after `ReconcileResult` for the snapshot.
- Later snapshots include everything still unacknowledged.
- Out-of-order pages are ignored; missing inventory can be re-advertised.
- A lost page must not become an empty delta that falsely implies completion.

## Completion and retries

### Per-record receipts

| Result | Meaning |
| --- | --- |
| `APPLIED` | The record's local effect applied. For control events, durable state changed successfully. |
| `ALREADY_APPLIED` | The effect was already present; evidence may have been upgraded. |
| `DEFERRED` | Retained pending a dependency or retryable application step. |
| `REJECTED` | Invalid, unauthorized, out-of-scope, or otherwise refused. Some dependency-shaped refusals may be retried internally. |
| `BUSY` | A bounded resource limit prevented accepting the record. |
| `CARRIED` | Envelope retained for another member. **Not proof of recipient delivery or application.** |

### Observer-facing phases

| Phase | Meaning |
| --- | --- |
| `AUTHENTICATING` / `OPENING_GROUP` | Establishing identity and authorized scope. |
| `COMPARING` / `TRANSFERRING` | Inventories or requested records are still moving. |
| `WAITING_DEPENDENCY` | Exchange finished without reported failures, but either side has durable pending work. |
| `INCOMPLETE` | Reconciliation found rejected, busy, unresolved, or missing held records, or local pending state is unreadable. |
| `UP_TO_DATE` | Both directions finished, no transfer remains, and no reported failure, pending work, or held-record gap remains. |
| `INTERRUPTED` | Transport dropped or timed out; durable records remain for a later session. |
| `UNSUPPORTED_PEER` / `AUTH_FAILED` / `UNAUTHORIZED` / `CLOSED` | Terminal protocol, authentication, authorization, or lifecycle outcome. |

- `UP_TO_DATE`, `WAITING_DEPENDENCY`, and `INCOMPLETE` keep the connection open for later changes.
- Pending counts come from durable storage, including work retained across sessions.
- An unreadable pending count fails closed: advertise at least one pending item and report at least one unresolved item.
- Readability changes trigger an updated report even if the last readable count was zero.
- Failure status takes precedence over waiting for dependencies once the exchange finishes.
- Counters describe transfer activity; retries may count a record more than once.

### Retry timing

| Condition | Policy |
| --- | --- |
| Authentication or group-open inactivity | 10-second idle limit. |
| Requested record stalls | After 30 seconds of inactivity, retry in-flight requests once per snapshot; then count unresolved. |
| Missing page or snapshot report | Re-advertise unacknowledged inventory at most twice. |
| Provider awaiting record receipts | Allows the consumer's retry window before replacing its snapshot. |
| Watchdog | Checks every 2.5 seconds. |

## Forwarding

### Envelope path

1. An author keeps per-recipient envelopes in `deliveries`.
2. An intermediate member retains an envelope as opaque ciphertext.
3. A later session offers it to the recipient or another eligible carrier.
4. The recipient opens it and verifies the original author's seal or signed key-rotation event.

- A carrier is not trusted as the author and cannot decrypt another recipient's envelope.
- Per-member key rotations carry a `p` recipient tag and can travel the same way.
- Both outer routing and decrypted inner group scope are checked where applicable.
- Room inventory revisions trigger re-advertisement across open peers, including an A-to-B-to-C chain.

### Storage limits

| Resource | Policy |
| --- | --- |
| Available carried envelopes | 512 per group, up to 4 MiB total. |
| One carried envelope | At most 64 KiB UTF-8 JSON. |
| Capacity recovery | Evict oldest available carried envelopes by local receipt time. |
| Eviction work per insertion | At most 16 evictions, then `BUSY` if still over quota. |
| Carried retention | 30 days by local receipt time, removed when pruning runs. |
| Authored retention | 90 days by local receipt time, removed when pruning runs. |

- These count/byte quotas apply to **available carried** envelopes, not all authored data.
- A full cache can evict older carriage; it does not guarantee eventual delivery.
- SQL revision triggers introduced in Room schema v3 cover events, deliveries, and group state; later schema versions retain them.
- Evidence upgrades, pending-state changes, and delivery-only writes advance the revision; no-op writes and sync timestamps do not.

## Ledger rules that changed with this protocol

These rules are shared with relay ingestion and durable storage, not implemented solely by the transport.

### Expense identity

- Identity is `(group, original author, uuid)`.
- Corrections and deletions affect only the same author's original expense.
- Two authors using one UUID create two distinct expenses.
- Lists, detail sheets, edit routes, drafts, deletion confirmation, and balance exclusions retain this identity.
- Missing or non-owned edit identities fail closed; there is no fallback to another author's expense.

### Group-state ordering

| Operation | Ordering and effect |
| --- | --- |
| **Rotation** | Ordered by epoch; updates epoch and roster atomically without advancing the creator-metadata watermark. |
| **Creator metadata** | Ordered by `(created_at, event ID)`; metadata from an old key epoch can update name, relays, and description, but not restore a stale roster. |
| **Member self-join / name** | Uses that member's event clock. A self-join adds only its author under the current key; concurrent joins and newer names are preserved. |
| **Identity revocation** | Has no independent epoch; replaces identity against live state, preserves concurrent membership/name changes, and advances the metadata watermark. |
| **Revocation tombstone** | Blocks the old identity from returning through self-join, stale metadata, creator bootstrapping, or later rotation rosters. |

- Creator authority and epoch are rechecked at the final write and on retries.
- Rotation and creator-metadata orderings remain independent, so late rotation does not block newer descriptive metadata.

### Journaled control operations

| Stage / condition | Recovery behavior |
| --- | --- |
| Before publication | Persist immutable removal/revocation intent, then exact signed events. |
| Interrupted operation | Reuse its signed events and epoch key; finish local effects and follow-up metadata. |
| Removal not yet prepared | Rebase the roster onto live state while retaining the intended member removal and epoch/key. |
| Prepared removal with roster changes | Preserve signed envelopes; add same-key envelopes for newly eligible members and newer corrective metadata. |
| Identity promotion | Check the journal's replacement key; recover when secure storage committed before reporting failure. |
| Pending identity without journal | Preserve and block it rather than guessing from outbox state or timestamps. |
| Unjournaled next-epoch key | Reuse only when the creator's stored rotation envelopes agree on the removal. |
| Changed creator authority / foreign epoch | Fail closed; journal existence is not an unconditional guarantee of completion. |

- A different removal cannot reuse an unfinished operation's key.
- Revocation preconditions are checked before journaling; a later roster change does not erase the revocation tombstone or re-add someone unintentionally.
- If an incoming roster names a revoked identity, resolve its recorded successor or remove the key, then advance the epoch.
- A device addressed only through its revoked key may advance without new key material and require a later creator rotation.

### Stored versus applied

| State | Ledger effect |
| --- | --- |
| `PENDING` | Awaiting a dependency or retryable application; excluded from balances. |
| `APPLIED` | Durable effect applied; eligible for ledger computation. |
| `FAILED` | Cannot apply on this device, such as conflicting epoch material; not offered as an ordinary event. |

- Missing epochs, joins, and originals remain retryable within admission limits.
- Historical authors may be accepted when the record decrypts under an epoch preceding their removal.
- That historical rule has the old-key limitation below.

## Platform

| Condition | Behavior |
| --- | --- |
| Android 12L and earlier | Fine/coarse location requested together for discovery. |
| Android 13+ | Nearby Wi-Fi permission used alongside relevant Bluetooth permissions. |
| API 37 / Android 17+ | Local-network permission requested for Wi-Fi LAN. Denial alone does not establish whether other transports work. |
| Leave screen / activity stops | Close sessions and stop discovery/advertising; keep durable data. |
| Activity pauses for system consent | Do not tear down solely on `ON_PAUSE`; the first-connection consent flow can pause the activity. |

See [NearbySyncScreen](../../ui/screens/nearby/NearbySyncScreen.kt) for lifecycle and permission handling.

## Known limits

| Limit | Implication |
| --- | --- |
| **No epoch/roster digest on the wire** | Two recipients at different epochs, with no other differing rows, can still report `UP_TO_DATE`. It is not proof of identical group state. |
| **Rumor-only evidence** | A peer cannot recreate a missing signed original or recipient envelope from an unsigned inner event. Missing held records keep reconciliation incomplete. |
| **Retained old group keys** | A removed member can backdate an event under an old key. Historical admission is epoch-based, not protected by a creator-signed membership checkpoint. |
| **Missing channel token** | If the SDK token is absent, signatures still prove identity but are not bound to the physical connection. |
| **Frame-size expansion** | Character-sized chunks can exceed the byte-frame limit after encoding; large records are not guaranteed to transfer. |
| **Bounded carriage** | Quotas, pruning, and unavailable peers can prevent delivery; `CARRIED` is only local retention. |
| **Foreground lifecycle** | No always-on background mesh. |
| **SDK diagnostics** | Google Nearby has its own diagnostics under device Usage & diagnostics settings. |

## Testing

Run from the repository root with the [documented toolchain](../../../../../../../../README.md#build-from-source):

```bash
./gradlew :app:testDebugUnitTest --tests 'com.splitfree.sync.nearby.*'
```

| Suite | Coverage |
| --- | --- |
| `NearbyAuthTest` | Transcript roles, channel binding, and invalid signatures. |
| `NearbyWireTest` | Frame parsing, paging, bounds, and compatibility. |
| `NearbySessionCoordinatorTest` | Multiple simulated engines, retries, lifecycle, completion, and forwarding. |
| `RoomReconciliationStoreTest` | Real Room-backed inventory, ingestion, evidence upgrades, and envelope carriage. |

- JVM tests use simulated transport, not physical radios.
- File-backed recovery tests use secure-storage/publication doubles; they do not certify OS process death or Keystore durability.
- Run the [signed-APK device checklist](../../../../../../../../RELEASING.md#4-test-the-exact-signed-apk) before making release claims about three-phone forwarding.
