# Installation acceptance: emulator smoke and manual data continuity

- [`install_smoke.py`](install_smoke.py) is **opt-in, local and emulator-only**.
- It installs already-built APKs on an explicitly selected empty emulator.
- It does not build APKs, load signing keys, publish artifacts, start/provision an
  emulator or select a device for you.
- Helper regression tests do not run ADB.

- This is an acceptance procedure, **not evidence that any emulator or phone has
  passed**.
- Keep these claims separate:

| Gate | What it can establish |
| --- | --- |
| Automated smoke | Exact-file previous-release fresh install, higher-code same-key update, immediate launches and debug coexistence on one emulator configuration. |
| Separate current fresh install | The current production APK installs and works without a predecessor. |
| Manual encrypted-data update | Recorded identity and encrypted application data survive an in-place update. |
| Feature/physical-radio acceptance | Actual app workflows and Nearby behavior on the recorded devices; see the [release checklist](../../RELEASING.md#4-test-the-exact-signed-apk). |

- A pass in one row does not establish another.
- Bind each result to exact APK hashes, platform observations and reviewer/date.
- Unsupported or unexecuted cases remain **pending**.
- None of these gates grants public-release approval.

## Safety boundary

- Use a dedicated, already-running disposable emulator owned exclusively by this test.
- Do not use one containing important data, run concurrent installers or change
  snapshots during a trial.
- The helper assumes a trusted local SDK/ADB server and exclusive emulator access; it is
  not a security boundary against a malicious local process or rooted device.

- The CLI requires **both** `--serial emulator-<port>` and `--allow-emulator-install`.
- It pins ADB to `127.0.0.1:5037` and ignores ambient ADB target/server overrides.
- Before each install and launch, it checks:
  - Online/authorized state.
  - `ro.kernel.qemu=1`.
  - Boot-complete state.
  - Foreground owner user 0.
  - A stable boot ID.
- Physical/network serials are refused even if a device claims to be an emulator.

- Before the first installation it inventories `pm list users` and `pm list packages -u`
  globally and for **every user**, including retained/uninstalled records.
- Either `com.splitfree` or `com.splitfree.debug` anywhere, or an inaccessible/
  unparseable inventory, causes refusal before installation.
- Another user/work profile is not a way around device-wide Android package-signature
  checks.

- There is no uninstall, `pm clear`, downgrade, `-t`, signature bypass, automatic
  rollback or installed-app cleanup.
- An install that fails or times out may already have modified the emulator.
- Stop, retain the available evidence and inspect its state under a separate approved
  plan; do not delete package data to retry.
- A repeat smoke requires another separately approved empty emulator.
- This tool does not perform that provisioning.

## Inputs and invocation

Provide reviewed local values for each input:

| Input | Required meaning |
| --- | --- |
| `PREVIOUS_APK` | The **actual retained previous production distribution**, not a newly rebuilt stand-in. |
| `CURRENT_APK` | The exact current signed production distribution APK, with a strictly higher versionCode. |
| `DEBUG_APK` | Debug from the same source as current production, using its separate nonproduction contract below. Source provenance must be established by the operator. |
| `PRODUCTION_CERTIFICATE_SHA256` | Independently trusted production certificate SHA-256: 64 hex characters, without colons. |
| `DEBUG_CERTIFICATE_SHA256` | Independently trusted debug certificate SHA-256 in the same format, distinct from production. |
| `ANDROID_SDK_ROOT` | Trusted SDK root with executable `platform-tools/adb`. |
| `BUILD_TOOLS` | Exact trusted build-tools directory containing executable `aapt2`, `apksigner` and `zipalign`; the project pins 36.1.0. |
| `SMOKE_SERIAL` | The reviewed local `emulator-<port>` serial. |
| `SMOKE_WORK_DIRECTORY` | An existing private work directory outside the repository and distribution inputs, with sufficient space for three fixed candidates and five pulled APK copies. |

- The operator must choose an outside-repository, private parent directory.
- The CLI checks that `--work-directory` exists, is a directory and is not itself a
  symlink.
- It does **not** enforce outside-repository placement or audit parent permissions.
- It creates a unique private child for the trial.
- Keep diagnostic evidence out of tracked source and distribution snapshots.

- Production label must be `SplitFree`.
- Debug must have:
  - Package `com.splitfree.debug` and label `SplitFree Debug`.
  - The current production version name plus `-debug`.
  - Equal versionCode/minSDK/targetSDK.
  - Explicitly debuggable, non-test-only standalone packaging.
  - No external VIEW handler.
- The CLI verifies these compiled contracts, not that the debug/release APKs were built
  from the same source.
- Retain independent build/source evidence for that requirement.
- Do not create trust by copying an arbitrary candidate's reported signer into a pin
  input.

- Current source is production **`1.0.0` / code `3`**.
- The display remains frozen until the first approved GitHub release; that does not
  permit a same-code upgrade.
- Use the real retained predecessor and higher-code candidate described by the
  [version and ledger policy](../../RELEASING.md#verified-local-distribution).

After reviewing paths and serial, explicitly opt in:

```bash
python3 -B tools/release/install_smoke.py \
  --serial "$SMOKE_SERIAL" \
  --allow-emulator-install \
  --previous-apk "$PREVIOUS_APK" \
  --current-apk "$CURRENT_APK" \
  --debug-apk "$DEBUG_APK" \
  --sdk "$ANDROID_SDK_ROOT" \
  --build-tools "$BUILD_TOOLS" \
  --certificate "$PRODUCTION_CERTIFICATE_SHA256" \
  --debug-certificate "$DEBUG_CERTIFICATE_SHA256" \
  --work-directory "$SMOKE_WORK_DIRECTORY"
```

- Run from the repository root.
- **Do not run this command on a phone.**
- Manual acceptance of existing encrypted data requires separate device/data-owner
  approval and is deliberately not automated by this command.

## What a pass establishes

- After argument, consent, path and tool prechecks, the CLI creates the trial
  subdirectory.
- Before **any device command**, it copies the three APKs to fixed read-only candidates
  and validates those exact bytes.
- Both production APKs use [`artifacts.inspect_signed_apk`](artifacts.py):
  - Compiled package/version/SDK.
  - Standalone packaging.
  - All four required native ABIs.
  - Native ZIP/ELF alignment.
  - v2 signature support.
  - One identical pinned production signer.
- Current code must strictly exceed previous code.
- Debug uses its separate explicit contract rather than relaxing production checks.

- The production inspector currently requires minSDK 26 and targetSDK 37 for **both**
  previous and current APKs.
- Historical artifacts outside that policy refuse.
- This differs intentionally from `distribute.py --previous-apk`, whose ordering
  baseline check does not apply the candidate's SDK/native/alignment requirements.
- Do not bypass smoke validation or substitute a rebuild to force a historical artifact
  through it; record that case as unsupported and plan separate acceptance.

The ordered device sequence is:

1. **Preflight:** record SDK, ABI list, actual page size, image fingerprint, boot ID and
   user inventory.
   - The emulator must meet each APK's minimum SDK and share a supported ABI.
   - Reported page size must be 4096 or 16384 bytes.
2. **Previous fresh install:** install previous production for user 0 without
   replacement flags.
   - Require exact `Success`, allowing only the known streamed/push-install preambles.
   - Inspect package/version, owner installation, UID and code path.
   - Pull the installed base APK and compare SHA-256, size and compiled identity with the
     fixed candidate.
3. **Previous launch:** explicitly launch `com.splitfree/com.splitfree.MainActivity`,
   require successful Activity Manager output and immediate process presence.
4. **Current update:** recheck installed previous bytes, then install current with
   **only `-r`** as the replacement option.
   - Repeat delivered-file and launch checks.
   - UID must remain the same.
   - firstInstallTime must match when both snapshots expose one unambiguous value;
     missing/ambiguous time is reported as a limitation, not silently described as
     preserved.
5. **Debug coexistence:** fresh-install and launch debug under its distinct package/UID.
   - Recheck production bytes against current and its UID/time continuity.
   - Query matching activities and resolve `splitfree://join`; both must identify only
     production, not debug, a chooser or another app.

### Evidence and failure handling

- When the unique work subdirectory has been created, the helper attempts to write
  `splitfree-install-smoke-*/report.json` on success or handled failure.
- It records status, completed checks, limitations, ADB command arguments, available
  artifact identities/hashes, platform observations and error details.
- Candidate and pulled files created so far are not automatically deleted.

- Do not assume every refusal leaves a complete report:
  - Argument/consent/path/tool failures before child-directory creation have none.
  - Early copy/validation failures may leave partial files.
  - A storage failure can prevent report writing too.
- Preserve console output and whatever evidence exists.
- A reported error or incomplete report is not a pass.
- Filesystem problems do not prove that an earlier installation was rolled back.

- Output parsing is deliberately strict: unsupported platform formats fail rather than
  manufacturing success.
- The helper's subprocess wrapper uses argument lists, not a host shell, with finite
  120-second timeouts and no retry/polling loop.
- Review reports and logs for identifiers and local paths before sharing.

- **The sequence proves previous-fresh + current-update, not current-fresh.**
- A momentarily present process is not sustained stability, scanner correctness,
  navigation performance, native-runtime coverage or absence of later crashes.

## Required platform matrix

- Use a separate acceptance record for the exact candidate.
- Initialize every cell as **pending** until it links an executed result.
- Record failures and limitations, not only passes.
- One emulator run is not universal compatibility.

| Required environment | Automated previous → current + debug | Separate current fresh | Manual encrypted-data update |
| --- | --- | --- | --- |
| API 26, supported ABI, normally 4096-byte pages | Evidence required | Evidence required | Evidence required |
| API 36, **actual 16384-byte pages**, supported 64-bit ABI | Evidence required | Evidence required | Evidence required |

- Record actual outputs, exact image fingerprint, API, ABI and artifact hashes for each
  cell.
- An API 36 image name/configuration does not establish 16 KB execution:
  `getconf PAGE_SIZE` must report **16384**.
- Record additional ABI/device coverage separately.
- Static four-ABI/16 KB alignment checks do not exercise all native runtimes, and
  targetSDK 37 metadata does not substitute for either row.
- The CLI reports one configuration; it does not execute or enforce the entire matrix.

For **current-fresh**:

1. Separately approve another empty disposable emulator.
2. Verify the exact current production APK and trusted signer.
3. Install without replacement/bypass flags.
4. Record delivered identity/hash plus launch and representative app/native-crypto behavior.

- Do not erase the smoke emulator to manufacture a fresh state, and do not relabel the
  previous-fresh step as this case.

## Separate manual existing-encrypted-data acceptance

- **UID/firstInstallTime continuity is not database, encryption-key, account-identity or
  user-data preservation proof.**
- The automated smoke refuses occupied emulators and does not seed application data.
- Separately approve a dedicated test device or emulator, non-sensitive identities/data,
  recovery responsibility and this before/after plan.
- A real phone or valuable existing data requires explicit new owner approval; do not
  assume Android backup can recover encryption keys/state.

Before the update:

- [ ] Record device/image/API/ABI/page size, actual previous/current APK hashes,
  versions and trusted production certificate pin.
  - Verify current code is higher.
- [ ] On the previous release, create or use a known test identity.
  - Record its **public** fingerprint/account identifier and profile details, never
    secret keys.
- [ ] Populate representative encrypted groups, members, expenses, currency/split
  variants, balances, settlements, notes and applicable pending sync state.
  - Record expected counts and selected values/screenshots privately.
- [ ] Verify baseline encrypted records remain readable after normal close/relaunch.
  - Capture relevant baseline errors privately.
- [ ] Record package UID and firstInstallTime when available.
  - Confirm the approved recovery plan without assuming a backup or a second profile
    bypasses key risks.

- Apply only the independently verified, same-key, higher-code production update.
- Do not uninstall, clear storage, import a backup, replace identity or use downgrade/
  signature bypass flags.
- Keep the device offline initially if synchronization could mask local data loss.
- A backup restore is a different acceptance case.

After the update:

- [ ] Open the upgraded app without reset, onboarding or account recreation.
- [ ] Match public identity fingerprint and recorded profile/group/expense/settlement
  counts and values, including balances and split details.
- [ ] Read encrypted records, edit a test record, close/relaunch normally and verify
  persistence under the same identity.
  - Review migration/decryption errors.
  - Absence of a crash alone is insufficient.
- [ ] With deliberate network approval, verify expected pending-state/sync behavior
  without identity rotation or duplicates.
  - Record untested network cases.
- [ ] Confirm a separate debug install does not change production identity/data, release
  branding or production-link ownership.
- [ ] Record actual results, exact hashes and reviewer/date for both platform rows.
  - On any mismatch, stop and preserve evidence; never erase data to make the test pass.
  - Missing evidence remains **pending**, not "preserved".

## Local regression tests

From the repository root:

```bash
python3 -B -m unittest discover -s tools/release/tests -v
```

- Tests use the system temporary directory (`TMPDIR` when set).
- Smoke regressions replace subprocess/device interactions and cover:
  - Consent.
  - Physical/occupied device refusals, including other users and retained records.
  - Signer/version boundaries.
  - Exact candidate/pulled bytes.
  - Failed updates.
  - Strict output parsing.
  - Launch/link failures.
  - Timeouts.
  - Absence of deletion/bypass commands.
- They check guard logic, not real emulator installations.
- The separate opt-in compiled-variant build checks are documented under
  [local release verification](../../RELEASING.md#local-verification-without-publishing).
