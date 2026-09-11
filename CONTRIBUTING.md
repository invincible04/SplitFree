# Contributing to SplitFree

Thank you for your interest in contributing to SplitFree! This document provides guidelines and instructions for contributing.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [How Can I Contribute?](#how-can-i-contribute)
- [Development Setup](#development-setup)
- [Development Workflow](#development-workflow)
- [Code Style](#code-style)
- [Testing](#testing)
- [Commit Messages](#commit-messages)
- [Pull Request Process](#pull-request-process)
- [Architecture Overview](#architecture-overview)
- [Areas Where Help Is Needed](#areas-where-help-is-needed)

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code. Please report unacceptable behavior via [GitHub Discussions](https://github.com/invincible04/SplitFree/discussions) or by opening a private issue.

## How Can I Contribute?

### Reporting Bugs

- Check [existing issues](https://github.com/invincible04/SplitFree/issues) to avoid duplicates
- Use the bug report template when creating a new issue
- Include: device model, Android version, steps to reproduce, expected vs actual behavior
- Attach logs from the **DebugLog** screen (Settings → Debug Log) if relevant

### Suggesting Features

- Open an issue with the `enhancement` label
- Describe the use case and why it would benefit users
- For large features, discuss in an issue before starting implementation

### Submitting Code

- Bug fixes, performance improvements, and documentation are always welcome
- For new features, open an issue first to discuss the approach
- See [Development Workflow](#development-workflow) below

## Development Setup

### Prerequisites

| Requirement | Version |
|-------------|---------|
| Android Studio | Meerkat (2024.3+) |
| JDK | 17 |
| Android SDK | 37 (compile), 26+ (min) |
| Kotlin | 2.4 |
| Gradle | 9.7 (AGP 9.4) |

### Getting Started

```bash
# Fork and clone
git clone https://github.com/<your-username>/SplitFree.git
cd SplitFree

# Open in Android Studio and let Gradle sync

# Verify everything builds
./gradlew assembleDebug

# Run tests
./gradlew test
```

### Project Configuration

- **Version catalog**: `gradle/libs.versions.toml` holds all dependency versions
- **Relay config**: `RelayConfig.kt` holds default and fallback relay URLs
- **ProGuard**: `app/proguard-rules.pro` holds keep rules for crypto, serialization, and native libs
- **Lint**: no baseline file; `./gradlew lint` must report zero issues (`abortOnError = true`)

### Local Relay (Optional)

For development without hitting public relays, run a local Nostr relay:

```bash
# Using nostr-rs-relay (Rust)
docker run -p 8080:8080 scsibug/nostr-rs-relay

# Then update RelayConfig.kt or use custom relays in Settings
```

## Development Workflow

1. **Create a branch** from `mainline`:
   ```bash
   git checkout -b feat/your-feature-name   # feature
   git checkout -b fix/bug-description      # bug fix
   git checkout -b docs/what-changed        # documentation
   ```

2. **Make your changes** following the [code style](#code-style) guidelines

3. **Write tests** for new functionality (see [Testing](#testing))

4. **Verify locally**:
   ```bash
   ./gradlew test              # all tests pass
   ./gradlew spotlessApply     # code formatted
   ./gradlew lint              # no new lint errors
   ```

5. **Commit** using [Conventional Commits](#commit-messages)

6. **Push** and open a Pull Request against `mainline`

## Code Style

The project enforces consistent formatting via **Spotless + ktlint**.

```bash
# Auto-format all Kotlin files
./gradlew spotlessApply

# Check formatting without modifying files
./gradlew spotlessCheck
```

### Key Rules

- **Max line length**: 120 characters
- **Indent**: 4 spaces (no tabs)
- **Imports**: No wildcard imports
- **Final newline**: Required in all files
- **Trailing whitespace**: Trimmed

The full configuration is in `.editorconfig` and `build.gradle.kts` (spotless block).

## Testing

The project has comprehensive unit tests across every layer. New code should include tests.

### Running Tests

```bash
# Unit tests only (integration tests excluded by default)
./gradlew test

# With coverage report (opens app/build/reports/coverage/test/debug/index.html)
./gradlew testDebugUnitTest app:createDebugUnitTestCoverageReport

# Specific test class
./gradlew test --tests "com.splitfree.domain.crypto.nip.Nip44Test"

# Specific test method
./gradlew test --tests "com.splitfree.domain.crypto.nip.Nip44Test.encrypt then decrypt round-trip"

# Integration tests (real Nostr relays; requires network)
./gradlew test -DREAL_RELAY_TEST=true                             # All tests including integration
./gradlew test -DREAL_RELAY_TEST=true --tests "*IntegrationTest"  # Integration tests only
```

### Test Naming Convention

- Unit tests: `*Test.kt`, run by default, no network required
- Integration tests: `*IntegrationTest.kt`, excluded by default, require `-DREAL_RELAY_TEST=true`

### Test Organization

| Directory | Purpose |
|-----------|---------|
| `domain/crypto/` | Crypto primitives: NIP-01, NIP-44, NIP-59, BIP-39 |
| `domain/crypto/integration/` | End-to-end crypto + relay round-trips |
| `domain/usecase/expense/` | Balance computation, debt simplification, splits |
| `domain/usecase/group/` | Group lifecycle: create, join, migrate, revoke |
| `domain/usecase/export/` | Export/import with HMAC verification |
| `domain/usecase/sync/` | Self-heal, constants |
| `domain/usecase/integration/` | Full multi-phone simulation |
| `domain/validation/` | Event validation: timestamps, rate limits, content safety |
| `data/nostr/` | NostrClient, relay protocol, health monitor |
| `data/ble/` | BLE binary protocol, transfer, handshake |
| `data/repository/` | Repository implementations |
| `sync/event/` | Event processing, publishing, notifications |
| `sync/worker/` | SyncEngine, PowerManager, BootReceiver |

### Test Conventions

- Use backtick-quoted test names: `` fun `descriptive test name`() ``
- Use `MockK` for mocking dependencies
- Use `Robolectric` when Android framework classes are needed
- Integration tests (`*IntegrationTest.kt`) hit live relays; they are excluded by default, run with `-DREAL_RELAY_TEST=true`

## Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<optional scope>): <description>

[optional body]

[optional footer]
```

### Types

| Type | When to Use |
|------|-------------|
| `feat` | New feature |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `refactor` | Code change that neither fixes a bug nor adds a feature |
| `test` | Adding or updating tests |
| `chore` | Build, CI, tooling changes |
| `perf` | Performance improvement |

### Examples

```
feat: add multi-currency settlement support
fix(sync): handle relay reconnection during gift wrap fetch
docs: update architecture diagram
refactor(crypto): extract HKDF into standalone utility
test(ble): add fragmentation edge case for MTU boundary
chore: bump Kotlin to 2.1.0
```

## Pull Request Process

1. **Title**: Use the same Conventional Commits format as commit messages
2. **Description**: Explain what changed and why. Link related issues with `Closes #123`
3. **Scope**: Keep PRs focused on a single concern. Split large changes into multiple PRs
4. **Tests**: Include tests for new functionality. Don't reduce existing coverage
5. **CI**: All checks must pass (tests, lint, spotless)
6. **Review**: At least one maintainer approval is required before merge

### PR Checklist

- [ ] Tests pass locally (`./gradlew test`)
- [ ] Code is formatted (`./gradlew spotlessApply`)
- [ ] Lint passes (`./gradlew lint`)
- [ ] Commit messages follow Conventional Commits
- [ ] New public APIs are documented with KDoc
- [ ] No secrets, keys, or credentials in the diff

## Architecture Overview

The codebase follows **Clean Architecture** with clear layer separation:

```
UI (Compose) → ViewModel → Use Case → Repository → Data Source
```

### Key Principles

- **Domain layer has no Android dependencies**: pure Kotlin, testable without Robolectric
- **Repository pattern**: contracts (interfaces) in `domain/repository/`, implementations in `data/repository/`
- **Use cases are single-responsibility**: one public `invoke()` method per use case
- **Crypto is from scratch**: NIP-01, NIP-44, NIP-59, BIP-39 are implemented without third-party Nostr libraries
- **Events are immutable**: Nostr events are signed and stored as-is; corrections/deletions are new events

### Where to Put New Code

| What | Where |
|------|-------|
| New screen | `ui/screens/<feature>/` + route in `NavGraph.kt` |
| New use case | `domain/usecase/<area>/` |
| New domain model | `domain/model/<area>/` |
| New Nostr protocol feature | `domain/crypto/nip/` or `data/nostr/protocol/` |
| New data source | `data/<source>/` |
| New DI binding | `di/` modules |

## Areas Where Help Is Needed

- 🌍 **Localization**: i18n support for multiple languages
- 🧪 **UI tests**: Compose UI test coverage with `ComposeTestRule`
- 📱 **iOS port**: Kotlin Multiplatform or native Swift implementation
- 📖 **Documentation**: User guides, relay operator docs, API documentation
- ♿ **Accessibility**: Screen reader support, content descriptions, focus management
- 🎨 **Design**: App icon, screenshots, Play Store listing assets
- 📊 **Analytics**: Privacy-respecting usage metrics (opt-in only)

---

Thank you for helping make SplitFree better! 🎉
