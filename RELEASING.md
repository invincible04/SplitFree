# Releasing SplitFree

- A maintainer procedure for producing, accepting and publishing an Android release.
- **This guide is not release approval or evidence that a candidate has passed.**
- Record results for the exact source and APK.
- Local checks, device acceptance, license review and GitHub publication are separate gates.

[Project overview](README.md) · [Contributor guide](CONTRIBUTING.md) · [Security policy](SECURITY.md) · [Installation acceptance](tools/release/INSTALL_TESTING.md)

## Version policy before the first release

- The current source in [`app/build.gradle.kts`](app/build.gradle.kts) declares:
  - Production **`versionName = "1.0.0"` and `versionCode = 3`**.
  - Debug displays `1.0.0-debug` under the separate `com.splitfree.debug` package.

- Keep the displayed production version at **`1.0.0`** until the first approved GitHub
  release.
- Uploading source is not an APK release and does not require a version bump.

Android update ordering is independent of the displayed version:

- Increase `versionCode` for each new distributed update.
  - A candidate must exceed the
    [local distribution ledger](#verified-local-distribution) and any additional
    retained previous APK supplied to the helper.
  - For another distribution after code 3, use a code greater than 3 while keeping the
    displayed version frozen.
- Public automation separately requires a code greater than every published release,
  including prereleases.
  - Neither check replaces the other history.
- Repeated local build checks are not new distributions.
  - Keep historical bundles unchanged.
  - Do not reset the code to 1 or weaken the ledger.
  - Do not rename an old APK as a new version.
  - Do not replace the signing identity or move an existing release tag.

## Checklist

- Follow these gates for the chosen candidate.
- A private handoff uses the local ledger.
- A public release additionally needs approved notices, protected hosted signing and a
  complete draft.
- Neither route grants device or publication approval.

1. [Confirm repository readiness](#1-confirm-repository-readiness)
2. [Protect the signing identity](#2-protect-the-signing-identity)
3. [Build and verify](#3-build-and-verify)
4. [Test the exact signed APK](#4-test-the-exact-signed-apk)
5. [Prepare the release bundle](#5-prepare-the-release-bundle)
6. [Publish only after approval](#6-publish-only-after-approval)

## 1. Confirm repository readiness

- Review all history intended for publication for credentials, private files, employer
  email addresses and material you lack permission to distribute.
  - Confirm applicable employer open-source approval and dependency/asset rights.
- Verify intended repository visibility, `mainline` default branch, branch
  protections/rulesets, required CI checks, Actions permissions and private
  vulnerability reporting.
  - Test public source, privacy and security-reporting links as an unauthenticated
    visitor when publication is approved.
- Check local author identity, upstream configuration and local hooks.
  - Hooks are not distributed with a clone.
  - Workflow YAML does not configure GitHub repository or environment protections.
- Choose the release commit and record its full hash.
  - Public release source must come from a clean checkout, not local fixes absent from
    that commit.
  - The local distribution route can preserve uncommitted source, but that does not make
    it corresponding source for a tagged public release.

### What may be published on GitHub

| Material | Git source repository | Public release or other handling |
| --- | --- | --- |
| Reviewed Kotlin/Python source, tests, manifests, Gradle files, workflows, license/docs and owned assets | Yes, after ownership, license and sensitive-data review | Corresponding source must match the released APK's exact commit. |
| Public certificate SHA-256 (`release/signing-certificate.sha256`), APK/file checksums, source commit SHA and pinned Actions SHAs | Yes | Verification metadata, not independent publisher authentication. |
| Public signing certificate and APK signature | Public; neither is a private key | Already extractable from signed APKs. Review certificate subject metadata for privacy. |
| Production-signed, accepted APK | No generated binaries in source history | Attach only the exact accepted APK to the approved GitHub Release. |
| `release-info.json`, `SHA256SUMS.txt`, exact-source archive, license and reviewed notices | Generated metadata need not be committed | These accompany the APK as the six allowlisted release assets. |
| Keystore (`*.jks`, `*.keystore`, private PKCS#12), private signing key, passwords, tokens, recovery phrases, real user backups | **Never**, including private repositories or history | Never in releases, Actions uploads, logs, issues or chat. Use protected signing-environment secrets and encrypted offline backups. Base64 key material is equally secret. |
| `local.properties`, credential-bearing `.env*`/key properties, SDK paths and local configuration | No | Publish only explicitly reviewed, secret-free examples. |
| Debug/unsigned APKs and raw local distribution directories | No | Not public production releases. Local bundles can contain uncommitted source and private logs. |
| R8 `mapping.txt`, test reports, diagnostic logs and screenshots | No by default | Retain mapping for crash diagnosis; review personal data and paths before sharing. Actions artifacts are not a private vault. Mapping is not a signing secret, but is not an allowlisted public asset. |

- Only explicit final-bundle assets belong in a draft.
- Never upload the workspace, keystore directory, build cache or entire local evidence
  folder.
- `.gitignore` prevents ordinary accidental adds; it neither scans content nor removes
  files already tracked or present in history.
- A path allowlist cannot detect every credential pasted into an otherwise allowed file.

- Never publish hashes of passwords, recovery phrases or other low-entropy secrets as
  diagnostics.
- Certificate fingerprints and binary checksums are different kinds of data.
- Git commit/tag signatures do not replace Android APK signing.
- The private GPG/SSH key used for Git signing must also remain private.

### Source publication checks

Run from the repository root on a stable checkout:

```bash
python3 -B tools/release/publication_check.py --source worktree
python3 -B tools/release/publication_check.py --source index
python3 -B tools/release/publication_check.py --source history --refs refs/heads/mainline
```

- The history example covers only that named local ref and its ancestors.
- Explicitly list **all branches/tags intended for sharing** as full `refs/...` names
  (up to 100 per invocation).
- Cached remote-tracking refs describe a local cache, not live GitHub accessibility.
- History scanning does not fetch, rewrite or push.
- Shallow or grafted history is refused; findings and limit errors must be resolved, not
  reported as a complete scan.
- Current-source CI passing is not history approval.

- The [scanner](tools/release/publication_check.py) and shared
  [publication policy](tools/release/publication_policy.py) check paths, selected bytes
  and recognized credential formats.
- Worktree scans include ignored files under `app/src`, `app/schemas` and `gradle`
  because they may affect the build.
- Unsafe inputs fail rather than being silently omitted.
- Both source packagers apply the policy before writing output and reconstruct tar
  output from verified file bytes instead of retaining unchecked archive
  comments/trailers:
  - The public source archive preserves executable status from the Git tree using modes
    `0755` or `0644`.
  - The local archive uses `0755` for `gradlew` and `0644` for every other file.
    - These are conventional owner-writable modes, **not read-only protection**.
  - Symlinks, unresolved Git LFS pointers, generated outputs, credential paths, unknown
    binaries and oversized inputs are rejected.

| Bound | Limit |
| --- | --- |
| Source file / total read set / files | 16 MiB / 128 MiB / 20,000 |
| Scanner runtime / tree entries / aggregate findings | 180 seconds / 1,000,000 / 20,000 |
| Findings per file / history commits | 1,000 / 2,000 |

- A limit failure is an incomplete scan, never success.
- JSON findings identify path, line and rule, plus commit/blob identity for history,
  without the matched value.
- Line 0 denotes a file- or operation-level finding.

- Seven existing binary build/visual inputs are pinned by exact path and SHA-256.
- A pin recognizes bytes; it does not establish licensing, image privacy or absence of
  hidden data.
- Changed/new binaries need provenance, visual/privacy and license review before
  changing a pin.
- Synthetic text exceptions cover exact matches at specific test paths.
- Do not skip whole test directories or auto-accept findings.
- Public test vectors and certificate fingerprints are not private signing keys.

- **Limits of the gate:** no scan proves arbitrary source, encoded data, personal
  records or binaries contain no secrets.
- Review ownership and diffs independently.
- The history scan excludes unrelated refs, reflogs, unreachable objects, commit
  messages, author metadata, server caches and forks; investigate those separately for
  an exposure.
- GitHub automatic source archives and direct Git uploads bypass the custom packagers.
- These tools install no local hook or remote protection.

- Do not edit source during scanning or packaging: per-read race detection and
  source-directory checks are not an atomic filesystem snapshot.
- If a secret is in history, stop publication, rotate its protection where appropriate,
  preserve a protected recovery copy and prepare an isolated, reviewed cleanup plan.
- Removing an assignment or adding ignore rules cannot erase an old commit.
- Coordinate remote cleanup separately; do not casually reset a working checkout or move
  tags.

### License-review gate

- [`release/policy.json`](release/policy.json) currently has **`approved: false`**,
  `versionName: "1.0.0"` and an empty notices hash.
- The reviewed `release/THIRD-PARTY-NOTICES.txt` is not yet present.
- This blocks public release preparation, not ordinary CI.
- Local signing/build checks do not approve employer obligations, redistribution terms,
  device acceptance or public launch.

Before an automated draft:

1. Inventory direct, transitive and native dependencies and assets for the exact
   candidate, including license/copyright/warranty notices.
2. Review Google Nearby and Code Scanner redistribution terms and compatibility with the
   project's GPL distribution.
   - The workflow does not decide this, and the Google-dependent build must not be
     assumed eligible for F-Droid.
3. Commit the reviewed, nonempty notice bundle as `release/THIRD-PARTY-NOTICES.txt`.
4. Set the policy's `versionName` to the candidate version and `noticesSha256` to the
   notice file's SHA-256.
   - Set `approved: true` only after review.
5. Revisit approval for each version and dependency change.
   - The helper checks the approved flag, version and hash; matching them does not prove
     legal completeness.

## 2. Protect the signing identity

- Local Gradle signing uses `splitfree-release.jks` at the repository root and these
  entries in `local.properties`:

| Property | Value to provide locally |
| --- | --- |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Existing release-key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

- Preserve any `sdk.dir` entry.
- Both files are Git-ignored; keep their contents out of issues, logs and artifacts.
- Setting environment variables with these names does **not** configure the current
  local Gradle build.
- The explicit unsigned mode below skips loading signing credentials.
- Signed builds have no debug-key fallback.

- Keep encrypted, access-controlled keystore and credential backups separate from the
  working machine, and verify recovery.
- Independently confirm the public certificate fingerprint in
  [`release/signing-certificate.sha256`](release/signing-certificate.sha256).
- Continue using that identity for updates: the same package ID with a different key
  cannot update an installed app.
- Never replace the key or pin just to make an update install.
- Follow the [version policy](#version-policy-before-the-first-release) for code
  ordering without prematurely changing the displayed version.

## 3. Build and verify

- Use the [documented toolchain](README.md#build-from-source).
- Choose the route by its effect; no build route installs an APK or certifies runtime
  acceptance:

| Route | Effect | Additional requirements |
| --- | --- | --- |
| Local unsigned verification | Checks/builds debug and unsigned release without production credentials | SDK and dependencies; no installable production output. |
| Verified local distribution | Builds/signs and records a new immutable-by-convention ledger bundle | Existing signing identity, higher code and a trustworthy distribution baseline. |
| Automated APK release | Hosted build, isolated signing and a six-asset **draft** | Reviewed tagged source, approved notices and configured GitHub protections/secrets. |

### Local verification, without publishing

From the repository root, with `ANDROID_HOME` set to the reviewed SDK location:

```bash
python3 -B -m unittest discover -s tools/release/tests -v
./gradlew --no-daemon --max-workers=2 -PsplitfreeUnsignedRelease=true \
  spotlessCheck :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease
SPLITFREE_VARIANT_BUILD_DIR=app/build \
  AAPT2="$ANDROID_HOME/build-tools/36.1.0/aapt2" \
  python3 -B -m unittest discover -s tools/release/tests -p test_variants.py -v
```

- Unsigned output is `app/build/outputs/apk/release/app-release-unsigned.apk`.
- Debug output is `app/build/outputs/apk/debug/app-debug.apk`.
- The final command is required: without `SPLITFREE_VARIANT_BUILD_DIR`, helper discovery
  skips the actual built-variant test.
- It checks compiled manifests, generated identity, provider/link isolation and ML Kit
  registrar metadata/DEX constructors in **both** APKs.
- Those static contracts do not establish scanner or native-runtime behavior on Android.

- Use `actionlint .github/workflows/ci.yml .github/workflows/release.yml` when
  Actionlint is installed.
- Helper regressions mock network/SDK/device boundaries; they are not release uploads or
  hardware tests.
- Do not use the upload helper with a real token as a dry run: its upload mode creates
  drafts and assets.
- Local checks cannot certify environment approvals, token permissions, hosted SDK
  downloads or a remote workflow run; retain separate hosted evidence.

For an explicitly approved local signed build without creating a new ledger entry:

1. Use the same Gradle gates with `-PsplitfreeUnsignedRelease=false`.
2. Repeat the compiled-variant check against those outputs.

- Signed output is `app/build/outputs/apk/release/app-release.apk`.
- Release uses R8 minification, obfuscation and resource shrinking; passing debug alone
  is insufficient.
- Raw signed output is still not a verified handoff bundle or device acceptance.

To inspect an existing signed output with the pinned tools:

```bash
"$ANDROID_HOME/build-tools/36.1.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.1.0/zipalign" -c -P 16 -v 4 \
  app/build/outputs/apk/release/app-release.apk
```

- Compare the printed signer with the independently trusted pin.
- These two commands alone do not check the full production manifest/native
  ELF/source/mapping contract; the shared validator and ledger workflow below cover
  those checks.

### Verified local distribution

- For an approved private APK handoff, use the ledger helper rather than copying raw
  Gradle output.
- It runs helper regressions, formatting, JVM tests, both lint variants,
  debug/signed-release builds, compiled-variant checks and the shared signed-APK
  validator, then preserves the candidate and its provenance:

```bash
python3 -B tools/release/distribute.py --build-tools "$ANDROID_HOME/build-tools/36.1.0"
```

- **This command signs and creates a new distribution record.**
- It requires a code higher than its baseline before any gate runs.
- Rechecking current code 3 is not permission to create a different code-3 handoff.

- By default data lives under `~/.local/share/splitfree/`; an absolute `XDG_DATA_HOME`
  selects `$XDG_DATA_HOME/splitfree/` instead.
- Choose paths **outside every checkout** so repository cleanup, re-cloning or worktree
  removal cannot discard the ledger.
- This location is operator policy, not an enforced containment check: `--directory` can
  also select a ledger inside a checkout.
- The helper rejects symlinked ledger paths, but it does not certify that the chosen
  location is safe or backed up.

| Path | Contents |
| --- | --- |
| `distributions/` (or `--directory`) | One bundle per build, named `SplitFree-v<versionName>-<versionCode>-<apk sha256 prefix>/`: APK, exact R8 `mapping.txt`, `source.tar.gz`, `source-sha256.json`, `build.log`, `local-release.json`, and optional retained `test-runtime.init.gradle`. |
| `distribution-failures/` under the data home | Logs preserved for handled failures after a build log exists. The error names the saved log, or reports that preservation itself failed. Early refusals may have no build log. |

- The ledger records handoff ordering.
- **Back it up like the keystore.**
- Completed bundles are never overwritten by the helper and their recorded hashes are
  checked on subsequent runs; they are not filesystem read-only or tamper-proof.
- Preserve old bundles as history, not as new candidates.
- Ordinary Finder `.DS_Store` files are tolerated; other unexpected entries, including
  an interrupted `.candidate-*` stage, stop the run for investigation instead of being
  deleted automatically.

Baseline rules:

- With ledger history, the candidate must exceed its highest code.
- `--previous-apk FILE` additionally names an actual distributed production APK, not a
  rebuild: `com.splitfree`, one pinned signer and a verifying v2 signature.
  - If the ledger has that code, the supplied APK must be byte-identical.
  - The candidate must exceed the higher of this APK's code and the ledger maximum.
- An empty ledger requires that retained previous APK, or `--first-distribution` only
  when no earlier distribution exists.
  - These flags are mutually exclusive; declaring a first distribution is refused once
    the ledger has history.
  - Do not create a new empty ledger to bypass known ordering.
- The previous-APK check deliberately does not impose the candidate's SDK/native/
  alignment policy.
  - In contrast, the installation smoke applies current packaging policy to **both**
    APKs; an acceptable ordering baseline may not be a supported smoke-test input.

- The candidate must have:
  - Standalone `com.splitfree` packaging.
  - minSDK 26/targetSDK 37.
  - Non-debuggable and non-test-only status.
  - All four native ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
  - 16 KB native ZIP alignment.
  - Appropriate ELF segment alignment: 16 KB for 64-bit, 4 KB for 32-bit.
- Its exact bytes must freshly verify with v2 signing and the pinned certificate.
- The R8 mapping ID must match the APK's embedded marker and its body checksum must
  verify.
- Source snapshots must match before and after build/verification; this is recorded
  local provenance, not a hermetic or reproducible-build attestation.

- Only one helper run may use a checkout (`.local-distribution.lock/`) and one may use a
  ledger (`.distribution-lock/`) at a time.
- Investigate leftover locks before approved removal; never run another build or edit
  source concurrently.
- `--offline` uses cached Gradle dependencies.
- `--init-script` accepts trusted local Gradle configuration, not downloaded/unreviewed
  scripts, and retains the exact script.

- The helper writes `publicReleaseApproved: false` and `deviceInstallTested: false`.
- Those fields describe its scope, not a permanent claim that nobody can later test the
  APK.
- Keep separate acceptance/approval evidence tied to its SHA-256 rather than altering
  the bundle to manufacture a pass.

### Automated APK releases

- The [release workflow](.github/workflows/release.yml) prepares a **draft**, never
  publishes it.
- GitHub-hosted execution does not require GitHub authentication on a development
  laptop, but it does require the workflow to be present on GitHub and the
  [one-time setup](#one-time-github-setup) to be completed and verified.

```text
Existing vX.Y.Z tag on GitHub
    → validate source/version/notices → tests + unsigned release build
    → approve isolated signing → verify signed package → draft release
    → test that exact APK → maintainer approves and publishes the draft
```

| Job | Authority and output |
| --- | --- |
| `build` | Read-only token, no signing secrets. Checks tagged source, license approval and published version ordering; runs gates, builds unsigned APK and exact-source archive. |
| `sign` | Protected `release-signing` environment. No checkout, Gradle, repository scripts or restored build cache. Signs with the existing key. |
| `package` | Read-only token, no secrets. Verifies identity, signer, ZIP/ELF alignment, corresponding source and unchanged APK entry payloads/compression; produces final metadata/checksums. |
| `draft` | Only job with `contents: write`. Creates or safely resumes a matching draft with the verified six-asset bundle; no public-publish operation. |

- Tag pushes matching `v*` trigger validation.
- Release jobs require a tag ref in a non-fork repository, and accept stable
  `vMAJOR.MINOR.PATCH` tags without leading zeroes.
- The existing tag must resolve to the checked-out commit and that commit must belong to
  `mainline` history.
- `versionName` must equal the tag without `v`.
- `versionCode` must exceed every published release, including prereleases.
- Missing or unusable historical `release-info.json` blocks automation: review and
  migrate metadata rather than guessing an installed version code.

- **Retries and concurrency:** prefer rerunning failed jobs on the same tag while input
  artifacts remain available.
- A full rerun or dispatch on an existing tag must reproduce identical bundle bytes to
  resume that draft.
- Branch dispatches do not run release jobs.
- The helper only adds missing assets to a draft bound to the same source and bundle,
  verifies existing bytes, and refuses conflicting assets or published releases.
- One release run executes at a time.
- GitHub concurrency may replace older pending runs; rerun a displaced tag deliberately.

- Do not manually edit or publish a draft while the workflow runs.
- GitHub offers neither an atomic draft-only asset upload nor atomic protection against
  concurrent tag deletion; repeated API checks cannot replace repository/tag
  protections.

#### One-time GitHub setup

- Configure and verify these settings after uploading the reviewed workflow.
- YAML alone does not establish them:

1. Protect `mainline`, require CI and review workflow/release-helper changes.
2. Restrict creation, updates and deletion of `v*` tags to trusted maintainers.
3. Create **`release-signing`**, restrict it to release tags and require approval before
   signing.
   - Check protection support for the repository visibility and GitHub plan.
   - Reviewer/self-approval settings must fit the maintainer setup; preventing
     self-review requires another eligible reviewer.
4. Put these entries in that environment, never in pull-request jobs:

   | Entry | Type | Value |
   | --- | --- | --- |
   | `RELEASE_KEYSTORE_BASE64` | Secret | Existing release keystore, one Base64 line without whitespace. Base64 is not encryption. |
   | `RELEASE_STORE_PASSWORD` | Secret | Existing keystore password. |
   | `RELEASE_KEY_ALIAS` | Secret | Existing signing-key alias. |
   | `RELEASE_KEY_PASSWORD` | Secret | Existing key password. |
   | `RELEASE_CERT_SHA256` | Variable | Independently verified SHA-256 certificate fingerprint, hex with optional colons. |

5. Allow scoped `GITHUB_TOKEN` release writes; no personal access token is required.
6. Confirm repository visibility and complete the [license gate](#license-review-gate).

- The workflow comes from the tag.
- Maintainers who can alter it must not gain unreviewed signing-secret access.
- Environment and tag controls are security boundaries, not optional substitutes for
  validation.

- Hosted builds use pinned action commits, JDK 17, Command-line Tools 22.0, SDK platform
  37.0 and Build Tools 36.1.0.
- Release builds do not restore Gradle caches.
- Downloaded dependencies are not yet covered by dependency verification/locking.

## 4. Test the exact signed APK

- Use dedicated test devices/emulators and disposable data, with explicit owner approval
  for installation and network experiments.
- A separate user/work profile does **not** bypass Android's device-wide
  package-signature checks.
- Do not replace a differently signed installation, uninstall it or clear valuable data
  to pass.

- Record the candidate's source identity, package/version, APK SHA-256, trusted signing
  fingerprint, device/image/API/ABI/page size, reviewer/date, actions, results and
  limits.
- Test the final signed, minified bytes: a debug pass, a signature check or an older
  report cannot accept a newly built production APK.

- The [installation acceptance guide](tools/release/INSTALL_TESTING.md) defines the
  emulator previous-fresh/current-update/debug smoke, separate current-fresh case, and
  manual encrypted-data continuity gate.
- Keep these distinct from feature and physical-radio acceptance below.
- Mark unexecuted cases **pending** and link actual evidence before claiming success.

### Acceptance checklist

- [ ] Cold launch, create/recover a test identity and verify secrets stay protected when
  the app backgrounds.
  - Exercise native crypto on the target runtime.
- [ ] Two-device group creation and QR/paste join:
  - Add, edit, delete and settle expenses.
  - Compare exact balances and history.
- [ ] Offline save, process termination/reopening, relay outage/recovery and
  background/Doze catch-up without duplicated or missing ledger entries.
- [ ] Three-device Nearby: permissions denied/granted, interrupted transfer,
  reconnection and A-to-B-to-C forwarding with default gift wrapping.
  - Complete the [hardware checklist](#nearby-hardware-validation).
- [ ] Member removal/group-key rotation:
  - Remaining members receive the new key.
  - Removed members cannot read new-key data.
  - A new invite uses the current key and relay list.
  - Retained old keys/data are not erased by removal.
- [ ] Identity replacement via key revocation: peers adopt the replacement public
  identity and tombstone the old identity without losing expected membership/name state.
  - This does not itself rotate the group key or erase old data.
  - Test group-key rotation separately when new-key confidentiality is required.
- [ ] Restore an exported test backup with its original identity on a fresh install,
  including history across group-key rotation.
  - Verify actual Keystore persistence.
- [ ] Same-key upgrade from the previous distributed release, when one exists, with
  separately demonstrated identity/encrypted-data continuity.
- [ ] Small screens, large text, TalkBack, keyboard/insets and representative supported
  Android versions.

- JVM/simulated-transport tests supplement rather than replace these cases.
- Run opt-in live-relay tests separately with disposable identities and explicit network
  approval.
- Never upload personal backups, invitations or recovery phrases as evidence.

### Nearby hardware validation

- The
  [protocol lifecycle](app/src/main/java/com/splitfree/sync/nearby/README.md#session-lifecycle)
  has JVM coverage with fake radios.
- Google Play services callback ordering and physical discovery require separate evidence.
- Treat each case below as pending until executed for the recorded APK/device
  combination.
- Keep private trial records in the maintainer release archive, not the source
  repository.
- This evergreen checklist is an evidence requirement, not a claim about completed
  trials.

- For every phone, record:
  - Model/Android/Google Play services versions.
  - Package and versionCode.
  - Installed APK hash and signer.
  - Runtime permission grants.
  - Bluetooth/Wi-Fi/location-services state.
  - Timestamped actions on all participating phones.
- Use the
  [platform permission matrix](app/src/main/java/com/splitfree/sync/nearby/README.md#platform)
  when recording prerequisites.

- [ ] **1. Automatic discovery:** open the same group's Nearby screen on both phones, with
    prerequisites satisfied and no extra discovery tap.
  - Each lists the other within 15 seconds after both are ready.
  - Record repeated trials and radio capability state.
  - 15 seconds is an acceptance target, not a demonstrated result.
- [ ] **2. First-permission and consent flow:** on a fresh install, grant permissions and
    confirm advertising/discovery start without an extra tap.
  - During first-link system consent, distinguish `ON_PAUSE` (attempt timers pause) from
    `ON_STOP` (the local run stops).
  - If only paused, confirm continuation after consent.
  - If stopped, confirm a fresh run on return when intent/prerequisites still allow it.
- [ ] **3. Offline exchange:** with no internet or reachable relay on either phone, use
    **Sync** and compare records to establish Nearby-only exchange.
- [ ] **4. Bidirectional changes:** create different test expenses/settlements on each phone,
    sync, then compare every expected record and exact balances.
- [ ] **5. Failure/recovery:** interrupt a connection with range or radio changes.
  - Record whether the failure is per-peer or run-level.
  - After prerequisites and discovery recover, use **Retry** or **Sync** as offered.
  - Confirm recovery without restarting the app.
  - A lost/undiscovered peer need not remain actionable.
- [ ] **6. Stop/Start and background/return:** after **Stop**, the run stays stopped until
    **Start**, including across recreation.
  - `ON_STOP` stops the run without clearing the user's enabled intent.
  - Returning starts a fresh run if allowed.
  - Old live discoveries/attempts must not remain actionable.
  - Same-group terminal results may remain as non-actionable recent rows; their presence
    is not a stale discovery bug or proof of new discovery.
- [ ] **7. Local close and reconnect ordering:**
  - Use a debug build for detailed adapter diagnostics.
  - Record which phone initiated the link.
  - Background A during a handshake and observe both phones' actual termination cause
    and time.
  - A's `ON_STOP` requests local teardown immediately; it does **not** establish that B
    will wait for the 10-second handshake-idle timeout.
  - B may disconnect earlier.
  - Only classify a remote timeout when evidence shows one.

  - For a phone that locally ended an **incoming** link, capture whether its advertising
    submission receives either confirmation before another incoming initiation:
    - `Platform disconnection confirms the locally ended link`
    - `Platform result confirms the locally ended link`
  - Without that confirmation, the adapter may log
    `its earlier link ended locally, unconfirmed` and reject the initiation.
  - Capture the exact order instead of assuming this branch was exercised by
    backgrounding.
  - After return/rediscovery, try **Sync** from A to B.
  - If incoming retirement blocks that direction, try a fresh outgoing attempt from B to A.
  - Record success or failure.
  - A retired/stale initiation is rejected without its own `ConnectionFailed` event, so
    do not require a specific refusal row on that phone.
  - Record both UIs and whether late callbacks disturb a newer live link.
  - A trial that never creates/confirms a retirement does not validate that ordering
    case.
- [ ] **8. Power conditions:** under low battery/battery saver on both phones, repeat
  discovery and exchange.
  - Record restrictions and actual recovery.
- [ ] **9. Simultaneous Sync:** both people tap **Sync** together.
  - One authenticated session survives without duplicate-session failure or
    stale-callback teardown.
- [ ] **10. Three phones:** add a third phone after two-phone discovery, and verify repeated
    A-to-B-to-C forwarding with exact records/balances, not only connection or
    completion text.
- [ ] **11. Production bytes:** repeat items 1, 3, 4, 5 and 9 on the exact signed, minified
    release APK.
  - Debug-only diagnostics are not production acceptance.
- [ ] **Compatibility:** record each peer's build/protocol compatibility.
  - An older compatible UI may require its own **Scan** tap; use that build's actual
    flow rather than assuming current automatic discovery behavior.

- Capture diagnostics deliberately for each approved trial: timestamped actions,
  reviewed/redacted `adb logcat` output, and the in-app debug log when using debug.
- Release builds have no in-app buffer and suppress debug/info logging.
- Warning/error logcat remains available with targeted redaction, not a guarantee of
  secret-free logs.
- Review before sharing.
- Absence of a crash or a "disconnected" row is not proof of completed exchange; compare
  expected data and preserve failures too.

## 5. Prepare the release bundle

- Use the exact accepted APK; do not rebuild a replacement after acceptance or alter it
  after final signing/checksumming.
- Corresponding source must include the build scripts for that binary and be accessible
  alongside it, not only via a mutable default-branch link.
- Include dependency/asset notices with binary distribution.

### Downloads and retained evidence

The automated draft contains exactly these six assets:

| Asset | Purpose |
| --- | --- |
| `SplitFree-vX.Y.Z.apk` | Signed, installable production APK. |
| `SplitFree-vX.Y.Z-source.tar.gz` | Corresponding source from the exact Git commit, including build scripts. |
| `LICENSE.txt` | Project license. |
| `THIRD-PARTY-NOTICES.txt` | Reviewed dependency/asset notices. |
| `release-info.json` | Commit, tag, version/code, signing fingerprint and hashes of the four payloads above. |
| `SHA256SUMS.txt` | SHA-256 of those four payloads and `release-info.json`. |

- The checksum manifest detects changed files; it is not independent publisher
  authentication or reproducible-build attestation.
- Compare the fingerprint with an independently trusted certificate, not merely the
  verifier's exit status.

- Unsigned and signed intermediate Actions artifacts are retained for **7 days**.
- The final bundle, release test/lint reports and R8 mapping are retained for **30 days**.
- Ordinary CI reports have **14-day** retention.
- Archive the exact APK, hashes, source commit, matching `mapping.txt`,
  candidate-specific reports and device/hosted/approval evidence privately for long-term
  maintenance.
- Review any logs/screenshots before sharing; the private ledger/evidence directory is
  not an upload allowlist.

- Prepare release notes with changes, Android requirements, the Google Play services
  dependency, backup/upgrade instructions, compatibility changes, known limitations,
  actual device-test coverage and the expected signing fingerprint.
- Preserve the helper's source/bundle markers if editing a completed draft's notes;
  retries use them to identify matching drafts.

- An Android App Bundle (`./gradlew :app:bundleRelease`) is a store artifact, not a
  directly installable APK.
- A Play release needs its own listing, policy and signing setup; this APK procedure
  does not approve a store launch.

## 6. Publish only after approval

1. Confirm repository/source/license/signing readiness before an approved tag and
   workflow run; the tag must exist for automation to prepare a draft.
   - Creating that draft is **not** permission to publish it.
2. Wait for the workflow to finish.
   - A failed upload can leave a partial draft; verify all six assets, checksums,
     signer, source and notices before publication.
3. Review acceptance evidence for the **exact** signed APK and obtain explicit
   maintainer publication approval.
   - Missing, failed or out-of-scope checks are not passes; describe limits rather than
     implying universal compatibility.
4. Immediately before manually publishing, recheck that the code exceeds all
   now-published versions.
   - Separate unpublished drafts may share a code; publish in a controlled order without
     moving tags or overwriting published assets.
5. After publication, verify download bytes, checksum, signer and corresponding source
   from a separate client under the intended repository visibility.
   - Only then advertise the download.

- Drafts are not public downloads.
- A Git operation alone neither creates a GitHub Release nor attaches an APK.
- Local success does not prove hosted settings or a remote run; publication approval and
  those observations need their own evidence.

## Recommended automation follow-up

- Reviewed dependency verification/locking and automated dependency updates.
- Artifact provenance attestations and an independently reproduced build.
- Issue/PR templates and a dedicated confidential conduct-reporting contact.
