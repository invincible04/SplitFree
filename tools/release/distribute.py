#!/usr/bin/env python3
"""Build and preserve a verified local release. Never installs or publishes an APK.

The ledger of completed bundles lives outside every checkout and is the only record of
what has been distributed: each run verifies it and must exceed its highest versionCode.
Public release approval and Android installation acceptance are separate gates.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import gzip
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tarfile
import tempfile
import zipfile
import xml.etree.ElementTree as ET

import artifacts as a
import publication_policy as publication
from publication_check import snapshot_worktree

ROOT = Path(__file__).resolve().parents[2]
PIN = ROOT / "release/signing-certificate.sha256"
REPORT = "local-release.json"
LEDGER_LOCK = ".distribution-lock"
WORKSPACE_LOCK = ".local-distribution.lock"
FINDER_METADATA = ".DS_Store"
BUNDLE_NAME = re.compile(r"SplitFree-v" + a.VERSION + r"-([1-9][0-9]*)-([0-9a-f]{12})")
MAX_INPUT = 256 * 1024 * 1024
GRADLE_TASKS = ("spotlessCheck", ":app:testDebugUnitTest", ":app:lintDebug", ":app:lintRelease",
                ":app:assembleDebug", ":app:assembleRelease")
FAILURES = (a.ReleaseError, OSError, ValueError, subprocess.SubprocessError, zipfile.BadZipFile, ET.ParseError)


def data_home() -> Path:
    """Tool data lives outside every checkout, so no git clean, clone or worktree removal can discard it."""
    configured = os.environ.get("XDG_DATA_HOME", "")
    base = Path(configured) if configured and Path(configured).is_absolute() else Path.home() / ".local/share"
    return base / "splitfree"


def default_ledger() -> Path:
    return data_home() / "distributions"


def failures_directory() -> Path:
    return data_home() / "distribution-failures"


def file_identity(value):
    return (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns)


def regular_bytes(path: Path, limit: int = MAX_INPUT) -> bytes:
    """Read a bounded ordinary file, rejecting symlinks and mid-read replacement."""
    before = path.lstat()
    a.require(stat.S_ISREG(before.st_mode) and before.st_size <= limit, f"Expected bounded regular file: {path}")
    with path.open("rb") as stream:
        a.require(file_identity(os.fstat(stream.fileno())) == file_identity(before), f"Input replaced: {path}")
        data = stream.read(limit + 1)
    a.require(file_identity(path.lstat()) == file_identity(before) and len(data) == before.st_size, f"Input changed: {path}")
    return data


def certificate_pin(path: Path) -> str:
    text = regular_bytes(path, 1024).decode("ascii").strip()
    a.require(re.fullmatch(r"[0-9a-f]{64}", text), "Signing pin must contain exactly one lowercase SHA-256 certificate")
    return text


def source_snapshot(root: Path) -> dict[str, bytes]:
    """Snapshot exact, policy-checked bytes, including ignored Gradle source inputs."""
    try:
        files = snapshot_worktree(root)
    except publication.PublicationError as error:
        raise a.ReleaseError(str(error)) from None
    a.require("app/build.gradle.kts" in files and "release/signing-certificate.sha256" in files, "Incomplete source snapshot")
    return files


def source_archive(files: dict[str, bytes]) -> bytes:
    try:
        publication.inspect_sources(files)
    except publication.PublicationError as error:
        raise a.ReleaseError(str(error)) from None
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w") as archive:
        for name, data in sorted(files.items()):
            member = tarfile.TarInfo("SplitFree-local/" + name)
            member.size, member.mtime, member.mode = len(data), 0, 0o755 if name == "gradlew" else 0o644
            archive.addfile(member, io.BytesIO(data))
    return gzip.compress(buffer.getvalue(), mtime=0)


def mapping_identity(apk: Path, mapping: bytes) -> str:
    """Require the R8 mapping ID embedded in the APK and verify the mapping body hash."""
    header = mapping[:16384]
    ids = re.findall(rb"^# pg_map_id: ([0-9a-f]{64})$", header, re.M)
    hashes = re.findall(rb"^# pg_map_hash: SHA-256 ([0-9a-f]{64})\n", header, re.M)
    a.require(len(ids) == len(hashes) == 1, "Missing/ambiguous R8 mapping identity")
    body = mapping.split(b"# pg_map_hash: SHA-256 " + hashes[0] + b"\n", 1)[1]
    a.require(a.sha256(body) == hashes[0].decode(), "R8 mapping body checksum mismatch")
    markers = set()
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            if re.fullmatch(r"classes\d*\.dex", entry.filename):
                a.require(entry.file_size <= MAX_INPUT, "DEX too large")
                for match in re.finditer(rb'~~R8(\{[^\x00\n]*\})', archive.read(entry)):
                    marker = json.loads(match[1])
                    if marker.get("backend") == "dex" and marker.get("compilation-mode") == "release":
                        markers.add(marker.get("pg-map-id"))
    a.require(markers == {ids[0].decode()}, "R8 mapping does not match the APK")
    return ids[0].decode()


@contextmanager
def directory_lock(lock: Path, message: str):
    """A mkdir lock: atomic on every platform and never inherited by a crashed process's successor."""
    try:
        lock.mkdir()
    except FileExistsError as error:
        raise a.ReleaseError(message) from error
    try:
        yield
    finally:
        lock.rmdir()


