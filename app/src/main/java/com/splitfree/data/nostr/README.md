# Nostr transport and synchronization

[Project README](../../../../../../../../README.md) · [Nearby protocol](../../sync/nearby/README.md) · [Contributor guide](../../../../../../../../CONTRIBUTING.md)

- SplitFree uses Nostr relays to exchange signed, encrypted application events.
- The ledger and outgoing queue live locally.
- A relay is a transport and storage peer.
  - It is not the authority for group membership, balances, or whether another member applied an event.

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
Outgoing: prepare event + deliveries → Room transaction → relay publication
Incoming: verify + decrypt + validate → Room event → apply pending effects → UI
```

- Saving locally, relay acceptance, and member application are separate outcomes.

## Implemented protocol subset

| NIP / convention | Implemented use and boundary |
| --- | --- |
| NIP-01 | Event serialization/IDs/signatures; outgoing `EVENT`, `REQ`, `CLOSE`; incoming `EVENT`, `OK`, `EOSE`, `CLOSED`, `NOTICE`. |
| NIP-78 / kind `30078` | App-specific addressable data carrying SplitFree JSON inside encrypted content. Other clients need SplitFree's tags, schemas, keys, and validation rules to interpret it. |
| NIP-44 v2 | secp256k1 ECDH, HKDF-SHA256, padded ChaCha20 content, HMAC-SHA256. Group-key derivation is a SplitFree convention, not a standardized group protocol. |
| NIP-59 | Unsigned rumor → sender-signed kind `13` seal → ephemeral-signed kind `1059` gift wrap. Outer `g` routing is a SplitFree extension with a privacy cost. |
| NIP-42 | Respond to `AUTH` challenges with signed kind `22242` when a signer is configured; replay limits are below. |
| NIP-11 | HTTPS information probes with `Accept: application/nostr+json`; parse advertised NIPs and payment requirement. |
| NIP-09 | `EventSigner.createDeletionEvent` can construct kind `5` requests. No production caller currently uses it; ledger deletion uses `expense_delete`, not relay erasure. |

- Filters support `kinds`, `authors`, `ids`, tag maps, `since`, `until`, and `limit`.
  - Callers supply wire tag names such as `#g` and `#p`; the serializer does not add `#`.
- `RelayMessage.parse` ignores malformed or unknown frames and checks event ID/pubkey/signature lengths before verification.
  - The separate `NostrEvent.fromJson` parser does not impose that length gate.
  - Neither parser establishes authenticity.
- The live client accepts kinds `30078` and `1059` and verifies signatures.
  - It keeps a bounded 10,000-ID in-memory deduplication set.
  - Persistent application deduplication uses the inner event ID.
- `AUTH` signs `challenge` and `relay` tags with the user's identity and empty content.
  - A socket allows at most three challenge responses.
  - Subscriptions are resent after the first response, not after a confirmed authentication ACK.
  - There is no general rejected-event replay here.

## Event shapes, tags, and addressing

### Kind `30078`: direct application events and inner rumors

- [EventSigner](../../domain/crypto/EventSigner.kt) emits these signed tags:

| Tag | Meaning |
| --- | --- |
| `d` | `groupId:eventType:commandId`: a relay address for one command, not the current state of the entire group. |
| `g` | Group ID used by `#g` subscription filters. |
| `t` | Application event type, such as `expense` or `key_rotation`. |
| `x` | Optional logical expense UUID; snapshots use their snapshot ID here. Revisions of one expense share its UUID. |
| `p` | Optional recipient identity; used on direct per-member `key_rotation` envelopes. |

- Kind `30078` is addressable by `(pubkey, kind, d)`.
- A fresh command address prevents an expense correction or deletion from replacing its original at that same relay address.

- `createSignedEvent` chooses a fresh random command ID.
- `createSignedCommandEvent` accepts a stable one for command recovery/idempotency.
- Identical canonical fields produce the same event ID.
  - Re-encrypting or changing the timestamp changes those fields.
  - A stable command ID alone does not make independently rebuilt events identical.

