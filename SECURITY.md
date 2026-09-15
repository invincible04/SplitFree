# Security policy

- Report vulnerabilities privately.
- Do not include sensitive details in a public issue.

[Project overview](README.md) · [Privacy policy](PRIVACY.md) · [Nostr guide](app/src/main/java/com/splitfree/data/nostr/README.md) · [Nearby protocol](app/src/main/java/com/splitfree/sync/nearby/README.md)

## Supported versions

| Version | Support |
| --- | --- |
| Current `mainline` | Security reports accepted. |

## Reporting a vulnerability

- Use [GitHub private vulnerability reporting](https://github.com/invincible04/SplitFree/security/advisories/new) when
  available for the repository.
- If that route is unavailable, request a private contact method in a public issue without disclosing the
  vulnerability or sensitive data.

| Include | Details |
| --- | --- |
| **Summary** | What fails and which security property is affected. |
| **Affected build** | App version or source commit, Android version, and device model when relevant. |
| **Reproduction** | Minimal steps or a test using disposable identities and data. |
| **Impact** | Access gained, information exposed, or availability affected. |
| **Suggested fix** | Optional mitigation or proposed correction. |

- Never post real private keys, recovery phrases, invitations, or backup files publicly.
- Redact identifying information from logs and screenshots.
- Dependency issues affecting SplitFree are welcome; include the upstream advisory when available.

### What to expect

| Stage | Target |
| --- | --- |
| Acknowledgment | Aim to respond within 48 hours. |
| Assessment | Aim to provide an initial assessment within 7 days. |
| Fix timeline | Communicated after assessment. |
| Credit | Included in release notes unless you prefer anonymity. |

These are response targets, not guaranteed service levels.

## Scope

### In scope

- Cryptographic implementation flaws involving NIP-44, NIP-59, BIP-39, or BIP-340.
- Private/group key exposure or extraction.
- Event-signature bypass, forgery, or unauthorized ledger changes.
- Nearby authentication or group-scope bypass.
- Deep-link injection or intent hijacking.
- Unintended data exposure through relay metadata.
- Denial of service through malformed events.
- Dependency vulnerabilities with an impact on SplitFree.

### Out of scope

- Vulnerabilities confined to third-party Nostr relays.
- Social engineering.
- Physical device access attacks.

## Security architecture

| Protection | Implementation | Boundary |
| --- | --- | --- |
| **Payload encryption** | NIP-44 v2 for expense, settlement, and group-metadata content. | Public event fields, routing tags, and IP addresses remain visible to relays. |
| **Gift wrapping** | NIP-59 for wrapped expenses and settlements. | Recipient/group tags remain visible; group-metadata and key events use direct publication. |
| **Identity** | BIP-340 Schnorr keypair generated locally. | Anyone with the private key or recovery phrase can act as that identity. |
| **Key storage** | AES-256-GCM under an Android Keystore key. | Hardware backing depends on the device; local group metadata is not encrypted by SplitFree. |
| **Event validation** | Signature, timestamp, membership, group-scope, rate, and payload checks. | Historical/reconciliation contexts deliberately use different age/rate admission rules. |
| **Nearby authentication** | Mutual Schnorr signatures including the SDK connection token when supplied. | A missing token is hashed as empty bytes, not rejected solely for absence; see the protocol's [authentication limits](app/src/main/java/com/splitfree/sync/nearby/README.md#authentication). |
| **Backup authentication** | HMAC-SHA256 over canonical exported data/metadata with an identity-derived HKDF key. | Only the exporting identity can restore it; metadata remains readable. |
| **Release build** | R8 shrinking and obfuscation. | Build hardening does not replace authentication or encryption. |

### Important limits

- Encryption does not guarantee anonymity or relay availability.
- Previously disclosed keys and history cannot be taken back.
- Historical membership is not tamper-proof when a former member retains an old group key.
- A Nearby receipt or “up to date” status is scoped to that exchange, not a global replication guarantee.
- Local diagnostics can contain identifiers and exception text.
  - Redaction covers specific patterns, not every secret; inspect reports before sharing.
- JVM tests do not establish physical-radio behavior or real Keystore/process-death durability.

See [PRIVACY.md](PRIVACY.md) for data storage, permissions, and third-party SDK diagnostics.

## Responsible disclosure

- Give maintainers reasonable time to assess and fix the issue before public disclosure.
- Do not access or modify other users' data.
- Do not degrade service for other users.
- Act in good faith.

We will not pursue legal action against researchers who follow this policy.
