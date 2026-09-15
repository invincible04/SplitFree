"""Local distribution transactions: real file/hash/archive logic, mocked build/SDK only."""
import argparse
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import distribute as d

CERT = "a" * 64
MAP_ID = "b" * 64
SOURCE = {"app/build.gradle.kts": b'applicationId = "com.splitfree"\nversionCode = 2\nversionName = "1.0.1"\nminSdk = 26\n',
          "release/signing-certificate.sha256": (CERT + "\n").encode(), "gradlew": b"fixture"}


def make_apk(path, marker=MAP_ID):
    with zipfile.ZipFile(path, "w") as apk:
        apk.writestr("classes.dex", b'dex\0~~R8' + json.dumps({"backend": "dex", "compilation-mode": "release", "pg-map-id": marker}).encode() + b'\0')


def make_mapping():
    body = b'com.example.Name -> a:\n    void test() -> a\n'
    return f'# compiler: R8\n# pg_map_id: {MAP_ID}\n# pg_map_hash: SHA-256 {d.a.sha256(body)}\n'.encode() + body


class DistributionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        # The helper refuses symlinked paths; platform temp roots (macOS /var -> /private/var) are resolved first.
        self.root = Path(self.temp.name).resolve()
        environment = patch.dict(os.environ, {"XDG_DATA_HOME": str(self.root / "data")})
        environment.start()
        self.addCleanup(environment.stop)
        self.pin = self.root / "pin"
        self.pin.write_text(CERT + "\n")
        self.previous = self.root / "previous.apk"
        self.previous.write_bytes(b"previous signed APK")
        self.apk = self.root / "app/build/outputs/apk/release/app-release.apk"
        self.apk.parent.mkdir(parents=True)
        make_apk(self.apk)
        self.mapping = self.root / "app/build/outputs/mapping/release/mapping.txt"
        self.mapping.parent.mkdir(parents=True)
        self.mapping.write_bytes(make_mapping())
        self.args = argparse.Namespace(directory=self.root / "distributions", previous_apk=self.previous,
                                      first_distribution=False, build_tools=self.root / "tools", offline=True,
                                      init_script=None)
        self.prior = {"apkSha256": d.a.sha256(self.previous.read_bytes()), "manifest": {"versionCode": 1}}
        self.calls = []
        tests = self.root / "app/build/test-results/testDebugUnitTest"
        tests.mkdir(parents=True)
        (tests / "TEST-fixture.xml").write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="pass"/></testsuite>')
        reports = self.root / "app/build/reports"
        reports.mkdir(parents=True)
        for variant in ("debug", "release"):
            (reports / f"lint-results-{variant}.xml").write_text('<issues/>')

    def inspect(self, apk, meta, tools, certificate):
        self.assertNotEqual(apk, self.apk, "must verify a fixed copy")
        self.assertEqual(certificate, CERT)
        self.calls.append(apk)
        return {"apkSha256": d.a.sha256(apk.read_bytes()), "apkBytes": apk.stat().st_size,
                "certificateSha256": CERT, "manifest": {**meta, "minSdk": 26, "targetSdk": 37},
                "abis": ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"], "payloadEntries": 1, "signingSchemes": ["v2"]}

    @contextlib.contextmanager
    def environment(self, build_code=0):
        with patch.object(d, "ROOT", self.root), patch.object(d, "PIN", self.pin), \
             patch.object(d, "source_snapshot", return_value=SOURCE.copy()) as source, \
             patch.object(d.a, "inspect_signed_baseline", return_value=self.prior) as prior, \
             patch.object(d.a, "inspect_signed_apk", side_effect=self.inspect) as inspector, \
             patch.object(d.subprocess, "run", side_effect=[subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], build_code), subprocess.CompletedProcess([], 0)]) as build:
            yield source, prior, inspector, build

    def failure_logs(self):
        directory = d.failures_directory()
        return sorted(directory.glob("*.log")) if directory.exists() else []

    def bundle_report(self, bundle):
        return json.loads((bundle / d.REPORT).read_bytes())

    def test_complete_build_fixed_bytes_mapping_source_and_history(self):
        with self.environment() as (_, prior, inspector, build):
            bundle = d.build_distribution(self.args)
            self.assertEqual(d.ledger_baseline(self.args.directory, CERT),
                             {"versionCode": 2, "apkSha256": d.a.sha256(self.apk.read_bytes()), "bundle": bundle.name,
                              "distributed": {2: d.a.sha256(self.apk.read_bytes())}})
            command = build.call_args_list[1].args[0]
            self.assertIn("test_variants.py", build.call_args_list[2].args[0])
            self.assertEqual(build.call_args_list[2].kwargs["env"]["SPLITFREE_VARIANT_BUILD_DIR"], str(self.root / "app/build"))
            self.assertNotIn("SPLITFREE_VARIANT_BUILD_DIR", build.call_args_list[0].kwargs["env"])
            self.assertTrue(set(d.GRADLE_TASKS) <= set(command))
            self.assertIn("--offline", command)
            self.assertNotIn("-PsplitfreeUnsignedRelease=true", command)
            self.assertIn("-PsplitfreeUnsignedRelease=false", command)
            self.assertEqual(prior.call_args.args[0].name, "previous.apk")
            self.assertEqual(inspector.call_count, 1)
        report = json.loads((bundle / d.REPORT).read_bytes())
        self.assertEqual(report["apkSha256"], d.a.sha256(self.apk.read_bytes()))
        self.assertEqual((bundle / report["apk"]).read_bytes(), self.apk.read_bytes())
        self.assertEqual(report["mappingId"], MAP_ID)
        self.assertFalse(report["publicReleaseApproved"])
        self.assertFalse(report["deviceInstallTested"])
        self.assertFalse((self.args.directory / ".distribution-lock").exists())
        self.assertEqual(len(list(self.args.directory.iterdir())), 1)
        with tarfile.open(bundle / "source.tar.gz") as source:
            self.assertEqual(source.extractfile("SplitFree-local/app/build.gradle.kts").read(), SOURCE["app/build.gradle.kts"])

    def test_increasing_code_accepts_unchanged_public_version_name(self):
        self.prior["manifest"]["versionName"] = "1.0.0"
        source = {**SOURCE, "app/build.gradle.kts": SOURCE["app/build.gradle.kts"].replace(b'"1.0.1"', b'"1.0.0"')}
        with self.environment() as (snapshot, _, _, _):
            snapshot.return_value = source
            bundle = d.build_distribution(self.args)
        report = json.loads((bundle / d.REPORT).read_bytes())
        self.assertEqual(report["manifest"]["versionName"], "1.0.0")
        self.assertEqual(report["manifest"]["versionCode"], 2)
        self.assertTrue(bundle.name.startswith("SplitFree-v1.0.0-2-"))

    def test_same_code_rebuild_and_lower_previous_code_cannot_bypass_history(self):
        with self.environment():
            bundle = d.build_distribution(self.args)
        original = {p.name: p.read_bytes() for p in bundle.iterdir()}
        with self.environment() as (_, _, _, build), self.assertRaisesRegex(d.a.ReleaseError, "versionCode"):
            d.build_distribution(self.args)
        build.assert_not_called()
        self.assertEqual(original, {p.name: p.read_bytes() for p in bundle.iterdir()})

    def test_prior_equal_or_newer_is_rejected_before_build(self):
        for code in (2, 3):
            self.prior["manifest"]["versionCode"] = code
            with self.environment() as (_, _, _, build), self.assertRaisesRegex(d.a.ReleaseError, "versionCode"):
                d.build_distribution(self.args)
            build.assert_not_called()

    def distribute(self, code, previous_apk=None, first_distribution=False):
        """Complete one distribution of the given versionCode with the ledger as the only baseline."""
        self.args.previous_apk, self.args.first_distribution = previous_apk, first_distribution
        with self.environment() as (snapshot, prior, _, _):
            snapshot.return_value = {**SOURCE, "app/build.gradle.kts": SOURCE["app/build.gradle.kts"].replace(b"versionCode = 2", b"versionCode = %d" % code)}
            bundle = d.build_distribution(self.args)
            self.assertEqual(prior.call_count, 0 if previous_apk is None else 1)
        return bundle

    def test_ledger_is_the_baseline_once_it_has_history(self):
        first = self.distribute(2, previous_apk=self.previous)
        second = self.distribute(3)
        report = self.bundle_report(second)
        self.assertEqual((report["baseline"], report["previousVersionCode"], report["previousApkSha256"]),
                         ("ledger", 2, self.bundle_report(first)["apkSha256"]))
        self.assertEqual(d.ledger_baseline(self.args.directory, CERT)["bundle"], second.name)
        with self.assertRaisesRegex(d.a.ReleaseError, r"versionCode 3 must exceed 3 \(ledger\)"):
            self.distribute(3)

    def test_empty_ledger_needs_a_previous_apk_or_an_explicit_first_distribution(self):
        self.args.previous_apk = None
        with self.environment() as (_, _, _, build), self.assertRaisesRegex(d.a.ReleaseError, "ledger is empty"):
            d.build_distribution(self.args)
        build.assert_not_called()
        self.assertEqual(list(self.args.directory.iterdir()), [])
        bundle = self.distribute(2, first_distribution=True)
        report = self.bundle_report(bundle)
        self.assertEqual((report["baseline"], report["previousVersionCode"], report["previousApkSha256"]),
                         ("first-distribution", None, None))

    def test_first_distribution_is_refused_once_the_ledger_has_history(self):
        self.distribute(2, previous_apk=self.previous)
        self.args.previous_apk, self.args.first_distribution = None, True
        with self.environment() as (_, _, _, build), self.assertRaisesRegex(d.a.ReleaseError, "already has history"):
            d.build_distribution(self.args)
        build.assert_not_called()

    def test_previous_apk_with_a_ledger_code_must_be_that_ledger_bundle(self):
        first = self.distribute(2, previous_apk=self.previous)
        self.prior = {"apkSha256": "f" * 64, "manifest": {"versionCode": 2}}
        self.args.previous_apk = self.previous
        with self.environment() as (_, _, _, build), self.assertRaisesRegex(d.a.ReleaseError, "two different builds share a code"):
            d.build_distribution(self.args)
        build.assert_not_called()
        self.prior = {"apkSha256": self.bundle_report(first)["apkSha256"], "manifest": {"versionCode": 2}}
        self.distribute(3, previous_apk=self.previous)

    def test_previous_apk_is_checked_against_every_retained_code_not_only_the_newest(self):
        first = self.distribute(2, previous_apk=self.previous)
        second = self.distribute(3)
        self.assertEqual(d.ledger_baseline(self.args.directory, CERT)["distributed"],
                         {2: self.bundle_report(first)["apkSha256"], 3: self.bundle_report(second)["apkSha256"]})
        # A predecessor claiming the older code 2 with different bytes must be refused even though 3 is newest.
        self.prior = {"apkSha256": "d" * 64, "manifest": {"versionCode": 2}}
        self.args.previous_apk = self.previous
        with self.environment() as (snapshot, _, _, build), self.assertRaisesRegex(d.a.ReleaseError, "versionCode 2; two different builds share a code"):
            snapshot.return_value = {**SOURCE, "app/build.gradle.kts": SOURCE["app/build.gradle.kts"].replace(b"versionCode = 2", b"versionCode = 4")}
            d.build_distribution(self.args)
        build.assert_not_called()
        self.assertEqual(len([b for b in self.args.directory.iterdir() if b.is_dir()]), 2)
        # The genuine code-2 bytes are accepted, and the ledger's newer code 3 remains the baseline.
        self.prior = {"apkSha256": self.bundle_report(first)["apkSha256"], "manifest": {"versionCode": 2}}
        fourth = self.distribute(4, previous_apk=self.previous)
        self.assertEqual((self.bundle_report(fourth)["baseline"], self.bundle_report(fourth)["previousVersionCode"]), ("ledger", 3))

    def test_previous_apk_newer_than_the_ledger_becomes_the_baseline(self):
        self.distribute(2, previous_apk=self.previous)
        self.prior = {"apkSha256": "e" * 64, "manifest": {"versionCode": 5}}
        with self.environment() as (_, _, _, build), self.assertRaisesRegex(d.a.ReleaseError, r"must exceed 5 \(previous-apk\)"):
            self.args.previous_apk = self.previous
            snapshot_code_three = {**SOURCE, "app/build.gradle.kts": SOURCE["app/build.gradle.kts"].replace(b"versionCode = 2", b"versionCode = 3")}
            with patch.object(d, "source_snapshot", return_value=snapshot_code_three):
                d.build_distribution(self.args)
        build.assert_not_called()

    def test_finder_metadata_is_tolerated_but_every_other_stray_entry_fails_closed(self):
        bundle = self.distribute(2, previous_apk=self.previous)
        (self.args.directory / ".DS_Store").write_bytes(b"finder")
        (bundle / ".DS_Store").write_bytes(b"finder")
        self.assertEqual(d.ledger_baseline(self.args.directory, CERT)["versionCode"], 2)
        for stray in ("notes.txt", "SplitFree-v9.9.9-9-000000000000.apk"):
            (self.args.directory / stray).write_bytes(b"x")
            with self.assertRaisesRegex(d.a.ReleaseError, "Unexpected/incomplete"):
                d.ledger_baseline(self.args.directory, CERT)
            (self.args.directory / stray).unlink()
        (bundle / "notes.txt").write_bytes(b"x")
        with self.assertRaisesRegex(d.a.ReleaseError, "Incomplete distribution history"):
            d.ledger_baseline(self.args.directory, CERT)
        (bundle / "notes.txt").unlink()
        (self.args.directory / ".DS_Store").unlink()
        (self.args.directory / ".DS_Store").symlink_to(bundle / d.REPORT)
        with self.assertRaisesRegex(d.a.ReleaseError, "Unexpected/incomplete"):
            d.ledger_baseline(self.args.directory, CERT)
        (self.args.directory / ".DS_Store").unlink()
        (self.args.directory / ".DS_Store").mkdir()
        with self.assertRaisesRegex(d.a.ReleaseError, "Unexpected/incomplete"):
            d.ledger_baseline(self.args.directory, CERT)

    def test_two_ledger_bundles_with_one_version_code_are_refused(self):
        bundle = self.distribute(2, previous_apk=self.previous)
        copy = self.args.directory / bundle.name.replace(bundle.name[-12:], "0" * 12)
        import shutil
        shutil.copytree(bundle, copy)
        report = self.bundle_report(copy)
        with self.assertRaisesRegex(d.a.ReleaseError, "History APK identity mismatch"):
            d.ledger_baseline(self.args.directory, CERT)
        shutil.rmtree(copy)
        self.distribute(3)
        newest = d.ledger_baseline(self.args.directory, CERT)
        forged = self.args.directory / ("SplitFree-v1.0.1-3-" + "1" * 12)
        shutil.copytree(self.args.directory / newest["bundle"], forged)
        (forged / d.REPORT).write_bytes(d.a.json_bytes({**self.bundle_report(forged), "apkSha256": "1" * 64}))
        with self.assertRaises(d.a.ReleaseError):
            d.ledger_baseline(self.args.directory, CERT)

    def test_wrong_prior_signer_fails_before_build(self):
        with self.environment() as (_, prior, _, build):
            prior.side_effect = d.a.ReleaseError("wrong signer")
            with self.assertRaisesRegex(d.a.ReleaseError, "signer"):
                d.build_distribution(self.args)
            build.assert_not_called()
        self.assertEqual(list(self.args.directory.iterdir()), [])
        self.assertEqual(self.failure_logs(), [], "no gate ran, so there is no log to keep")

    def test_current_unsigned_wrong_signer_or_corrupt_failure_never_distributes(self):
        for message in ("unsigned", "wrong signer", "corrupted ZIP"):
            with self.environment() as (_, _, inspector, _):
                inspector.side_effect = d.a.ReleaseError(message)
                with self.assertRaisesRegex(d.a.ReleaseError, message):
                    d.build_distribution(self.args)
            self.assertEqual(list(self.args.directory.iterdir()), [])

    def test_failed_gate_preserves_log_not_apk(self):
        with self.environment(build_code=1), self.assertRaisesRegex(d.a.ReleaseError, r"Gate failed \(1\).*Log: ") as failure:
            d.build_distribution(self.args)
        self.assertEqual(list(self.args.directory.iterdir()), [])
        logs = self.failure_logs()
        self.assertEqual(len(logs), 1)
        self.assertIn(str(logs[0]), str(failure.exception))

    def test_every_failure_after_the_gates_start_preserves_the_build_log(self):
        def gates(command, **kwargs):
            kwargs["stdout"].write(b"gradle output\n")
            return subprocess.CompletedProcess(command, 0)
        cases = {
            "candidate rejected": lambda env: setattr(env[2], "side_effect", d.a.ReleaseError("wrong signer")),
            "source changed": lambda env: setattr(env[0], "side_effect", [SOURCE, {**SOURCE, "app/new.kt": b"x"}]),
            "mapping missing": lambda env: self.mapping.unlink(),
            "results missing": lambda env: (self.root / "app/build/test-results/testDebugUnitTest/TEST-fixture.xml").unlink(),
            "lint report malformed": lambda env: (self.root / "app/build/reports/lint-results-debug.xml").write_text("<issues"),
        }
        for name, arrange in cases.items():
            with self.subTest(failure=name), self.environment() as env:
                env[3].side_effect = gates
                arrange(env)
                with self.assertRaisesRegex(d.a.ReleaseError, r"Log: ") as failure:
                    d.build_distribution(self.args)
                logs = self.failure_logs()
                self.assertEqual(len(logs), 1, name)
                self.assertEqual(logs[0].read_bytes(), b"gradle output\n" * 3)
                self.assertIn(str(logs[0]), str(failure.exception))
                self.assertEqual(list(self.args.directory.iterdir()), [])
                logs[0].unlink()
            self.setUp()

    def test_unpreservable_log_reports_the_original_failure(self):
        d.failures_directory().parent.mkdir(parents=True)
        d.failures_directory().symlink_to(self.root)
        with self.environment(build_code=1), self.assertRaisesRegex(d.a.ReleaseError, r"Gate failed \(1\).*could not be preserved.*symlink"):
            d.build_distribution(self.args)
        self.assertEqual(list(self.args.directory.iterdir()), [])

    def test_postbuild_variant_failure_prevents_distribution(self):
        with self.environment() as (_, _, _, build):
            build.side_effect = [subprocess.CompletedProcess([], code) for code in (0, 0, 1)]
            with self.assertRaisesRegex(d.a.ReleaseError, "Gate failed"):
                d.build_distribution(self.args)
        self.assertEqual(list(self.args.directory.iterdir()), [])

    def test_helper_failure_prevents_gradle_and_distribution(self):
        with self.environment() as (_, _, _, build):
            build.side_effect = [subprocess.CompletedProcess([], 1)]
            with self.assertRaisesRegex(d.a.ReleaseError, "Gate failed"):
                d.build_distribution(self.args)
            self.assertEqual(build.call_count, 1)
        self.assertEqual(list(self.args.directory.iterdir()), [])

    def test_build_timeout_releases_lock_and_never_distributes(self):
        with self.environment() as (_, _, _, build):
            build.side_effect = subprocess.TimeoutExpired("gradle", 1800)
            with self.assertRaisesRegex(d.a.ReleaseError, "timed out"):
                d.build_distribution(self.args)
        self.assertEqual(list(self.args.directory.iterdir()), [])

    def test_changed_source_after_build_and_verification_rejected(self):
        for snapshots in ([SOURCE, {**SOURCE, "app/new.kt": b"changed"}],
                          [SOURCE, SOURCE, {**SOURCE, "app/new.kt": b"changed"}]):
            with self.environment() as (source, _, _, _):
                source.side_effect = snapshots
                with self.assertRaisesRegex(d.a.ReleaseError, "Source changed"):
                    d.build_distribution(self.args)
            self.assertEqual(list(self.args.directory.iterdir()), [])

    def test_mismatched_final_apk_hash_is_rejected(self):
        def changed(apk, *args):
            result = self.inspect(apk, *args)
            with apk.open("ab") as stream:
                stream.write(b"changed")
            return result
        with self.environment() as (_, _, inspector, _):
            inspector.side_effect = changed
            with self.assertRaisesRegex(d.a.ReleaseError, "Verified APK changed"):
                d.build_distribution(self.args)
        self.assertEqual(list(self.args.directory.iterdir()), [])

    def test_original_build_output_mutation_does_not_change_fixed_candidate(self):
        original = self.apk.read_bytes()
        def alter_original(apk, *args):
            result = self.inspect(apk, *args)
            self.apk.write_bytes(b"replaced build output")
            return result
        with self.environment() as (_, _, inspector, _):
            inspector.side_effect = alter_original
            bundle = d.build_distribution(self.args)
        info = json.loads((bundle / d.REPORT).read_bytes())
        self.assertEqual((bundle / info["apk"]).read_bytes(), original)

    def test_history_tampering_missing_files_extra_entries_fail_closed(self):
        with self.environment():
            bundle = d.build_distribution(self.args)
        mapping = bundle / "mapping.txt"
        saved = mapping.read_bytes()
        mapping.write_bytes(b"tampered")
        with self.assertRaisesRegex(d.a.ReleaseError, "hash mismatch"):
            d.ledger_baseline(self.args.directory, CERT)
        mapping.unlink()
        with self.assertRaisesRegex(d.a.ReleaseError, "Incomplete"):
            d.ledger_baseline(self.args.directory, CERT)
        mapping.write_bytes(saved)
        (self.args.directory / ".candidate-aborted").mkdir()
        with self.assertRaisesRegex(d.a.ReleaseError, "incomplete"):
            d.ledger_baseline(self.args.directory, CERT)

    def test_different_directory_cannot_race_workspace_build(self):
        with d.workspace_build_lock(self.root):
            with self.environment() as (_, _, _, build), self.assertRaisesRegex(d.a.ReleaseError, r"active or interrupted; investigate .*\.local-distribution\.lock"):
                d.build_distribution(self.args)
            build.assert_not_called()
            self.assertTrue((self.root / d.WORKSPACE_LOCK).is_dir())
        self.assertFalse((self.root / d.WORKSPACE_LOCK).exists())
        self.assertFalse((self.root / "docs").exists(), "the tool must not create anything else in the checkout")

    def test_concurrent_distribution_refused_without_stealing_lock(self):
        with d.distribution_lock(self.args.directory):
            with self.environment(), self.assertRaisesRegex(d.a.ReleaseError, "locked"):
                d.build_distribution(self.args)
            self.assertTrue((self.args.directory / ".distribution-lock").exists())

    def test_pin_invalid_and_symlinks_refused(self):
        for value in ("", "b" * 63, "B" * 64, "a" * 64 + "\n" + "b" * 64):
            self.pin.write_text(value)
            with self.assertRaises(d.a.ReleaseError):
                d.certificate_pin(self.pin)
        self.pin.unlink()
        self.pin.symlink_to(self.previous)
        with self.assertRaises(d.a.ReleaseError):
            d.certificate_pin(self.pin)

    def test_path_symlink_and_oversized_file_refused(self):
        self.args.directory.symlink_to(self.root, target_is_directory=True)
        with self.environment(), self.assertRaisesRegex(d.a.ReleaseError, "symlinks"):
            d.build_distribution(self.args)
        with self.assertRaisesRegex(d.a.ReleaseError, "bounded"):
            d.regular_bytes(self.previous, 2)

    def test_mapping_id_body_and_absent_marker_rejected(self):
        self.assertEqual(d.mapping_identity(self.apk, make_mapping()), MAP_ID)
        with self.assertRaisesRegex(d.a.ReleaseError, "checksum"):
            d.mapping_identity(self.apk, make_mapping() + b"tamper")
        with self.assertRaisesRegex(d.a.ReleaseError, "identity"):
            d.mapping_identity(self.apk, b"no R8 headers")
        for marker in ("c" * 64, None):
            make_apk(self.apk, marker)
            with self.assertRaisesRegex(d.a.ReleaseError, "does not match"):
                d.mapping_identity(self.apk, make_mapping())

    def init_source_repo(self):
        subprocess.run(["git", "-C", str(self.root), "init", "-q"], check=True)
        for name, data in SOURCE.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        (self.root / ".gitignore").write_text("build/\n*.apk\n/pin\n/distributions/\n/data/\n/.local-distribution.lock/\nlocal.properties\nHidden.kt\n")
        subprocess.run(["git", "-C", str(self.root), "add", "--", *SOURCE, ".gitignore"], check=True)

    def test_source_snapshot_excludes_ignored_secrets_and_captures_local_edits(self):
        self.init_source_repo()
        (self.root / "local.properties").write_text("private fixture")
        snapshot = d.source_snapshot(self.root)
        self.assertEqual({n: snapshot[n] for n in SOURCE}, SOURCE)
        self.assertNotIn("local.properties", snapshot)
        subprocess.run(["git", "-C", str(self.root), "add", "-f", "local.properties"], check=True)
        with self.assertRaises(d.a.ReleaseError):
            d.source_snapshot(self.root)

    def test_source_archive_deterministic_no_host_metadata(self):
        one = d.source_archive(SOURCE)
        self.assertEqual(one, d.source_archive(dict(reversed(list(SOURCE.items())))))
        with tarfile.open(fileobj=io.BytesIO(one), mode="r:gz") as archive:
            self.assertTrue(all(member.uid == member.gid == member.mtime == 0 for member in archive))

    def test_gate_missing_failed_skipped_and_count_mismatch_refused(self):
        path = self.root / "app/build/test-results/testDebugUnitTest/TEST-fixture.xml"
        for text in ('<testsuite tests="1" failures="1" errors="0" skipped="0"><testcase/></testsuite>',
                     '<testsuite tests="1" failures="0" errors="0" skipped="1"><testcase/></testsuite>',
                     '<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase/></testsuite>'):
            path.write_text(text)
            with self.environment(), self.assertRaises(d.a.ReleaseError):
                d.build_distribution(self.args)
            self.assertEqual(list(self.args.directory.iterdir()), [])
        path.unlink()
        with self.assertRaisesRegex(d.a.ReleaseError, "Missing"):
            d.gate_results(self.root)

    def test_hidden_failure_in_success_counts_is_rejected(self):
        path = self.root / "app/build/test-results/testDebugUnitTest/TEST-fixture.xml"
        path.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase><failure/></testcase></testsuite>')
        with self.assertRaisesRegex(d.a.ReleaseError, "counter mismatch"):
            d.gate_results(self.root)

    def test_lint_error_refused_and_warnings_recorded(self):
        path = self.root / "app/build/reports/lint-results-release.xml"
        path.write_text('<issues><issue severity="Error"/></issues>')
        with self.assertRaisesRegex(d.a.ReleaseError, "Lint"):
            d.gate_results(self.root)
        path.write_text('<issues><issue severity="Warning"/></issues>')
        self.assertEqual(d.gate_results(self.root)["lintIssues"]["release"], 1)

    def test_ignored_compilable_source_is_snapshotted(self):
        self.init_source_repo()
        source = self.root / "app/src/main/java/Hidden.kt"
        source.parent.mkdir(parents=True)
        source.write_bytes(b"ignored but compiled")
        result = d.source_snapshot(self.root)
        self.assertEqual(result["app/src/main/java/Hidden.kt"], b"ignored but compiled")

    def test_ignored_private_database_below_source_root_blocks_snapshot(self):
        self.init_source_repo()
        ignore = self.root / ".gitignore"
        ignore.write_text(ignore.read_text() + "*.db\n")
        private = self.root / "app/src/main/assets/personal.db"
        private.parent.mkdir(parents=True)
        private.write_bytes(b"synthetic private database")
        with self.assertRaisesRegex(d.a.ReleaseError, "SensitiveSourcePath"):
            d.source_snapshot(self.root)

    def test_cli_failure_is_nonzero(self):
        with patch.object(d, "build_distribution", side_effect=d.a.ReleaseError("test refusal")), \
             contextlib.redirect_stderr(io.StringIO()) as error, self.assertRaises(SystemExit) as exit:
            d.main(["--build-tools", "tools", "--previous-apk", "previous.apk"])
        self.assertEqual(exit.exception.code, 1)
        self.assertIn("test refusal", error.getvalue())

    def test_cli_defaults_to_the_data_home_ledger_and_refuses_contradictory_baselines(self):
        with patch.object(d, "build_distribution", side_effect=lambda args: args.directory) as build, \
             contextlib.redirect_stdout(io.StringIO()) as output:
            d.main(["--build-tools", "tools"])
        self.assertEqual(build.call_args.args[0].directory, self.root / "data/splitfree/distributions")
        self.assertEqual(output.getvalue().strip(), str(self.root / "data/splitfree/distributions"))
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as exit:
            d.main(["--build-tools", "tools", "--previous-apk", "previous.apk", "--first-distribution"])
        self.assertEqual(exit.exception.code, 2)

    def test_data_home_follows_xdg_and_falls_back_to_the_user_share_directory(self):
        self.assertEqual(d.default_ledger(), self.root / "data/splitfree/distributions")
        self.assertEqual(d.failures_directory(), self.root / "data/splitfree/distribution-failures")
        fallback = Path.home() / ".local/share/splitfree/distributions"
        for value in ("", "relative/state"):
            with patch.dict(os.environ, {"XDG_DATA_HOME": value}):
                self.assertEqual(d.default_ledger(), fallback)


if __name__ == "__main__":
    unittest.main()
