<p align="center">
  <img src="assets/splitfree-banner.svg" alt="SplitFree Banner" width="600"/>
</p>

<h1 align="center">SplitFree</h1>

<p align="center">
  <b>Decentralized expense splitting: no proprietary backend, no accounts, no analytics SDKs.</b>
</p>

<p align="center">
  <a href="https://github.com/invincible04/SplitFree/actions"><img src="https://img.shields.io/github/actions/workflow/status/invincible04/SplitFree/ci.yml?branch=mainline&style=flat-square" alt="Build Status"/></a>
  <img src="https://img.shields.io/badge/API-26%2B-brightgreen?style=flat-square" alt="Min SDK"/>
  <img src="https://img.shields.io/badge/Kotlin-2.1-blue?style=flat-square&logo=kotlin" alt="Kotlin"/>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-orange?style=flat-square" alt="License"/></a>
</p>

<p align="center">
  <a href="#why-splitfree">Why SplitFree?</a> •
  <a href="#features">Features</a> •
  <a href="#how-it-works">How It Works</a> •
  <a href="#architecture">Architecture</a> •
  <a href="#getting-started">Getting Started</a> •
  <a href="#testing">Testing</a> •
  <a href="#contributing">Contributing</a>
</p>

---

## Why SplitFree?

Every expense-splitting app today wants your money or your data, or both. Monthly subscriptions for basic features, mandatory accounts that harvest your financial data, and proprietary servers that can shut down anytime. I built SplitFree because I was fed up with all of it.

| | Traditional Apps | SplitFree |
|---|---|---|
| **Cost** | Free tier + paywalled features | 100% free, forever |
| **Data storage** | Company servers | Your device, plus encrypted events on relays you choose |
| **Account** | Email/phone required | Cryptographic keypair (no signup) |
| **Sync** | Proprietary cloud | Nostr relays + Nearby Connections |
| **Encryption** | Server-side (they can read it) | End-to-end payloads (relays see ciphertext plus routing tags) |
| **Identity** | Email/password | 24-word mnemonic backup (BIP-39) |
| **Offline** | ❌ Requires internet | ✅ Nearby sync with group members, no internet needed |

