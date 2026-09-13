# Privacy Policy

**Last updated:** March 19, 2026

SplitFree is a decentralized expense-splitting app built on the [Nostr](https://nostr.com) protocol. It is designed from the ground up to protect your privacy. This policy explains what data exists, where it lives, and what we do (and don't) have access to.

## The Short Version

- We **do not** collect, store, or transmit any personal data
- We **do not** operate any servers; there is no "backend"
- We **cannot** read your expenses, group names, or balances
- We **do not** use analytics, tracking, or advertising SDKs
- All your data stays on your device and is encrypted end-to-end

## Data Storage

All data is stored locally on your device:

| Data | Where | Encrypted |
|------|-------|-----------|
| Expenses, settlements, groups | Room (SQLite) on-device | ✅ NIP-44 v2 (ChaCha20 + HMAC-SHA256) |
| Private key | Android Keystore | ✅ AES-256-GCM (hardware-backed) |
| Group symmetric keys | Android Keystore | ✅ AES-256-GCM (hardware-backed) |
| User preferences | SharedPreferences on-device | Plain (non-sensitive settings only) |

SplitFree does **not** use cloud databases, Firebase, or any remote storage controlled by us.

## Network Communication

### Nostr Relays

SplitFree syncs encrypted events through public Nostr relays. Relays are third-party servers that store and forward messages, similar to email servers.

- **What relays see:** Ciphertext (encrypted blobs), your public key, and event timestamps
- **What relays cannot see:** Expense amounts, descriptions, group names, member identities, or balances
- **Gift Wrap (optional):** When enabled, NIP-59 triple-layer encryption hides even the sender's public key from relays

To be precise about the metadata: relays (and anyone watching the traffic) can see the group id tag on every event, event timestamps, the sender's public key on events that are not gift-wrapped, and the recipient's public key on gift wraps. That is enough to learn that a set of keys belongs to the same group and when it is active, but not what any of it says.

You can choose which relays to use. We do not operate any relays.

### Nearby sync (Bluetooth / Wi-Fi)

When you use nearby sync, SplitFree communicates directly with other group members through Google Nearby Connections, which picks Bluetooth, Bluetooth Low Energy or Wi-Fi (including Wi-Fi LAN and Wi-Fi Direct) for the link. Your expense data travels only between the phones involved; no SplitFree server exists and no relay is involved.

Before anything about a group is disclosed, both phones prove ownership of their Nostr keys with a signature that is bound to that specific connection, and the group is opened only if the other phone is a member. Records you receive are verified against the original author's signature; an intermediate phone that carries an envelope for you cannot read it or alter it.

Nearby sync is foreground only: it runs while the Nearby sync screen is open, and stops when you leave it. A phone may carry sealed envelopes addressed to other members so it can hand them over later; these envelopes are encrypted to their recipient and are dropped after 30 days (90 days for envelopes you authored).

The Nearby Connections SDK is part of Google Play services. Google documents that it may report performance and device diagnostics to Google under the device's "Usage & diagnostics" setting; this is outside SplitFree's control and does not include your expense data.

## Identity

Your identity is a cryptographic keypair (secp256k1), not an email, phone number, or account. Your public key is your identifier. Your private key never leaves your device. You can back up your identity as a 24-word BIP-39 mnemonic phrase.

We have no way to associate your keypair with your real-world identity.

## Permissions

| Permission | Why |
|------------|-----|
| Internet | Connect to Nostr relays for sync |
| Bluetooth / Nearby Devices | Offline peer-to-peer sync |
| Foreground Service | Keep relay connections alive for real-time sync |
| Notifications | Notify you of new expenses and settlements |

QR code scanning uses Google's ML Kit Code Scanner, which handles camera access internally without requiring a separate camera permission from the app.

## Backups

SplitFree disables Android Auto Backup (`allowBackup="false"`) and explicitly excludes all app data from cloud backup and device-to-device transfer. The only way to back up your data is through the in-app export feature, which produces an HMAC-signed, encrypted `.splitfree` file that only your private key can decrypt.

## Third-Party Services

SplitFree does **not** integrate with:

- Analytics or crash reporting services
- Advertising networks
- Social login providers
- Cloud storage providers

The only third-party network interactions are with the Nostr relays you choose to connect to and, when you use nearby sync, the Google Play services Nearby Connections SDK described above.

## Children's Privacy

SplitFree does not knowingly collect any data from anyone, including children under 13. Since no personal data is collected or transmitted, there is no age-specific data handling.

## Changes to This Policy

If this policy changes, the update will be reflected in this file with an updated date. Since SplitFree is open source, all changes are visible in the git history.

## Contact

If you have questions about this privacy policy, open an issue on [GitHub](https://github.com/invincible04/SplitFree/issues) or reach out via the contact methods listed in the repository.
