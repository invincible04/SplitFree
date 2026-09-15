import argparse
import copy
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import install_smoke as s


CERT = "a" * 64
DEBUG_CERT = "b" * 64
BOOT = "12345678-1234-1234-1234-123456789abc"
ROLES = ("previous", "current", "debug")


def info(role, data):
    debug = role == "debug"
    return {"apkSha256": s.a.sha256(data), "apkBytes": len(data),
            "certificateSha256": DEBUG_CERT if debug else CERT,
            "manifest": {"packageName": s.DEBUG if debug else s.RELEASE, "versionCode": 1 if role == "previous" else 2,
                         "versionName": "1.0.0" if role == "previous" else "1.0.1-debug" if debug else "1.0.1",
                         "minSdk": 26, "targetSdk": 37, "debuggable": debug, "testOnly": False, "standalone": True},
            "abis": ["arm64-v8a", "x86_64"], "payloadEntries": 3, "signingSchemes": ["v2"]}


def node(name, attributes=None, children=None):
    return {"name": name, "attributes": attributes or {}, "children": children or []}


def debug_tree():
    return node("manifest", {"package": '"com.splitfree.debug"', "android:versionCode": "2", "android:versionName": '"1.0.1-debug"'}, [
        node("uses-sdk", {"android:minSdkVersion": "26", "android:targetSdkVersion": "37"}),
        node("application", {"android:label": '"SplitFree Debug"', "android:debuggable": "(type 0x12)0xffffffff"}),
    ])


class FakeAdb:
    def __init__(self, test):
        self.test = test
        self.calls = []
        self.packages = {}
        self.overrides = {}
        self.corrupt_pull = False
        self.uid_change = False
        self.time_change = False
        self.omit_time = False
        self.fail_install = None
        self.foreign_handler = False

    def __call__(self, command, **kwargs):
        self.test.assertEqual(kwargs["timeout"], 120)
        self.test.assertIs(kwargs["shell"], False)
        self.test.assertEqual(command[1:7], ["-H", "127.0.0.1", "-P", "5037", "-s", "emulator-5554"])
        args = tuple(command[7:])
        self.calls.append(args)
        if args in self.overrides:
            value = self.overrides[args]
            if isinstance(value, BaseException):
                raise value
            return subprocess.CompletedProcess(command, 0, value, "")
        props = {"ro.kernel.qemu": "1", "sys.boot_completed": "1", "ro.build.version.sdk": "36",
                 "ro.product.cpu.abilist": "arm64-v8a", "ro.build.fingerprint": "sdk_gphone64_arm64/test"}
        if args == ("get-state",):
            value = "device\n"
        elif args[:2] == ("shell", "getprop"):
            value = props[args[2]] + "\n"
        elif args == ("shell", "am", "get-current-user"):
            value = "0\n"
        elif args == ("shell", "cat", "/proc/sys/kernel/random/boot_id"):
            value = BOOT + "\n"
        elif args == ("shell", "pm", "list", "users"):
            value = "Users:\n\tUserInfo{0:Owner:c13} running\n\tUserInfo{10:Work:30} running\n"
        elif args[:5] == ("shell", "pm", "list", "packages", "-u"):
            value = "package:android\n" + "".join("package:" + package + "\n" for package in self.packages)
        elif args == ("shell", "getconf", "PAGE_SIZE"):
            value = "16384\n"
        elif args[0] == "install":
            data = Path(args[-1]).read_bytes()
            role = self.test.role_for(data)
            if role == self.fail_install:
                value = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\n"
            else:
                package = self.test.infos[role]["manifest"]["packageName"]
                self.packages[package] = role
                value = "Performing Streamed Install\nSuccess\n"
        elif args[:3] == ("shell", "pm", "path"):
            value = "package:/data/app/~~hash/" + args[-1] + "-hash/base.apk\n"
        elif args[:3] == ("shell", "dumpsys", "package"):
            package = args[-1]
            role = self.packages[package]
            identity = self.test.infos[role]["manifest"]
            uid = 10124 if package == s.DEBUG else 10125 if self.uid_change and role == "current" else 10123
            time = "2026-09-16 12:00:00" if self.time_change and role == "current" else "2026-09-15 12:00:00"
            value = (f"Packages:\n  Package [{package}] (abcdef):\n    userId={uid}\n"
                     f"    codePath=/data/app/~~hash/{package}-hash\n"
                     f"    versionCode={identity['versionCode']} minSdk=26 targetSdk=37\n"
                     f"    versionName={identity['versionName']}\n"
                     + ("" if self.omit_time else f"    firstInstallTime={time}\n")
                     + "    User 0: ceDataInode=123 installed=true hidden=false\n\nQueries:\n  unrelated=1\n")
        elif args[0] == "pull":
            package = s.DEBUG if s.DEBUG in args[1] else s.RELEASE
            data = self.test.data[self.packages[package]]
            Path(args[2]).write_bytes(data + b"corrupt" if self.corrupt_pull else data)
            value = "1 file pulled\n"
        elif args[:3] == ("shell", "am", "start"):
            value = "Starting: Intent\nStatus: ok\nActivity: " + args[-1] + "\nTotalTime: 300\nComplete\n"
        elif args[:2] == ("shell", "pidof"):
            value = "1234\n"
        elif args[:3] == ("shell", "cmd", "package"):
            value = s.RELEASE + "/.MainActivity\n"
            if self.foreign_handler and args[3] == "query-activities":
                value += s.DEBUG + "/" + s.ACTIVITY + "\n"
        else:
            raise AssertionError(f"Unexpected ADB command: {args}")
        return subprocess.CompletedProcess(command, 0, value, "")


class SmokeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="smoke-tests-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.data = {role: (role + " signed APK fixture").encode() for role in ROLES}
        self.infos = {role: info(role, data) for role, data in self.data.items()}
        for role, data in self.data.items():
            (self.root / (role + "-source.apk")).write_bytes(data)
        for tool in ("platform-tools/adb", "build-tools/aapt2", "build-tools/apksigner", "build-tools/zipalign"):
            path = self.root / tool
            path.parent.mkdir(exist_ok=True)
            path.write_text("fixture SDK tool; never executed\n")
            path.chmod(0o700)
        self.args = argparse.Namespace(serial="emulator-5554", allow_emulator_install=True,
                                       certificate=CERT, debug_certificate=DEBUG_CERT, sdk=self.root,
                                       build_tools=self.root / "build-tools", work_directory=self.root,
                                       **{role + "_apk": self.root / (role + "-source.apk") for role in ROLES})
        self.fake = FakeAdb(self)
        self.report = {"commands": [], "passedChecks": [], "limitations": [], "status": "failed"}
        self.device = s.Emulator(self.root / "platform-tools/adb", self.args.serial, self.report)
        self.addCleanup(patch.stopall)
        patch.object(s.subprocess, "run", side_effect=self.fake).start()
        self.identity = patch.object(s.a, "read_apk_identity", side_effect=self.read_identity).start()
        self.signed = patch.object(s.a, "inspect_signed_apk", side_effect=self.inspect_release).start()
        self.debug = patch.object(s, "inspect_debug", side_effect=self.inspect_debug).start()
        patch.object(s, "manifest", return_value=node("manifest", children=[node("application", {"android:label": '"SplitFree"'})])).start()

    def role_for(self, data):
        return next(role for role in ROLES if self.data[role] == data)

    def read_identity(self, apk, tools):
        role = self.role_for(Path(apk).read_bytes())
        return {key: self.infos[role]["manifest"][key] for key in ("packageName", "versionCode", "versionName")}

    def inspect_release(self, apk, identity, tools, certificate):
        role = self.role_for(Path(apk).read_bytes())
        self.assertIn(role, ("previous", "current"))
        self.assertEqual(certificate, CERT)
        return copy.deepcopy(self.infos[role])

    def inspect_debug(self, apk, tools, certificate, current):
        self.assertEqual(certificate, DEBUG_CERT)
        return copy.deepcopy(self.infos["debug"])

    def candidate_directory(self):
        path = self.root / "candidates"
        path.mkdir(exist_ok=True)
        return path

    def execute(self):
        s.execute(self.args, self.candidate_directory(), self.report)

    def argv(self):
        result = ["--allow-emulator-install"]
        for key, value in vars(self.args).items():
            if key != "allow_emulator_install":
                result.extend(["--" + key.replace("_", "-"), str(value)])
        return result

    def assert_no_mutation(self):
        self.assertFalse(any(call[0] == "install" or call[:3] == ("shell", "am", "start") for call in self.fake.calls))

    def tearDown(self):
        for command in self.fake.calls:
            self.assertNotIn("uninstall", command)
            self.assertNotIn("clear", command)
            self.assertNotIn("-d", command if command[0] == "install" else ())
            self.assertNotIn("-t", command)
            self.assertNotIn("--bypass-low-target-sdk-block", command)

    def test_complete_smoke_uses_fixed_candidates_exact_hashes_and_only_update_replace(self):
        self.execute()
        installs = [call for call in self.fake.calls if call[0] == "install"]
        self.assertEqual(["-r" in call for call in installs], [False, True, False])
        self.assertEqual([Path(call[-1]).name for call in installs], ["previous.apk", "current.apk", "debug.apk"])
        for call in installs:
            path = Path(call[-1])
            self.assertEqual(path.parent, self.root / "candidates")
            self.assertEqual(path.stat().st_mode & 0o777, 0o400)
        self.assertEqual(self.report["status"], "passed")
        self.assertEqual(self.report["device"]["pageSizeBytes"], 16384)
        self.assertEqual(self.report["currentInstall"]["apkSha256"], self.infos["current"]["apkSha256"])
        self.assertEqual(set(self.fake.packages), {s.RELEASE, s.DEBUG})
        self.assertTrue(any("firstInstallTime unchanged" in check for check in self.report["passedChecks"]))

    def test_missing_consent_or_physical_serial_rejected_before_tools(self):
        for args in ([arg for arg in self.argv() if arg != "--allow-emulator-install"],
                     ["physical-device" if arg == "emulator-5554" else arg for arg in self.argv()]):
            with self.subTest(args=args), redirect_stderr(io.StringIO()):
                self.assertEqual(s.main(args), 1)
        self.assertEqual(self.fake.calls, [])
        self.signed.assert_not_called()

    def test_invalid_certificate_and_equal_pins_rejected_before_tools(self):
        for pin in ("no-pin", DEBUG_CERT):
            args = [pin if arg == CERT else arg for arg in self.argv()]
            with self.subTest(pin=pin), redirect_stderr(io.StringIO()):
                self.assertEqual(s.main(args), 1)
        self.assertEqual(self.fake.calls, [])

    def test_missing_sdk_executable_fails_without_device(self):
        self.args.sdk = self.root / "missing-sdk"
        with redirect_stderr(io.StringIO()):
            self.assertEqual(s.main(self.argv()), 1)
        self.assertEqual(self.fake.calls, [])

    def test_preflight_rejects_unverified_device_states(self):
        cases = {("get-state",): "offline", ("shell", "getprop", "ro.kernel.qemu"): "0",
                 ("shell", "getprop", "sys.boot_completed"): "0", ("shell", "am", "get-current-user"): "10",
                 ("shell", "cat", "/proc/sys/kernel/random/boot_id"): "unknown",
                 ("shell", "getprop", "ro.build.version.sdk"): "25",
                 ("shell", "getprop", "ro.product.cpu.abilist"): "unsupported",
                 ("shell", "getconf", "PAGE_SIZE"): "unknown"}
        for command, output in cases.items():
            with self.subTest(command=command):
                self.fake.overrides = {command: output}
                with self.assertRaises(s.a.ReleaseError):
                    self.device.preflight(self.infos)
                self.assert_no_mutation()

    def test_physical_serial_rejected_even_when_qemu_claims_one(self):
        self.device.serial = "192.0.2.1:5555"
        with self.assertRaisesRegex(s.a.ReleaseError, "Serial"):
            self.device.guard()
        self.assertEqual(self.fake.calls, [])

    def test_rejects_occupied_retained_global_owner_and_other_user(self):
        for package in (s.RELEASE, s.DEBUG):
            for suffix in ((), ("--user", "0"), ("--user", "10")):
                command = ("shell", "pm", "list", "packages", "-u", *suffix)
                with self.subTest(package=package, suffix=suffix):
                    self.fake.overrides = {command: "package:android\npackage:" + package + "\n"}
                    with self.assertRaisesRegex(s.a.ReleaseError, "contains"):
                        self.device.preflight(self.infos)
                    self.assert_no_mutation()

    def test_unknown_user_inventory_or_package_output_fails_closed(self):
        cases = {("shell", "pm", "list", "users"): "Users:\nunknown\n",
                 ("shell", "pm", "list", "packages", "-u"): "Error: permission denied",
                 ("shell", "pm", "list", "packages", "-u", "--user", "10"): ""}
        for command, output in cases.items():
            self.fake.overrides = {command: output}
            with self.subTest(command=command), self.assertRaises(s.a.ReleaseError):
                self.device.preflight(self.infos)
        self.assert_no_mutation()

    def test_signer_mismatch_rejected_before_any_device_command(self):
        self.infos["previous"]["certificateSha256"] = "c" * 64
        with self.assertRaisesRegex(s.a.ReleaseError, "same pinned"):
            self.execute()
        self.assertEqual(self.fake.calls, [])

    def test_same_version_and_downgrade_rejected_before_device(self):
        for version in (1, 0):
            self.infos["current"]["manifest"]["versionCode"] = version
            with self.subTest(version=version), tempfile.TemporaryDirectory(dir=self.root) as directory:
                with self.assertRaisesRegex(s.a.ReleaseError, "strictly increase"):
                    s.prepare_candidates(self.args, Path(directory))
        self.assertEqual(self.fake.calls, [])

    def test_debug_verification_failure_happens_before_device(self):
        self.debug.side_effect = s.a.ReleaseError("Debug wrong signature")
        with self.assertRaisesRegex(s.a.ReleaseError, "wrong signature"):
            self.execute()
        self.assertEqual(self.fake.calls, [])

    def test_source_paths_can_change_after_copy_without_changing_installed_bytes(self):
        candidates, infos = s.prepare_candidates(self.args, self.candidate_directory())
        self.args.previous_apk.write_bytes(b"replacement unverified source")
        self.device.install(candidates["previous"], infos["previous"])
        self.assertEqual(candidates["previous"].read_bytes(), self.data["previous"])

    def test_source_symlink_rejected(self):
        link = self.root / "link.apk"
        link.symlink_to(self.args.previous_apk)
        with self.assertRaisesRegex(s.a.ReleaseError, "regular file"):
            s.copy_candidate(link, self.root / "copy.apk")
        self.assert_no_mutation()

    def test_candidate_tamper_refused_before_install(self):
        candidates, infos = s.prepare_candidates(self.args, self.candidate_directory())
        candidate = candidates["previous"]
        candidate.chmod(0o600)
        candidate.write_bytes(b"altered candidate")
        with self.assertRaisesRegex(s.a.ReleaseError, "no longer matches"):
            self.device.install(candidate, infos["previous"])
        self.assert_no_mutation()

    def test_boot_identity_change_prevents_mutation(self):
        self.device.preflight(self.infos)
        self.fake.overrides[("shell", "cat", "/proc/sys/kernel/random/boot_id")] = BOOT.replace("abc", "abd")
        with self.assertRaisesRegex(s.a.ReleaseError, "changed boot"):
            self.device.install(self.args.previous_apk, self.infos["previous"])
        self.assert_no_mutation()

    def test_install_requires_exact_success_not_substring(self):
        for output in ("Failure [Success]", "Success\nFailure", "Not Success", "", "Success but skipped"):
            command = ("install", "--user", "0", str(self.args.previous_apk))
            self.fake.overrides = {command: output}
            with self.subTest(output=output), self.assertRaisesRegex(s.a.ReleaseError, "exact Success"):
                self.device.install(self.args.previous_apk, self.infos["previous"])

    def test_failed_update_keeps_previous_no_cleanup_or_debug_install(self):
        self.fake.fail_install = "current"
        with self.assertRaisesRegex(s.a.ReleaseError, "INSTALL_FAILED_UPDATE_INCOMPATIBLE"):
            self.execute()
        self.assertEqual(self.fake.packages, {s.RELEASE: "previous"})
        self.assertEqual(len([call for call in self.fake.calls if call[0] == "install"]), 2)

    def test_corrupt_delivered_apk_fails_before_update(self):
        self.fake.corrupt_pull = True
        with self.assertRaisesRegex(s.a.ReleaseError, "Delivered APK SHA-256"):
            self.execute()
        self.assertEqual(self.fake.packages, {s.RELEASE: "previous"})

    def test_wrong_installed_version_path_uid_and_user_refused(self):
        self.fake.packages[s.RELEASE] = "previous"
        path_command = ("shell", "pm", "path", "--user", "0", s.RELEASE)
        dump_command = ("shell", "dumpsys", "package", s.RELEASE)
        original = self.fake(["adb", "-H", "127.0.0.1", "-P", "5037", "-s", "emulator-5554", *dump_command], timeout=120, shell=False).stdout
        cases = [(path_command, "package:/data/app/name/base.apk\npackage:/data/app/name/split.apk"),
                 (path_command, "package:/sdcard/not-installed/base.apk"),
                 (dump_command, original.replace("versionCode=1", "versionCode=99")),
                 (dump_command, original.replace("userId=10123", "unknownId=10123")),
                 (dump_command, original.replace("installed=true", "installed=false")),
                 (dump_command, original.replace("codePath=/data/app/", "codePath=/other/"))]
        for command, output in cases:
            self.fake.overrides = {command: output}
            with self.subTest(output=output), self.assertRaises(s.a.ReleaseError):
                self.device.installed(self.infos["previous"], self.root / "never-pulled.apk", self.args.build_tools)
        self.assertFalse(any(call[0] == "pull" for call in self.fake.calls))

    def test_uid_or_first_install_time_change_fails_update(self):
        for field in ("uid", "firstInstallTime"):
            before = {"uid": 1001, "firstInstallTime": "2026-09-15 01:00:00"}
            after = {**before, field: 1002 if field == "uid" else "2026-09-15 01:01:00"}
            with self.subTest(field=field), self.assertRaises(s.a.ReleaseError):
                s.check_update(before, after, self.report)

    def test_missing_first_install_time_reports_limited_claim(self):
        self.fake.omit_time = True
        self.execute()
        self.assertTrue(any("not established" in text for text in self.report["limitations"]))
        self.assertFalse(any("firstInstallTime unchanged" in text for text in self.report["passedChecks"]))

    def test_debug_link_interception_fails_without_removing_either_app(self):
        self.fake.foreign_handler = True
        with self.assertRaisesRegex(s.a.ReleaseError, "ONLY release"):
            self.execute()
        self.assertEqual(set(self.fake.packages), {s.RELEASE, s.DEBUG})

    def test_launch_error_and_missing_process_fail(self):
        command = ("shell", "am", "start", "-W", "--user", "0", "-n", s.RELEASE + "/" + s.ACTIVITY)
        for override in ({command: "Status: ok\nActivity: com.splitfree/.MainActivity\nError: crashed"},
                         {("shell", "pidof", s.RELEASE): ""}):
            self.fake.overrides = override
            with self.subTest(override=override), self.assertRaises(s.a.ReleaseError):
                self.device.launch(s.RELEASE)

    def test_debug_launch_requires_activity_record(self):
        command = ("shell", "am", "start", "-W", "--user", "0", "-n", s.DEBUG + "/" + s.ACTIVITY)
        self.fake.overrides[command] = "Status: ok\n"
        with self.assertRaisesRegex(s.a.ReleaseError, "launch"):
            self.device.launch(s.DEBUG)

    def test_timeout_has_finite_bound_and_no_retry(self):
        self.fake.overrides[("get-state",)] = subprocess.TimeoutExpired("adb", 120)
        with self.assertRaisesRegex(s.a.ReleaseError, "timed out"):
            self.device.guard()
        self.assertEqual(self.fake.calls, [("get-state",)])

    def test_subprocess_error_includes_stdout_and_stderr(self):
        with patch.object(s.subprocess, "run", return_value=subprocess.CompletedProcess(["adb"], 1, "Failure details", "denied")):
            with self.assertRaisesRegex(s.a.ReleaseError, "Failure details denied"):
                s.run("adb")

    def test_failure_report_retained_and_failed_apps_not_deleted(self):
        self.fake.fail_install = "current"
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            self.assertEqual(s.main(self.argv()), 1)
        reports = list(self.root.glob("splitfree-install-smoke-*/report.json"))
        self.assertEqual(len(reports), 1)
        report = json.loads(reports[0].read_text())
        self.assertEqual(report["status"], "failed")
        self.assertIn("INSTALL_FAILED", report["error"])
        self.assertTrue((reports[0].parent / "previous.apk").is_file())
        self.assertEqual(self.fake.packages, {s.RELEASE: "previous"})

    def test_success_report_is_precise_and_local_adb_target_ignores_env(self):
        with patch.dict(os.environ, {"ADB_SERVER_SOCKET": "tcp:remote:5037", "ANDROID_SERIAL": "phone"}):
            with redirect_stdout(io.StringIO()):
                self.assertEqual(s.main(self.argv()), 0)
        report = json.loads(next(self.root.glob("splitfree-install-smoke-*/report.json")).read_text())
        self.assertEqual(report["status"], "passed")
        self.assertIn("No encrypted database", report["limitations"][0])
        for call in s.subprocess.run.call_args_list:
            self.assertNotIn("ADB_SERVER_SOCKET", call.kwargs["env"])
            self.assertNotIn("ANDROID_SERIAL", call.kwargs["env"])