| `t` value | Content / normal publication path |
| --- | --- |
| `expense`, `expense_correction`, `expense_delete`, `settlement` | Group-encrypted payload. Per-member gift wraps when enabled; direct signed `30078` otherwise. |
| `group_meta` | Group-encrypted metadata, roster/name/relay updates. Creation, joins and subsequent updates publish directly regardless of gift-wrap preference. |
| `key_rotation` | Creator-to-recipient NIP-44-encrypted rotation payload, one direct `30078` with `p` per target member. The payload includes individually encrypted new-key entries. |
| `key_revocation` | Group-encrypted identity replacement record signed by the old identity; direct publication. Replaces/tombstones the identity, without rotating the group key or erasing old data. |
| `snapshot` | Creator-authored, group-encrypted balance snapshot. [CreateSnapshotUseCase](../../domain/usecase/expense/CreateSnapshotUseCase.kt) initially queues it directly via `saveAndQueue`; authored-history redelivery can also wrap it. |

- See [RotateGroupKeyUseCase](../../domain/usecase/group/RotateGroupKeyUseCase.kt) and [RevokeKeyUseCase](../../domain/usecase/group/RevokeKeyUseCase.kt) for control construction.

### Kind `1059`: recipient gift wraps

- Gift wrapping defaults to enabled in [UserPreferences](../settings/UserPreferences.kt).
- For wrapped group publications, the publisher prepares one envelope for each distinct member other than the author.
  - Envelopes are prepared in shuffled order.
- A sole author's event is saved with zero deliveries.

- The inner rumor retains the application kind, tags, timestamp, and group-encrypted content.
  - It is unsigned and has a self-consistent event ID.
- The kind `13` seal encrypts the rumor for the recipient.
  - The real sender signs it.
  - It is constructed with empty tags.
  - It is inside the wrap, not separately published.
- The kind `1059` outer event encrypts the seal.
  - A fresh ephemeral key signs it.
  - It carries `p = recipient` plus `g = groupId` copied from the rumor when present.
- Seal and wrap timestamps are independently randomized within the preceding 48 hours.
  - The rumor retains its original timestamp.
- Unwrapping verifies outer and seal signatures, seal kind, sender/rumor pubkey consistency, and the rumor ID.
  - Unwrapping is deliberately lenient about a nonempty incoming rumor signature.
  - Authentication comes from the seal, not that field.
- Stored received rumors carry a `seal:` signature marker.
  - That marker records authenticated ingestion.
  - It is not a standalone signature over the rumor suitable for third-party forwarding.

## Keys, epochs, and admission

### Encryption and identity

- An event ID hashes UTF-8 canonical JSON `[0,pubkey,created_at,kind,tags,content]` with SHA-256.
  - [NostrEvent.verify](../../domain/crypto/NostrEvent.kt) recomputes the ID and verifies BIP-340 Schnorr on secp256k1.
  - Signing uses fresh auxiliary randomness.
- Group keys are random 32-byte values encoded as Base64.
  - `GroupEncryption` derives valid secp256k1 sender/recipient secrets using HMAC-SHA256.
    - Labels: `splitfree-sender-N` and `splitfree-recipient-N` (`N` starts at zero).
  - It then derives a NIP-44 conversation key from that pair.
- Eligible plaintext may be LZ4-compressed and encoded with the internal `SF_LZ4:` prefix before encryption.
  - Compression and this key derivation are application conventions.
- NIP-44 accepts 1–65,535 UTF-8 plaintext bytes **per encryption layer**, including nested JSON/Base64 overhead.
  - Wire content is `base64(0x02 || nonce32 || ciphertext || hmac32)`.
  - Decryption proceeds in order:
    1. Check version/size.
    2. Authenticate the MAC before decrypting.
    3. Check padding after decryption.
- Shared-key possession permits content decryption, not impersonating another member's signature.
  - Recipients can retain/share plaintext and keys.
  - This is not a forward-secret ratcheting protocol.

### Epoch transitions

- [EventProcessor](../../sync/event/EventProcessor.kt) loads keys by immutable epoch, trying the current epoch and then older keys.
- Only `key_rotation` gets the additional pairwise author-to-recipient NIP-44 decryption attempt.
- The caller's `knownGroupKey` is ignored.

