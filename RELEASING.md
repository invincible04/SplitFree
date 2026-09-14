# Releasing SplitFree

A maintainer checklist for a signed Android release, not evidence that one has shipped.

[Project overview](README.md) · [Contributor guide](CONTRIBUTING.md) · [Security policy](SECURITY.md)

## Automated APK releases

GitHub-hosted releases do not require GitHub authentication on your development laptop. Complete the [one-time GitHub setup](#one-time-github-setup) before triggering a release.

The [release workflow](.github/workflows/release.yml) prepares a **draft**, never publishes it.
It becomes active after the workflow reaches GitHub and the setup below is complete.

```text
Existing vX.Y.Z tag on GitHub
    → validate source/version/notices → tests + unsigned release build
    → approve isolated signing → verify signed package → draft release
    → test that exact APK → maintainer publishes the draft
```

| Job | Authority and output |
| --- | --- |
| `build` | Read-only token, no signing secrets. Checks tagged source and license approval, runs checks, builds unsigned APK and exact-source archive. |
| `sign` | Protected `release-signing` environment. No checkout, Gradle, repository scripts, or restored build cache. Signs the APK with the existing key. |
| `package` | Read-only token, no secrets. Verifies APK identity, signer, ZIP/ELF alignment, and unchanged APK entry payloads; produces checksums and release metadata. |
| `draft` | Only job with `contents: write`. Uploads the verified bundle to a new or matching draft. No public-publish operation. |

- Tag pushes matching `v*` start validation; accepted release tags are stable `vMAJOR.MINOR.PATCH` with no leading zeroes.
- The tag must already exist, resolve to the checked-out commit, and belong to `mainline` history.
- `versionName` must equal the tag without `v`; `versionCode` must exceed every published release, including prereleases.
- An older release without usable `release-info.json` blocks automation. Review and migrate its metadata before proceeding; do not guess the installed version code.
- **Retry:** prefer rerunning failed jobs on the same tag while their input artifacts remain available. A full rerun or dispatch on that existing tag must reproduce identical bundle bytes to resume a draft; otherwise it fails rather than replaces files. A branch dispatch does not run release jobs.
- A retry only adds missing, byte-matching assets to a draft bound to the same source and bundle. It never replaces a published release or overwrites conflicting assets.
- One release run executes at a time. GitHub concurrency may replace older pending runs; rerun a displaced tag deliberately.

### One-time GitHub setup

Configure these settings yourself after uploading the reviewed workflow. Merely adding YAML does not configure repository protections.

1. Protect `mainline`, require CI, and review changes to workflows and release helpers.
2. Restrict creation, updates, and deletion of `v*` tags to trusted maintainers. Do not move release tags.
3. Create the **`release-signing` environment**, restrict it to release tags, and require approval before signing. Verify support for environment protections on your GitHub plan and repository visibility.
4. Add the secrets and variable below to that environment, not to pull-request jobs.
5. Allow the workflow's scoped `GITHUB_TOKEN` release writes; no personal access token is required.
6. Confirm repository visibility and complete the license review below before starting the first release.

| Environment entry | Type | Value |
| --- | --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Secret | Existing release keystore encoded as one Base64 line, with no whitespace. Base64 is not encryption. |
| `RELEASE_STORE_PASSWORD` | Secret | Existing keystore password. |
| `RELEASE_KEY_ALIAS` | Secret | Existing signing-key alias. |
| `RELEASE_KEY_PASSWORD` | Secret | Existing key password. |
| `RELEASE_CERT_SHA256` | Variable | Independently verified SHA-256 certificate fingerprint, hex with optional colons. |

- Never paste key material into issues, logs, chat, or tracked files. Keep encrypted offline backups.
- Required-reviewer self-approval settings must fit your maintainer setup; preventing self-review requires another eligible reviewer.
- Environment controls and tag protection are security boundaries. The workflow comes from the tag; someone allowed to alter it must not gain unreviewed access to signing secrets.
- GitHub API tag checks cannot atomically prevent a concurrent tag deletion. Tag protections are required, not optional substitutes for validation.

### License-review gate

[`release/policy.json`](release/policy.json) starts with **`approved: false`**. This deliberately blocks release preparation while the dependency-license review is unfinished. Ordinary CI remains usable.

Before the first automated draft:

1. Inventory direct, transitive, and native dependencies and assets for the exact candidate, including required license/copyright/warranty notices.
2. Review Google Nearby and Code Scanner redistribution terms and their compatibility with the project's GPL distribution. This workflow does not make that legal determination.
3. Commit the reviewed notice bundle as `release/THIRD-PARTY-NOTICES.txt`.
4. Set the policy's `versionName` to the candidate version and `noticesSha256` to the file's SHA-256. Set `approved: true` only after review.
5. Revisit this approval for each new version and any dependency change. Matching a hash proves file identity, not legal completeness.

### Downloads and retained evidence

| Artifact | Location |
| --- | --- |
| `SplitFree-vX.Y.Z.apk` | Draft release, signed installable APK. |
| `SplitFree-vX.Y.Z-source.tar.gz` | Draft release, exact Git commit including build scripts. |
| `LICENSE.txt`, `THIRD-PARTY-NOTICES.txt` | Draft release, project license and reviewed notices. |
| `release-info.json` | Draft release, source commit, version/code, signing fingerprint and payload hashes. |
| `SHA256SUMS.txt` | Draft release, hashes for the four payloads and `release-info.json`. |
| Unsigned package and signed intermediate | Actions artifacts, 7 days. |
| Final bundle, test/lint reports, R8 mapping | Actions artifacts, 30 days. Archive these yourself for long-term maintenance. |

- Download and test the signed APK before publishing the draft; do not rebuild a replacement after acceptance.
- Drafts are not public downloads. After publishing, GitHub Releases exposes the APK for sharing, subject to repository visibility.
- A failed upload can leave a partial draft. Do not publish until all six assets and checksums have been verified.
- Do not manually edit or publish a draft while its workflow is running. GitHub does not offer an atomic draft-only asset upload; concurrent manual changes can race the checks.
- Before manual publication, recheck that its `versionCode` exceeds all now-published versions. Separate unpublished drafts can have the same code; publish in a controlled order.
- Builds are pinned to action commits, JDK 17, Command-line Tools 22.0, SDK platform 37.0 and Build Tools 36.1.0. Release builds do not restore Gradle caches; downloaded dependencies are not yet covered by dependency verification/locking.
- The checksum manifest detects changed files. It is not independent publisher authentication or a reproducible-build attestation.

### Local verification, without publishing

```bash
python3 -m unittest discover -s tools/release/tests -v
./gradlew -PsplitfreeUnsignedRelease=true spotlessCheck :app:testDebugUnitTest \
  :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

Unsigned output: `app/build/outputs/apk/release/app-release-unsigned.apk`. This explicit mode does not read local signing credentials. Without that flag, local release signing works as described below.

- Use `actionlint .github/workflows/ci.yml .github/workflows/release.yml` if Actionlint is installed.
- Helper tests use disposable data and mocked network/SDK boundaries. They never upload release assets.
- Do not run the upload helper with a real token as a dry run: its upload mode creates drafts and assets.
- Local checks cannot certify GitHub environment approvals, token permissions, hosted SDK downloads, or the first remote workflow execution.

## Checklist

1. [Confirm repository readiness](#1-confirm-repository-readiness)
2. [Protect the signing identity](#2-protect-the-signing-identity)
3. [Build and verify](#3-build-and-verify)
4. [Test the exact signed APK](#4-test-the-exact-signed-apk)
5. [Prepare the release bundle](#5-prepare-the-release-bundle)
6. [Publish only after approval](#6-publish-only-after-approval)

## 1. Confirm repository readiness

- Review the complete history being published for credentials, private files, employer email
  addresses, and material you do not have permission to distribute. Confirm any applicable
  employer open-source approval. `.gitignore` does not remove files from history.
- Verify the intended repository visibility, `mainline` default branch, branch protection/rulesets,
  required CI check, Actions permissions, and private vulnerability reporting. Test the public
  source, privacy, and security-reporting links as an unauthenticated visitor.
- Check local author identity and upstream configuration. Keep signing keys, `local.properties`,
  and generated APKs out of Git. Review local hooks: hooks are not distributed with a clone.
- Choose a release commit and record its full hash. Release from a clean checkout, not local
  fixes that are absent from the corresponding source. Do not move a published release tag.

## 2. Protect the signing identity

The current Gradle configuration reads `splitfree-release.jks` at the repository root and
these entries in `local.properties`:

| Property | Value to provide locally |
| --- | --- |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Existing release-key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

Preserve any existing `sdk.dir` entry. Both files are Git-ignored. Do not put their contents
in issues, logs, or build artifacts. Merely setting environment variables with those names
does not configure the current build.

- Keep encrypted, access-controlled backups of the keystore and credentials, stored separately
  from the working machine. Verify that you can recover them.
- Record and independently confirm the signing certificate's SHA-256 fingerprint before the
  first release. Continue using that signing identity for updates.
- Increment `versionCode` for each subsequent distributed update and set an appropriate
  `versionName`. APKs with the same app ID but a different signing key cannot update an install.

## 3. Build and verify

Use the [documented toolchain](README.md#build-from-source). From the release checkout:

```bash
./gradlew spotlessCheck :app:testDebugUnitTest :app:lintDebug :app:lintRelease
./gradlew :app:assembleDebug :app:assembleRelease
```

- **Signed output:** `app/build/outputs/apk/release/app-release.apk`.
- **Release behavior:** R8 shrinking and obfuscation; a passing debug build is not sufficient.
- **Evidence:** fresh test results for this candidate, not an older report.

With Android SDK Build Tools on your `PATH`:

```bash
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk
```

| Verification | Required check |
| --- | --- |
| Signing identity | Compare the fingerprint with the expected certificate, not just the verifier's exit status. |
| APK identity | Package name, version code/name, minimum SDK, and non-debuggable release manifest. |
| Native compatibility | Verify native ELF segment alignment as well as ZIP alignment for 16 KB page-size support. |
| Runtime | Exercise native crypto on a compatible device or emulator. |

## 4. Test the exact signed APK

- Use dedicated test devices or emulators with disposable data.
- A separate user/work profile does **not** bypass Android's device-wide package-signature checks.
- Do not replace a differently signed installation or clear real user data to make a test pass.
- Record devices, Android versions, APK SHA-256, results, and known limits.

### Acceptance checklist

- [ ] Cold launch; create/recover an identity; verify secrets stay protected when the app backgrounds.
- [ ] Two-device group creation and QR/paste join; add, edit, delete, and settle expenses; compare exact balances and history.
- [ ] Offline save, process termination, reopening, relay outage/recovery, and background/Doze catch-up without duplicated or missing ledger entries.
- [ ] Three-device Nearby sync: permissions denied/granted, interrupted transfer, reconnection, and A-to-B-to-C forwarding with default gift wrapping.
- [ ] Member removal and identity rotation; remaining members receive the new key; removed members cannot read new-key data. A newly generated invite uses the current key and relay list.
- [ ] Restore an exported backup with its original identity on a fresh installation, including history across a group-key rotation; verify real Keystore persistence.
- [ ] Upgrade from the previous distributed release without losing keys or data, when such a release exists.
- [ ] Small screens, large text, TalkBack, keyboard/insets, and representative supported Android versions.

JVM tests and simulated transports supplement this checklist; they do not replace it.
Run opt-in live-relay tests separately with disposable identities. Never upload personal
backups, invitations, or recovery phrases as test evidence.

## 5. Prepare the release bundle

Publish these together, linked to the exact release commit/tag:

- **Signed APK**, named with its version, plus a **SHA-256 checksum file** and the expected
  signing-certificate fingerprint in the release notes. Generate checksums after final signing
  and naming; never modify the APK afterward. A checksum alone does not authenticate a publisher.
- **Corresponding source**, including build scripts, for that exact binary. Make source access
  available alongside the APK, not only through a mutable default-branch link.
- **License and notices:** GPL text, project copyright/warranty notice, and the required notices
  for bundled dependencies and assets. Inventory transitive and native dependencies as well as
  direct dependencies. Include notices with binary distribution, not only in the source tree.
- **Release notes:** changes, Android requirements, Google Play services dependency, backup and
  upgrade instructions, compatibility changes, known limitations, and device-test coverage.

Review redistribution terms and compatibility of Google Play services dependencies with the
project's GPL license before distributing binaries. This checklist is not a legal determination.
Do not assume the current Google-dependent build is eligible for F-Droid.

Retain the exact APK, its checksum, source commit, test reports, and
`app/build/outputs/mapping/release/mapping.txt` in the maintainer release archive.
An Android App Bundle (`./gradlew :app:bundleRelease`) is for a store workflow, not direct
installation; Play distribution also needs its own listing, policy, and signing setup.

## 6. Publish only after approval

1. Confirm every preceding gate and obtain maintainer approval.
2. Create the version tag and release through the approved process.
3. Verify the download, checksum, signing fingerprint, and corresponding-source link from a separate client.
4. Advertise the download only after those checks pass.

A Git operation alone does not create a GitHub Release or attach an APK.

## Recommended automation follow-up

- Reviewed dependency verification/locking and automated dependency updates.
- Artifact provenance attestations and an independently reproduced build.
- Issue/PR templates and a dedicated confidential conduct-reporting contact.
