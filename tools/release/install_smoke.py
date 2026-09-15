#!/usr/bin/env python3
"""Opt-in installation smoke on an explicitly selected, empty Android emulator only."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

import artifacts as a


RELEASE = "com.splitfree"
DEBUG = "com.splitfree.debug"
ACTIVITY = "com.splitfree.MainActivity"
LINK = "splitfree://join"
TIMEOUT = 120


def run(*command):
    environment = {key: value for key, value in os.environ.items()
                   if key not in {"ANDROID_SERIAL", "ADB_SERVER_SOCKET", "ANDROID_ADB_SERVER_PORT",
                                  "ADB_SERVER_PORT", "ANDROID_ADB_SERVER_ADDRESS"}}
    try:
        result = subprocess.run([str(arg) for arg in command], capture_output=True,
                                text=True, timeout=TIMEOUT, env=environment, shell=False)
    except subprocess.TimeoutExpired as error:
        raise a.ReleaseError(f"Command timed out after {TIMEOUT}s: {command[0]}; device state is unknown") from error
    a.require(result.returncode == 0, f"Command failed ({result.returncode}): {command!r}: "
              f"{result.stdout.strip()} {result.stderr.strip()}")
    return result.stdout


def manifest(apk, tools):
    snapshot = a.apk_snapshot(apk)
    xml = a.apk_tool(apk, snapshot, tools / "aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml")
    return a.manifest_tree(xml)


def one_child(root, name):
    nodes = [node for node in root["children"] if node["name"] == name]
    a.require(len(nodes) == 1, f"Expected one compiled {name}")
    return nodes[0]


def debug_manifest(root, current):
    app, sdk = one_child(root, "application"), one_child(root, "uses-sdk")
    identity = {"packageName": a.manifest_value(root, "package", str),
                "versionCode": a.manifest_value(root, "android:versionCode", int),
                "versionName": a.manifest_value(root, "android:versionName", str),
                "minSdk": a.manifest_value(sdk, "android:minSdkVersion", int),
                "targetSdk": a.manifest_value(sdk, "android:targetSdkVersion", int)}
    a.require(identity == {**{key: current[key] for key in identity}, "packageName": DEBUG,
                           "versionName": current["versionName"] + "-debug"},
              "Debug compiled package/version/SDK metadata does not match current release")
    a.require(a.manifest_value(app, "android:label", str) == "SplitFree Debug", "Debug label must be SplitFree Debug")
    a.require(re.fullmatch(r"(?:\(type 0x12\))?(?:true|0xffffffff)", app["attributes"].get("android:debuggable", "")),
              "Debug APK must be explicitly debuggable")
    if "android:versionCodeMajor" in root["attributes"]:
        a.require(a.manifest_value(root, "android:versionCodeMajor", int) == 0, "Debug versionCodeMajor must be zero")
    a.require("android:sharedUserId" not in root["attributes"] and "android:maxSdkVersion" not in sdk["attributes"],
              "Debug shared UID or maxSDK is forbidden")
    nodes, pending = [], [root]
    while pending:
        node = pending.pop()
        nodes.append(node)
        pending.extend(node["children"])
    for name in ("manifest", "application", "uses-sdk"):
        a.require(sum(node["name"] == name for node in nodes) == 1, f"Duplicate debug {name}")
    for node in nodes:
        for name in ("split", "configForSplit", "android:splitTypes", "android:requiredSplitTypes", "android:splitName"):
            a.require(name not in node["attributes"], f"Debug split marker forbidden: {name}")
        for name in ("android:testOnly", "android:isSplitRequired", "android:isFeatureSplit", "android:isolatedSplits"):
            if name in node["attributes"]:
                a.manifest_value(node, name, bool)
        a.require(node["name"] != "uses-split", "Debug must be standalone")
        if node["name"] == "meta-data":
            a.require(a.manifest_value(node, "android:name", str) not in
                      {"com.android.vending.splits", "com.android.vending.splits.required"}, "Debug split metadata forbidden")
        if node["name"] == "action":
            a.require(a.manifest_value(node, "android:name", str) != "android.intent.action.VIEW",
                      "Debug must not register external VIEW links")
    return {**identity, "debuggable": True, "testOnly": False, "standalone": True, "label": "SplitFree Debug"}


def inspect_debug(apk, tools, certificate, current):
    snapshot = a.apk_snapshot(apk)
    info = debug_manifest(manifest(apk, tools), current)
    a.require(a.read_apk_identity(apk, tools) == {key: info[key] for key in ("packageName", "versionCode", "versionName")},
              "Debug badging/compiled identity differs")
    entries = a.apk_entries(apk)
    a.apk_tool(apk, snapshot, tools / "zipalign", "-c", "-P", "16", "-v", "4")
    signature = a.apk_tool(apk, snapshot, tools / "apksigner", "verify", "--verbose", "--print-certs", "--min-sdk-version", "26")
    a.certificate_check(signature, certificate)
    schemes = a.signing_schemes(signature)
    a.require(a.apk_snapshot(apk) == snapshot, "Debug candidate changed during verification")
    return {"apkSha256": snapshot[1], "apkBytes": snapshot[2], "certificateSha256": certificate,
            "manifest": info, "abis": sorted(a.NATIVE_ABIS), "payloadEntries": len(entries), "signingSchemes": schemes}


def copy_candidate(source, destination):
    before = a.apk_snapshot(source)
    with Path(source).open("rb") as incoming, destination.open("xb") as output:
        shutil.copyfileobj(incoming, output)
    destination.chmod(0o400)
    a.require(a.apk_snapshot(source) == before and a.apk_snapshot(destination)[1:] == before[1:],
              "Input APK changed while copying to fixed candidate")
    return destination


def prepare_candidates(args, directory):
    candidates = {role: copy_candidate(getattr(args, role + "_apk"), directory / (role + ".apk"))
                  for role in ("previous", "current", "debug")}
    info = {}
    for role in ("previous", "current"):
        identity = a.read_apk_identity(candidates[role], args.build_tools)
        info[role] = a.inspect_signed_apk(candidates[role], identity, args.build_tools, args.certificate)
        app = one_child(manifest(candidates[role], args.build_tools), "application")
        a.require(a.manifest_value(app, "android:label", str) == "SplitFree", "Production label must be SplitFree")
    a.require(info["previous"]["certificateSha256"] == info["current"]["certificateSha256"] == args.certificate,
              "Previous/current must have the same pinned production signer")
    a.require(info["current"]["manifest"]["versionCode"] > info["previous"]["manifest"]["versionCode"],
              "Current versionCode must strictly increase; no downgrade or same-version replacement")
    info["debug"] = inspect_debug(candidates["debug"], args.build_tools, args.debug_certificate, info["current"]["manifest"])
    a.require(info["debug"]["certificateSha256"] == args.debug_certificate != args.certificate,
              "Debug certificate must be pinned and distinct from production")
    for role, path in candidates.items():
        a.require(a.apk_snapshot(path)[1:] == (info[role]["apkSha256"], info[role]["apkBytes"]), "Verified candidate bytes changed")
    return candidates, info


class Emulator:
    def __init__(self, adb, serial, report):
        self.adb, self.serial, self.report = adb, serial, report
        self.boot_id = None

    def call(self, *command):
        self.report["commands"].append([str(part) for part in command])
        return run(self.adb, "-H", "127.0.0.1", "-P", "5037", "-s", self.serial, *command)

    def guard(self):
        a.require(re.fullmatch(r"emulator-[0-9]+", self.serial), "Serial must explicitly match emulator-<port>")
        a.require(self.call("get-state").strip() == "device", "Emulator must be online and authorized")
        a.require(self.call("shell", "getprop", "ro.kernel.qemu").strip() == "1", "Refusing non-emulator: ro.kernel.qemu must be 1")
        a.require(self.call("shell", "getprop", "sys.boot_completed").strip() == "1", "Emulator must already be booted")
        a.require(self.call("shell", "am", "get-current-user").strip() == "0", "Only foreground owner user 0 is supported")
        boot = self.call("shell", "cat", "/proc/sys/kernel/random/boot_id").strip()
        a.require(re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", boot), "Missing emulator boot identity")
        a.require(self.boot_id is None or boot == self.boot_id, "Selected emulator changed boot identity; stop")
        self.boot_id = boot

    def inventory(self):
        text = self.call("shell", "pm", "list", "users")
        lines = [line.strip() for line in text.splitlines() if line.strip()]
        a.require(lines and lines[0] == "Users:", "Cannot enumerate all emulator users")
        matches = [re.fullmatch(r"UserInfo\{([0-9]+):[^\r\n]+\}(?: running)?", line) for line in lines[1:]]
        a.require(matches and all(matches), "Cannot parse all emulator users")
        users = [match[1] for match in matches]
        a.require("0" in users and len(users) == len(set(users)), "Invalid emulator user inventory")
        packages = set()
        for user in [None, *users]:
            command = ["shell", "pm", "list", "packages", "-u"]
            if user is not None:
                command.extend(["--user", user])
            output = self.call(*command)
            records = [line.strip() for line in output.splitlines() if line.strip()]
            a.require(records and all(re.fullmatch(r"package:[A-Za-z0-9_.]+", line) for line in records),
                      "Cannot reliably enumerate installed/retained packages")
            packages.update(line.removeprefix("package:") for line in records)
        a.require(not {RELEASE, DEBUG} & packages,
                  "Emulator contains com.splitfree or com.splitfree.debug (possibly retained data/another user); refusing without deletion")
        return users

    def preflight(self, infos):
        self.guard()
        users = self.inventory()
        sdk = self.call("shell", "getprop", "ro.build.version.sdk").strip()
        abis = self.call("shell", "getprop", "ro.product.cpu.abilist").strip().split(",")
        page = self.call("shell", "getconf", "PAGE_SIZE").strip()
        a.require(re.fullmatch(r"[0-9]+", sdk) and int(sdk) >= max(info["manifest"]["minSdk"] for info in infos.values()),
                  "Emulator SDK is below APK minimum or unknown")
        a.require(abis and all(re.fullmatch(r"[A-Za-z0-9_-]+", abi) for abi in abis), "Invalid emulator ABI list")
        a.require(all(set(abis) & set(info["abis"]) for info in infos.values()), "Emulator ABI is not in every APK")
        a.require(page in {"4096", "16384"}, "Unsupported or unknown emulator page size")
        return {"serial": self.serial, "bootId": self.boot_id, "users": users, "sdk": int(sdk),
                "abis": abis, "pageSizeBytes": int(page),
                "fingerprint": self.call("shell", "getprop", "ro.build.fingerprint").strip()}

    def install(self, candidate, expected, replace=False):
        self.guard()
        snapshot = a.apk_snapshot(candidate)
        a.require(snapshot[1:] == (expected["apkSha256"], expected["apkBytes"]), "Candidate no longer matches verified APK")
        output = self.call("install", *(["-r"] if replace else []), "--user", "0", candidate)
        a.require(a.apk_snapshot(candidate) == snapshot, "Candidate changed during install; device state is unknown")
        lines = [line.strip() for line in output.splitlines() if line.strip()]
        a.require(lines in (["Success"], ["Performing Streamed Install", "Success"], ["Performing Push Install", "Success"]),
                  f"ADB install did not return exact Success: {output.strip()}")

    def installed(self, expected, destination, tools):
        package = expected["manifest"]["packageName"]
        paths = self.call("shell", "pm", "path", "--user", "0", package).strip().splitlines()
        a.require(len(paths) == 1 and re.fullmatch(r"package:/data/app/[A-Za-z0-9_./=+~-]+/base\.apk", paths[0])
                  and ".." not in Path(paths[0][8:]).parts, "Expected one installed standalone /data/app base.apk")
        path = paths[0][8:]
        dump = self.call("shell", "dumpsys", "package", package)
        headers = list(re.finditer(r"^( +)Package \[" + re.escape(package) + r"\] \([^\n]+\):$", dump, re.M))
        a.require(len(headers) == 1, "Cannot identify one active installed package record")
        header = headers[0]
        block_lines = []
        for line in dump[header.end():].splitlines():
            if line.strip() and len(line) - len(line.lstrip()) <= len(header[1]):
                break
            block_lines.append(line)
        block = "\n".join(block_lines)
        versions = re.findall(r"^\s+versionCode=([0-9]+)(?:\s[^\n]*)?$", block, re.M)
        names = re.findall(r"^\s+versionName=(\S+)\s*$", block, re.M)
        uids = re.findall(r"^\s+userId=([0-9]+)\s*$", block, re.M)
        codes = re.findall(r"^\s+codePath=(\S+)\s*$", block, re.M)
        first = sorted(set(re.findall(r"^\s+firstInstallTime=(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)\s*$", block, re.M)))
        a.require(versions == [str(expected["manifest"]["versionCode"])] and names == [expected["manifest"]["versionName"]],
                  "Installed package version differs from verified APK")
        a.require(len(uids) == 1 and int(uids[0]) > 0 and codes == [str(Path(path).parent)], "Installed UID/codePath unavailable or ambiguous")
        a.require(re.search(r"^\s+User 0:.*\binstalled=true\b", block, re.M), "Package not installed for user 0")
        self.call("pull", path, destination)
        a.require(a.apk_snapshot(destination)[1:] == (expected["apkSha256"], expected["apkBytes"]),
                  "Delivered APK SHA-256/size differs from exact verified candidate")
        identity = a.read_apk_identity(destination, tools)
        a.require(identity == {key: expected["manifest"][key] for key in ("packageName", "versionCode", "versionName")},
                  "Pulled APK compiled identity differs")
        return {"packageName": package, "versionCode": int(versions[0]), "versionName": names[0], "uid": int(uids[0]),
                "firstInstallTime": first[0] if len(first) == 1 else None, "path": path, "apkSha256": expected["apkSha256"]}

    def launch(self, package):
        self.guard()
        output = self.call("shell", "am", "start", "-W", "--user", "0", "-n", package + "/" + ACTIVITY)
        components = [[package + "/" + ACTIVITY]]
        if package == RELEASE:
            components.append([package + "/.MainActivity"])
        a.require(re.findall(r"^Status: (\S+)\s*$", output, re.M) == ["ok"]
                  and re.findall(r"^Activity: (\S+)\s*$", output, re.M) in components
                  and not re.search(r"\b(?:Error|Exception|Failure)\b", output), "Explicit activity launch was not successful")
        pid = self.call("shell", "pidof", package).strip()
        a.require(re.fullmatch(r"[1-9][0-9]*(?: [1-9][0-9]*)*", pid), "App process was not present immediately after launch")

    def links(self):
        intent = ["--user", "0", "-a", "android.intent.action.VIEW", "-c", "android.intent.category.BROWSABLE", "-d", LINK]
        handlers = self.call("shell", "cmd", "package", "query-activities", "--brief", "--components", *intent).strip().splitlines()
        resolved = self.call("shell", "cmd", "package", "resolve-activity", "--brief", "--components", *intent).strip().splitlines()
        component = RELEASE + "/" + ACTIVITY
        allowed = ([component], [RELEASE + "/.MainActivity"])
        a.require(handlers in allowed and resolved in allowed, "Production splitfree link must match and resolve ONLY release")
        return {"uri": LINK, "handlers": handlers, "resolved": resolved[0]}


def check_update(before, after, report):
    a.require(before["uid"] == after["uid"], "Production UID changed across update")
    report["passedChecks"].append("production UID unchanged across update")
    if before["firstInstallTime"] is not None and after["firstInstallTime"] is not None:
        a.require(before["firstInstallTime"] == after["firstInstallTime"], "Production firstInstallTime changed across update")
        report["passedChecks"].append("production firstInstallTime unchanged across update")
    else:
        report["limitations"].append("firstInstallTime continuity not established: absent/ambiguous platform field")


def execute(args, directory, report):
    candidates, infos = prepare_candidates(args, directory)
    report["artifacts"] = infos
    report["passedChecks"].append("fixed candidate bytes, compiled identity/SDK, pinned signatures, strict version increase and debug isolation verified before device mutation")
    device = Emulator(args.sdk / "platform-tools" / "adb", args.serial, report)
    report["device"] = device.preflight(infos)
    report["passedChecks"].append("explicit emulator serial, qemu=1, owner user, every user and retained-package inventory empty of SplitFree")
    device.inventory()
    device.install(candidates["previous"], infos["previous"])
    before = device.installed(infos["previous"], directory / "installed-previous.apk", args.build_tools)
    report["previousInstall"] = before
    device.launch(RELEASE)
    report["passedChecks"].append("previous release fresh install, delivered APK hash/identity and immediate explicit launch")
    device.installed(infos["previous"], directory / "before-update.apk", args.build_tools)
    device.install(candidates["current"], infos["current"], replace=True)
    after = device.installed(infos["current"], directory / "installed-current.apk", args.build_tools)
    report["currentInstall"] = after
    check_update(before, after, report)
    device.launch(RELEASE)
    report["passedChecks"].append("current release same-key update, delivered APK hash/identity and immediate explicit launch")
    device.install(candidates["debug"], infos["debug"])
    debug = device.installed(infos["debug"], directory / "installed-debug.apk", args.build_tools)
    report["debugInstall"] = debug
    a.require(debug["uid"] != after["uid"], "Debug and production must have distinct UIDs")
    device.launch(DEBUG)
    production = device.installed(infos["current"], directory / "coexisting-current.apk", args.build_tools)
    check_update(after, production, report)
    report["links"] = device.links()
    report["passedChecks"].append("debug coexists under separate ID/UID; production bytes unchanged; production link only resolves release")
    report["status"] = "passed"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit already-running emulator-<port> serial")
    parser.add_argument("--allow-emulator-install", action="store_true", help="Consent to persistent installs on an empty emulator, never a phone")
    for name in ("previous-apk", "current-apk", "debug-apk", "sdk", "build-tools", "work-directory"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--certificate", required=True, help="Trusted production certificate SHA-256 (64 hex)")
    parser.add_argument("--debug-certificate", required=True, help="Trusted distinct nonproduction certificate SHA-256 (64 hex)")
    args = parser.parse_args(argv)
    directory = None
    report = {"schemaVersion": 1, "status": "failed", "passedChecks": [], "commands": [],
              "limitations": ["No encrypted database, account/key identity, migration correctness or user-data preservation claim.",
                              "Immediate launch/process presence is not a crash-free or sustained runtime test.",
                              "This is one emulator configuration, not the complete API26 and API36/16KB matrix.",
                              "No emulated installs are removed automatically, including on failure."]}
    try:
        a.require(args.allow_emulator_install, "Explicit --allow-emulator-install is required; no device commands executed")
        a.require(re.fullmatch(r"emulator-[0-9]+", args.serial), "Serial must explicitly match emulator-<port>; physical devices refused")
        args.certificate = a.normalized_certificate(args.certificate)
        args.debug_certificate = a.normalized_certificate(args.debug_certificate)
        a.require(args.certificate != args.debug_certificate, "Debug and production certificate pins must differ")
        a.require(args.work_directory.is_dir() and not args.work_directory.is_symlink(), "Work directory must be an existing non-symlink directory")
        for tool in (args.sdk / "platform-tools" / "adb", *(args.build_tools / name for name in ("aapt2", "apksigner", "zipalign"))):
            a.require(tool.is_file() and os.access(tool, os.X_OK), f"Missing executable SDK tool: {tool}")
        args.sdk, args.build_tools = args.sdk.resolve(), args.build_tools.resolve()
        directory = Path(tempfile.mkdtemp(prefix="splitfree-install-smoke-", dir=args.work_directory.resolve()))
        execute(args, directory, report)
    except (a.ReleaseError, OSError, UnicodeError, subprocess.TimeoutExpired) as error:
        report["error"] = str(error)
        print(f"Installation smoke refused/failed: {error}. No deletion or automatic rollback attempted.", file=sys.stderr)
    finally:
        if directory is not None:
            with (directory / "report.json").open("x") as output:
                json.dump(report, output, sort_keys=True, indent=2)
                output.write("\n")
            print(f"Evidence retained: {directory / 'report.json'}")
    if report["status"] == "passed":
        print("PASS: exact-file emulator install/update/coexistence checks only; data preservation and full device matrix remain separate acceptance.")
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