- [RotateGroupKeyUseCase.handleKeyRotation](../../domain/usecase/group/RotateGroupKeyUseCase.kt) requires the authenticated author to be the creator.
- Current/older epochs are ignored.
- Missing epochs or unknown joins defer a newer rotation.
- Only the next epoch can apply.
- When installing its key, conflicting already-stored key material is rejected rather than overwritten.

- Old epoch keys remain available for history.
- New-key distribution targets remaining members, including the creator.
- Neither member removal nor identity revocation erases retained keys/plaintext.

### Incoming admission

- `EventProcessor` separates authentication from ledger authority:

1. **Authentication and scope.**
   - Verify a direct signature or authenticated rumor.
   - Inner `g` determines the group; `knownGroupId`/`expectedGroupId` can reject it, never rebind it.
   - Live/pull callers skip `p`-addressed copies for other identities.
2. **Deduplication and traffic limits.**
   - Check stored inner IDs before rate limits.
   - Re-drive pending effects.
   - [EventValidator](../../domain/validation/EventValidator.kt) applies a 30-day age window and author/group rate counters to normal live traffic.
   - Reconciliation skips those counters and age limits, but rejects nonpositive or more-than-one-hour-future timestamps.
3. **Membership and payloads.**
   1. Check membership.
   2. Decrypt with an eligible key.
   3. Before storage, validate content, author-qualified expense revisions and money payloads.
4. **Control authority.**
   - [EventPostProcessor](../../sync/event/EventPostProcessor.kt) restricts member metadata to their own membership/display name.
   - Group settings and other members require creator authority.
   - Self-joins require current-key decryption, a roster adding only the author and a non-revoked identity.
   - Rotations/revocations have separate handlers.

- [MembershipHistory](../../sync/event/MembershipHistory.kt) uses applied rotations this device can decrypt.
  - Its reconciliation exception admits a removed author's `expense`, `expense_correction`, `expense_delete` or `settlement` only under an epoch before their latest recorded removal.
  - The exception does not admit controls or snapshots.
- This checks key-epoch eligibility, not authorship time.
  - A removed author retaining an old key can create a backdated record that passes.
- No creator-signed membership checkpoint closes that gap.

## Publication: what success means

| Observation | Meaning / non-guarantee |
| --- | --- |
| `publishExpense` / `publishMutation` returns true | Event and all prepared delivery rows committed. This is save admission, not relay acceptance. |
| WebSocket `send` returns true | Frame accepted into the socket's send path; not relay acceptance. |
| Relay `OK(eventId, true, ...)` | That relay reported acceptance. No proof of indefinite retention, replication, or recipient access. |
| `NostrClient.publish` returns true | At least one target relay accepted; targets are attempted concurrently and their results awaited. Not an all-relay quorum. |
| Successful publication removes an outbox row | That envelope's transport retry obligation ended. Age-based cleanup can also remove rows; see [recovery](#durable-recovery-and-its-limits). |
| Incoming `APPLIED` / `DEFERRED` / rejection | Local ingestion result, independent of the sender's relay ACK. |

- [Relay.sendEvent](relay/Relay.kt) normally waits up to seven seconds for the matching `OK`.
- Concurrent sends of the same ID to one relay share an in-flight request.
- Rejection, send failure, timeout, or disconnect does not establish acceptance.
  - A timed-out relay may still have stored it.

- Recipient envelopes settle independently.
- Neither relay ACKs nor EOSE acknowledge member delivery or application.

## Relay selection and availability

- [RelayDefaults](../../domain/util/RelayDefaults.kt) is the source of truth for these configured lists:

| Pool | Current configured endpoints (all `wss://`) |
| --- | --- |
| Default primaries | `purplerelay.com`, `nos.lol`, `relay.primal.net`, `relay.snort.social`, `offchain.pub` |
| Fallbacks | `nostr.data.haus`, `nostr.oxtr.dev`, `relay.nostr.wirednet.jp` |

- When resolving a connection set, `RelayConnectionManager` unions each local group's primary list (defaults when empty) with all fallbacks.
  - Cached online status only orders attempts; an offline primary is not excluded.
  - An already-connected, non-forced call reuses the current pool.
