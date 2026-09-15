# Nearby sync protocol

- SplitFree synchronizes a selected group over Google Nearby Connections while the Nearby destination is started.
- This guide describes the implemented **v3** protocol, ownership boundaries, recovery behavior and validation limits.
- It is a source-level contract, not evidence of physical-radio performance.

[Project overview](../../../../../../../../README.md) · [Nostr and relay sync](../../data/nostr/README.md) · [Privacy](../../../../../../../../PRIVACY.md)

## At a glance

| Property | Behavior |
| --- | --- |
| Transport | Google Nearby Connections, `P2P_CLUSTER`; the SDK selects the underlying Bluetooth/BLE/Wi-Fi paths. |
| Scope | One selected group per controller run and one authorized group per peer session. |
| Authentication | Each side signs a role-specific Schnorr transcript and verifies the peer proof locally; SDK-token binding is used when available. |
| Data | Signed ledger/control events, recipient-encrypted envelopes and accounting for non-forwardable held records. |
| Recovery | Reconnect, authenticate again and reconcile against durable records; session buffers and acknowledgments are not persisted. |
| Lifecycle | Search and advertise when the destination is started, prerequisites hold and user intent is enabled. Stop on destination stop/disposal; resume only when those conditions hold again. |
| Completion | `UP_TO_DATE` describes the reported exchange, not proof of equal ledger, epoch or roster state. |

## Contents

