# Privacy Policy

**Last updated:** September 13, 2026

SplitFree is a decentralized expense-splitting app built on the [Nostr](https://nostr.com) protocol. It is designed from the ground up to protect your privacy. This policy explains what data exists, where it lives, and what we do (and don't) have access to.

## The Short Version

- SplitFree has no developer-operated service. Nothing you enter is sent to us; it is stored on your phone and, as encrypted events, on the Nostr relays you choose
- We **do not** operate any servers; there is no "backend"
- We **cannot** read your expenses, group names, or balances
- SplitFree adds no analytics, tracking, or advertising SDKs. Google Play services (Nearby Connections, ML Kit code scanner) have their own diagnostics, described below
- Expense payloads are encrypted end-to-end in transit and on relays; group metadata (names, member lists, relay lists) is stored unencrypted in the app's private database on your phone

## Data Storage

The app keeps a local ledger on your device. The relays you choose also retain encrypted events for synchronization:

| Data | Where | Encrypted |
|------|-------|-----------|
| Expense, settlement and group-metadata payloads | Room (SQLite) on-device, and on your relays | ✅ NIP-44 v2 ciphertext (ChaCha20 + HMAC-SHA256) |
| Group names, descriptions, member public keys and display names, relay lists, member clocks; event authors, types and timestamps | Room (SQLite) on-device | Plaintext in an app-private database; protected by Android's app sandbox and your device lock, not by SplitFree's encryption |
| Sealed envelopes (gift wraps, per-member key rotations) held for other members | Room (SQLite) on-device | Ciphertext addressed to the recipient. Dropped after 30 days when carried for someone else, 90 days when you authored them |
| Private key | App-private encrypted storage | ✅ AES-256-GCM under an Android Keystore key; hardware backing depends on the device |
| Group symmetric keys | App-private encrypted storage | ✅ AES-256-GCM under an Android Keystore key; hardware backing depends on the device |
| User preferences (theme, gift-wrap toggle, your display name) | SharedPreferences on-device | Plain |

SplitFree does **not** use cloud databases, Firebase, or any remote storage controlled by us.

## Network Communication

### Nostr Relays

SplitFree syncs encrypted events through public Nostr relays. Relays are third-party servers that store and forward messages, similar to email servers.

- **What relays see:** Ciphertext (encrypted blobs), the tags on each event, the signing public key, the event kind and timestamp, and connection metadata such as your IP address
- **What relays cannot read:** Expense amounts, descriptions, group names, or balances. Public-key and tag metadata remain visible
- **Gift Wrap (optional):** NIP-59 triple-layer encryption hides which member authored a wrapped expense or settlement. The wrap still carries the recipient's public key and the group tag, and group-metadata and key events are published directly under your own key

To be precise about the metadata: direct events (kind 30078) carry the group id tag, an event type tag (`expense`, `settlement`, `group_meta`, ...), an expense id tag linking revisions of one expense, the author's public key, the `created_at` timestamp and, on per-member key rotations, the recipient's public key. Gift wraps (kind 1059) carry the recipient's public key, the group id tag, a one-time outer signing key and a randomized timestamp within the past two days; the inner event and its real author are encrypted. Relays also see the IP address you connect from. That is enough to learn that a set of keys belongs to the same group and when it is active, but not what any event says.

You can choose which relays to use. We do not operate any relays.

### Nearby sync (Google Nearby Connections)

When you use nearby sync, SplitFree communicates directly with other group members through Google Nearby Connections, which picks Bluetooth, Bluetooth Low Energy or Wi-Fi (including Wi-Fi LAN and Wi-Fi Direct) for the link. Your expense data travels only between the phones involved; no SplitFree server exists and no relay is involved.

Before anything about a group is disclosed, both phones prove ownership of their Nostr keys with a Schnorr signature over a challenge that is bound to that specific connection's Nearby authentication token, and exactly one group is opened only if the other phone is a member of it. Records are exchanged as small JSON frames (at most 16 KiB, with paged inventories and chunked records), each answered with a receipt; progress is kept in the local database, so an interrupted session re-authenticates and transfers only what is still missing. Every record you receive is verified against the original author's signature. Both phones must run a current SplitFree: the protocol is versioned (v3), and a phone speaking an older protocol is refused rather than partially understood.

Nearby sync is foreground only: it runs while the Nearby sync screen is open, and stops when you leave it. A phone may carry sealed envelopes addressed to other members so it can hand them over later; these envelopes are encrypted to their recipient, the carrier cannot read or alter them, and they are dropped after 30 days (90 days for envelopes you authored). Data applied from one connected phone is re-offered to the other phones connected at the same time. Only records that carry a signature a third party can verify are forwarded; an expense a phone received only as a gift-wrapped rumor is not forwarded on the author's behalf.

The Nearby Connections SDK is part of Google Play services. Google documents that the Nearby SDK collects connection performance metrics and device information, including device model, country, build version and application package name; you can allow or deny this through Android Settings > Google > Usage & diagnostics. See [Google Nearby data collection](https://developers.google.com/nearby/connections/overview). This section describes Google's SDK documentation; we have not captured the SDK's network traffic to measure its actual diagnostics, and it is outside SplitFree's control. It does not include your expense data.

## Identity

Your identity is a cryptographic keypair (secp256k1), not an email, phone number, or account. Your public key is your identifier. The app never sends your private key to relays or to other phones. You can deliberately reveal and copy it, or back up your identity as a 24-word BIP-39 mnemonic phrase, from Settings; anything you copy or write down is outside the app's protection and must be kept private.

We have no way to associate your keypair with your real-world identity.

## Permissions

| Permission | Why |
|------------|-----|
| Internet, network state | Connect to Nostr relays for sync; resume sync when connectivity returns |
| Bluetooth (scan, advertise, connect on Android 12+; legacy Bluetooth on older versions) | Nearby Connections discovery and transport. Scanning is declared as never used for location |
| Nearby Wi-Fi devices (Android 13+), Wi-Fi state | Nearby Connections Wi-Fi transport. Declared as never used for location |
| Location (Android 12L and earlier) | Android requires it for Bluetooth and Wi-Fi discovery on those versions. SplitFree never reads or stores your location |
| Local network (API 37 / Android 17 and later) | Nearby Connections Wi-Fi LAN transport |
| Notifications (Android 13+) | Notify you of new expenses and settlements |
| Receive boot completed | Reschedule background relay sync after a reboot |

QR code scanning uses Google's ML Kit Code Scanner (`GmsBarcodeScanning`), a Google Play services module that provides the scanner screen and camera access itself; SplitFree declares no camera permission.

## Backups

SplitFree disables Android Auto Backup (`allowBackup="false"`) and explicitly excludes all app data from cloud backup and device-to-device transfer. The only way to back up your data is through the in-app export feature, which produces a `.splitfree` JSON file authenticated with HMAC-SHA256 under a key derived from your private key, so only the same identity can restore it. Inside the file, expense payloads remain NIP-44 ciphertext and the group keys are encrypted to your own key, but the group name, relay list, member public keys, event types and timestamps are readable by anyone who obtains the file. Treat it like the plaintext metadata described above.

## Third-Party Services

SplitFree does **not** integrate with:

- Analytics or crash reporting services
- Advertising networks
- Social login providers
- Cloud storage providers

Beyond the Nostr relays you choose, the app relies on two Google Play services modules: Nearby Connections (nearby sync, described above) and the ML Kit Code Scanner (QR scanning). The app's manifest asks Google Play services to install the barcode scanner module; Google Play services performs that download and handles its own diagnostics under your device's Google settings and Google's policies.

## Children's Privacy

SplitFree does not knowingly collect any data from anyone, including children under 13, and has no developer-operated service that could. Expense descriptions, group names and display names entered by any user are still shared, encrypted, with their group members and the relays they choose.

## Changes to This Policy

If this policy changes, the update will be reflected in this file with an updated date. Since SplitFree is open source, all changes are visible in the git history.

## Contact

If you have questions about this privacy policy, open an issue on [GitHub](https://github.com/invincible04/SplitFree/issues) or reach out via the contact methods listed in the repository.