- This is a **shared pool**, not per-group isolation.
  - Publish fan-out and subscriptions use its relays.
  - A group's ciphertext/metadata can reach another local group's primary relay as well as fallbacks.
- Custom selection is **not a custom-only/private-relay mode**.
  - The manager adds fallbacks; `NostrClient.connect` itself does not.
  - Relay-list migration calls it directly with the old/new selected lists, temporarily replacing the shared set until a manager-driven refresh.
- [UpdateGroupRelaysUseCase](../../domain/usecase/group/UpdateGroupRelaysUseCase.kt) requires the creator, a nonempty list, and invite-link compatibility.
  - [InviteLinkCodec](../../domain/invite/InviteLinkCodec.kt) caps selection at 10 relays.
  - Each custom URL is at most 254 UTF-8 bytes.
  - Custom URLs total at most 255 bytes, **including one length byte per URL**.
  - Known relays use a bitmap.
- The 10-relay limit is per selected list, not a global socket-pool cap.
  - Transport accepts only `wss://`-prefixed URLs.
  - That check alone does not prove a URL is valid, reachable, or trustworthy.
- `RelayHealthMonitor` marks successful HTTP responses online even with empty/malformed NIP-11 documents.
  - Advertised NIP-59 support is not required.
  - `verifyRelayRoundTrip` separately **publishes a supplied test event** and looks for its ID in a kind-30078 readback.
  - Configuration and NIP-11 advertisements do not establish current routing, retention or delivery support.
- `ensureConnected` waits up to five seconds for any relay, then may return while offline.
- Relay reconnects use exponential backoff with jitter.
  - Base delay is capped at 60 seconds.
  - After 20 attempts, reconnects pause until another connection cycle resets the budget.

## Catch-up, EOSE, and durable cursors

### Query windows

| Query | Filter / window |
| --- | --- |
| Main group history and live subscription | Kinds `30078` and `1059`, `#g = groupId`. Omit `since` when requesting all history. |
| Additional recipient filter | Kind `1059`, `#p = myPubkey`; widen `since` by 48 hours, floored at zero, for randomized outer timestamps. |
| Self-heal ID fetch | Kind `30078`, `#g`; returns pooled IDs, not independently certified per-relay inventories. |
| Standalone `fetchGiftWraps` helper | Kind `1059`, `#p`, last 24 hours, ten-second fetch timeout, events only. It is not the main widened group catch-up and can miss older randomized wraps. |

### Fetch completion

- Historical fetch proceeds in order:
  1. Briefly settle connecting sockets.
  2. Attach collectors **before** sending REQs.
  3. Normally wait up to 15 seconds for EOSE.
  - Missing collector readiness fails the fetch.
- EOSE is counted once per relay and subscription.
  - One relay cannot satisfy another's completion.
- Cleanup closes temporary subscriptions and joins collectors, including on cancellation.
- Completion excludes a relay that disconnected, reopened, dropped buffered frames, or was not connected when queried.
  - Requested but unavailable relays remain incomplete even if others EOSE.
- The 4,096-frame relay buffer can overflow.
  - Dropped events trigger a delayed re-REQ covering the older of last-delivered/oldest-dropped timestamps minus 60 seconds.
  - Reconnect replay uses the same mechanism.
  - This is recovery effort, not lossless transport or proof of full history.
- EOSE means the relay says it finished this request.
  - A dishonest, retention-limited, or policy-filtered relay can omit events.
  - The client has no global inventory proving otherwise.
  - Main history fetches have no pagination loop; omitting `limit` does not guarantee unlimited results.

### Durable coverage

- [RelaySyncCursorEntity](../local/entities/RelaySyncCursorEntity.kt) keys coverage by `(groupId, relayUrl, recipientPubkey)`.
- [RelaySyncCursorDao](../local/dao/RelaySyncCursorDao.kt) advances coverage monotonically.
  - A missing entry starts at zero, never at the group's progress timestamp.