- [Components and ownership](#components-and-ownership)
- [Session lifecycle](#session-lifecycle)
- [Connection attempts and failures](#connection-attempts-and-failures)
- [Transport fencing](#transport-fencing)
- [Framing](#framing)
- [Authentication](#authentication)
- [Reconciliation](#reconciliation)
- [Completion and retries](#completion-and-retries)
- [Forwarding](#forwarding)
- [Shared ledger and recovery contracts](#shared-ledger-and-recovery-contracts)
- [Platform](#platform)
- [Diagnostics](#diagnostics)
- [Known limits](#known-limits)
- [Testing](#testing)

<a id="components"></a>

## Components and ownership

```text
NearbySyncScreen → NearbySyncViewModel → NearbySessionController
                                              ├── NearbyRadio ────────┐
                                              └── NearbySessionCoordinator
                                                    ├── PeerSession  │
                                                    ├── ReconciliationStore
                                                    └── NearbyTransport
                                                            │        │
                                                            └── NearbySync → ConnectionsClient
```

- `NearbyRadio` extends `NearbyTransport`.
  - The production adapter implements both.
- The coordinator does not start discovery/advertising.
- The controller does not decode protocol frames.

| Component | Responsibility |
| --- | --- |
| [NearbySyncScreen](../../ui/screens/nearby/NearbySyncScreen.kt) | Runtime permission and Bluetooth-enable flows, destination lifecycle and stateless rendering. |
| [NearbySyncViewModel](../../ui/viewmodels/NearbySyncViewModel.kt) | Saved enabled/stopped intent and projection of run/session state into rows, actions and headline. |
| [NearbySessionController](NearbySessionController.kt) / [NearbyRunState](NearbyRunState.kt) | Owner tokens, one run, capability retries, pre-connection attempts, cleanup boundaries and diagnostics. |
| [NearbyRadio](NearbyRadio.kt) / [NearbySync](../../data/ble/NearbySync.kt) | SDK submissions/outcomes, callback ownership, link identity and event-overflow reporting. |
| [NearbySessionCoordinator](NearbySessionCoordinator.kt) | One selected group, live peer sessions, serialized event/timer work and durable-change observation. |
| [PeerSession](PeerSession.kt) / [NearbySessionState](NearbySessionState.kt) | Authentication, authorization, bidirectional reconciliation, receipts and observer-facing protocol progress. |
| [NearbyWire](NearbyWire.kt) / [NearbyAuth](NearbyAuth.kt) | Framing, message types, bounds and role-specific signed transcripts. |
| [ReconciliationStore](ReconciliationStore.kt) / [RoomReconciliationStore](RoomReconciliationStore.kt) | Inventory, ingestion, evidence upgrades, dependency recovery and envelope carriage. |
| [NearbyTransport](NearbyTransport.kt) | Connection-identity-scoped byte sends/disconnects and transport events. |

### Concurrency boundaries

- Public controller intents enqueue work.
  - One actor applies commands in channel order.
  - Concurrent callers have no stronger call-order guarantee.
  - Returning from an intent does not acknowledge its completion.
- Coordinator session entry points, watchdog callbacks and store-change handling share one mutex.
  - A slow store operation can delay other peers even though ordinary peer-session failures are isolated.
- The application-scope transport collector awaits each event's run-owned work before collecting the next.
  - Run cancellation cancels those children, not the lifetime collector.
  - New connections without an active run are disconnected best effort rather than left without a session.
- Closing a peer cancels its timers.
  - Connection capabilities independently fence already-running work whose old session has not yet observed a replacement link.
- Relay and Nearby ingestion share [EventProcessor](../event/EventProcessor.kt).
  - Nearby's `IngestionContext.RECONCILIATION` relaxes historical-age and in-memory rate gates.
  - Signature, group-scope, membership, decryption and payload validation still apply.

## Session lifecycle

- The destination defaults to enabled.
- Missing permissions are requested automatically once per saved screen entry.
- Enabling Bluetooth still requires a user action.
- The ViewModel preserves an explicit Stop across recreation through saved state.

| Trigger | Behavior |
| --- | --- |
| Destination started and prerequisites satisfied | Request startup for its nonempty group id if user intent is enabled. |
| Stop tapped | Clear enabled intent, enqueue stop and discard a remembered restart. |
| Destination `ON_STOP` or disposal | Enqueue stop without clearing intent. Return can start a new run, not resume an old session. |
| `ON_PAUSE` without stop | Pause outgoing pre-connection attempt timers only; do not tear down for a consent/permission overlay alone. |
| Prerequisites lost | Enqueue owner-scoped stop even if startup has not yet published state. Unchanged prerequisite reports are inert. |
| Another owner acquired | Retire the previous owner's run; stale-owner commands are ignored. |
| Start requested during cleanup | Remember the latest requested group and wait for the previous cleanup boundary. |

### Ordered startup

1. Activate the coordinator:
   1. Close previous sessions.
   2. Attempt maintenance; ordinary failures are logged, while cancellation propagates.
   3. Install run ownership.
   4. Wait for its lifetime event subscription.
2. Wait for the controller's per-run event subscription too.
   - The adapter's `SharedFlow` has no replay.
   - Registration readiness prevents the initial no-subscriber gap, not every possible event loss.
3. Queue advertising and discovery work independently.
   - Neither waits for the other's SDK task.
   - The run becomes `ACTIVE` before those tasks settle.
   - Capability state is the authority for which operation is running.
4. Mark a capability `Running` on success or `ALREADY_ACTIVE`.
   - Discovery has no application duty cycle while running.
   - After 15 seconds of running discovery with no currently discovered peer, set search guidance.

### Stop and cleanup boundary

- For a run whose radio work may have started:

1. Retire the run and cancel its collectors/retry/attempt timers.
   - Request `stopDiscovery`, then `stopAdvertising`, before waiting for coordinator cleanup.
2. Cancel activation and launch coordinator deactivation.
   - Before taking the mutex, deactivation cancels its observer and run-owned work.
   - After taking the mutex, it attempts `Close` frames and per-session disconnects.
3. Wait up to 1.5 seconds for the activation/deactivation joins, then request `stopAllEndpoints` even if those joins timed out.
   - `Close` submission is best effort and is not proof the peer received it.
4. Join the run's outstanding start/request tasks.
   - Request discovery, advertising and endpoint stop again after they settle, to counter a submission that reached the SDK after the first stop pass.
5. Join activation/deactivation to completion before publishing the cleanup boundary and allowing a new run.

- A coordinator activation failure before radio startup uses coordinator-only cleanup.

- The grace period bounds **only the initial coroutine joins**, not synchronous SDK calls or total cleanup.
- Cooperative store waits unwind on cancellation.
- Blocking or non-cancellable work and SDK tasks that never settle can delay restart indefinitely.
- SDK stop calls are best effort.
  - Stopping advertising alone does not invalidate adapter admission.
  - A late initiation may still be processed until the endpoint boundary.
- The controller refuses new user attempts once stopping.
- Coordinator cleanup handles late links.

<a id="connection-attempts"></a>

## Connection attempts and failures

### Outgoing attempt state

```text
None / Failed → Requesting → AwaitingConnection → Connected → peer protocol phases
                     └──────────────┴── failure / timeout → Failed
                     └──────────────┴── cancel            → None
```

- `connect` requires an active run and a discovered endpoint without a pending/connected attempt.
  - Repeated taps do not submit another request for such a row.
  - Incoming links need not have a discovered row.
- `Requesting` includes queued work that has not reached the adapter.
  - Request-task success means submission succeeded, not that the endpoint connected.
  - `Connected` comes from the lifecycle callback.
- The timeout is 30 seconds of unpaused pre-connection time.
  - Capability and peer-protocol timers are separate.
- A single-use `NearbyConnectionAttempt` is created before dispatch.
  - Adapter cancellation and SDK request submission share a lock.
  - Cancellation before submission revokes the token, not an endpoint-wide future request.
- Cancellation cannot disconnect a link the adapter already knows is connected, even if the controller's `Connected` event is still queued.
  - Submitted tasks remain observed while application scope is alive.
- When an attempt settles or a connected link ends:
  - Keep a still-discovered row connectable.
  - Remove an undiscovered row.
- A pending attempt is settled by its own outcome, cancellation or timeout, not by an unrelated connection-ended event.

### Failure scopes

| Scope | Handling |
| --- | --- |
| Advertising or discovery | Retry/escalate that capability. One failed capability does not alone close peers; both failed capabilities end the run. |
| One outgoing attempt | Mark that row failed (or remove it if no longer discovered); other peers are unaffected. No automatic connection-request retry. |
| Asynchronous payload error | Adapter emits an error; coordinator relies on receipts/timeouts. Endpoint-class send failures also end the adapter link. |
| One record's ingestion exception | Report `REJECTED`; cancellation still propagates. |
| Unexpected frame, watchdog or dirty-work exception | Interrupt the affected session with `session_error`, cancel its timers and request its connection's disconnect. Healthy peers can continue. |
| Initial `Hello` submission throws | Interrupt with `transport_error` without starting its watchdog. Later SDK send failures follow the asynchronous path. |
| Whole run | `FAILED` for transport fault, coordinator preparation failure or both capabilities failed. Cleanup still applies. |

- The following retry policy applies to **capability starts**, not arbitrary failures with the same category:

| Start outcome | Recovery |
| --- | --- |
| Success or `ALREADY_ACTIVE` | Treat capability as running. |
| `RADIO`, `ENDPOINT`, `PAYLOAD`, `UNKNOWN`, or cancelled task | Three retries after 2 s, 5 s and 10 s, each plus 0–499 ms jitter from the default random source; then fail the capability. |
| `PERMISSION` | No automatic retry; request permission or use app Settings as appropriate. |
| `LOCATION_SETTING` (status 8025) | No automatic retry; offer Location settings. |
| `SERVICE` | No automatic retry; offer Retry. |

- A user Start on a partially working active run retries only failed capabilities with a fresh budget.
  - It does not duplicate running capabilities.
  - It does not bypass a scheduled retry.
- A fully failed run starts again only after cleanup.

## Transport fencing

- These are adapter checks, not promises that Google Play services delivers all callbacks in one universal order.

| Identity | Protection |
| --- | --- |
| Controller owner/run/attempt | Reject stale intents and task/timer outcomes belonging to earlier controller work. |
| Adapter generation | `stopAllEndpoints` advances the generation and clears links, requests and retirement markers. Discovery checks its captured generation and discovery-enabled flag. |
| Advertising/request submission | A lifecycle callback settles only a link attributed to that submission. Old request callbacks cannot claim a newer request token. |
| `NearbyConnection` object | Payload/disconnect events and session side effects identify one link, not just a reusable endpoint id. |
| Accepted link object | Payload callbacks and asynchronous accept/send failures compare against the currently registered link. |

- The adapter checks a connection handle under the same lock as send/disconnect submission.
- An ended or unrecognized handle is a no-op, including when an old watchdog is preempted before reaching the adapter.
- The coordinator also ignores payload/disconnect events whose handle differs from the current session.

- A replacement initiation ends the prior connected link before a new successful result emits `Connected`.
- The adapter's normal end-link paths attempt one `Disconnected` event per previously connected link.
- Pending links have no session yet and settle through attempt outcomes.
- If a replacement `Connected` reaches a coordinator with a live session still present, that session retires without `Close` or a redundant disconnect.

### Reused incoming callbacks

- One advertising submission reuses its lifecycle callback for incoming connections.
- Results and disconnections identify only the endpoint, so the callback cannot inherently distinguish every successive link on that endpoint.
- After locally ending an incoming link, the adapter retires that `(advertising submission, endpoint)` pair.
  - A matching disconnection or failed result removes the retirement.
  - Initiations through the retired pair are refused without a new app event.
  - A new outgoing request, later advertising submission or run boundary provides a different path.

- A rejected successor's terminal callback can consume the prior link's retirement before that prior link's own terminal callback arrives.
- If no matching terminal callback arrives, same-submission incoming reuse remains refused.
- This retirement is not an arbitrary-ordering fence.
  - After reuse, submission identity alone cannot prove which physical link a delayed or duplicate endpoint-only event names.
- Actual SDK ordering and reconnection behavior remain a hardware-validation concern.

- Stale initiations and orphan successful results are rejected/closed best effort only when another submission does not own the endpoint.
  - An endpoint-only SDK cleanup call must not target a known replacement.

### Event buffering

- The production event flow has capacity for 1,024 buffered events for slow subscribers and no replay.
- Full-buffer drops publish `TransportFault("event_overflow", ...)` through a separate state flow.
  - The controller then fails the run rather than knowingly continuing a lossy exchange.
- This is detection, not lossless delivery.
  - Without subscribers, `tryEmit` can succeed while discarding the event.
  - Already-queued controller commands are not bounded by the adapter buffer.
- State flows are conflated snapshots, not event logs.

## Framing

```text
[0x7F][message type: 1 byte][UTF-8 JSON body]
```

- `0x7F` distinguishes current framing from legacy v1 type prefixes `0x01..0x04`.
- Recognized legacy peers close with `unsupported_version`.
- `Hello.v` must match **3**.
  - The framing marker was introduced in v2 and is not itself a version selector.
- The retained `reconcile-v2` capability name and `splitfree-nearby-auth-v2` transcript domain are wire constants.
  - They do not claim that the current peer speaks protocol v2.
- Decoding validates serialization.
- `PeerSession` checks field values and message ordering.

### Bounds

| Limit | Value | Enforced by |
| --- | --- | --- |
| Incoming encoded frame | 16 KiB, including header | `NearbyWire.decode` |
| Outgoing inventory page | 256 entries and 16 KiB encoded for valid inventory items | `NearbyWire.paginate` |
| Inventory being assembled | 200,000 accumulated entries | `PeerSession` receive validation |
| Record chunk | 12,000 characters | `NearbyWire.chunk` and receive validation |
| Chunks per record | 64 | Chunking and receive validation |
| Requested records in flight | 16 per peer | Request batching / `Want` validation |
| Partially assembled records | 4 per peer | Receive validation, then `BUSY` |
| Dependency-shaped rejections retained | 4,096 per session | `PeerSession` retry collection |

> - **Bytes and characters differ:** chunking counts Kotlin string characters (UTF-16 code units).
>   - UTF-8 encoding and JSON escaping can increase byte size.
>   - `encode` and `chunk` do not enforce the 16 KiB encoded-frame limit.
>   - `paginate` assumes each inventory item fits a page by itself.

- These are message/session limits, not a global memory bound.
  - The controller command channel is unbounded.
  - Held-id tracking and acknowledged inventory can accumulate across delta snapshots.

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
2. The outgoing connection side becomes initiator.
   - If roles match, the lexically smaller public key initiates.
3. Each side derives the intersection of offered capabilities and binds it into its transcript.
   - There is no separate capability acknowledgment or required-capability check.
4. Each signs the transcript for its own role and verifies the peer's signature.
5. After locally verifying the peer's proof, the initiator requests one group.
   - Membership checks gate opening.

- The exchange is mutual in design, but there is no final acknowledgment that the peer verified the local proof.
- A published verified key means **this device verified that peer's signature**, not that both devices have confirmed the same handshake state.

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
- Different channel tokens produce different transcripts; a signature for one token does not verify against another.
- `NearbySync` forwards the token captured at connection initiation.
  - Its callback model permits a missing token.
- `PeerSession` does not reject a missing token solely for absence.
  - `NearbyAuth` hashes empty bytes instead.
  - Identity proof remains, but **channel binding is absent**.
- This is a source-level fallback, not evidence that the SDK normally omits tokens or that a device exploit has been reproduced.
- Identical duplicate `Hello` / `Auth` messages are idempotent; conflicting ones close the session.
- Locally generated group-open traffic follows local verification of the peer proof.
  - The initiator also checks the peer is authorized before disclosing the selected group id.

### Group authorization

- `OpenGroup` is sent only to a known member or creator of the selected group.
- A newly invited member may attach a signed self-join `group_meta` so the other phone can admit it before checking membership.
- Receiving reconciliation messages and handling store-change notifications recheck the local identity and both memberships after group opening.
  - This is not a separate authorization acknowledgment for every send.
- A group-scoped session cannot apply a received envelope to a different group the phone happens to know.

## Reconciliation

- Both peers act as provider and consumer after the group opens.

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
- Later snapshots include currently available items not in the acknowledged baseline.
  - A snapshot acknowledgment records that inventory was consumed, not that every record applied successfully.
- Only the next contiguous page is consumed.
  - Out-of-order pages are ignored rather than buffered.
  - The provider can recover by advertising a new snapshot.
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
| `INTERRUPTED` | A transport loss, timeout, synchronous initial-send failure or unexpected session operation ended the session; durable records remain for a later session. |
| `UNSUPPORTED_PEER` / `AUTH_FAILED` / `UNAUTHORIZED` / `CLOSED` | Terminal protocol, authentication, authorization, or lifecycle outcome. |

- `UP_TO_DATE`, `WAITING_DEPENDENCY`, and `INCOMPLETE` keep the connection open for later changes.
- Pending counts come from durable storage, including work retained across sessions.
- An unreadable pending count fails closed: advertise at least one pending item and report at least one unresolved item.
- Readability changes trigger an updated report even if the last readable count was zero.
- Failure status takes precedence over waiting for dependencies once the exchange finishes.
- Counters describe transfer activity; retries may count a record more than once.

### Retry timing

- Protocol timeouts use **session receive inactivity**, not a separate deadline for each record.
- Any frame received before close updates activity, including invalid frames.
- Watchdog scheduling and shared-mutex contention can delay a check.
- Unlike pre-connection attempt timers, these timers are not paused on `ON_PAUSE`.

| Condition | Policy |
| --- | --- |
| Authentication or group-open inactivity | 10-second idle limit. |
| Requested records stall | After 30 seconds of session receive inactivity, retry in-flight requests once per snapshot; another idle window counts remaining requests unresolved. |
| Missing page or snapshot report | Re-advertise unacknowledged inventory at most twice, then interrupt if the provider still lacks a completion report. |
| Provider awaiting record receipts | Wait until at least 60 seconds of receive inactivity before replacing the snapshot, allowing the consumer's retry window. |
| Watchdog | Checks every 2.5 seconds. |

## Forwarding

### Envelope path

1. An author keeps per-recipient envelopes in `deliveries`.
2. An intermediate member retains an envelope as opaque ciphertext.
3. A later session offers it to the recipient or another eligible carrier.
4. The recipient opens it and verifies the original author's seal or signed key-rotation event.

- A carrier is not trusted as the author.
  - It cannot decrypt another recipient's envelope without that recipient's private key.
  - It may separately receive the same underlying event through its own envelope or a signed original.
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
  - Recipient-addressed input also has the applicable framing and ingestion limits.
  - The 64 KiB carriage limit is not a universal record limit.
- Retention pruning uses the delivery source and local receipt time, including consumed tombstones.
  - It is invoked during coordinator activation, not by a continuously running expiration timer.
- A full cache can evict older carriage; it does not guarantee eventual delivery.
- SQL revision triggers cover events, deliveries, and group state.
  - Room schema versions are independent of the Nearby wire version.
- Relevant evidence, apply-state, delivery and group-state changes advance the revision.
  - Tracked-column no-op updates and sync-timestamp-only changes do not.

<a id="ledger-rules-that-changed-with-this-protocol"></a>

## Shared ledger and recovery contracts

- Nearby and relay ingestion share these contracts; they are not additional Nearby wire messages.
- See [EventProcessor](../event/EventProcessor.kt), [GroupRepository](../../data/repository/GroupRepository.kt), and the [control-operation journal](../../data/repository/ControlOperationJournal.kt) for the durable implementation.

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
- Revocation preconditions are checked before journaling.
  - A later roster change does not erase the revocation tombstone or re-add someone unintentionally.
- If an incoming roster names a revoked identity, resolve its recorded successor or remove the key, then advance the epoch.
- A device addressed only through its revoked key may advance without new key material and require a later creator rotation.

### Stored versus applied

| State | Ledger effect |
| --- | --- |
| `PENDING` | Awaiting a dependency or retryable application; excluded from balances. |
| `APPLIED` | Durable effect applied; eligible for ledger computation. |
| `FAILED` | Cannot apply on this device, such as conflicting epoch material; not offered as an ordinary event. |

- Missing epochs, joins, and originals remain retryable within admission limits.
- In reconciliation, a removed author's `expense`, `expense_correction`, `expense_delete` or `settlement` may be admitted under an epoch before their latest recorded removal.
  - This exception does not admit controls or snapshots.
- That historical rule has the old-key limitation below.

## Platform

- The route's startup gate requires **every permission below**, plus an available/enabled Bluetooth adapter.
- This is the application's prerequisite policy, not a statement that every SDK transport individually needs all of them.
- In particular, it does not attempt Bluetooth-only fallback after local-network permission denial.

| API level | Requested runtime permissions |
| --- | --- |
| 26–28 | `ACCESS_COARSE_LOCATION` |
| 29–30 | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` |
| 31–32 | `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` |
| 33–36 | `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES` |
| 37+ | The 33–36 set plus `ACCESS_LOCAL_NETWORK` for the Wi-Fi LAN path. |

| Condition | Handling |
| --- | --- |
| Permission absent at the route gate | Do not start; offer permission request or app Settings according to the route's denial state. |
| Permission revoked during an SDK operation | An observed permission-class outcome is surfaced; prerequisite rechecks can also stop the run. |
| Location services required by the SDK path | `MISSING_SETTING_LOCATION_MUST_BE_ON` (8025) maps to `LOCATION_SETTING`; offer Location settings. |
| Bluetooth disabled | Offer the system enable flow on user action; readiness is rechecked afterwards and on state broadcasts/resume. |
| Service-class SDK failure | Offer Retry. Classification reflects observed status codes, not an exhaustive diagnosis of Play services installation/version. |
| Destination stops/disposes | Execute the cleanup sequence; retain durable data and the user's enabled/stopped intent. |
| Activity pauses for system consent | Do not tear down for pause alone; only pre-connection attempt time is paused. |

### Peer rows and headline

- Rows join discovery/attempt state with protocol progress by endpoint id.
- Advertised names are unverified.
- The verified-identity caption uses the peer key only after its signature has verified locally.
  - It does not claim membership or final mutual-confirmation.
- A verified key can remain in retained terminal progress.

- Open result phases (`UP_TO_DATE`, `WAITING_DEPENDENCY`, `INCOMPLETE`) remain attached to their connected rows.
- Terminal progress for endpoints absent from the run appears separately as recent results.
- Headline priority is syncing → connecting → found → searching → getting ready → failed → idle.
  - An active peer can therefore take precedence over another peer's result or run notice.

## Diagnostics

- The controller retains up to 200 timeline entries across runs in memory.
  - Fields: timestamp, run/attempt ids, endpoint id, event name, optional status code and detail.
  - This timeline exists in release builds too.
- Normal discovery/attempt entries do not copy advertised names, keys, channel tokens or payload bytes.
  - **Detail is not sanitized:** coordinator activation error messages are retained verbatim.
  - The timeline must not be described as guaranteed metadata-only or safe to export without review.
- `DebugLog.i` mirrors timeline entries only in debug builds.
  - Release builds have no `DebugLog` in-app buffer or informational log output.
  - Warning/error logging is separate and uses limited sanitization.
- Neither timeline nor debug buffer is a durable diagnostic record; process death loses them.

## Known limits

| Limit | Implication |
| --- | --- |
| **No epoch/roster digest on the wire** | Different group state can still report `UP_TO_DATE` when no advertised record or reported pending/failure count reveals the difference. |
| **Rumor-only evidence** | An unsigned inner event cannot recreate its missing signed original or a recipient envelope. Held-record gaps keep the exchange incomplete. |
| **Retained old group keys** | A removed member can backdate a newly created old-key event. Historical admission is epoch-based, without a creator-signed membership checkpoint. |
| **Missing channel token** | Null tokens are hashed as empty bytes; identity proof remains but transport-channel binding is absent. The fallback is not evidence of normal SDK behavior or a reproduced device exploit. |
| **Frame-size expansion** | Character-sized chunks can exceed the receiver's encoded-byte limit; large records are not guaranteed to transfer. |
| **Bounded carriage** | Quotas, pruning and unavailable peers prevent any eventual-delivery guarantee; `CARRIED` is local acknowledgment, not recipient proof. |
| **Foreground scope** | No always-on background mesh. Returning to a started destination creates fresh sessions when intent and prerequisites permit. |
| **Continuous discovery** | No application duty cycle or discovery-duration cap; energy use and discovery latency require device measurement. |
| **Timing and cleanup** | Timeouts use the injected clock (wall clock in production) and coroutine scheduling; clock changes, mutex contention, blocking work or unsettled tasks can extend waits. |
| **Callback ambiguity** | Incoming endpoint reuse through one advertising callback depends on callback ordering the app cannot encode in an endpoint-only SDK event. |
| **Partial bounds** | Per-message/session bounds and the adapter event buffer do not bound every queue, peer count or accumulated snapshot set. |
| **Retry exhaustion** | Capability retries are finite; attempts require user retry. Reconnection cannot recover records no eligible peer retains. |
| **Diagnostics** | In-memory only; coordinator error details are not redacted by the timeline. |
| **SDK diagnostics** | Google Nearby has its own diagnostics under device Usage & diagnostics settings. |

- The 15-second search hint is not an SDK discovery guarantee.
- The discovery target in the [hardware validation list](../../../../../../../../RELEASING.md#nearby-hardware-validation) is an acceptance criterion, not a demonstrated result from JVM tests.

## Testing

- Run from the repository root with the [documented toolchain](../../../../../../../../README.md#build-from-source):

```bash
./gradlew :app:testDebugUnitTest --tests '*Nearby*' --tests '*ReconciliationStore*'
```

| Suite | Boundary exercised |
| --- | --- |
| `NearbyAuthTest` | Transcript roles, channel-token inputs and signature validation. |
| `NearbyWireTest` | Serialization, framing, pagination/chunk bounds and version compatibility. |
| `NearbySyncCallbackTest` | Real adapter over scripted SDK tasks/callback objects: outcomes, status mapping, link fencing, atomic submission/cancellation and event overflow. |
| `NearbySessionControllerTest` | Fake-radio run/attempt lifecycle, startup order, capability retries, timeout accounting, cleanup boundary and bounded diagnostics. |
| `NearbySessionCoordinatorTest` | Real coordinators/peer sessions over routed in-memory frames and scripted stores: authentication, transfer, recovery, propagation, cooperative cancellation, replacement fencing and failure isolation. |
| `NearbyStackTest` | Real adapter/controller/coordinator with scripted client and store: subscription gates, queued cancellation, watchdog replacement, retryable link ends and late task outcomes. Remote peers remain silent; this is not full authenticated exchange. |
| `RoomReconciliationStoreTest` | Real in-memory Room and ingestion/crypto paths over simulated transport: evidence, group scope, pending state, rotations and onward carriage. Secure storage and selected publication/post-processing dependencies are doubles. |
| `NearbySyncViewModelTest` | Enabled/stopped intent, row projection and headline priority. |
| `NearbySyncViewModelLifecycleTest` | Controller/coordinator integration for prerequisite transitions, queued startup cancellation, readiness restart and owner release. |
| `NearbySyncContentTest` | Robolectric Compose rendering and action wiring. |

- Scheduler gates select reproducible interleavings.
  - They do not establish arbitrary thread safety or real Google Play services callback ordering.
  - Room direct executors do not certify production executor behavior.
- File-backed recovery suites elsewhere use secure-storage/publication doubles.
  - They do not certify OS process death or Android Keystore durability.
- Hardware validation is a separate gate.
  - Before claiming in-person discovery, consent behavior, energy performance or three-phone forwarding on devices, run both:
    - [Nearby hardware validation list](../../../../../../../../RELEASING.md#nearby-hardware-validation).
    - [Signed-APK device checklist](../../../../../../../../RELEASING.md#4-test-the-exact-signed-apk).
