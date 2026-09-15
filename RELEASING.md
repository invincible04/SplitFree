# Releasing SplitFree

A maintainer checklist for a signed Android release, not evidence that one has shipped.

[Project overview](README.md) · [Contributor guide](CONTRIBUTING.md) · [Security policy](SECURITY.md)

## Version policy before the first release

Keep the displayed production `versionName` at **`1.0.0`** until the first approved
GitHub release. Uploading source does not itself release an APK or require a version
bump. The debug label remains `1.0.0-debug` while this policy applies.

Android's internal `versionCode` is independent of `versionName`. The current code
is the `versionCode` literal in `app/build.gradle.kts`; every bundle in the
[distribution ledger](#verified-local-distribution) records the code it was built
with. Increase the code for each new distributed update, even while the displayed
version stays `1.0.0`. Do not reset it to 1, weaken the ledger, rename an old APK
as a new version, or move an existing release tag. Repeated local build checks are
not new distributions. Earlier bundles in the ledger are historical, not candidates;
they remain unchanged. The distribution command refuses a code that does not exceed
the ledger's newest bundle, so a new distribution never needs a displayed-version
bump.

## What may be published on GitHub

| Material | Git source repository | Public release or other handling |
| --- | --- | --- |
| Reviewed Kotlin/Python source, tests, manifests, Gradle files, workflows, license/docs and owned assets | Yes, after ownership, license and sensitive-data review | Corresponding source must match the released APK's exact commit. |
| Public signing certificate SHA-256 (`release/signing-certificate.sha256`), APK/file checksums, source commit SHA and pinned Actions SHAs | Yes | Safe and useful verification metadata; hashes alone do not authenticate the publisher. |
| Public signing certificate and APK signature | Public, but do not confuse a certificate with a private key | Already extractable from signed APKs. Review certificate subject metadata for privacy. |
| Production-signed, verified APK | No, keep generated binaries out of source history | Attach only the accepted APK to the approved GitHub Release. |
| `release-info.json`, `SHA256SUMS.txt`, exact-source archive, license and reviewed dependency notices | Generated release metadata need not be committed | These accompany the APK as the six allowlisted release assets. |
| Keystore (`*.jks`, `*.keystore`, private PKCS#12), private signing key, passwords, tokens, recovery phrases, real user backups | **Never**, including private repositories or Git history | Never in releases, Actions uploads, logs, issues or chat. Use protected signing-environment secrets and encrypted offline backups. Base64 keystore data is equally secret. |
| `local.properties`, credential-bearing `.env*`/key properties, SDK paths and local configuration | No | Keep local; publish only explicitly reviewed, secret-free examples. |
| Debug/unsigned APKs and raw local distribution directories | No | Not public production releases. Local bundles include uncommitted source/logs and are not automatically release-approved. |
| R8 `mapping.txt`, test reports, diagnostic logs and screenshots | No by default | Retain exact mapping for crash diagnosis; review for personal data and paths before any sharing. Actions artifacts are not a private vault. Mapping is not a signing secret, but is not one of the public release assets. |

Only the final release bundle's explicit assets are uploaded to a draft. Do not
upload a workspace, keystore directory, build cache, or the entire local evidence
folder. `.gitignore` prevents ordinary accidental adds; it neither scans contents
nor removes anything already tracked or present in history. A source-archive path
allowlist does not detect a credential pasted inside an otherwise allowed file.
Review Git history and file contents separately before public source publication.

Never publish hashes of passwords, recovery phrases or other low-entropy secrets
as diagnostic evidence. A public certificate fingerprint or binary checksum is a
different kind of data. Commit/tag signatures also do not replace Android APK
signing; never publish the private GPG/SSH key used for Git signing.

The license gate remains closed (`approved: false`). Local signature/build checks
do not approve employer open-source obligations, dependency redistribution terms,
GitHub settings, device acceptance or public launch.

## Source publication checks

```bash
python3 -B tools/release/publication_check.py --source worktree
python3 -B tools/release/publication_check.py --source index
python3 -B tools/release/publication_check.py --source history --refs refs/heads/mainline
```

The history example checks only the named local ref and its ancestors. Explicitly
list **all branches/tags you intend to share**; cached remote-tracking refs describe
a local cache, not live GitHub accessibility. History scanning never fetches,
rewrites or pushes. It rejects shallow history and must finish without findings or
limit errors. It does not inspect unrelated refs, reflogs, unreachable objects,
commit messages, author metadata, server caches or forks. Review those separately
when responding to an exposure. Current-source CI passing is not history approval.

The shared [publication policy](tools/release/publication_policy.py) checks full
paths, selected bytes and recognized credential formats before either source
packager writes output. Ignored files below `app/src`, `app/schemas` and `gradle`
are included because they may affect the build. Unsafe inputs fail the operation;
they are not silently omitted. Source tar output is reconstructed from verified
file bytes, not unchecked input archive comments or trailers: the release packager
takes each entry's mode from the Git tree, while the local distribution archive
records `gradlew` as executable and every other file as read-only. Symlinks,
unresolved Git LFS pointers, generated outputs, credential files, unknown binaries
and oversized inputs are rejected.
Bounds are 16 MiB/file, 128 MiB/read set, 20,000 files and, for history, 2,000
commits with a 180-second scanner deadline. Limit failures are incomplete scans,
never success. Each file also caps findings at 1,000; the scanner caps aggregate
findings at 20,000 and tree entries at 1,000,000. JSON findings contain path, line
and rule, with commit/blob identity for history, never the matched value. Line 0 denotes a file/operation-level finding.

Seven existing binary build/visual inputs are pinned by exact path and SHA-256.
A pin recognizes existing bytes; it does not prove licensing, image privacy or the
absence of hidden data. New/changed binaries need provenance, visual/privacy and
license review before changing a pin. Synthetic exceptions identify exact matches
at specific test paths. Never add a blanket test-directory skip or auto-accept a
new finding. Public test vectors and certificate fingerprints are allowed; private
signing keys and passwords are not.

Do not edit the checkout while scanning or packaging. Per-read race checks and
source-directory change checks are not an atomic filesystem snapshot.

These checks reduce accidental exposure but cannot prove arbitrary source, encoded
data, personal records or binaries contain no secrets. Independently review diffs
and ownership before uploading. GitHub automatic source archives and direct Git
uploads bypass the custom packagers. No local hook or remote setting is installed
by these changes. The release license gate remains `approved: false`.

If a secret exists in history, stop publication. Rotate its protection where
appropriate, preserve a protected recovery copy and prepare an isolated,
reviewed history-cleanup plan. Removing a current assignment or adding ignore
rules cannot erase an old commit. Do not casually reset a working checkout or
move public tags; coordinate any remote cleanup separately.

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

### Verified local distribution

Use this path for private APK handoffs, rather than copying a raw Gradle output. It runs the release-helper regressions, formatting, the JVM suite, both lint variants and debug/signed-release builds, then checks both compiled variants and uses the same APK validator as CI.

```bash
python3 -B tools/release/distribute.py --build-tools "$ANDROID_HOME/build-tools/36.1.0"
```

The tool keeps its data outside every checkout, under `$XDG_DATA_HOME/splitfree/` (default `~/.local/share/splitfree/`), so no `git clean`, re-clone or worktree removal can discard it:

| Path | Contents |
| --- | --- |
| `distributions/` | The **ledger**: one immutable, hash-verified bundle per distributed build, named `SplitFree-v<versionName>-<versionCode>-<apk sha256 prefix>/`. Each holds the APK, its exact R8 mapping, the source archive and hash manifest that built it, the full build log, `local-release.json` and, when `--init-script` was given, `test-runtime.init.gradle`. `--directory` selects another ledger. |
| `distribution-failures/` | The build log of every run that failed after its gates started; the error names the saved file. No bundle is produced. |

The ledger is the record of what exists in the wild. **Back it up like the keystore**; a ledger you cannot restore means version ordering rests on memory. Finder's `.DS_Store` files are tolerated; every other unexpected entry, including an interrupted `.candidate-*` stage, stops the run for investigation rather than being deleted.

Baseline rules, checked before any gate runs:

- With history in the ledger, the candidate's `versionCode` must exceed the newest bundle. Nothing else needs to be supplied.
- `--previous-apk FILE` additionally names a distributed production APK: a `com.splitfree` package with a verifying v2 signature from the single signer pinned in [`release/signing-certificate.sha256`](release/signing-certificate.sha256). If the ledger already holds that `versionCode`, the file must be byte-identical to that bundle's APK; two different builds may never share a code. The candidate's packaging policy (SDK levels, native ABIs, alignment) is not applied to a previous APK, so a later policy change never rejects the release it supersedes.
- An empty ledger is refused unless you pass `--previous-apk` for the last build you handed out, or `--first-distribution` to declare that none exists. The two flags are mutually exclusive, and `--first-distribution` is refused once the ledger has history.

The pin is a public certificate fingerprint, not a private key. Never replace the key or change the pin merely to make an update install. Local Gradle signing still uses the existing keystore setup below; there is no debug-key fallback.

- The candidate must pass the shared checker: production identity, numeric minimum/target SDK 26/37, non-debuggable/non-test-only standalone packaging, all four supported native architectures, 16 KB native ZIP/64-bit ELF alignment and a fresh v2 signature check of the exact candidate bytes.
- R8 mapping ID must match the marker embedded in the APK; the mapping body checksum is verified. Source files must remain unchanged across the build. This is recorded local provenance, not a claim of reproducible builds or a hermetic build environment. Published-release ordering remains separately enforced by the GitHub helper.
- One run per checkout at a time (`.local-distribution.lock/` at the repository root, ignored) and one run per ledger at a time (`.distribution-lock/` inside it). Completed bundles are never overwritten. A lock left behind by an interrupted run requires investigation, not automatic deletion. Do not run another build or edit source while the distribution command is running.
- Use `--offline` when dependencies are cached. `--init-script` accepts trusted local Gradle configuration, not downloaded/unreviewed scripts.

These bundles are explicitly **not public-release approved** and **not device-install tested**. The license review and GitHub gates remain closed until completed. The [installation acceptance guide](tools/release/INSTALL_TESTING.md) covers emulator fresh installs, same-key upgrades and the separate identity/database-preservation checks. A successful build and signature check do not certify the phone's current package/signing state.

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
