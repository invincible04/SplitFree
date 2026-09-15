# Installation acceptance: emulator smoke and manual data continuity

`install_smoke.py` is **opt-in, local, emulator-only**. It does not build APKs,
load signing keys, publish artifacts, start an emulator, or select a device for
you. Running unit tests does not run ADB. This guide is an acceptance procedure,
**not evidence that any emulator or phone has passed**.

## Safety boundary

Use a dedicated, already-running disposable emulator owned exclusively by this
test. Do not connect concurrent installers or change emulator snapshots during
a run. Do not use an emulator containing important data.

The CLI requires both `--serial emulator-<port>` and
`--allow-emulator-install`. It pins ADB to the local server at `127.0.0.1:5037`,
ignores ambient ADB target/server overrides, and checks `ro.kernel.qemu=1`,
online/boot-complete state, foreground user 0, and a stable boot ID before each
install and launch. It inventories `pm list users` and `pm list packages -u`
globally and for **every user**, including retained/uninstalled package records.
If either `com.splitfree` or `com.splitfree.debug` is present, or inventory is
unparseable/inaccessible, it refuses before installation. Physical/network serials
are refused even if an unexpected device claims to be an emulator.

There is no uninstall, `pm clear`, downgrade, `-t`, signature bypass, automatic
rollback, or automatic installed-app cleanup. A failed or timed-out install may
have modified the emulator: inspect the retained report and leave that emulator
alone. Do not retry by deleting package data. A repeat smoke needs another
separately approved empty emulator. The script never performs that provisioning.
These checks assume a trusted local SDK/ADB server and exclusive emulator access;
they are not a security boundary against a malicious local process/rooted device.

## Inputs and invocation

Provide explicit files for:

- The **actual retained previous production release**, not a newly rebuilt stand-in.
- The exact current signed production distribution APK.
- The current debug APK from the same source/version: separate package
  `com.splitfree.debug`, `SplitFree Debug` label, `-debug` version-name suffix,
  equal versionCode/minSDK/targetSDK, debuggable, not test-only, and no external
  VIEW handler. The release label must remain `SplitFree`.
- Independently trusted SHA-256 certificate pins for production and debug. The
  debug pin must differ from production. Do not establish trust by copying an
  arbitrary candidate's reported certificate into the pin input.
- SDK root, exact build-tools directory (including `aapt2`, `apksigner`,
  `zipalign`), and an existing private local work directory with enough disk space
  for three fixed candidates and five pulled APK copies. Keep evidence outside
  tracked source/distribution inputs.

Set `PREVIOUS_APK`, `CURRENT_APK`, `DEBUG_APK`, `ANDROID_SDK_ROOT`, `BUILD_TOOLS`,
`PRODUCTION_CERTIFICATE_SHA256`, `DEBUG_CERTIFICATE_SHA256`, `SMOKE_SERIAL`, and
`SMOKE_WORK_DIRECTORY` to reviewed local values. `SMOKE_WORK_DIRECTORY` must be an
existing private directory outside the repository; the tool does not choose one for
you. Review the selected serial before explicitly opting in:

```bash
python3 tools/release/install_smoke.py \
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

Do not run this command on a phone. The separate manual acceptance below requires
its own device/data-owner approval and is deliberately not automated here.

## What a pass establishes

Before **any device command**, the CLI creates a private unique work subdirectory,
copies input APKs to fixed read-only candidate files, and validates those exact
bytes. Both production APKs use the shared `artifacts.inspect_signed_apk` policy:
compiled package/version/SDK, standalone packaging, all required native ABIs and
alignment, v2 signature support and the same pinned signing certificate. The
current versionCode must strictly exceed the previous one. Debug uses a separate
explicit nonproduction contract; there is no relaxation of the release verifier.
The shared inspector currently requires minSDK 26 and targetSDK 37 for production;
historical artifacts outside that policy refuse rather than bypass verification.

The ordered device checks are:

1. Record SDK, ABI list, actual page size, fingerprint, boot ID and user inventory.
2. Fresh-install **previous release** without replacement flags. Require exact
   `Success` (allowing only known ADB transport preambles), inspect package/version/
   owner installation/UID/code path, pull the installed base APK, and compare its
   SHA-256, byte size and compiled identity to the verified fixed candidate.
3. Launch explicitly via `com.splitfree/com.splitfree.MainActivity`; require
   successful Activity Manager output and immediate process presence.
4. Recheck the installed previous APK, then install current with **only `-r`**
   as the replacement option. Repeat delivered-file and launch checks. Require
   the same UID; require unchanged firstInstallTime when both snapshots expose
   one unambiguous value. Missing/ambiguous time is explicitly reported as a
   limitation, not silently described as preserved.
5. Fresh-install and launch debug under its distinct package and UID. Recheck
   that the production APK remains byte-identical to current. Query all matching
   activities and resolve `splitfree://join`; both must identify only release,
   never debug or a chooser/other app.

