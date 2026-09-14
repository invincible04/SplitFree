# Nostr transport and synchronization

[Project README](../../../../../../../../README.md) · [Nearby protocol](../../sync/nearby/README.md) · [Contributor guide](../../../../../../../../CONTRIBUTING.md)

SplitFree uses Nostr relays to exchange signed, encrypted application events.
The ledger and outgoing queue live locally; a relay is a transport and storage peer,
not the authority for group membership, balances, or whether another member applied an event.

This guide describes the implementation in this checkout, not blanket NIP compliance,
current public-relay availability, a cryptographic audit, or a delivery guarantee.

## Contents

- [Component map](#component-map)
- [Implemented protocol subset](#implemented-protocol-subset)
- [Event shapes, tags, and addressing](#event-shapes-tags-and-addressing)
- [Keys, epochs, and admission](#keys-epochs-and-admission)
- [Publication: what success means](#publication-what-success-means)
- [Relay selection and availability](#relay-selection-and-availability)
- [Catch-up, EOSE, and durable cursors](#catch-up-eose-and-durable-cursors)
- [Durable recovery and its limits](#durable-recovery-and-its-limits)
- [Metadata visibility](#metadata-visibility)
- [Tests and verification](#tests-and-verification)

## Component map

| Component | Responsibility |
| --- | --- |
| [NostrClient](NostrClient.kt) | Shared relay pool, subscriptions, signature checks, live deduplication, publish fan-out, historical fetch coverage. |
| [Relay](relay/Relay.kt) | One OkHttp WebSocket, reconnects, subscription replay, bounded message buffer, `OK` tracking, optional `AUTH`. |
| [ClientMessage](protocol/ClientMessage.kt), [RelayMessage](protocol/RelayMessage.kt), [NostrFilter](protocol/NostrFilter.kt) | Wire JSON encoding/parsing and filter serialization. |
| [RelayConnectionManager](relay/RelayConnectionManager.kt), [RelayHealthMonitor](relay/RelayHealthMonitor.kt) | Resolve relay candidates, acquire connections, probe relay information; explicit round-trip verification is separate. |
| [EventSigner](../../domain/crypto/EventSigner.kt), [NostrEvent](../../domain/crypto/NostrEvent.kt), [NostrKind](../../domain/crypto/NostrKind.kt) | Event addresses, canonical IDs, Schnorr signatures, kind constants. |
| [GroupEncryption](../../domain/crypto/GroupEncryption.kt), [Nip44](../../domain/crypto/nip/Nip44.kt) | Shared-group-key convention, optional compression, NIP-44 v2 primitives. |
| [GiftWrapService](../../domain/crypto/GiftWrapService.kt), [Nip59](../../domain/crypto/nip/Nip59.kt) | Preference-controlled recipient envelopes and authenticated unwrapping. |
| [EventPublisher](../../sync/event/EventPublisher.kt), [EventThrottler](EventThrottler.kt) | Prepare complete delivery batches, persist before sending, attempt immediate publication. |
| [EventProcessor](../../sync/event/EventProcessor.kt), [EventPostProcessor](../../sync/event/EventPostProcessor.kt) | Membership/payload validation, decryption, durable application states, control effects. |
| [SyncEngine](../../sync/worker/SyncEngine.kt), [RelaySyncCursors](../repository/RelaySyncCursors.kt) | Historical reconciliation, dependency retries, per-relay coverage, due outbox retries. |
| [LiveSync](../../sync/worker/LiveSync.kt), [SyncScheduler](../../sync/worker/SyncScheduler.kt), [OutboxWorker](../../sync/worker/OutboxWorker.kt) | Visible-app session and network-constrained WorkManager recovery. |

```text
Outgoing: local change → Room event + outbox → relay publication
Incoming: relay event → verification and application → Room → UI
```

Saving locally, relay acceptance, and member application are separate outcomes.

## Implemented protocol subset

| NIP / convention | Implemented use and boundary |
| --- | --- |
| NIP-01 | Event serialization/IDs/signatures; outgoing `EVENT`, `REQ`, `CLOSE`; incoming `EVENT`, `OK`, `EOSE`, `CLOSED`, `NOTICE`. Not a general-purpose Nostr client. |
| NIP-78 / kind `30078` | App-specific addressable data carrying SplitFree JSON inside encrypted content. Other clients need SplitFree's tags, schemas, keys, and validation rules to interpret it. |
| NIP-44 v2 | secp256k1 ECDH, HKDF-SHA256, padded ChaCha20 content, HMAC-SHA256. Group-key derivation is a SplitFree convention, not a standardized group protocol. |
| NIP-59 | Unsigned rumor → sender-signed kind `13` seal → ephemeral-signed kind `1059` gift wrap. Outer `g` routing is a SplitFree extension with a privacy cost. |
| NIP-42 | Handle an `AUTH` challenge using signed kind `22242`, when a signer is configured. This does not certify compatibility with every relay authentication policy. |
| NIP-11 | HTTPS information probes with `Accept: application/nostr+json`; parse advertised NIPs and payment requirement. Reachability/advertisement is not storage or delivery proof. |
| NIP-09 | `EventSigner.createDeletionEvent` can construct kind `5` requests. No production caller currently uses it; ledger deletion uses `expense_delete`, not relay erasure. |

- Filters support `kinds`, `authors`, `ids`, tag maps, `since`, `until`, and `limit`.
  Callers supply wire tag names such as `#g` and `#p`; the serializer does not add `#`.
- Malformed or unknown relay message forms are ignored. Event parsing checks ID/key/signature
  lengths before cryptographic verification; parsing alone never establishes authenticity.
- The live client accepts kinds `30078` and `1059`, verifies signatures, and keeps a bounded
  10,000-ID in-memory deduplication set. Persistent application deduplication uses the inner event ID.
- `AUTH` signs `challenge` and `relay` tags with the user's identity and empty content.
  A socket allows at most three challenge responses; subscriptions are resent after the first
  response, not after a confirmed authentication ACK. There is no general rejected-event replay here.

## Event shapes, tags, and addressing

### Kind `30078`: direct application events and inner rumors

[EventSigner](../../domain/crypto/EventSigner.kt) emits these signed tags:

| Tag | Meaning |
| --- | --- |
| `d` | `groupId:eventType:commandId`: a relay address for one command, not the current state of the entire group. |
| `g` | Group ID used by `#g` subscription filters. |
| `t` | Application event type, such as `expense` or `key_rotation`. |
| `x` | Optional logical expense UUID; snapshots use their snapshot ID here. Revisions of one expense share its UUID. |
| `p` | Optional recipient identity; used on direct per-member `key_rotation` envelopes. |

Kind `30078` is addressable by `(pubkey, kind, d)`. A fresh command address prevents an
expense correction or deletion from replacing its original at that same relay address.

- `createSignedEvent` chooses a fresh random command ID; `createSignedCommandEvent` accepts
  a stable one for command recovery/idempotency.
- Identical canonical fields produce the same event ID. Re-encrypting or changing the timestamp
  changes those fields; a stable command ID alone does not make independently rebuilt events identical.
- A signature authenticates the author and tags. It does not authorize a ledger mutation by itself.

| `t` value | Content / normal publication path |
| --- | --- |
| `expense`, `expense_correction`, `expense_delete`, `settlement` | Group-encrypted payload. Per-member gift wraps when enabled; direct signed `30078` otherwise. |
| `group_meta` | Group-encrypted metadata, roster/name/relay updates. Published directly, including creation and joins, irrespective of gift-wrap preference. |
| `key_rotation` | Creator-to-recipient NIP-44-encrypted rotation payload, one direct `30078` with `p` per target member. The payload includes individually encrypted new-key entries. |
| `key_revocation` | Group-encrypted identity replacement record signed by the old identity; direct publication. Not a relay deletion request or automatic erasure of old keys/data. |
| `snapshot` | Group-encrypted balance snapshot. [CreateSnapshotUseCase](../../domain/usecase/expense/CreateSnapshotUseCase.kt) currently queues it directly via `saveAndQueue`. |

See [RotateGroupKeyUseCase](../../domain/usecase/group/RotateGroupKeyUseCase.kt) and
[RevokeKeyUseCase](../../domain/usecase/group/RevokeKeyUseCase.kt) for control construction.

### Kind `1059`: recipient gift wraps

Gift wrapping defaults to enabled in [UserPreferences](../settings/UserPreferences.kt).
For group publications that use wrapping, the publisher prepares one envelope for each distinct
member other than the author, in shuffled order. A one-member group has no such relay deliveries.

- The inner rumor retains the application kind, tags, timestamp, and group-encrypted content;
  it is unsigned and has a self-consistent event ID.
- The kind `13` seal encrypts the rumor for the recipient, is signed by the real sender,
  and is constructed with empty tags. It is inside the wrap, not separately published.
- The kind `1059` outer event encrypts the seal, is signed with a fresh ephemeral key,
  and carries `p = recipient` plus `g = groupId` copied from the rumor when present.
- Seal and wrap timestamps are independently randomized within the preceding 48 hours.
  The rumor retains its original timestamp.
- Unwrapping verifies outer and seal signatures, seal kind, sender/rumor pubkey consistency,
  and the rumor ID. It is deliberately lenient about a nonempty incoming rumor signature;
  authentication comes from the seal, not that field.
- Stored received rumors carry a `seal:` signature marker. That marker records authenticated
  ingestion; it is not a standalone signature over the rumor suitable for third-party forwarding.

## Keys, epochs, and admission

- An event ID hashes UTF-8 canonical JSON `[0,pubkey,created_at,kind,tags,content]` with SHA-256.
  [NostrEvent.verify](../../domain/crypto/NostrEvent.kt) recomputes the ID and verifies BIP-340 Schnorr
  on secp256k1. Signing uses fresh auxiliary randomness.
- Group keys are random 32-byte values encoded as Base64. `GroupEncryption` derives valid
  secp256k1 sender/recipient secrets using HMAC-SHA256 labels `splitfree-sender-N` and
  `splitfree-recipient-N`, then derives a NIP-44 conversation key from that pair.
- Eligible plaintext may be LZ4-compressed and encoded with the internal `SF_LZ4:` prefix
  before encryption. Compression and this key derivation are application conventions.
- NIP-44 encryption accepts 1–65,535 UTF-8 plaintext bytes. Wire content is
  `base64(0x02 || nonce32 || ciphertext || hmac32)`; decryption checks version/size,
  authenticates the MAC before decrypting, then checks padding. Nested wraps add size overhead.
- Shared-key possession permits content decryption, not impersonating another member's signature.
  Recipients can retain/share plaintext and keys. This is not a forward-secret ratcheting protocol.

[EventProcessor](../../sync/event/EventProcessor.kt) reads stored keys by immutable epoch,
trying the current epoch and then older keys. Only `key_rotation` gets the additional
pairwise author-to-recipient NIP-44 decryption attempt; caller-supplied cached keys are ignored.

- Creator-authorized rotations advance exactly one epoch at a time. Missing epochs or join
  dependencies defer application; conflicting already-stored epoch key material is rejected.
- Old epoch keys are retained for history. New-key distribution targets remaining members,
  including the creator; rotation cannot revoke copies of old keys or plaintext.
- Signed inner `g` determines the group. A pull/session scope can reject another group,
  never rebind its event. Live/pull callers skip `p`-addressed copies meant for other identities.
- Membership, timestamp, rate, content, author/revision, and payload rules are separate from
  signature verification. Creator controls and members' self-updates have different authority rules.
- Live ingestion applies age and rate limits. Reconciliation admits older history and skips live
  rate counters, but still rejects malformed/far-future timestamps and invalid application data.
- [MembershipHistory](../../sync/event/MembershipHistory.kt) admits removed authors' historical
  records only during reconciliation and only under an epoch preceding their recorded removal.
  **This is a bounded check:** an old-key holder can backdate a newly created old-epoch record;
  the protocol has no creator-signed membership checkpoint proving when it was really authored.

## Publication: what success means

| Observation | Meaning / non-guarantee |
| --- | --- |
| Local save succeeds | Event and prepared delivery rows committed. `publishExpense`/`publishMutation` return save admission, not relay ACKs. |
| WebSocket `send` returns true | Frame accepted into the socket's send path; not relay acceptance. |
| Relay `OK(eventId, true, ...)` | That relay reported acceptance. No proof of indefinite retention, replication, or recipient access. |
| `NostrClient.publish` returns true | At least one target relay accepted; targets are attempted concurrently and their results awaited. Not an all-relay quorum. |
| Outbox row disappears after publish | Transport retry obligation for that envelope ended after one relay ACK; not a member read/apply receipt. |
| Incoming `APPLIED` / `DEFERRED` / rejection | Local ingestion result, independent of the sender's relay ACK. `EOSE` is not an application receipt either. |

[Relay.sendEvent](relay/Relay.kt) normally waits up to seven seconds for the matching `OK`.
Concurrent sends of the same ID to one relay share an in-flight request. Rejection, send failure,
timeout, or disconnect does not establish acceptance; a timed-out relay may still have stored it.

Wrapping produces separate outbox rows per recipient. One recipient envelope reaching one relay
does not mean every envelope reached a relay, nor that any member decrypted or applied the event.
There is no recipient-delivery acknowledgement in this relay protocol layer.

## Relay selection and availability

[RelayDefaults](../../domain/util/RelayDefaults.kt) is the source of truth for these configured lists:

| Pool | Current configured endpoints (all `wss://`) |
| --- | --- |
| Default primaries | `purplerelay.com`, `nos.lol`, `relay.primal.net`, `relay.snort.social`, `offchain.pub` |
| Fallbacks | `nostr.data.haus`, `nostr.oxtr.dev`, `relay.nostr.wirednet.jp` |

- Manager-managed connections union each local group's primary list (defaults when empty)
  with all fallbacks. Cached online status only orders attempts; an offline primary is not excluded.
- This is a **shared pool**, not per-group isolation. Publish fan-out and subscriptions use its relays;
  a group's ciphertext/metadata can reach another local group's primary relay as well as fallbacks.
- Custom selection is **not a custom-only/private-relay mode**. The manager adds fallbacks;
  direct callers of `NostrClient.connect`, such as relay-list migration, can temporarily choose
  a different set. `connect` itself does not inject defaults or fallbacks.
- [UpdateGroupRelaysUseCase](../../domain/usecase/group/UpdateGroupRelaysUseCase.kt) requires the
  creator, a nonempty list, and invite-link compatibility. [InviteLinkCodec](../../domain/invite/InviteLinkCodec.kt)
  caps selection at 10 relays; each custom URL is at most 254 UTF-8 bytes, with at most 255 bytes
  total for custom URLs **including one length byte per URL**. Known relays use a bitmap.
- The 10-relay limit is per selected list, not a global socket-pool cap. Transport accepts only
  `wss://`-prefixed URLs; that check alone does not prove a URL is valid, reachable, or trustworthy.
- NIP-11 success may include an empty/malformed information document. Advertising NIP-59 is
  not required for inclusion. The separate `verifyRelayRoundTrip` probe **publishes a test event**;
  an information probe alone does not do this or establish kind-1059 routing support.
- `ensureConnected` waits up to five seconds for any relay, then may return while offline.
  Relay reconnects use exponential backoff with jitter, capped base delay of 60 seconds,
  pausing after 20 attempts until another connection cycle resets the budget.

## Catch-up, EOSE, and durable cursors

| Query | Filter / window |
| --- | --- |
| Main group history and live subscription | Kinds `30078` and `1059`, `#g = groupId`. Omit `since` when requesting all history. |
| Additional recipient filter | Kind `1059`, `#p = myPubkey`; widen `since` by 48 hours, floored at zero, for randomized outer timestamps. |
| Self-heal ID fetch | Kind `30078`, `#g`; returns pooled IDs, not independently certified per-relay inventories. |
| Standalone `fetchGiftWraps` helper | Kind `1059`, `#p`, last 24 hours, ten-second fetch timeout, events only. It is not the main widened group catch-up and can miss older randomized wraps. |

- Historical fetch briefly settles connecting sockets, attaches collectors **before** sending REQs,
  and normally waits up to 15 seconds for EOSE. Missing collector readiness fails the fetch.
- EOSE is counted once per relay and subscription; one relay cannot satisfy another's completion.
  Cleanup closes temporary subscriptions and joins collectors, including on cancellation.
- Completion excludes a relay that disconnected, reopened, dropped buffered frames, or was not
  connected when queried. Requested but unavailable relays remain incomplete even if others EOSE.
- The 4,096-frame relay buffer can overflow. Dropped events trigger a delayed re-REQ covering
  the older of last-delivered/oldest-dropped timestamps minus 60 seconds; reconnect replay uses
  the same mechanism. This is recovery effort, not lossless transport or proof of full history.
- EOSE means the relay says it finished this request. A dishonest, retention-limited, or
  policy-filtered relay can omit events; the client has no global inventory proving otherwise.
  Main history fetches have no pagination loop; omitting `limit` does not guarantee unlimited results.

[RelaySyncCursorEntity](../local/entities/RelaySyncCursorEntity.kt) keys coverage by
`(groupId, relayUrl, recipientPubkey)`. [RelaySyncCursorDao](../local/dao/RelaySyncCursorDao.kt)
advances it monotonically; a missing entry starts at zero, never at the group's progress timestamp.

- `SyncEngine` queries configured primaries, fallbacks, and the client's current pool. Each window
  starts at the earlier of the caller's `since` and that relay's cursor minus a one-hour overlap.
- Received events are sorted by outer `created_at`, then ID. Other recipients' copies and
  clearly other-group wraps are skipped; the processor checks the signed inner group again.
- All pulls use reconciliation admission. Deferred effects are retried before/after processing;
  rejected dependency/quota records get up to eight progress-driven passes within the batch.
- After processing, only completed relays advance to **fetch-start time**. Unstored retryable
  dependencies/quota failures conservatively hold every cursor because per-event relay provenance
  is not retained. An identity change during the pull prevents certifying the old recipient's window.
- `group.lastSyncTimestamp` is a progress indicator, not proof that every relay was covered.
  Persisted pending effects may coexist with completed transport coverage; they have local recovery.

[LiveSync](../../sync/worker/LiveSync.kt) runs while the app is visible:

- It attaches its incoming collector before subscribing, catches up, then subscribes from just
  before the pull with overlap.
- New groups, relay-set changes, and **each socket-open generation** trigger recovery, even if
  another relay kept aggregate connectivity true.
- Incomplete catches get bounded 30/60/120-second retries and scheduled-sync fallback.
- Hiding the app releases this session; WorkManager timing can be deferred.

## Durable recovery and its limits

- `EventPublisher` transactionally saves the event, exact signed outbox JSON, and retained gift-wrap
  envelopes before dispatch. [OutboxDao](../local/dao/OutboxDao.kt) insert-or-ignore preserves retry state.
  Money mutations recheck identity, roster/epoch and revision/command constraints; admission is capped
  at 5,000 pending rows. Controls bypass that cap to avoid half-admitted multi-event operations.
- `EventThrottler` is opportunistic: its 500-entry memory queue is not the durable queue.
  Failed dispatch or memory-queue overflow leaves committed outbox rows for worker recovery.
- [OutboxDrainScheduler](../../sync/worker/OutboxDrainScheduler.kt) requests network-constrained,
  `APPEND_OR_REPLACE` work with exponential backoff. One request has ten retries; exhausting it
  leaves rows for later saves/periodic sync rather than proving the events can never be sent.
- `flushOutbox` does not evict by retry count. After 50 failed attempts, noncritical rows are due
  at most every six hours; `group_meta`, `key_rotation`, and `key_revocation` remain due each pass.
- **Retention exception:** [DailySyncWorker](../../sync/worker/DailySyncWorker.kt) removes noncritical
  outbox rows idle over 90 days, using last attempt time or `createdAt` if never attempted.
  Controls are exempt. This cleanup is not conditional on demonstrated member delivery.
- Control effects are stored `PENDING` before application, then `APPLIED` or terminally `FAILED`.
  Missing originals can leave corrections/deletes pending too. Startup retries persisted effects;
  rotation/revocation [operation journals](../repository/ControlOperationJournal.kt) separately resume
  interrupted locally authored control plans. Undecryptable rejected events are not stored as pending.
- [SelfHealUseCase](../../domain/usecase/sync/SelfHealUseCase.kt) skips while gift wrapping is enabled.
  Otherwise it republishes eligible verifiable direct history absent from pooled relay IDs;
  it is not a guarantee that each new relay receives a full copy. Received unsigned rumors are skipped.
- The author can prepare fresh wraps for late joiners; original envelopes are also retained for
  [nearby reconciliation](../../sync/nearby/). Neither provides unlimited retention or guaranteed contact.
  Losing local data/keys, relay retention policies, permanent rejection, and OS scheduling still matter.

## Metadata visibility

| Observer | What remains visible |
| --- | --- |
| Relay receiving a direct event | Real signing pubkey, kind, timestamp, `d/g/t`, optional `x/p`, signature, ciphertext and its size. |
| Relay receiving a wrap | Ephemeral pubkey, kind `1059`, randomized timestamp, recipient `p`, group `g`, ciphertext size and arrival timing. Inner sender/type/expense tags are encrypted. |
| Relay receiving `AUTH` | User's real identity pubkey and the signed relay/challenge binding; authentication can link otherwise wrapped traffic to that identity. |
| Network endpoint/operator | Connections, timing, volume and network addresses; TLS protects transport content in transit, not against the relay endpoint. |
| Authorized recipient/group-key holder | Decrypted application data it has keys/envelopes for; members can retain or redistribute it. |

Gift wrapping reduces exposed inner metadata; it does **not** hide the group/recipient routing tags,
provide anonymity, or retroactively hide direct controls, snapshots, or earlier direct publications.
See the repository [privacy policy](../../../../../../../../PRIVACY.md) and
[security policy](../../../../../../../../SECURITY.md) for broader boundaries.

## Tests and verification

Run from the repository root after the [contributor setup](../../../../../../../../CONTRIBUTING.md).
These are commands for contributors, **not results claimed by this document**:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.splitfree.domain.crypto.*' --tests 'com.splitfree.data.nostr.*'
./gradlew :app:testDebugUnitTest --tests 'com.splitfree.sync.*' --tests 'com.splitfree.data.repository.RelaySyncCursorsTest' --tests 'com.splitfree.data.local.dao.*'
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug spotlessCheck
```

- [Nip44Test](../../../../../../test/java/com/splitfree/domain/crypto/nip/Nip44Test.kt) loads vendored
  vectors and checks encryption, MAC/padding, and invalid input cases.
  [Nip59Test](../../../../../../test/java/com/splitfree/domain/crypto/nip/Nip59Test.kt) includes a spec example,
  round-trips, tampering, sender binding, rumor IDs, and routing tags. These are not an independent audit.
- [NostrClientFetchTest](../../../../../../test/java/com/splitfree/data/nostr/NostrClientFetchTest.kt) uses fake
  relays for EOSE, cancellation, disconnection, overflow and partial coverage;
  [SyncEngineRotationCatchUpRoomTest](../../../../../../test/java/com/splitfree/sync/worker/SyncEngineRotationCatchUpRoomTest.kt)
  exercises database-backed rotation catch-up. [Sync tests](../../../../../../test/java/com/splitfree/sync/)
  cover publisher admission, deferred application, live recovery, and worker retries.
- [app/build.gradle.kts](../../../../../../../build.gradle.kts) excludes `*IntegrationTest*` unless
  `-DREAL_RELAY_TEST=true`. Dependency/tool downloads may still require network for ordinary builds.
- **Opt-in integration tests make real network writes.** After reviewing
  [RelayIntegrationTest](../../../../../../test/java/com/splitfree/data/nostr/relay/RelayIntegrationTest.kt)
  and accepting public test publication, the targeted command is:

```sh
./gradlew :app:testDebugUnitTest -DREAL_RELAY_TEST=true --tests 'com.splitfree.data.nostr.relay.RelayIntegrationTest' --rerun-tasks
```

The live fixture currently targets `nos.lol` and includes public kind-1 test publications with a
public test key. Passing it is not evidence for every configured relay, NIP-44/59 interoperability
across clients, long-term storage, member delivery, or Android background behavior on physical devices.