SplitFree is built entirely on the [Nostr](https://nostr.com) protocol, an open, decentralized network. Your groups sync peer-to-peer through public Nostr relays and Google Nearby Connections, so your financial data stays under your control.

## Features

### 🔐 Privacy & Security
- **End-to-end encryption**: event payloads encrypted with [NIP-44](https://nips.nostr.com/44) v2 (ChaCha20 + HMAC-SHA256); relays see ciphertext plus the routing tags listed in [PRIVACY.md](PRIVACY.md)
- **Gift Wrap privacy**: optional [NIP-59](https://nips.nostr.com/59) triple-layer encryption hides which member authored a wrapped expense; the wrap still shows the recipient's key and the group tag, and group-metadata events are published directly
- **Cryptographic identity**: [BIP-340](https://bips.dev/340) Schnorr keypair as your identity, exportable as a [BIP-39](https://en.bitcoin.it/wiki/BIP_0039) 24-word mnemonic
- **Key revocation & group migration**: rotate your identity or remove members without losing history

### 💰 Expense Management
- **Flexible splits**: equal, exact amount, percentage, or share-based splitting
- **Multi-currency support**: track expenses in any currency, balances computed per currency
- **Smart debt simplification**: greedy algorithm reduces settlement to at most n−1 transfers (not guaranteed minimal; that problem is NP-hard)
- **Balance snapshots**: periodic snapshots reduce recomputation for large groups

### 🔄 Sync & Connectivity
- **Decentralized sync**: expenses propagate through Nostr relays ([NIP-01](https://nips.nostr.com/1)) with no proprietary backend
- **Offline-first nearby sync**: sync with nearby group members through Google Nearby Connections (Bluetooth, BLE or Wi-Fi, chosen by the SDK) when there's no internet, and carry sealed updates to members you meet later
- **Adaptive power management**: periodic sync intervals and Nearby discovery duty cycles adjust to battery state
- **Invite links**: one compact, versioned bearer link carries everything a joiner needs (group id, creator, key epoch, group key, relays, expiry, name); the group id is bound to its creator so a forged "created by" cannot pass

### 🎨 User Experience
- **Material 3 light/dark themes**: system, light or dark mode with a fixed brand palette and restrained directional navigation motion
- **Export / Import**: self-contained backup (`.splitfree` files, format v2) for cross-device transfer; expense payloads stay NIP-44 encrypted and the group keys are encrypted to your identity, and the whole file is authenticated with HMAC-SHA256 under a key HKDF-derived from your identity, so only the identity that made a backup can restore it
- **QR code sharing**: scan to join groups instantly
- **Debug log**: real-time protocol event viewer for developers

## How It Works

```
┌──────────┐   splitfree://join?d=...   ┌──────────┐
│  Alice   │ ◄────────────────────────  │   Bob    │
│ (keypair)│                            │ (keypair)│
└────┬─────┘                            └────┬─────┘
     │                                       │
     │  ┌─────────────────────────────────┐  │
     ├──►  Nostr Relay (sees ciphertext)  ◄──┤  ← NIP-44 encrypted events
     │  └─────────────────────────────────┘  │
     │                                       │
     │  ┌─────────────────────────────────┐  │
     └──►       Nearby Connections        ◄──┘  ← Direct sync (no internet)
        └─────────────────────────────────┘
```

1. **Identity**: On first launch, a secp256k1 keypair is generated. The public key is your identity. Back it up as a 24-word BIP-39 mnemonic.

2. **Groups**: Creating a group generates a random 256-bit symmetric key. All group events are encrypted with this key using NIP-44 v2 before publishing to relays.

3. **Invites**: Share a compact deep link (`splitfree://join?d=...`). It is a single versioned binary payload containing the group id, the creator's pubkey, the creation time, the key epoch, the group key itself, the relay list, an expiry, and the group name. The group id is derived from `(creator pubkey, createdAt)`, so a joiner can verify the creator claim before trusting it. The link is a bearer credential (anyone holding it can join and read the group), so share it over a channel you trust (in person via QR, or an end-to-end encrypted messenger).

4. **Expenses**: Expenses are signed Nostr events (kind 30078) with encrypted JSON content. Each expense records who paid, who owes, the split method, and the amount.

5. **Sync**: The app connects to multiple Nostr relays via WebSocket, subscribes to group events, and processes them through validation → decryption → storage → post-processing. While the app is visible, `LiveSync` holds the relay connection, catches every group up from persisted per-relay history cursors and streams live events; there is no background service or persistent notification. A fallback relay cannot mark an unavailable relay's history complete. Incomplete catch-up retries on individual relay recovery and on a bounded foreground timer (30, 60 and 120 seconds), then hands off to a durable WorkManager job. WorkManager also handles the durable outbox drain, periodic catch-up and daily full sync. Android may defer background work; public relay retention and delivery are best effort.

6. **Nearby sync**: When group members are together, they can sync directly through Google Nearby Connections (Bluetooth, BLE or Wi-Fi, chosen by the SDK). Before any group data is exchanged, both phones sign a Schnorr challenge bound to that specific connection's Nearby authentication token, and exactly one group is opened only after both sides pass a membership check. Records then flow as small JSON frames (16 KiB max, paged inventories, chunked records) with a per-record receipt and durable progress, so an interrupted session re-authenticates and transfers only what is still missing. A member can carry sealed envelopes (gift wraps, per-member key rotations) for other members and hand them over later, and data applied from one connected peer is re-offered to the other connected peers. Only records with a third-party-verifiable signature are forwarded; an expense a phone holds only as a gift-wrapped rumor is not. Both phones must speak the current protocol version (v3); an older peer is refused rather than half-understood. See `app/src/main/java/com/splitfree/sync/nearby/README.md`.

7. **Balances**: Balances are computed from the full event history (with snapshot optimization). The debt simplification algorithm greedily matches the largest creditor with the largest debtor, which settles everyone in at most n−1 transfers; it does not search for the true minimum.

## Architecture

The codebase follows **Clean Architecture** with strict layer separation. The domain layer is pure Kotlin with zero Android dependencies.

```
┌─────────────────────────────────────────────────────────────┐
│                        UI Layer                             │
│   Jetpack Compose  •  Material 3  •  Navigation Compose     │
│   Screens: Onboarding, Groups, GroupDetail, AddExpense,     │
│            Settings, NearbySync, DebugLog                   │
├─────────────────────────────────────────────────────────────┤
│                    ViewModel Layer                          │
│   Hilt-injected ViewModels  •  StateFlow  •  UiState        │
├─────────────────────────────────────────────────────────────┤
│                Domain Layer (pure Kotlin)                   │
│   Use Cases: CreateGroup, JoinGroup, AddExpense,            │
│     ComputeBalances, SimplifyDebts, CreateSnapshot,         │
│     RevokeKey, MigrateGroup, Export/Import, SelfHeal        │
│   Crypto: NostrEvent, GroupEncryption,                      │
│           EventSigner, Bip39                                │
│   Validation: EventValidator (timestamps, rate limits,      │
│              content safety, author checks)                 │
├─────────────────────────────────────────────────────────────┤
│                     Data Layer                              │
│   Room DB v4 (ledger, outbox, deliveries, relay cursors)       │
│   Nostr: Relay (OkHttp WebSocket), NostrClient,             │
│          RelayHealthMonitor, RelayConnectionManager         │
│   Nearby: NearbySync, NearbySessionCoordinator, PeerSession │
│   Sync: SyncEngine, LiveSync, SyncWorker, OutboxWorker,     │
│         DailySyncWorker, SyncScheduler, PowerManager        │
│   Keys: Keystore-wrapped AES-256-GCM storage                │
└─────────────────────────────────────────────────────────────┘
```

### Nostr Protocol (implemented from scratch, no third-party Nostr libraries)

| NIP | Purpose | Implementation |
|-----|---------|----------------|
| [NIP-01](https://nips.nostr.com/1) | Event model, signing, relay protocol | `NostrEvent`, `Relay`, `ClientMessage`, `RelayMessage` |
| [NIP-44](https://nips.nostr.com/44) | Encrypted payloads (v2) | `Nip44`: ChaCha20 + HKDF + HMAC-SHA256 + padding |
| [NIP-59](https://nips.nostr.com/59) | Gift Wrap (triple-layer encryption) | `Nip59`: Rumor → Seal → Gift Wrap |
| [NIP-78](https://nips.nostr.com/78) | App-specific data (kind 30078) | Used for expense/settlement/group events |
| [BIP-39](https://en.bitcoin.it/wiki/BIP_0039) | Mnemonic seed phrases | `Bip39`: 24-word mnemonic generation & recovery |
| [BIP-340](https://bips.dev/340) | Schnorr signatures | `Secp256k1` via `fr.acinq.secp256k1` |

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.4 |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt (Dagger) |
| Database | Room (SQLite) |
| Networking | OkHttp (raw WebSocket) |
| Crypto | secp256k1-kmp, BouncyCastle |
| Nearby sync | Google Nearby Connections API (Bluetooth, BLE or Wi-Fi, chosen by the SDK) |
| Serialization | kotlinx.serialization |
| Compression | LZ4 |
| Background | WorkManager; live relay session only while visible (`LiveSync`) |
| QR | ZXing + ML Kit Code Scanner |
| Build | Gradle 9.7 + AGP 9.4 (built-in Kotlin) + Version Catalog |
| Lint | Spotless + ktlint |
| Testing | JUnit 4, MockK, Robolectric |

## Getting Started

### Prerequisites

- Android Studio Meerkat (2024.3+) or later
- JDK 17
- Android SDK 37 (compile) / SDK 26+ (min)

### Clone & Run

```bash
git clone https://github.com/invincible04/SplitFree.git
cd SplitFree
```

1. Open in Android Studio → **File → Open** → select the project root
2. Let Gradle sync complete
3. Run on a device or emulator (API 26+)

### Building

```bash
./gradlew assembleDebug          # Debug build
./gradlew assembleRelease        # Release build (needs signing config)
./gradlew spotlessApply           # Format code
./gradlew lint                    # Lint check
```

### Release Signing

Place your keystore file as `splitfree-release.jks` in the project root, then create `local.properties`:

```properties
RELEASE_STORE_PASSWORD=<your-store-password>
RELEASE_KEY_ALIAS=<your-key-alias>
RELEASE_KEY_PASSWORD=<your-key-password>
```

## Project Structure

```
app/src/main/java/com/splitfree/
├── data/
│   ├── ble/              # Nearby Connections transport (NearbySync)
│   ├── identity/         # IdentityManager (keypair management)
│   ├── local/            # Room database, DAOs, entities
│   ├── nostr/            # NostrClient, Relay, RelayConfig, EventThrottler
│   │   ├── protocol/     # ClientMessage, RelayMessage, NostrFilter
│   │   └── relay/        # RelayConnectionManager, RelayHealthMonitor
│   ├── repository/       # GroupRepository, ExpenseRepository
│   ├── settings/         # UserPreferences (plain SharedPreferences)
│   └── util/             # Compression, Keystore-backed encrypted storage
├── di/                   # Hilt modules (Database, Repository, Security, Nearby, Coroutine)
├── domain/
│   ├── crypto/           # NostrEvent, GroupEncryption, IdentityManager, EventSigner
│   │   └── nip/          # Nip44, Nip59, Bip39 (from-scratch implementations)
│   ├── invite/           # InviteLinkCodec (compact bearer invite link, creator-bound group id)
│   ├── model/            # Domain models (Group, Expense, Balance, Settlement)
│   ├── repository/       # Repository contracts (interfaces)
│   ├── usecase/          # Business logic use cases
│   │   ├── expense/      # AddExpense, ComputeBalances, SimplifyDebts, Snapshots
│   │   ├── export/       # Export/Import, whole-file HMAC keyed from the identity (HKDF)
│   │   ├── group/        # CreateGroup, JoinGroup, MigrateGroup, RevokeKey
│   │   └── sync/         # SelfHeal
│   ├── util/             # HexUtil, HashUtil, RelayDefaults, CompressionProvider
│   └── validation/       # EventValidator (timestamps, rate limits, content safety)
├── sync/
│   ├── event/            # EventProcessor, EventPublisher, EventPostProcessor, MembershipHistory
│   ├── nearby/           # Nearby session engine: wire protocol, auth, PeerSession, coordinator, store
│   └── worker/           # LiveSync, SyncEngine, SyncWorker, OutboxWorker, DailySyncWorker, PowerManager
├── ui/
│   ├── navigation/       # NavGraph, Screen definitions
│   ├── screens/          # Compose screens (onboarding, groups, expenses, settings, etc.)
│   ├── theme/            # Material 3 color/type/shape tokens, theme preference, navigation motion
│   └── util/             # QR code generator
├── util/                 # DebugLog, CurrencyFormatter, ProcessHealthTracker
├── MainActivity.kt       # Deep link handling, intent sanitization
└── SplitFreeApp.kt       # Application class, WorkManager config, battery receiver
```

## Testing

Comprehensive unit tests across every layer of the app.

```bash
./gradlew test                                                    # Unit tests only
./gradlew testDebugUnitTest app:createDebugUnitTestCoverageReport # With coverage
./gradlew test --tests "com.splitfree.domain.crypto.nip.Nip44Test" # Specific class

# Integration tests (real Nostr relays; excluded by default)
./gradlew test -DREAL_RELAY_TEST=true                             # All tests including integration
./gradlew test -DREAL_RELAY_TEST=true --tests "*IntegrationTest"  # Integration tests only
```

| Area | What's Tested |
|------|--------------|
| Crypto | NIP-01 signing/verification, NIP-44 encrypt/decrypt with spec test vectors, NIP-59 gift wrap round-trips, BIP-39 mnemonic generation |
| Protocol | Relay message parsing, client message serialization, filter construction, WebSocket lifecycle |
| Use Cases | Balance computation, debt simplification, group creation/join/migration, key revocation, export/import, snapshots |
| Sync | SyncEngine pull/flush, power management modes, boot receiver, self-heal republishing |
| Nearby | Wire framing/pagination, channel-bound authentication, two- and three-engine session tests over an in-memory transport, Room-backed end-to-end forwarding and key-rotation carry |
| Validation | Timestamp bounds, rate limiting, content safety (nesting depth, size), author authorization, tombstone checks |
| Integration | Full relay round-trips, end-to-end expense lifecycle, multi-phone simulation (`*IntegrationTest.kt`; excluded by default, run with `-DREAL_RELAY_TEST=true`) |

## Contributing

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for detailed guidelines.

```bash
# Quick start
git clone https://github.com/<your-username>/SplitFree.git
git checkout -b feat/your-feature-name
# Make changes, then:
./gradlew test && ./gradlew spotlessApply && ./gradlew lint
# Commit with Conventional Commits format, push, and open a PR
```

### Areas Where Help Is Needed

- 🌍 **Localization**: i18n support for multiple languages
- 🧪 **UI tests**: Compose UI test coverage
- 📱 **iOS port**: Kotlin Multiplatform or native Swift implementation
- 📖 **Documentation**: User guides, relay operator docs
- ♿ **Accessibility**: Screen reader support, content descriptions
- 🎨 **Design**: App icon, screenshots, Play Store assets

## Security

SplitFree takes security seriously:

- Event payloads are end-to-end encrypted (NIP-44 v2); routing tags on relays and group metadata in the local database are not
- Private and group keys stored AES-256-GCM under an Android Keystore key; hardware backing depends on the device
- Event signatures verified on receipt (BIP-340 Schnorr)
- Content validated for size limits, nesting depth, and rate limiting
- Nearby connections authenticated with Schnorr signatures bound to the connection's Nearby authentication token; group scope disclosed only to members
- ProGuard/R8 enabled for release builds

If you discover a security vulnerability, please **do not** open a public issue. Instead, [report it privately via GitHub](https://github.com/invincible04/SplitFree/security/advisories/new) and we'll address it promptly. See [SECURITY.md](SECURITY.md) for full details.

## Privacy

SplitFree has no developer-operated service and collects nothing itself. Expense payloads are end-to-end encrypted and stored on your device and, as ciphertext, on the relays you choose; group names, member lists and relay lists are kept in plaintext in the app's private database. See [PRIVACY.md](PRIVACY.md) for the full privacy policy, including what relays and Google Play services can see.

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).

```
Copyright (C) 2026 SplitFree Contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

### Third-party licenses

- **Inter** typeface by The Inter Project Authors, licensed under the [SIL Open Font License 1.1](app/src/main/assets/licenses/Inter-OFL.txt). Bundled as `res/font/inter_variable.ttf`.

---

<p align="center">
  Built with ❤️ on <a href="https://nostr.com">Nostr</a>
</p>