def distribution_lock(directory: Path):
    a.require(not directory.is_symlink(), "Distribution directory cannot be a symlink")
    directory.mkdir(parents=True, exist_ok=True)
    return directory_lock(directory / LEDGER_LOCK,
                          f"Distribution is locked; investigate an active/interrupted build before removing {directory / LEDGER_LOCK}")


def workspace_build_lock(root: Path):
    """Serialize this helper per checkout even when two callers choose different ledgers: both write app/build."""
    return directory_lock(root / WORKSPACE_LOCK,
                          f"Another local distribution build is active or interrupted; investigate {root / WORKSPACE_LOCK}")


def ledger_entries(directory: Path):
    """Directory entries that matter: Finder's own metadata file is the only thing tolerated silently."""
    for entry in directory.iterdir():
        if entry.name == FINDER_METADATA and not entry.is_symlink() and entry.is_file():
            continue
        yield entry


def ledger_baseline(directory: Path, certificate: str) -> dict | None:
    """Verify every completed bundle; return the newest plus every distributed code, or None for an empty ledger."""
    newest, distributed = None, {}
    for bundle in ledger_entries(directory):
        if bundle.name == LEDGER_LOCK:
            continue
        a.require(BUNDLE_NAME.fullmatch(bundle.name) and not bundle.is_symlink() and bundle.is_dir(),
                  f"Unexpected/incomplete distribution entry: {bundle.name}")
        report = json.loads(regular_bytes(bundle / REPORT, 1024 * 1024))
        a.require(isinstance(report, dict) and type(report.get("schemaVersion")) is int
                  and report["schemaVersion"] == 1 and report.get("certificateSha256") == certificate,
                  "Distribution history schema or signer mismatch")
        a.require(isinstance(report.get("manifest"), dict), "Invalid history manifest")
        code = report["manifest"].get("versionCode")
        a.require(type(code) is int and code > 0 and int(BUNDLE_NAME.fullmatch(bundle.name)[1]) == code,
                  "Invalid distribution history version")
        artifacts = report.get("artifacts")
        a.require(isinstance(artifacts, dict) and {"mapping.txt", "source.tar.gz", "source-sha256.json", "build.log"} <= set(artifacts)
                  and set(p.name for p in ledger_entries(bundle)) == set(artifacts) | {REPORT},
                  "Incomplete distribution history")
        for name, digest in artifacts.items():
            a.require(isinstance(name, str) and Path(name).name == name and name not in {".", ".."}
                      and isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest), "Invalid history artifact")
            a.require(a.sha256(regular_bytes(bundle / name)) == digest, f"Distribution history hash mismatch: {name}")
        apk_name = report.get("apk")
        a.require(isinstance(apk_name, str) and apk_name in artifacts
                  and artifacts[apk_name] == report.get("apkSha256")
                  and report["apkSha256"].startswith(BUNDLE_NAME.fullmatch(bundle.name)[2]), "History APK identity mismatch")
        a.require(code not in distributed, f"Two ledger bundles share versionCode {code}")
        distributed[code] = report["apkSha256"]
        if newest is None or code > newest["versionCode"]:
            newest = {"versionCode": code, "apkSha256": report["apkSha256"], "bundle": bundle.name}
    return None if newest is None else {**newest, "distributed": distributed}


