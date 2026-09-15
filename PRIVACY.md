# Privacy policy

**Last updated:** September 15, 2026

- SplitFree stores your ledger locally and synchronizes encrypted events through Nostr relays or nearby phones.
- This policy explains what data exists and who can see it.

[Project overview](README.md) · [Security policy](SECURITY.md) · [Nostr guide](app/src/main/java/com/splitfree/data/nostr/README.md) · [Nearby protocol](app/src/main/java/com/splitfree/sync/nearby/README.md)

## Contents

- [The short version](#the-short-version)
- [Data storage](#data-storage)
- [Network communication](#network-communication)
- [Identity](#identity)
- [Permissions](#permissions)
- [Backups](#backups)
- [Third-party services](#third-party-services)
- [Children's privacy](#childrens-privacy)
- [Changes to this policy](#changes-to-this-policy)
- [Contact](#contact)

## The short version

- **No developer-operated service:** SplitFree operates no backend or relay to receive your financial records.
- **Local ledger:** expenses live on your phone; encrypted events also reach configured relays and built-in fallbacks.
- **Private content:** relays cannot read encrypted expense amounts, descriptions, group names, or balances without
  the relevant keys.
- **Visible metadata:** local group metadata and public relay routing information are not hidden by payload
  encryption.
- **No added analytics or advertising:** Google Play services modules have their own diagnostics, described below.
- **Your backups:** keep both your identity recovery phrase and group exports; there is no developer-operated account
  recovery.

## Data storage

| Data | Location | Protection |
| --- | --- | --- |
| Expense, settlement, and group-metadata event payloads | Room on-device; Nostr relays | NIP-44 v2 ciphertext. |
| Group names/descriptions, members and display names, relay lists, member clocks | App-private Room database | Plaintext metadata protected by Android's sandbox and device security, not SplitFree database encryption. |
| Event authors, types, and timestamps | App-private Room database | Plaintext metadata. |
| Sealed envelopes retained for others | App-private Room database | Recipient-encrypted content. Carried and authored retention differ. |
| Private identity key and group keys | App-private encrypted storage | AES-256-GCM under an Android Keystore key; hardware backing is device-dependent. |
| Theme, gift-wrap preference, display name | SharedPreferences | Plaintext preferences. |
| Local diagnostics | App-private preferences and in-memory debug/Nearby state | Heartbeats, crash reports, identifiers, and exception text; redaction is limited. See below. |

**Envelope retention**

- Carried envelopes are pruned after 30 days by local receipt time; authored envelopes after 90 days.
- Available carried envelopes are bounded to 512 / 4 MiB per group; older carriage may be evicted sooner.
- Pruning runs as part of app maintenance, not as a guaranteed deletion at an exact wall-clock deadline.
- Other phones and third-party relays control their own retained copies.

### Local diagnostics

- Both build variants keep the latest heartbeat and uncaught-crash report in app-private preferences.
  - Settings can display and copy a report, including the latest available OS exit reason on API 30+.
- Debug builds additionally keep an in-memory log and send debug/info messages to logcat.
  - Warning/error messages reach logcat in both variants.
- The Nearby controller keeps a bounded in-memory timeline with endpoint identifiers and status details.
  - Activation failures can include exception text without redaction.
- The shared sanitizer shortens long hexadecimal identifiers and replaces compact invite links.
  - It is not general secret detection: heartbeat details, debug log entries, and throwable text passed directly to
    logcat are not all sanitized.
- Reports are not automatically uploaded to a developer service.
  - Review and redact them before copying or sharing.

SplitFree does not configure a Firebase backend, a developer-operated cloud database, or developer-controlled remote storage.

## Network communication

### Nostr relays

Nostr relays are third-party servers that store and forward encrypted events.

| Event form | Public relay-visible information |
| --- | --- |
| **Direct event, kind `30078`** | Signing public key, event ID/signature, kind, timestamp, group and type tags, and revision/expense-routing tags where present. |
| **Per-member key rotation** | Direct-event metadata plus the recipient's public key. |
| **Gift wrap, kind `1059`** | Recipient key, group tag, event ID/signature, one-time outer signing key, kind, and a timestamp randomized within the previous two days. |
| **Connection** | IP address and timing; subscription filters disclose what data the client requests. |
| **Relay authentication (`AUTH`)** | When challenged, the client signs with your identity key; this can link wrapped traffic to that public identity. |

**What encryption does and does not hide**

- Encrypted content hides expense amounts, descriptions, group names, and balances from relays without keys.
- Gift wrapping hides the author of the wrapped expense or settlement inside encrypted layers.
- Group-metadata and key events are published directly under the relevant signing identity.
- Tags, keys, and timing can reveal relationships and group activity.
  - Encryption is not anonymity.

**Relay selection**

- You can configure each group's primary relays.
- The managed connection pool also includes built-in fallback relays and other locally stored groups' primary relays.
- Publication uses the connected relay pool; a custom group list is not an exclusive network boundary.
- Defaults and fallbacks are defined in
  [`RelayDefaults.kt`](app/src/main/java/com/splitfree/domain/util/RelayDefaults.kt).
- SplitFree does not operate these relays or control their retention policies.

### Nearby sync (Google Nearby Connections)

| Aspect | Behavior |
| --- | --- |
| Transport | The SDK selects Bluetooth, BLE, or Wi-Fi, including Wi-Fi LAN / Direct. |
| Data path | Between participating phones; no SplitFree server or Nostr relay is required for the Nearby transfer. |
| Identity proof | Mutual Schnorr challenge-response; the SDK connection token is included when supplied. |
| Group scope | One group opens after membership checks. |
| Transfer | Bounded frames, inventory pages, chunks, receipts, and durable reconciliation progress. |
| Compatibility | Nearby protocol v3; incompatible peers are refused. |
| Lifecycle | A ready Nearby screen requests advertising and discovery. Stop, screen disposal, or lifecycle stop requests cleanup; SDK completion is asynchronous. A later ready screen start creates a fresh run unless Stop remains selected. |

**Authentication and carriage boundaries**

- Nearby discovery advertises a short public-key prefix while the screen is open; the handshake exchanges public keys
  before group authorization.
- Either person can tap Sync to initiate a connection.
  - Each phone verifies the other phone's signed proof before opening a group; this local verification is not a
    separate acknowledgment that the other phone accepted its proof.
- The current authentication helper hashes a missing connection token as empty bytes.
  - It does not supply channel binding in that case.
- Received event signatures are checked; a gift-wrap recipient verifies its encrypted author's seal.
- A carrier verifies outer routing/signature information but cannot read or verify the encrypted inner content for
  another recipient.
- Sealed envelopes can be handed to another member later; receiving an unsigned inner event alone does not allow
  forwarding it as a signed original.
- Applied/retained data can be re-offered to other connected peers while Nearby remains active.

### Google's Nearby diagnostics

According to [Google's Nearby documentation](https://developers.google.com/nearby/connections/overview):

- The SDK collects connection-performance metrics and device information.
- Examples include device model, country, build version, and application package name.
- Usage and diagnostics are controlled through **Android Settings → Google → Usage & diagnostics**.

- This summarizes Google's SDK documentation.
- We have not measured the SDK's actual network diagnostics through traffic capture; they are outside SplitFree's
  control.
- The documented metrics do not include expense content.

## Identity

| Item | Meaning |
| --- | --- |
| Public key | Your identifier, rather than an email address or phone number. |
| Private key | Signs your events and unlocks identity-encrypted data. |
| Recovery phrase | A 24-word BIP-39 encoding of your private key. |

- The app does not send your private key to relays or other phones.
- You can deliberately reveal/copy it or its recovery phrase from Settings.
- Material copied or written outside the app is outside its protection.
- SplitFree operates no identity-directory service.
  - Public keys, display names, or information you share elsewhere may still identify you.

## Permissions

| Permission | Purpose |
| --- | --- |
| Internet and network state | Relay connections and synchronization when connectivity returns. |
| Bluetooth scan / advertise / connect | Nearby discovery and transport; scanning is declared as never used for location. |
| Legacy Bluetooth permissions | Discovery/transport on older Android versions. |
| Nearby Wi-Fi devices, Wi-Fi state | Nearby Wi-Fi transport; nearby-device access is declared as never used for location. |
| Fine/coarse location, Android 12L and earlier | Platform requirement for discovery; SplitFree does not read or store location coordinates. |
| Local network, API 37+ | Nearby Wi-Fi LAN transport. The app currently requires this permission along with the other Nearby permissions; it does not offer a Bluetooth-only fallback after denial. |
| Notifications, Android 13+ | New-expense and settlement notifications. |
| Boot completed | Reschedule background synchronization after device startup. |

**QR scanning**

- Google's ML Kit Code Scanner (`GmsBarcodeScanning`) supplies its own scanner screen and camera access.
- SplitFree does not declare a camera permission.
- When you tap Scan, SplitFree checks scanner availability and asks Google Play services to download the module if
  needed.
  - Install-time downloads may also be requested by the app manifest.

## Backups

> - **Keep identity and data backups:** a recovery phrase alone does not restore group keys or expense history.

| Backup property | Behavior |
| --- | --- |
| Format | `.splitfree` JSON export, produced in-app. |
| Authentication | HMAC-SHA256 over canonical exported fields, with a key derived from the exporting private identity. |
| Restore identity | Must match the identity that created the export. |
| Expense payloads | Remain NIP-44 ciphertext. |
| Group keys | Encrypted to the exporting identity. |
| Readable metadata | Group name, relay list, member public keys, event types, and timestamps. |

- Treat exports as sensitive even though payloads and keys remain encrypted.
- Keep recovery phrases separately from exported files.
- Android Auto Backup is disabled; backup rules exclude app data from cloud backup and device transfer.
- SplitFree has no remote password-reset or key-recovery service.

## Third-party services

| Service | Used for |
| --- | --- |
| Nostr relays | Encrypted-event storage and forwarding. |
| Google Nearby Connections | Direct device discovery and synchronization. |
| Google ML Kit Code Scanner | QR scanning and scanner-module delivery through Play services. |

- SplitFree adds no dedicated analytics/crash-reporting service, advertising network, social-login provider, or
  cloud-storage provider.
- Google Play services handles its own diagnostics and downloads under Google's policies and device settings.

## Children's privacy

- SplitFree has no developer-operated service that collects children's records.
- The project does not knowingly collect personal data from children under 13.
- Data entered by any user can still be shared with group members and stored as ciphertext on configured/fallback
  relays.

## Changes to this policy

- Policy changes update the date at the top of this file.
- Changes remain visible in the repository's Git history.

## Contact

- For privacy questions, open a [GitHub issue](https://github.com/invincible04/SplitFree/issues) without sensitive
  data.
- For vulnerabilities or unintended exposure, use the private route in [SECURITY.md](SECURITY.md).
