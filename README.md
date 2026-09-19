<p align="center">
  <img src="assets/splitfree-logo.png" alt="SplitFree" height="96"/>
</p>

<h1 align="center">SplitFree</h1>

<p align="center">
  <b>Decentralized expense splitting for Android.</b><br/>
  No signup. No subscription. No developer-operated backend.
</p>

<p align="center">
  Android 8.0+ · Kotlin · Jetpack Compose · Nostr · <a href="LICENSE">GPL-3.0-or-later</a>
</p>

<p align="center">
  <a href="#why-splitfree">Why SplitFree?</a> ·
  <a href="#features">Features</a> ·
  <a href="#getting-started">Getting started</a> ·
  <a href="#how-it-works">How it works</a> ·
  <a href="#backups-and-recovery">Recovery</a> ·
  <a href="#architecture">Architecture</a> ·
  <a href="#testing">Testing</a> ·
  <a href="#contributing">Contributing</a>
</p>

SplitFree is an open-source Android app for sharing expenses with friends, flatmates, and travel companions.

- **Track shared costs:** record who paid, split expenses, and see who owes whom.
- **Work offline:** keep your ledger and calculate balances on your phone.
- **Sync privately:** exchange encrypted updates through [Nostr](https://nostr.com) relays or nearby group members.

<p align="center">
  <img src="assets/screenshots/groups.png" alt="Groups and balances in light mode" width="195"/>
  <img src="assets/screenshots/add-expense.png" alt="Adding an expense with an equal split" width="195"/>
  <img src="assets/screenshots/balances-dark.png" alt="Group balances and suggested payments in dark mode" width="195"/>
</p>

## Why SplitFree?

Shared expenses should be simple to track, without a subscription or a new account.

| Principle | What it means in SplitFree |
| --- | --- |
| **No signup** | A locally generated cryptographic keypair identifies you. No email or phone number is required. |
| **Local-first** | Add expenses and calculate balances on your device; synchronization happens separately. |
| **No proprietary backend** | Use third-party Nostr relays and direct Nearby connections rather than a SplitFree cloud service. |
| **Private payloads** | Expense content is end-to-end encrypted. Routing metadata remains visible to relays. |
| **Portable data** | Back up your identity and export group data instead of depending on account recovery from a provider. |
| **Open source** | Inspect, build, and modify the app under GPL-3.0-or-later. No subscription or ad-network SDKs. |

- Nostr relays are third-party servers; Nearby and QR scanning use Google Play services.
- See [Privacy](#privacy) for what those dependencies can see.

## Features

### Expense management

| Capability | Behavior |
| --- | --- |
| Flexible splits | Equal shares, exact amounts, percentages, or weighted shares. |
| Multiple currencies | Balances stay separate by currency; amounts use integer minor units. No exchange-rate conversion or combined cross-currency total. |
| Corrections and settlements | Edit or delete your own expenses and record payments. Each change creates another ledger event rather than rewriting signed history. |
| Debt simplification | Suggested debtor-to-creditor payments settle each currency's balances. The greedy algorithm does not guarantee the fewest possible payments. |
| Balance snapshots | Creator-signed snapshots record covered events and can reduce repeated balance computation. |

SplitFree records payments; it does not transfer money or connect to a bank.

<a id="privacy-and-identity"></a>

### Identity and sharing

- A locally generated keypair identifies you without an email address or phone number.
- A 24-word recovery phrase backs up that identity.
  - Group exports back up available group data and keys separately.
- Private links and QR codes carry the group's current key, relay list, and joining information.
- NIP-44 v2 encrypts application payloads.
  - Optional NIP-59 gift wrapping, enabled by default, conceals the author of wrapped money events inside
    recipient-encrypted layers.
- The creator can remove members and rotate group keys.
  - A member can replace their own identity.
  - Neither operation erases keys or history someone already obtained.

<a id="sync-and-connectivity"></a>
<a id="everyday-use"></a>

### Sync and everyday use

- **Relay sync:** foreground subscriptions, durable outgoing delivery rows, and per-relay history recovery.
  - Android schedules background retries; relay acceptance is not a member-delivery receipt.
- **Nearby sync:** foreground exchange over Google Nearby Connections without a Nostr relay or internet connection for
  the transfer.
  - The SDK chooses Bluetooth, BLE, or Wi-Fi.
- **Carry-forward delivery:** members can retain recipient-encrypted envelopes and offer them to another member later,
  within the protocol's storage and retention limits.
- **Android UI:** Material 3, system/light/dark themes, QR scanning, invite pasting, and per-group or all-group
  exports.
- **Diagnostics:** a local report in Settings is available in both variants; debug builds also expose an in-app log.
  - Review and redact diagnostics before sharing them.

## Getting started

### Use the app

- **Android:** 8.0 (API 26) or newer.
- **Google Play services:** required for Nearby sync and QR scanning.
- **Installation:** build from source below; see the [release guide](RELEASING.md) for a signed APK build.

| Step | Action |
| --- | --- |
| **1. Set up** | Create an identity and back up its recovery phrase. |
| **2. Join a group** | Create your own group or use a privately shared invite. |
| **3. Add expenses** | Enter the amount, choose who paid, and select the split. |
| **4. Settle up** | Review balances and record payments. |
| **5. Sync nearby** | Keep both phones on the group's Nearby screen, grant the requested permissions and enable Bluetooth. When a peer appears, tap its Sync action on either phone. |

- Keep both your recovery phrase and `.splitfree` exports.
- See [Backups and recovery](#backups-and-recovery).

### Build from source

| Requirement | Version / setup |
| --- | --- |
| JDK | **17** matches the repository CI configuration; select it with `JAVA_HOME` |
| Android SDK | API **37**, installed as `platforms;android-37.0`; Build Tools **36.1.0** |
| Gradle | Included wrapper; no separate Gradle installation needed |
| Android Studio | Optional; use a version compatible with the pinned Android Gradle Plugin |

```bash
git clone https://github.com/invincible04/SplitFree.git
cd SplitFree
./gradlew :app:assembleDebug
```

- **SDK path:** set `ANDROID_HOME` or configure `sdk.dir` in untracked `local.properties`.
- **Android Studio:** open the repository root, let Gradle sync, then select a device.
- **Windows:** use `gradlew.bat` instead of `./gradlew`.
- **Debug signing:** no release keystore or signing passwords needed.

| Command | Result |
| --- | --- |
| `./gradlew :app:assembleDebug` | Debug APK at `app/build/outputs/apk/debug/app-debug.apk` |
| `./gradlew :app:assembleRelease` | Signed, minified APK at `app/build/outputs/apk/release/app-release.apk`; requires signing setup |
| `./gradlew spotlessCheck` | Check Kotlin and Gradle formatting without modifying files |
| `./gradlew -PsplitfreeUnsignedRelease=true :app:assembleRelease` | Unsigned, minified release APK for build checks, not installation |
| `./gradlew :app:lintDebug :app:lintRelease` | Analyze both variants with Android Lint |

> - **Installation safety:** debug is `com.splitfree.debug` (**SplitFree Debug**); production remains `com.splitfree`
>   (**SplitFree**).

- Debug and production can coexist with separate private data.
  - Debug does not claim external production invite links; use its in-app Scan or Paste.
- Older debug builds used the production ID.
  - The new suffix does not migrate their data or make them replaceable by a differently signed release.
  - Do not uninstall or clear them without verified backups.
- For a local handoff, use the [verified distribution command](RELEASING.md#verified-local-distribution) and preserve
  its bundle.
  - A successful build alone does not establish installation or device acceptance.

### Release signing

- For a locally signed release, follow the key setup in [RELEASING.md](RELEASING.md).
- The build expects `splitfree-release.jks` at the repository root and these properties in untracked
  `local.properties`:

| Property | Value |
| --- | --- |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Release-key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

- **Keep credentials private:** both files are Git-ignored; back them up securely.
- **Preserve update compatibility:** retain the signing identity and increase `versionCode` for updates.
- **Configure the file:** the build reads these properties from `local.properties`, not environment variables.
- **Verify the release:** follow [RELEASING.md](RELEASING.md) for signatures, checksums, source, notices, and device
  acceptance.

**CI scope:** formatting, JVM tests, release-helper tests, both lints, and debug/unsigned release builds.

- **Release automation:** the checked-in workflow prepares a signed APK and draft GitHub Release only after its
  source, license, version, and signing gates pass.
- It does not publish the draft.
- See the [release setup](RELEASING.md#automated-apk-releases); workflow configuration alone is not a completed
  release.

## How it works

### 1. Create an identity and a group

| Component | How it works |
| --- | --- |
| **Identity** | A secp256k1 keypair is generated locally. Your public key identifies you; your private key signs events. |
| **Recovery phrase** | A 24-word BIP-39 phrase encodes your private key. Keep it secret. |
| **Group key** | Each group gets a random 256-bit shared key. `GroupEncryption` derives its NIP-44 conversation key. |
| **Key epoch** | The group-key version. Rotation advances the epoch and delivers the new key individually to remaining members, not under the old shared key. |

### 2. Invite members privately

Share a QR code or a compact link: `splitfree://join?d=...`.

| Included in the link | Purpose |
| --- | --- |
| Group ID, original creator's public key, creation time | Identify the group; the decoder verifies this immutable binding even after creator replacement. |
| Creator replacement certificates, when needed | Verify current creator authority without sharing historical group keys. |
| Group key and epoch | Let the recipient decrypt data under the included key version. |
| Relay list, expiry, group name | Locate the group's relays, check link expiry, and display the group name. |

> - **Treat invitations as secrets:** the link itself grants access to the included group key.

- Share invites in person or through a trusted private channel.
- Use a fresh link after membership or relay changes.
- Expiry does not erase a disclosed key; previously copied links are not automatically revoked.
- Compact invitations carry at most four creator replacement certificates (including known conflicting history). Longer histories show an explicit limit; authority proofs are never truncated.

### 3. Record expenses and compute balances

- **Event format:** expenses and settlements use Nostr kind `30078` with encrypted JSON payloads.
- **Direct events:** signed by the original author.
- **Gift-wrapped events:** authenticated through a signed seal; the inner event has no standalone signature.
- **Expense identity:** group, original author, and UUID.
  - Reusing a UUID cannot overwrite another member's expense.
- **Balances:** computed from applied events, with snapshots reducing replay work.

**Debt simplification**

- Matches the largest creditor and debtor until balances settle.
- Keeps each currency separate.
- Produces at most **n − 1 transfers per currency** among *n* members with non-zero balances in that currency.
- Uses a greedy algorithm; the result is not guaranteed to use the mathematically fewest transfers.

### 4. Sync through relays

| Situation | Behavior |
| --- | --- |
| **App visible** | `LiveSync` catches up history and streams new events. |
| **App in background** | WorkManager handles queued delivery, periodic catch-up, and daily full sync. No persistent syncing notification. |
| **Relay unavailable** | Its persisted history cursor remains incomplete, even when a fallback relay responds. |
| **Connection restored** | Recovery retries on reconnection and bounded foreground timers, then uses scheduled work. |

**Delivery boundaries**

- Android may defer background jobs; public relays may be unavailable or discard history.
- Group relays are configurable, but built-in fallback relays are also included.
- Events publish through the connected relay pool, not exclusively through one group's selected relays.
- A successful publish means at least one relay accepted the event, not that every member received or applied it.
- **Sync is not a backup.** Keep identity and group-data backups separately.

### 5. Sync with nearby members

| Stage | Behavior |
| --- | --- |
| **Discover** | Both phones keep the group's Nearby screen in the foreground. Once required permissions and Bluetooth are ready, advertising and discovery start without a separate Scan action. |
| **Connect** | Either person taps Sync for a discovered peer. Incoming connections are handled while the run is active; the other person does not need to tap simultaneously. |
| **Authenticate** | Each phone proves key ownership with a Schnorr challenge; channel binding uses the SDK connection token when supplied. |
| **Authorize** | Membership checks permit the session to open one group. |
| **Reconcile** | Bounded frames, paged inventories, and receipts track the records exchanged. |
| **Reconnect** | Already stored records and pending work remain local. A new connection authenticates again and reconciles fresh inventories; partial frame buffers and session counters are not resumed. |

**Lifecycle and forwarding**

- Stop keeps Nearby off until Start for that screen owner.
  - Screen disposal or lifecycle stop requests asynchronous cleanup; returning requests a fresh run if sync is still
    enabled and prerequisites hold.
- Discovery is continuous while its capability is running, not battery-duty-cycled.
  - Capability failures can pause discovery for retries; neither Task success nor an empty peer list proves physical
    discovery.
- Sealed envelopes can be carried to their intended recipients.
- Newly received records can be offered to other connected peers.
- An unsigned inner event alone is not independently verifiable forwarding evidence.
- Both phones must use Nearby protocol **v3**; their offered capability intersection is bound into authentication.
- “Up to date” describes the exchange, not proof that every device has identical group state.
- Forwarding is foreground-only and bounded by storage and retention limits.

See the [Nearby protocol guide](app/src/main/java/com/splitfree/sync/nearby/README.md) for the wire format, recovery rules, and limits.

## Backups and recovery

- A recovery phrase restores your identity, not your group keys or expense history.
- Keep both:

| Backup | Purpose | Keep in mind |
| --- | --- | --- |
| **24-word recovery phrase** | Restore the original private key and identity | Anyone with it can act as you. It does not contain the group ledger. |
| **`.splitfree` export** | Restore exported records and included epoch keys | Requires the exporting identity; readable metadata is not hidden by the file authentication. |

**Backup format v3**

- Includes the stored event records available on this device; it cannot recover records or keys already missing here.
- Preserves encrypted event payloads rather than exporting decrypted expense text.
- Encrypts group keys to the exporting identity.
- Retains the verified original creator binding and known creator replacement certificates, including when event history is empty.
- Rejects prelaunch format-v2 files; create a fresh export with the current build.
- Authenticates the recognized export fields with HMAC-SHA256 and an identity-derived key.
- Import validates the backup and merges admissible records; it is not a complete app-storage image or outbox backup.

**Recovery checklist**

- Store your phrase separately from exports.
- Refresh exports after important changes.
- Restore using the same identity that created the export.
- Do not rely on Android system backup; it is disabled for app data.
- Neither SplitFree nor relays can reconstruct lost keys for you.

## Security

| Protection | Scope |
| --- | --- |
| **Event validation** | Checks signatures, group scope, author permissions, and payload bounds before applying updates. |
| **Nearby authentication** | Binds proof of identity to the SDK connection token when supplied; a missing token does not provide channel binding. |
| **Key storage** | Encrypts private and group keys with AES-256-GCM under an Android Keystore key. Hardware backing depends on the device. |
| **Release hardening** | Enables R8 shrinking and obfuscation; these do not replace cryptographic checks. |

- Previously shared keys and history cannot be taken back.
- Historical membership has limits when a former member retains an old key; see [known
  limits](app/src/main/java/com/splitfree/sync/nearby/README.md#known-limits).

> - **Report vulnerabilities privately:** follow the reporting instructions and availability guidance in
>   [SECURITY.md](SECURITY.md).

Never post private keys, recovery phrases, usable invitations, or backup files in public issues.

## Privacy

- No developer-operated backend or relay.
- No app-configured analytics, advertising, or remote crash-reporting service.
  - Local diagnostics are still recorded.
- Encryption protects payloads; the app is not anonymous and its entire database is not encrypted.

**What remains visible**

| Observer / storage | What remains visible |
| --- | --- |
| **Nostr relays** | Ciphertext, public event fields and tags, connection IP addresses, and activity timing. Gift wraps still expose recipient and group tags. |
| **Local app storage** | Group names, member lists, relay lists, and event metadata. Expense payloads remain encrypted; Android's sandbox protects access to app files. |
| **Local diagnostics** | Crash/heartbeat reports persist in app-private preferences; debug logs and Nearby diagnostic state can contain identifiers or exception text. Redaction is limited, not a guarantee of anonymity. |
| **Google Play services** | Nearby and scanner modules have their own diagnostics and module downloads, governed by Google's policies and device settings. |

Read [PRIVACY.md](PRIVACY.md) for storage, permissions, and third-party-services details.

## Architecture

SplitFree uses a **single Android app module**, organized into UI, domain, data, and sync packages.

- Repository contracts separate storage access from business operations.
- Focused use cases handle expense, group, and recovery actions.
- Relay and Nearby traffic share one event-processing pipeline.
- Boundaries are package-level, not separately enforced Android-free modules.

```mermaid
flowchart LR
    UI[Compose and ViewModels] --> Domain[Use cases]
    Domain --> Store[Room ledger and outbox]
    Store <--> Sync[Sync and event processing]
    Sync <--> Relays[Nostr relays]
    Sync <--> Peers[Nearby peers]
```

| Layer | Responsibilities | Main components |
| --- | --- | --- |
| **UI** | Screens, navigation, observable state, and reusable controls | Compose, ViewModels, `StateFlow`, Material 3 |
| **Domain** | Expense rules, balances, group operations, identity, and crypto | Use cases, models, repository contracts, `GroupEncryption`, `EventSigner` |
| **Data** | Persistence, secure key storage, repositories, and transports | Room, Android Keystore-backed storage, `NostrClient`, `NearbySync` |
| **Sync** | Validate and apply events, track delivery, recover history, and own foreground Nearby runs | `EventProcessor`, `EventPublisher`, `SyncEngine`, `LiveSync`, `NearbySessionController`, `PeerSession` |

### Protocols and cryptography

- **Implemented here:** Nostr protocol handling, NIP-44/NIP-59 support, and BIP-39 encoding.
- **Library primitives:** secp256k1-kmp and Bouncy Castle provide the underlying cryptography.

| Specification | Role | Implementation |
| --- | --- | --- |
| [NIP-01](https://nips.nostr.com/1) | Event model, signatures, subscriptions, relay messages | `NostrEvent`, `Relay`, `ClientMessage`, `RelayMessage` |
| [NIP-44](https://nips.nostr.com/44) | Version 2 encrypted payloads | `Nip44`: ChaCha20, HKDF, HMAC-SHA256, and padding |
| [NIP-59](https://nips.nostr.com/59) | Recipient-specific gift wrapping | `Nip59`: unsigned inner event → signed seal → outer gift wrap |
| [NIP-78](https://nips.nostr.com/78) | Application-specific data | Kind `30078` for ledger and group events |
| [BIP-39](https://en.bitcoin.it/wiki/BIP_0039) | 24-word identity backup and recovery | `Bip39`: private-key entropy encoded with the English wordlist and checksum |
| [BIP-340](https://bips.dev/340) | Schnorr signing and verification | `EventSigner`, `NostrEvent`, and secp256k1-kmp |

### Tech stack

| Area | Technology |
| --- | --- |
| Language | Kotlin 2.4.20 |
| Interface | Jetpack Compose, Material 3, Navigation Compose |
| Dependency injection | Hilt |
| Database | Room / SQLite |
| Networking | OkHttp WebSockets |
| Nearby transport | Google Nearby Connections |
| Cryptography | secp256k1-kmp, Bouncy Castle |
| Serialization / compression | kotlinx.serialization, LZ4 |
| Background work | WorkManager |
| QR generation / scanning | ZXing / Google ML Kit Code Scanner |
| Build | Gradle 9.7.1, Android Gradle Plugin 9.4.0, version catalog |
| Formatting / tests | Spotless + ktlint; JUnit 4, MockK, Robolectric, Compose UI tests |

Dependency versions are maintained in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

### Further reading

| Guide | Focus |
| --- | --- |
| [Nostr and relay sync](app/src/main/java/com/splitfree/data/nostr/README.md) | Event formats, encryption, relay acknowledgments, and history recovery. |
| [Nearby protocol](app/src/main/java/com/splitfree/sync/nearby/README.md) | Authentication, reconciliation, forwarding, and completion limits. |
| [Contributing](CONTRIBUTING.md) | Development workflow, test commands, and code organization. |
| [Releasing](RELEASING.md) | Signing, APK verification, source/notices, and device acceptance. |

## Project structure

```text
app/src/main/java/com/splitfree/
├── data/
│   ├── ble/              # Google Nearby Connections transport
│   ├── identity/         # Identity/keypair management
│   ├── local/            # Room database, DAOs, entities
│   ├── nostr/            # Relay client, protocol, health and connection management
│   ├── repository/       # Repository implementations
│   ├── settings/         # Device preferences
│   └── util/             # Compression and encrypted storage
├── di/                   # Hilt dependency bindings
├── domain/
│   ├── crypto/           # Events, signatures, group encryption, NIP and BIP support
│   ├── invite/           # Versioned invite encoding and validation
│   ├── model/            # Groups, expenses, balances, exports, and sync models
│   ├── money/            # Currency catalog, amount parsing, and split calculations
│   ├── repository/       # Storage, identity, and publishing contracts
│   ├── usecase/          # Expense, group, backup, and recovery operations
│   ├── util/             # Shared helpers and relay defaults
│   └── validation/       # Event and payload checks
├── sync/
│   ├── event/            # Event processing, publication, and membership history
│   ├── nearby/           # Authentication, peer sessions, reconciliation, forwarding
│   └── worker/           # Live relay sync, WorkManager jobs, power-aware scheduling
├── ui/
│   ├── components/       # Shared Compose controls
│   ├── navigation/       # Routes and navigation graph
│   ├── screens/          # Onboarding, groups, expenses, Nearby, settings, diagnostics
│   ├── theme/            # Color, typography, spacing, and motion
│   ├── util/             # UI helpers and QR generation
│   └── viewmodels/       # Screen state and user actions
├── util/                 # Formatting, logging, and process-health diagnostics
├── MainActivity.kt       # Activity lifecycle and incoming links
└── SplitFreeApp.kt       # Application initialization and background-work setup
```

| Supporting files | Location |
| --- | --- |
| Test suites | [`app/src/test/`](app/src/test/) |
| Versioned Room schemas | [`app/schemas/`](app/schemas/) |
| Nearby state machine and wire format | [Protocol guide](app/src/main/java/com/splitfree/sync/nearby/README.md) |

## Testing

Run the standard local checks before submitting a change:

```bash
./gradlew spotlessCheck :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

For a single class or coverage report:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.splitfree.domain.crypto.nip.Nip44Test'
./gradlew :app:testDebugUnitTest :app:createDebugUnitTestCoverageReport
```

| Area | Coverage |
| --- | --- |
| **Cryptography** | Event signing/verification, NIP-44 test vectors, gift-wrap round trips, mnemonic encoding and recovery |
| **Ledger rules** | Split rounding, per-currency balances, corrections/deletions, settlements, snapshot coverage |
| **Groups and backups** | Invitations, membership, key rotation/revocation, authenticated export/import, epoch-key recovery |
| **Storage** | Room migrations, transaction boundaries, durable outbox, control-operation journals, deferred events |
| **Relay sync** | Message parsing, WebSocket lifecycle, per-relay catch-up, retries, and worker scheduling |
| **Nearby** | Authentication and optional channel binding, framing, reconciliation, peer-scoped failures, simulated forwarding, and queued startup/cancellation with fake radios |
| **UI** | ViewModel state, Compose interactions, large-text/layout cases, and native-graphics fixture renders |

### Live-relay integration tests

- Tests named `*IntegrationTest*` are excluded by default.
- Opt in explicitly:

```bash
./gradlew :app:testDebugUnitTest -DREAL_RELAY_TEST=true --tests '*IntegrationTest*'
```

- **Real network activity:** these tests contact relays and can publish events.
- **Strict results:** once opted in, relay rejection, timeout, incomplete history, or a missing expected event fails
  the relevant probe. An unavailable relay is not silently treated as a successful round trip.
- **Default offline coverage:** `RelayLedgerScenarioRoomTest` runs the shared create/join and money-flow scenarios
  through separate Room stores with a simulated client boundary. Fault cases verify that false relay acceptance
  and missing data cannot satisfy the scenario. `RelayProbeOptInTest` verifies safe JUnit skips without opt-in.
- **Live ledger scope:** the two ledger scenarios use real relay clients, repositories, outbox/`SyncEngine`,
  event processing and balances; key storage and Android scheduling remain test substitutes. They include the
  production fallback relays, so completion requires those history requests too.
- **Safe fixtures:** use disposable identities and sample data.
  - Offline cross-component tests should not use the `IntegrationTest` suffix, which is reserved by the build filter
    for these opt-in classes.
- **JVM limits:** Robolectric and in-memory transports do not certify radios, Keystore durability, OS process death,
  or background timing.
- **Release acceptance:** test the signed, minified APK using the [device
  checklist](RELEASING.md#4-test-the-exact-signed-apk) and the [Nearby hardware validation
  list](RELEASING.md#nearby-hardware-validation).

## Product website

The frontend-only product website lives in [`website/`](website/README.md), with a
v2-film-inspired design, an interactive expense-split example, and direct links
to the official Android APK. It has no backend or runtime third-party scripts.

- **Local preview:** `npm --prefix website ci --ignore-scripts`, then `npm --prefix website run dev`.
- **Build:** `npm --prefix website run build` produces the isolated `website/dist` folder.
- **Self-deploy:** follow the [GitHub Pages guide](website/README.md#deploy-to-github-pages-yourself).
  Deployment is manual, via the separate **Website · GitHub Pages** workflow.

## Contributing

- Contributions are welcome.
- Start with [CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md).

| Contribution | Useful starting point |
| --- | --- |
| **Device testing** | Relay recovery, Nearby transfers, permissions, and backup restoration. |
| **Accessibility** | TalkBack, large text, focus behavior, and keyboard navigation. |
| **Localization** | Translations and locale-sensitive formatting. |
| **Regression coverage** | Focused tests for a reproducible bug or edge case. |

- Discuss larger features and protocol changes in an issue before implementing them.
- Include reproducible steps in bug reports.
- Redact identifiers and sensitive data from logs before sharing.

## License

Copyright © 2026 SplitFree Contributors.

- **Project:** [GNU General Public License, version 3 or later](LICENSE) (`GPL-3.0-or-later`), without warranty.
- **Binary redistribution:** include corresponding source and required notices; see the [release guide](RELEASING.md).
- **Dependencies:** retain their own licenses.
- **Inter typeface:** by The Inter Project Authors, licensed under the [SIL Open Font License
  1.1](app/src/main/assets/licenses/Inter-OFL.txt).