def resolve_baseline(args, stage: Path, ledger: dict | None, certificate: str) -> dict:
    """Decide what the candidate must exceed, refusing an empty ledger that nobody declared to be first."""
    previous = None
    if args.previous_apk is not None:
        copy = stage / "previous.apk"
        copy.write_bytes(regular_bytes(Path(args.previous_apk)))
        verified = a.inspect_signed_baseline(copy, Path(args.build_tools), certificate)
        copy.unlink()
        previous = {"versionCode": verified["manifest"]["versionCode"], "apkSha256": verified["apkSha256"]}
        retained = (ledger or {}).get("distributed", {}).get(previous["versionCode"])
        a.require(retained in (None, previous["apkSha256"]),
                  f"Previous APK is not the ledger bundle with versionCode {previous['versionCode']}; two different builds share a code")
    if ledger is None and previous is None:
        a.require(args.first_distribution,
                  "The ledger is empty: pass --previous-apk for the last distributed build, or --first-distribution if none exists")
        return {"source": "first-distribution", "versionCode": 0, "apkSha256": None}
    a.require(not args.first_distribution, "--first-distribution was given but the ledger already has history")
    candidates = [c for c in ((ledger, "ledger"), (previous, "previous-apk")) if c[0] is not None]
    record, source = max(candidates, key=lambda c: c[0]["versionCode"])
    return {"source": source, "versionCode": record["versionCode"], "apkSha256": record["apkSha256"]}


def gate_results(root: Path) -> dict:
    """Read Gradle's test/lint results; missing results are never success."""
    results = sorted((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    a.require(results, "Missing JVM test results")
    counts = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for path in results:
        suite = ET.fromstring(regular_bytes(path))
        a.require(suite.tag == "testsuite", "Invalid JVM test results")
        for key in counts:
            value = suite.get(key, "")
            a.require(re.fullmatch(r"[0-9]+", value), "Invalid JVM test counts")
            counts[key] += int(value)
        cases = suite.findall("testcase")
        a.require(len(cases) == int(suite.get("tests")), "JVM test count mismatch")
        for key, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
            a.require(sum(case.find(tag) is not None for case in cases) == int(suite.get(key)), "JVM result counter mismatch")
    a.require(counts["tests"] > 0 and not any(counts[k] for k in ("failures", "errors", "skipped")),
              "JVM tests failed or skipped")
    lint = {}
    for variant in ("debug", "release"):
        report = ET.fromstring(regular_bytes(root / f"app/build/reports/lint-results-{variant}.xml"))
        a.require(report.tag == "issues", "Invalid lint report")
        issues = report.findall("issue")
        a.require(not any(issue.get("severity", "").lower() in {"fatal", "error"} for issue in issues),
                  "Lint has fatal/errors")
        lint[variant] = len(issues)
    return {"jvm": counts, "suites": len(results), "lintIssues": lint}


def run_gate(command, root: Path, log, timeout: int, *, env=None):
    """Run one gate into the shared build log; a nonzero exit or timeout fails the distribution."""
    try:
        result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=timeout, env=env)
    except subprocess.TimeoutExpired:
        raise a.ReleaseError(f"Gate timed out after {timeout}s; no APK distributed") from None
    a.require(result.returncode == 0, f"Gate failed ({result.returncode}); no APK distributed")


def preserve_log(log: Path) -> Path:
    """Copy the stage's build log to the failures directory, where the stage cleanup never reaches."""
    directory = failures_directory()
    a.require(not directory.is_symlink(), "Distribution failure directory cannot be a symlink")
    directory.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix="gate-", suffix=".log", dir=directory, delete=False) as failed:
        failed.write(regular_bytes(log))
    return Path(failed.name)


def build_distribution(args) -> Path:
    root = ROOT
    a.require((root / "app").is_dir(), "Repository root missing")
    directory = Path(args.directory).absolute()
    a.require(not any(p.is_symlink() for p in (directory, *directory.parents)), "Distribution path cannot contain symlinks")
    certificate = certificate_pin(PIN)
    with workspace_build_lock(root), distribution_lock(directory):
        ledger = ledger_baseline(directory, certificate)
        with tempfile.TemporaryDirectory(prefix=".candidate-", dir=directory) as temp:
            stage = Path(temp)
            log = stage / "build.log"
            try:
                return stage_distribution(args, root, directory, stage, certificate, ledger)
            except FAILURES as error:
                # Every failure after the gates start has actionable output in the log; keep it.
                if not log.exists():
                    raise
                try:
                    saved = preserve_log(log)
                except FAILURES as copy_error:
                    raise a.ReleaseError(f"{error}. Build log could not be preserved: {copy_error}") from error
                raise a.ReleaseError(f"{error}. Log: {saved}") from error