A unique `splitfree-install-smoke-*/report.json` records passed checks, failures,
commands, artifact identities/hashes and platform observations. Candidate and
pulled APK files are retained on success and failure. Output parsing is deliberately
strict: an unsupported platform format fails closed rather than manufacturing a
pass. All subprocesses have finite 120-second timeouts and use argument lists,
not a host shell; there are no retries or polling loops.

**This sequence proves previous-fresh + current-update, not current-fresh.**
Current-fresh acceptance is an additional separate manual case on another empty
emulator. A momentarily present process is not sustained stability, scanner
correctness, navigation performance, or absence of later crashes.

## Required platform matrix

Do not call one emulator pass universal compatibility. Record actual outputs and
artifact hashes for every cell; use `pending` until executed.

| Required environment | Automated previous → current + debug | Separate current fresh | Manual encrypted-data update |
| --- | --- | --- | --- |
| API 26, supported ABI, normally 4096-byte pages | Pending | Pending | Pending |
| API 36, **actual 16384-byte pages**, supported 64-bit ABI | Pending | Pending | Pending |

The API36 image's name/configuration is insufficient: report `getconf PAGE_SIZE`
as **16384**. Record the exact image fingerprint and ABI, and additional ABI/device
coverage separately. Static four-ABI/16KB alignment checks do not exercise every
native runtime. SDK37 target metadata does not substitute for running either row.

## Separate manual existing-encrypted-data acceptance

**The automated smoke makes no database, encryption-key, account identity, or
user-data preservation claim. UID/firstInstallTime continuity is not such proof.**
It intentionally refuses already-occupied emulators and does not seed application
data. A reviewer must separately approve a dedicated test device/emulator and use
non-sensitive test identities/data for the following before/after acceptance.
Do not use a real phone or existing valuable data without explicit new approval.

Before the update:

- [ ] Record device/image/API/ABI/page size and exact previous/current APK hashes,
      versions and trusted production certificate pin; current code is higher.
- [ ] On the previous release, create or use a known test identity. Record its
      **public** fingerprint/account identifier and profile details, not secret keys.
- [ ] Populate representative encrypted groups, members, expenses, currency/split
      variants, balances, settlements, notes, and applicable pending sync state.
      Record expected counts and selected values/screenshots in private evidence.
- [ ] Verify the baseline opens correctly after normal close/relaunch and can
      read the encrypted records. Capture relevant baseline errors privately.
- [ ] Record package UID and firstInstallTime if available. Document the acceptance
      plan and recovery responsibility; do not assume Android backup can recover
      encryption keys or application state.

Apply only the independently verified same-key higher-version production update,
without uninstalling, clearing storage, importing a backup, replacing the identity,
or using downgrade/signature bypass flags. Keep the device offline initially if
network synchronization could mask local data loss.

After the update:

- [ ] Open the upgraded app with no reset/onboarding/account recreation.
- [ ] Match the same public identity fingerprint and all recorded profile/group/
      expense/settlement counts and values; compare balances and split details.
- [ ] Read encrypted records, edit a test record, close/relaunch normally, and
      verify the edit persists under the same identity. Review migration/decryption
      errors; absence of a crash alone is not sufficient.
- [ ] With deliberate network approval, verify expected pending-state/sync behavior
      without identity rotation or duplicates; record any untested network cases.
- [ ] Confirm separate debug install does not change production identity/data,
      release branding or production-link ownership.
- [ ] Record actual results, exact hashes and reviewer/date for both matrix rows.
      Any mismatch is a failure: stop and preserve evidence; do not erase data to
      make the test pass. Missing evidence remains **pending**, not preserved.

## Local regression tests

```bash
python3 -B -m unittest discover -s tools/release/tests -v
```

The tests use the system temporary directory (`TMPDIR` when set).

The smoke tests replace subprocess/device interactions and cover consent, physical
and occupied devices (including other users and retained records), signer/version
refusals, exact candidate/pulled bytes, failed updates, strict outputs, launch/link
failures, timeout behavior and the absence of deletion/bypass commands. They prove
the guard logic, not successful real emulator installations.