- `SyncEngine` queries configured primaries, fallbacks, and the client's current pool.
  - Each window starts at the earlier of the caller's `since` and that relay's cursor minus a one-hour overlap.
- Received events are sorted by outer `created_at`, then ID.
  - Other recipients' copies and clearly other-group wraps are skipped.
  - The processor checks the authenticated inner group again.
- All pulls use reconciliation admission.
  - Deferred effects are retried before/after processing.
  - Rejected dependency/quota records get up to eight progress-driven passes within the batch.
- After processing, only completed relays advance to **fetch-start time**.
  - Unstored retryable dependencies/quota failures conservatively hold every cursor because per-event relay provenance is not retained.
  - An identity change during the pull prevents certifying the old recipient's window.
- `group.lastSyncTimestamp` is a progress indicator, not proof that every relay was covered.
  - Persisted pending effects may coexist with completed transport coverage; they have local recovery.

### Visible-app recovery

- [LiveSync](../../sync/worker/LiveSync.kt) runs while the app is visible:
  1. Attach its incoming collector before subscribing.
  2. Catch up.
  3. Subscribe from just before the pull with overlap.
- New groups, relay-set changes, and socket-open generation changes signal recovery, even if another relay kept aggregate connectivity true.
  - Signals are coalesced and recovery is rate-limited.
- Incomplete catches get bounded 30/60/120-second retries and scheduled-sync fallback.
- Hiding the app releases this session.
- WorkManager timing can be deferred.

## Durable recovery and its limits

### Outgoing queue

- [EventPublisher](../../sync/event/EventPublisher.kt) commits the event, exact signed outbox JSON and retained gift wraps before dispatch.
- [OutboxDao](../local/dao/OutboxDao.kt) insert-or-ignore preserves retry state.

- Money saves recheck identity, roster/epoch and revision/command constraints.
  - Admission rejects a resulting outbox count above 5,000.
  - This is not a global cap: direct, generic group and `saveAndQueue` paths bypass it.
  - Thus multi-event controls do not hit a money-save quota midway through a plan.
- `EventThrottler` is an opportunistic 500-entry memory queue.
  - Dispatch failure or overflow leaves committed rows for worker recovery.
  - Its failed attempts do not increment durable retry counters.
- [OutboxDrainScheduler](../../sync/worker/OutboxDrainScheduler.kt) requests network-constrained `APPEND_OR_REPLACE` work through `SyncScheduler`.
  - Exponential backoff allows ten retries per `OutboxWorker` request.
  - Exhaustion fails the chain but leaves rows for later saves/periodic sync.
- `SyncEngine.flushOutbox` records failed attempts without evicting rows.
  - At 50 recorded failures, noncritical rows are due no more often than every six hours.
  - `group_meta`, `key_rotation` and `key_revocation` remain due each pass.
  - The delay limits flushes, not every possible publication path.
- [DailySyncWorker](../../sync/worker/DailySyncWorker.kt) removes noncritical outbox rows whose `COALESCE(lastRetryAt, createdAt)` is older than 90 days.
  - The three control types above are exempt.
  - This cleanup is based on inactivity, not demonstrated member delivery.

### Incoming effects and interrupted controls

- Controls are stored `PENDING` before effects run, then marked `APPLIED` or terminally `FAILED`.
- Corrections/deletes awaiting their author's original also remain pending.
  - Admission rejects them when that author/group already has 128 pending rows.
- Startup and pulls retry persisted effects.
- Undecryptable rejections are not stored pending and must be supplied again by a fetch/session.

- Locally authored rotation/revocation [operation journals](../repository/ControlOperationJournal.kt) separately preserve intents and prepared signed events to resume interrupted plans.

### Replication and late joiners

- [SelfHealUseCase](../../domain/usecase/sync/SelfHealUseCase.kt) skips while gift wrapping is enabled.
  - Otherwise, with a connection, it republishes local history from current members only when both conditions hold:
    - The original signed JSON is available.
    - Its ID is absent from the pooled relay result.
  - Unsigned/`seal:` rumors are ineligible.
  - An ID present on one relay suppresses repair to others, so this is not per-relay replication.