def stage_distribution(args, root: Path, directory: Path, stage: Path, certificate: str, ledger: dict | None) -> Path:
    baseline = resolve_baseline(args, stage, ledger, certificate)
    before = source_snapshot(root)
    a.require(before["release/signing-certificate.sha256"].decode("ascii").strip() == certificate,
              "Signing pin changed before build")
    identity = a.gradle_identity(before["app/build.gradle.kts"].decode())
    code = int(identity["versionCode"])
    a.require(code > baseline["versionCode"],
              f"versionCode {code} must exceed {baseline['versionCode']} ({baseline['source']}); increment it before building")
    # Never pass credentials or an unsigned/test flag through this API.
    command = [str(root / ("gradlew.bat" if os.name == "nt" else "gradlew")), "--no-daemon", "--max-workers=2", "-PsplitfreeUnsignedRelease=false"]
    if args.offline:
        command.append("--offline")
    if args.init_script:
        command += ["--init-script", str(Path(args.init_script).resolve())]
    init_configuration = None
    if args.init_script:
        init_configuration = regular_bytes(Path(args.init_script))
    command += GRADLE_TASKS
    with (stage / "build.log").open("wb") as log:
        helper_env = {key: value for key, value in os.environ.items() if key != "SPLITFREE_VARIANT_BUILD_DIR"}
        run_gate([sys.executable, "-B", "-m", "unittest", "discover", "-s", "tools/release/tests", "-v"],
                 root, log, 180, env=helper_env)
        run_gate(command, root, log, 1800)
        variant_env = {**os.environ, "SPLITFREE_VARIANT_BUILD_DIR": str(root / "app/build"),
                       "AAPT2": str(Path(args.build_tools).resolve() / "aapt2")}
        run_gate([sys.executable, "-B", "-m", "unittest", "discover", "-s", "tools/release/tests",
                  "-p", "test_variants.py", "-v"], root, log, 180, env=variant_env)
    a.require(source_snapshot(root) == before, "Source changed during build; no APK distributed")
    if args.init_script:
        a.require(regular_bytes(Path(args.init_script)) == init_configuration, "Gradle init script changed during build")
    gate = gate_results(root)
    candidate = stage / "candidate.apk"
    candidate.write_bytes(regular_bytes(root / "app/build/outputs/apk/release/app-release.apk"))
    meta = {"packageName": identity["applicationId"], "versionCode": code, "versionName": identity["versionName"]}
    info = a.inspect_signed_apk(candidate, meta, Path(args.build_tools), certificate)
    mapping = regular_bytes(root / "app/build/outputs/mapping/release/mapping.txt")
    map_id = mapping_identity(candidate, mapping)
    a.require(source_snapshot(root) == before, "Source changed during verification")
    payload = regular_bytes(candidate)
    a.require(a.sha256(payload) == info["apkSha256"] and len(payload) == info["apkBytes"], "Verified APK changed")
    stem = f"SplitFree-v{identity['versionName']}-{code}-{info['apkSha256'][:12]}"
    candidate.unlink()
    (stage / (stem + ".apk")).write_bytes(payload)
    files = {"mapping.txt": mapping, "source.tar.gz": source_archive(before),
             "source-sha256.json": a.json_bytes({n: a.sha256(d) for n, d in sorted(before.items())})}
    if init_configuration is not None:
        files["test-runtime.init.gradle"] = init_configuration
    for name, data in files.items():
        (stage / name).write_bytes(data)
    hashes = {p.name: a.sha256(regular_bytes(p)) for p in stage.iterdir()}
    report = {"schemaVersion": 1, **info, "apk": stem + ".apk", "variant": "release", "verification": gate,
              "mappingId": map_id, "baseline": baseline["source"], "previousApkSha256": baseline["apkSha256"],
              "previousVersionCode": baseline["versionCode"] or None, "artifacts": hashes,
              "sourceProvenance": "local source unchanged through build; not a reproducible-build attestation",
              "publicReleaseApproved": False, "deviceInstallTested": False}
    a.require(hashes[stem + ".apk"] == info["apkSha256"], "APK changed during handoff")
    (stage / REPORT).write_bytes(a.json_bytes(report))
    destination = directory / stem
    a.require(not destination.exists(), "Refusing to overwrite distributed APK")
    # Same-filesystem rename: a failed candidate never becomes a completed bundle.
    stage.rename(destination)
    return destination


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--build-tools", required=True, type=Path)
    baseline = parser.add_mutually_exclusive_group()
    baseline.add_argument("--previous-apk", type=Path,
                          help="A distributed production APK the candidate must supersede; required only when the ledger is empty")
    baseline.add_argument("--first-distribution", action="store_true",
                          help="Declare that nothing has been distributed before; refused unless the ledger is empty")
    parser.add_argument("--directory", default=default_ledger(), type=Path,
                        help="Ledger of completed bundles (default: $XDG_DATA_HOME/splitfree/distributions)")
    parser.add_argument("--offline", action="store_true", help="Use cached Gradle dependencies only")
    parser.add_argument("--init-script", type=Path, help="Optional trusted local Gradle test-runtime configuration")
    args = parser.parse_args(argv)
    try:
        print(build_distribution(args))
    except FAILURES as error:
        parser.exit(1, f"local distribution: {error}\n")


if __name__ == "__main__":
    main()