class DebugContractTests(unittest.TestCase):
    def setUp(self):
        self.current = info("current", b"fixture")["manifest"]

    def test_debug_exact_metadata_allowed_without_production_policy_bypass(self):
        result = s.debug_manifest(debug_tree(), self.current)
        self.assertEqual(result["packageName"], s.DEBUG)
        self.assertTrue(result["debuggable"])
        self.assertFalse(result["testOnly"])

    def test_wrong_debug_metadata_labels_flags_links_and_splits_refused(self):
        cases = [("root", "package", '"com.splitfree"'), ("root", "android:versionCode", "1"),
                 ("root", "android:versionName", '"1.0.1"'), ("root", "android:sharedUserId", '"shared"'),
                 ("root", "android:versionCodeMajor", "1"), ("sdk", "android:minSdkVersion", "25"),
                 ("sdk", "android:targetSdkVersion", "36"), ("sdk", "android:maxSdkVersion", "37"),
                 ("app", "android:label", '"SplitFree"'), ("app", "android:debuggable", "false"),
                 ("app", "android:testOnly", "true"), ("app", "android:isSplitRequired", "true")]
        for scope, name, value in cases:
            tree = debug_tree()
            selected = tree if scope == "root" else tree["children"][0 if scope == "sdk" else 1]
            selected["attributes"][name] = value
            with self.subTest(scope=scope, name=name), self.assertRaises(s.a.ReleaseError):
                s.debug_manifest(tree, self.current)
        for extra in (node("action", {"android:name": '"android.intent.action.VIEW"'}), node("uses-split"), node("application")):
            tree = debug_tree()
            tree["children"][1]["children"].append(extra)
            with self.subTest(extra=extra), self.assertRaises(s.a.ReleaseError):
                s.debug_manifest(tree, self.current)


if __name__ == "__main__":
    unittest.main()
