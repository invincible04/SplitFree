# Releasing SplitFree

Prepare, verify and publish a signed Android release from reviewed source.
Run commands below from the repository root. Device acceptance applies to the
exact signed APK, not just a version name or successful build.

[Build requirements](README.md#build-from-source) · [Installation testing](tools/release/INSTALL_TESTING.md) · [Security policy](SECURITY.md)

## Version policy

- Current candidate: **`versionName = "1.0.1"` and `versionCode = 5`** in
  [`app/build.gradle.kts`](app/build.gradle.kts). Its release tag is **`v1.0.1`**.
- Preserve the published **v1.0.0 / code 4**, its tag and assets. Publish updates as
  new releases; never move a published tag or overwrite its APK.
- Increase `versionCode` above every previously distributed APK, including private
  handoffs and prereleases. Recheck distribution history before release.
- Keep package `com.splitfree` and the existing signing key. Debug uses
  `com.splitfree.debug` and displays `1.0.1-debug`.

## 1. Confirm repository readiness

Review the complete diff and commit all intended source, tests, workflows and
release metadata. The public release must be built from a clean, tagged commit
in `mainline` history. Local uncommitted builds are verification candidates, not
commit-bound public release bundles.

### Source publication checks

```bash
python3 -B tools/release/publication_check.py --source worktree
python3 -B tools/release/publication_check.py --source index
python3 -B tools/release/publication_check.py --source history --refs refs/heads/mainline
```

The history command covers that ref and its ancestors only. Include any additional
branches/tags intended for sharing. Review findings and incomplete scans before
publication; a passing current-source scan does not clear historical findings.

The scanner checks allowed paths and recognized sensitive content, including
ignored build inputs under `app/src`, `app/schemas` and `gradle`. It rejects
symlinks, unresolved LFS pointers and unreviewed binaries. Binary pins and narrow
synthetic-test exceptions live in
[`publication_policy.py`](tools/release/publication_policy.py); review provenance,
licensing and privacy before changing them. No scanner proves that arbitrary
source is secret-free. Review authorship and employer open-source obligations
separately. Do not edit source during scanning or packaging.

**Never commit or upload** keystores, passwords, recovery phrases, user backups,
`local.properties`, private configuration or whole evidence/build directories.
APK signing fingerprints are public; private signing keys are not. Keep generated
APKs and private R8 mappings out of source history.

### License-review gate

SplitFree is **GPL-3.0-or-later**, not Apache 2.0. Include `LICENSE` and the reviewed
[`THIRD-PARTY-NOTICES.txt`](release/THIRD-PARTY-NOTICES.txt) with binary releases.

[`release/policy.json`](release/policy.json) must contain the candidate version,
the notice file's SHA-256 and `approved: true` only for approved notices. The
v1.0.1 preparation retains the existing notice approval and unchanged notice
bundle; production dependency declarations are unchanged from v1.0.0. Revisit
approval for dependency, asset or redistribution-term changes. This metadata is
not device acceptance or proof of legal completeness. Google Play services
redistribution terms still apply; this build is not automatically F-Droid eligible.

## 2. Protect the signing identity

Local signed builds read `splitfree-release.jks` at the repository root and these
properties from Git-ignored `local.properties`:

| Property | Local value |
| --- | --- |
| `RELEASE_STORE_PASSWORD` | Existing keystore password |
| `RELEASE_KEY_ALIAS` | Existing release-key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

Preserve `sdk.dir` if present. Environment variables with these names do not
configure local Gradle signing. Keep access-controlled, encrypted key backups
outside the checkout and verify recovery. Never replace the key or certificate
pin to make an update install.

Compare the APK signer with the independently trusted
[`release/signing-certificate.sha256`](release/signing-certificate.sha256) and the
previous published APK. A different key cannot update the installed production app.

## 3. Build and verify

Use the [documented toolchain](README.md#build-from-source), with `ANDROID_HOME`
pointing to the SDK. Finish edits before running these gates.

### Local verification, without publishing

With the signing files configured, run:

```bash
./gradlew --no-daemon --max-workers=2 -PsplitfreeUnsignedRelease=false \
  spotlessCheck :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease
SPLITFREE_VARIANT_BUILD_DIR=app/build \
  AAPT2="$ANDROID_HOME/build-tools/36.1.0/aapt2" \
  python3 -B -m unittest discover -s tools/release/tests -v
"$ANDROID_HOME/build-tools/36.1.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/36.1.0/zipalign" -c -P 16 -v 4 \
  app/build/outputs/apk/release/app-release.apk
```

- Signed, minified release: `app/build/outputs/apk/release/app-release.apk`.
- Debug: `app/build/outputs/apk/debug/app-debug.apk`.
- Without signing credentials, use `-PsplitfreeUnsignedRelease=true`. That produces
  `app-release-unsigned.apk`; skip the signed-APK commands. It is not installable.
- Keep the compiled-variant environment variables: without them, helper discovery
  skips verification of the built APKs. These checks cover manifest/variant
  isolation and ML Kit registrar metadata and constructors, not device behavior.
- Compare the printed signer with the trusted pin. The local distribution helper
  below additionally checks the complete production manifest, native libraries,
  mapping identity and unchanged source. Signature/alignment checks alone do not.

### Verified local distribution

For a private handoff, use the existing distribution ledger:

```bash
python3 -B tools/release/distribute.py \
  --build-tools "$ANDROID_HOME/build-tools/36.1.0" \
  --previous-apk /absolute/path/to/previously-distributed.apk
```

Replace the APK path with the retained previous production APK. The helper verifies
its signer and code, also checks every retained ledger bundle, and requires the
candidate code to exceed both baselines. The previous-APK argument may be omitted
only when the ledger already covers the highest distributed code.

- This command signs and creates a new local distribution record. It runs helper
  regressions, formatting, JVM tests, both lints, debug/release builds and compiled
  variant checks. It verifies production identity, pinned signer, four native ABIs,
  ZIP/ELF alignment, R8 mapping and source preservation.
- Ledger: `~/.local/share/splitfree/distributions`, or
  `$XDG_DATA_HOME/splitfree/distributions` when that variable is absolute. Preserve
  it outside checkouts and retain earlier bundles. Do not create an empty ledger
  to bypass history. `--first-distribution` is only for an actual first handoff.
- Keep source stable and run one build per checkout/ledger. Investigate stale
  locks before removing them. `--offline` uses cached Gradle dependencies;
  `--init-script` is only for trusted local configuration.
- Preserve the exact APK, source archive/hashes, build log and matching mapping.
  The local report records `publicReleaseApproved: false` and
  `deviceInstallTested: false`; record later acceptance separately against its hash.

## 4. Test the exact signed APK

Follow [INSTALL_TESTING.md](tools/release/INSTALL_TESTING.md) for installation and
same-key upgrade checks. Record APK SHA-256, signer, device/Android versions and
actual results. Never uninstall or clear production data to make an upgrade pass.

- [ ] Fresh install, launch, onboarding, invite paste/scan and external invite links.
- [ ] Upgrade from the prior published APK without losing identity, groups,
  encrypted history or balances. Confirm persistence after relaunch.
- [ ] Create/join groups, split expenses, edit/delete and settle in supported
  currency precisions; verify exact balances across peers.
- [ ] Relay history recovery and outgoing delivery after offline work, restart and
  reconnect, using disposable test identities and deliberate network approval.
- [ ] Export/restore a backup with the original identity, including identity and
  group-key rotation history. Verify restored encrypted records and persistence.
- [ ] Small screens, large text, TalkBack and supported Android versions.

### Nearby hardware validation

Use actual phones with recorded Android/Google Play services versions, permissions,
radio state and timestamped actions. See the
[platform prerequisites](app/src/main/java/com/splitfree/sync/nearby/README.md#platform).

- [ ] Automatic discovery and first-permission/system-consent recovery. Listing
  peers within 15 seconds after both are ready is a target, not a measured promise.
- [ ] Offline bidirectional expense/settlement exchange, with exact record and
  balance comparisons, including simultaneous Sync and three-phone forwarding.
- [ ] Range/radio interruption, retry, Stop/Start, background/return and recreation.
  Confirm intended stop behavior and that stale callbacks do not disturb new links.
- [ ] Incoming-link retirement/reconnect ordering and low-battery/battery-saver
  recovery. Record actual callbacks and timing rather than assuming a timeout.
- [ ] Repeat discovery, offline exchange and recovery on the exact signed,
  minified APK, not only debug. Record compatibility with older peers.

JVM tests and simulated transports do not replace these cases. Retain failures and
untested cases. Review/redact logs before sharing; completion text or absence of a
crash is not proof that all records arrived.

## 5. Prepare the release bundle

### Automated APK releases

The [release workflow](.github/workflows/release.yml) builds, signs and creates an
**unpublished draft**. It does not publish a release.

1. Commit all intended changes and get that commit onto `mainline` from your chosen
   publishing machine. A branch push alone does not create an Android release.
2. Create the new **v1.0.1** tag on that reviewed commit and push that tag from the
   publishing machine. Do not move or reuse v1.0.0.
3. The tag triggers tests and unsigned packaging, then the protected signing job.
   Approve signing when prompted. Only the draft job has release-write permission.
4. Wait for the completed draft, then test and review its exact signed APK before
   publication. A locally tested APK is not evidence for different hosted bytes.

The tag must match `versionName`, resolve to the checkout and belong to `mainline`
history. `versionCode` must exceed all published releases, including prereleases.
Missing or invalid historical release metadata blocks the workflow. Manual dispatch
works on an existing matching tag; branch dispatches do not run release jobs.

Prefer rerunning failed jobs while artifacts remain available. Resuming a draft
requires the same source and bundle bytes; conflicting assets and published
releases are rejected. Do not manually edit or publish a draft during its workflow.

### One-time GitHub setup

Configure these repository settings separately; workflow YAML does not create them:

- Protect `mainline` with required CI/reviews and restrict creation, update and
  deletion of `v*` tags to trusted maintainers.
- Create **release-signing**, restrict it to release tags and require appropriate
  approval. Keep signing secrets unavailable to pull-request jobs.
- Add environment secrets **RELEASE_KEYSTORE_BASE64**, **RELEASE_STORE_PASSWORD**,
  **RELEASE_KEY_ALIAS**, **RELEASE_KEY_PASSWORD**, using the existing key. Base64
  must be one line; it is encoding, not encryption.
- Add environment variable **RELEASE_CERT_SHA256** with the trusted certificate
  fingerprint. Allow the workflow's scoped `GITHUB_TOKEN` release writes.
- For the website, set **Settings → Pages → GitHub Actions** and allow `mainline`
  in the **github-pages** environment. Required reviewers would make deployment
  approval-dependent. See the [website guide](website/README.md).

### Release assets

The completed draft contains exactly six assets:

| Asset | Content |
| --- | --- |
| `SplitFree-v1.0.1.apk` | Signed production APK |
| `SplitFree-v1.0.1-source.tar.gz` | Corresponding source/build scripts from the tagged commit |
| `LICENSE.txt` | GPL-3.0-or-later license |
| `THIRD-PARTY-NOTICES.txt` | Reviewed third-party notices |
| `release-info.json` | Commit, version/code, signer and payload hashes |
| `SHA256SUMS.txt` | Checksums of the other five assets |

Keep R8 mapping, test reports and device evidence privately. Actions intermediates
expire after 7 days; the final bundle and release reports/mapping after 30 days.
Archive needed evidence before expiry. Checksums detect changed bytes; they are
not independent publisher authentication or reproducible-build proof.

## 6. Publish only after approval

1. Verify all six draft assets, source commit, signer and checksums. A failed
   workflow may leave an incomplete draft.
2. Review exact-APK acceptance and release notes. Include Android/Google Play
   services requirements, backup/upgrade guidance, changes, known limitations and
   actual test coverage. Use the correct GPL license and retain helper bundle/source
   markers in the notes.
3. Recheck version-code ordering, approve publication and manually publish the draft.
   Preserve published assets; fixes use a new version and higher code.
4. Download from a separate client and verify bytes, signer and corresponding
   source before announcing the release.
5. Run **Website · GitHub Pages** on `mainline` to refresh the bundled download.

Successful `mainline` pushes automatically deploy the website after its own checks,
subject to Pages/environment setup. PRs check only; `dev` pushes do not deploy.
Runs are serialized per ref, but newer pushes can replace pending runs. The website
uses the latest valid published release, not the version in uncommitted source or
a draft. Until v1.0.1 is published, it continues to offer the public v1.0.0 APK.
