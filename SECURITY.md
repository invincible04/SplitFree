# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.0.x   |    ✅     |

## Reporting a Vulnerability

If you discover a security vulnerability in SplitFree, please **do not** open a public issue.

### How to Report

[Report it privately via GitHub](https://github.com/invincible04/SplitFree/security/advisories/new) with:

1. A description of the vulnerability
2. Steps to reproduce
3. Potential impact
4. Suggested fix (if any)

### What to Expect

- **Acknowledgment** within 48 hours
- **Assessment** within 7 days
- **Fix timeline** communicated after assessment
- **Credit** in the release notes (unless you prefer anonymity)

### Scope

The following are in scope:

- Cryptographic implementation flaws (NIP-44, NIP-59, BIP-39, BIP-340)
- Key material exposure or leakage
- Event signature bypass or forgery
- Group encryption key extraction
- BLE handshake authentication bypass
- Deep link injection or intent hijacking
- Data exfiltration through relay metadata
- Denial of service through malformed events

### Out of Scope

- Vulnerabilities in third-party Nostr relays
- Social engineering attacks
- Physical device access attacks
- Issues in dependencies (report upstream)

## Security Architecture

SplitFree's security model is built on several layers:

- **End-to-end encryption**: All group data encrypted with NIP-44 v2 (ChaCha20 + HMAC-SHA256). Relays only see ciphertext.
- **Gift Wrap privacy**: Optional NIP-59 triple-layer encryption hides sender metadata.
- **Cryptographic identity**: BIP-340 Schnorr keypairs. No passwords, no accounts.
- **Key storage**: Private keys stored in Android Keystore (AES-256-GCM, hardware-backed).
- **Event validation**: Signature verification, timestamp bounds, rate limiting, content size limits, nesting depth checks.
- **BLE authentication**: Challenge-response handshake before any data exchange over Bluetooth.
- **Export security**: HMAC-SHA256 integrity verification on all exported data. Group keys NIP-44 encrypted to the exporter's own pubkey.
- **ProGuard/R8**: Code shrinking and obfuscation enabled for release builds.

## Responsible Disclosure

We follow a responsible disclosure process. We ask that you:

- Give us reasonable time to fix the issue before public disclosure
- Do not access or modify other users' data
- Do not degrade the service for other users
- Act in good faith

We will not pursue legal action against researchers who follow this policy.
