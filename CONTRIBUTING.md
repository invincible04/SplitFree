# Contributing to SplitFree

Help improve the app through focused fixes, tests, documentation, and reproducible bug reports.

[Project overview](README.md) · [Nostr guide](app/src/main/java/com/splitfree/data/nostr/README.md) · [Nearby protocol](app/src/main/java/com/splitfree/sync/nearby/README.md) · [Release guide](RELEASING.md)

## Contents

- [Code of Conduct](#code-of-conduct)
- [How can I contribute?](#how-can-i-contribute)
- [Development setup](#development-setup)
- [Development workflow](#development-workflow)
- [Code style](#code-style)
- [Testing](#testing)
- [Commit messages](#commit-messages)
- [Pull request process](#pull-request-process)
- [Architecture overview](#architecture-overview)
- [Areas where help is needed](#areas-where-help-is-needed)

## Code of Conduct

- Follow the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).
- Use its confidential reporting route for conduct concerns, not a public issue.
- Report security vulnerabilities through [SECURITY.md](SECURITY.md).

## How can I contribute?

| Contribution | What to include |
| --- | --- |
| **Bug report** | Device model, Android/app version, exact reproduction steps, expected behavior, and actual behavior. |
| **Feature proposal** | The user problem, intended workflow, and why the existing behavior is insufficient. |
| **Code change** | A focused implementation, regression tests, and an explanation of compatibility or data changes. |
| **Documentation** | Source-verified behavior, working links, and clear examples without real credentials. |

**Before opening an issue**

- Search [existing issues](https://github.com/invincible04/SplitFree/issues).
- Discuss large features or protocol changes before implementation.
- Use disposable identities and sample data for reproductions.
- Redact logs: never attach recovery phrases, private keys, usable invite links, or personal backups.
- Debug builds expose **Settings → Debug Log**; release builds do not expose that screen.

## Development setup

### Prerequisites

| Requirement | Setup |
| --- | --- |
| JDK | **17**, selected through `JAVA_HOME` or your IDE's Gradle JDK. |
| Android SDK | Platform **37** for compilation; API **26+** device or emulator. |
| Gradle | Use the checked-in wrapper, not a system Gradle installation. |
| Android Studio | Optional; use a version compatible with the pinned Android Gradle Plugin. |
| Google Play services | Needed to exercise Nearby Connections and QR scanning. |

### Get a working build

1. Fork the project if you plan to submit changes.
2. Clone your fork, or clone the project to inspect it locally:

```bash
git clone https://github.com/invincible04/SplitFree.git
cd SplitFree
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

- Configure `ANDROID_HOME` or `sdk.dir` in untracked `local.properties`.
- In Android Studio, open the repository root and let Gradle sync.
- On Windows, use `gradlew.bat`.
- Debug builds do not require release signing credentials.

> **Protect existing installs:** debug and release share `com.splitfree`, but normally use different signing certificates. Use a dedicated test device or emulator; separate work profiles do not bypass package-signature checks.

### Configuration map

| Setting | Source |
| --- | --- |
| Dependencies and plugins | [`gradle/libs.versions.toml`](gradle/libs.versions.toml) |
| SDK versions, signing, test options | [`app/build.gradle.kts`](app/build.gradle.kts) |
| Default and fallback relays | [`RelayDefaults.kt`](app/src/main/java/com/splitfree/domain/util/RelayDefaults.kt) |
| Release keep rules | [`app/proguard-rules.pro`](app/proguard-rules.pro) |
| Formatting | [`.editorconfig`](.editorconfig) and [`build.gradle.kts`](build.gradle.kts) |
| CI tasks | [`.github/workflows/ci.yml`](.github/workflows/ci.yml) |

### Relay testing

- Production relay URLs require `wss://`; a plain local WebSocket endpoint is not sufficient.
- Custom group relays do **not** disable the built-in fallback pool.
- Use fake transport fixtures for isolated tests, or explicitly configure a test-only relay setup.
- Live-relay tests can publish events. Never use real financial data or production identities.

## Development workflow

| Step | Action |
| --- | --- |
| **1. Branch** | Start from current `mainline` and create a focused feature/fix branch. |
| **2. Implement** | Keep the change scoped; preserve ledger, identity, and protocol invariants. |
| **3. Test** | Add regression coverage for new behavior and failures. |
| **4. Verify** | Run formatting checks, JVM tests, lint, and debug assembly. |
| **5. Review the diff** | Check files being staged, generated output, credentials, and unrelated formatting. |
| **6. Submit** | Open a pull request from your fork against `mainline`. |

```bash
git checkout -b fix/describe-the-change
./gradlew spotlessCheck :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- Use `./gradlew spotlessApply` when formatting needs adjustment; review its changes before staging.
- Git hooks are local configuration, not a substitute for CI or explicit diff review.
- For release-specific changes, also follow [RELEASING.md](RELEASING.md).
- Run `python3 -m unittest discover -s tools/release/tests -v` when changing release helpers or workflow signing steps.
- CI uses `-PsplitfreeUnsignedRelease=true` to check release builds without release credentials.

## Code style

| Convention | Requirement |
| --- | --- |
| Kotlin / Gradle formatting | Spotless with pinned ktlint. |
| Kotlin line length | 120 characters. |
| Indentation | Four spaces; YAML uses two. |
| Imports | No wildcard imports. |
| Text files | UTF-8, LF, final newline. |
| Trailing whitespace | Follow `.editorconfig`; Markdown has its own exception. |
| Public APIs | Document behavior, failure modes, and invariants with KDoc. |

```bash
./gradlew spotlessCheck
./gradlew spotlessApply
```

- `spotlessCheck` does not rewrite files; `spotlessApply` does.
- Android Lint must pass without errors. Warnings are reported but are not configured as fatal.

## Testing

### Commands

| Scope | Command |
| --- | --- |
| Ordinary debug JVM suite | `./gradlew :app:testDebugUnitTest` |
| Both JVM variants | `./gradlew test` |
| Coverage report | `./gradlew :app:testDebugUnitTest :app:createDebugUnitTestCoverageReport` |
| One class | `./gradlew :app:testDebugUnitTest --tests 'com.splitfree.domain.crypto.nip.Nip44Test'` |
| One method | `./gradlew :app:testDebugUnitTest --tests 'com.splitfree.domain.crypto.nip.Nip44Test.encrypt then decrypt round-trip'` |
| Live-relay integration suite | `./gradlew :app:testDebugUnitTest -DREAL_RELAY_TEST=true --tests '*IntegrationTest*'` |
| Include live tests with the debug suite | `./gradlew :app:testDebugUnitTest -DREAL_RELAY_TEST=true` |

- `*IntegrationTest*` classes are excluded unless `REAL_RELAY_TEST=true`.
- Opt-in integration tests require network access and may write to public relays.
- Test reports are under `app/build/reports/tests/`; Gradle reports the coverage output location.
- Do not report excluded integration tests as passed device or live-network checks.

### Test organization

Paths below are relative to `app/src/test/java/com/splitfree/`.

| Directory | Coverage |
| --- | --- |
| `domain/crypto/` | Nostr events, NIP-44/NIP-59, mnemonic encoding, signatures. |
| `domain/crypto/integration/` | Crypto and relay round trips. |
| `domain/money/`, `domain/usecase/expense/` | Parsing, rounding, splits, balances, settlements, snapshots. |
| `domain/usecase/group/` | Invitations, membership, rotation, and revocation. |
| `domain/usecase/export/` | Authenticated backups and identity/epoch recovery. |
| `domain/usecase/integration/` | Multi-phone and expense-lifecycle integration scenarios. |
| `domain/validation/` | Timestamps, bounds, authorization, and content checks. |
| `data/local/`, `data/repository/` | Room storage, migrations, transaction and repository behavior. |
| `data/nostr/` | Relay messages, connection lifecycle, and health. |
| `data/ble/`, `sync/nearby/` | Nearby transport, authentication, reconciliation, and forwarding. |
| `sync/event/`, `sync/worker/` | Ingestion, publication, deferred work, catch-up, and scheduling. |
| `ui/` | ViewModels, Compose behavior, accessibility/layout fixtures. |

### Test conventions

- Use descriptive backtick-quoted names, such as `` fun `rejects an expired invitation`() ``.
- Use MockK for dependencies and Robolectric for Android framework behavior.
- Prefer deterministic fixtures and explicit clocks where timing matters.
- Exercise rejection, retry, and interruption paths, not only successful round trips.
- Preserve tests for ledger identity, event ordering, group scope, and key recovery when refactoring.

### Device acceptance

| JVM coverage can verify | Requires device acceptance |
| --- | --- |
| Protocol state machines and deterministic recovery logic | Physical radios, SDK consent, permissions, and OEM behavior. |
| Room-backed replay and injected storage failures | Real process death and Android Keystore persistence. |
| Compose interactions and fixture renders | Keyboard/insets, TalkBack, and real-device navigation. |
| Worker scheduling configuration | Actual Doze/background delivery timing. |

Use the [signed-APK device checklist](RELEASING.md#4-test-the-exact-signed-apk) before making release claims.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/):

```text
type(optional-scope): short description

- Explain the relevant change.
- Note compatibility or recovery implications when needed.
```

| Type | Purpose |
| --- | --- |
| `feat` | New capability. |
| `fix` | Bug correction. |
| `docs` | Documentation-only change. |
| `refactor` | Internal restructuring without an intended behavior change. |
| `test` | Added or improved tests. |
| `chore` | Build, CI, dependencies, or tooling. |
| `perf` | Performance improvement. |

```text
fix(sync): retry retained history after relay recovery
docs: clarify Nearby completion and carriage limits
test(nearby): cover interrupted envelope forwarding
```

## Pull request process

- **Title:** use the commit-message convention.
- **Description:** explain the problem, approach, tests, and remaining limitations.
- **Issue links:** reference related issues; use closing keywords only when the change resolves them.
- **Scope:** separate unrelated behavior, formatting, and dependency updates.
- **Review:** obtain at least one maintainer approval before merging.

### PR checklist

- [ ] Formatting, tests, lint, and debug assembly pass.
- [ ] Regression tests cover new behavior and relevant failure paths.
- [ ] Public APIs and user-visible behavior are documented.
- [ ] Protocol, schema, invite, or backup compatibility changes are called out.
- [ ] No secrets, real backups, or signing credentials appear in the diff.
- [ ] Generated artifacts and unrelated local changes are excluded.
- [ ] CI checks pass; any unperformed device/live tests are stated explicitly.

## Architecture overview

```text
Compose UI → ViewModel → Use case → Repository contract → Data implementation
                                     ↕
                             Sync and event processing
```

- The app is one Gradle module with UI, domain, data, and sync packages.
- The domain boundary is package-level, not a separately enforced Android-free module.
- Both transports use shared event validation and application logic.
- Signed events remain immutable; corrections/deletions create new events.
- Nostr protocol code lives here; underlying cryptography uses secp256k1-kmp and Bouncy Castle.

### Where to put new code

Paths below are relative to `app/src/main/java/com/splitfree/`.

| Change | Location |
| --- | --- |
| Screen / navigation | `ui/screens/`, `ui/navigation/` |
| Screen state | `ui/viewmodels/` |
| Reusable UI | `ui/components/` |
| Use case / model | `domain/usecase/`, `domain/model/` |
| Money rules | `domain/money/` |
| Nostr crypto / wire messages | `domain/crypto/`, `data/nostr/protocol/` |
| Nearby protocol | `sync/nearby/` |
| Storage / repository | `data/local/`, `data/repository/` |
| Dependency binding | `di/` |

## Areas where help is needed

| Area | Useful contributions |
| --- | --- |
| Device validation | Reproducible multi-phone, recovery, and upgrade checks. |
| Accessibility | TalkBack, large text, focus, keyboard behavior. |
| Localization | Translations and locale-sensitive formatting. |
| Tests | Regression cases, Compose behavior, deterministic protocol coverage. |
| Documentation | User guidance, relay behavior, protocol and release maintenance. |
| Other platforms | Discuss an iOS or multiplatform design before starting a port. |
| Release assets | Reviewed screenshots and store-listing materials. |