- When metadata adds members locally, `EventPostProcessor` requests authored-history redelivery.
  - With wrapping enabled, it prepares fresh wraps of this device's expenses, corrections, deletions, settlements and snapshots for those members.
  - Redelivery fills only available space below the 5,000-row admission limit.
  - It can queue a partial batch.
  - Preparing a wrap does not supply historical group keys needed to decrypt the rumor's content.

- Original envelopes are also retained for [nearby reconciliation](../../sync/nearby/).
- Recovery still depends on local data/keys, relay retention and policy, available peers, and OS scheduling.

## Metadata visibility

| Observer | What remains visible |
| --- | --- |
| Relay receiving a direct event | Real signing pubkey, kind, timestamp, `d/g/t`, optional `x/p`, signature, ciphertext and its size. |
| Relay receiving a wrap | Ephemeral pubkey, kind `1059`, randomized timestamp, recipient `p`, group `g`, ciphertext size and arrival timing. Inner sender/type/expense tags are encrypted. |
| Relay receiving `AUTH` | User's real identity pubkey and the signed relay/challenge binding; authentication can link otherwise wrapped traffic to that identity. |
| Network endpoint/operator | Connections, timing, volume and network addresses; TLS protects transport content in transit, not against the relay endpoint. |
| Authorized recipient/group-key holder | Decrypted application data it has keys/envelopes for; members can retain or redistribute it. |

- Gift wrapping reduces exposed inner metadata.
  - It does **not** hide the group/recipient routing tags.
  - It does not provide anonymity.
  - It does not retroactively hide direct controls, snapshots, or earlier direct publications.
- See the repository [privacy policy](../../../../../../../../PRIVACY.md) and [security policy](../../../../../../../../SECURITY.md) for broader boundaries.

## Tests and verification

- Run from the repository root after the [contributor setup](../../../../../../../../CONTRIBUTING.md).
- The following commands run the relevant unit suites and build checks:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.splitfree.domain.crypto.*' --tests 'com.splitfree.data.nostr.*'
./gradlew :app:testDebugUnitTest --tests 'com.splitfree.sync.*' --tests 'com.splitfree.data.repository.RelaySyncCursorsTest' --tests 'com.splitfree.data.local.dao.*'
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug spotlessCheck
```

- [Nip44Test](../../../../../../test/java/com/splitfree/domain/crypto/nip/Nip44Test.kt) loads vendored vectors and checks encryption, MAC/padding, and invalid input cases.
- [Nip59Test](../../../../../../test/java/com/splitfree/domain/crypto/nip/Nip59Test.kt) includes a spec example, round-trips, tampering, sender binding, rumor IDs, and routing tags.
- [NostrClientFetchTest](../../../../../../test/java/com/splitfree/data/nostr/NostrClientFetchTest.kt) uses fake relays for EOSE, cancellation, disconnection, overflow and partial coverage.
- [SyncEngineRotationCatchUpRoomTest](../../../../../../test/java/com/splitfree/sync/worker/SyncEngineRotationCatchUpRoomTest.kt) exercises database-backed rotation catch-up.
- [Sync tests](../../../../../../test/java/com/splitfree/sync/) cover publisher admission, deferred application, live recovery, and worker retries.
- [app/build.gradle.kts](../../../../../../../build.gradle.kts) excludes `*IntegrationTest*` unless `-DREAL_RELAY_TEST=true`.
  - Dependency/tool downloads may still require network for ordinary builds.
- **Opt-in integration tests make real network writes.**
  - Review [RelayIntegrationTest](../../../../../../test/java/com/splitfree/data/nostr/relay/RelayIntegrationTest.kt) and accept public test publication before running the targeted command:

```sh
./gradlew :app:testDebugUnitTest -DREAL_RELAY_TEST=true --tests 'com.splitfree.data.nostr.relay.RelayIntegrationTest' --rerun-tasks
```

- The live fixture targets `nos.lol` and includes public kind-1 test publications with a public test key.
- Its publish assertions allow rejection, so a passing run does not require acceptance of that event.
- These tests are not a cryptographic audit or broad interoperability certification.
- Retention, member delivery and Android background behavior require separate verification.
