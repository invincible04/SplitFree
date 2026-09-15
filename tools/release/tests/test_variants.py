"""Variant policy checks, including opt-in checks against freshly built APKs.

After assembling both variants, run this module with SPLITFREE_VARIANT_BUILD_DIR
pointing at app/build and AAPT2 pointing at the SDK build-tools aapt2 executable.
The artifact checks never invoke Gradle, sign an APK, or access a device.
"""
import copy
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"
sys.path.insert(0, str(ROOT / "tools/release"))
import dex_contract
SPEC = importlib.util.spec_from_file_location("variant_artifacts", ROOT / "tools/release/artifacts.py")
artifacts = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(artifacts)
IDENTITY = artifacts.gradle_identity((ROOT / "app/build.gradle.kts").read_text())
VERSION_CODE = int(IDENTITY["versionCode"])
VERSION_NAME = IDENTITY["versionName"]
VARIANTS = {
    "release": ("com.splitfree", VERSION_NAME, "SplitFree"),
    "debug": ("com.splitfree.debug", VERSION_NAME + "-debug", "SplitFree Debug"),
}


def check_registrar_metadata(test, manifest):
    services = [node for node in manifest.findall("application/service")
                if node.get(ANDROID + "name") == "com.google.mlkit.common.internal.MlKitComponentDiscoveryService"]
    test.assertEqual(len(services), 1, "Expected exactly one ML Kit component discovery service")
    for registrar in dex_contract.REGISTRARS:
        entries = [node for node in services[0].findall("meta-data")
                   if node.get(ANDROID + "name") == "com.google.firebase.components:" + registrar]
        test.assertEqual(len(entries), 1, f"Missing/duplicate registrar metadata: {registrar}")
        test.assertEqual(entries[0].get(ANDROID + "value"), "com.google.firebase.components.ComponentRegistrar")


def tree_element(node):
    element = ET.Element(node["name"])
    booleans = {"android:debuggable", "android:testOnly", "android:allowBackup", "android:exported"}
    integers = {"android:versionCode", "android:minSdkVersion", "android:targetSdkVersion", "android:launchMode"}
    for name, raw in node["attributes"].items():
        key = ANDROID + name[len("android:"):] if name.startswith("android:") else name
        if raw.startswith('"'):
            value = artifacts.manifest_value(node, name, str)
        elif name in booleans:
            raw = re.sub(r"^\(type 0x12\)", "", raw)
            artifacts.require(raw in {"true", "false", "0", "1", "0x0", "0x1", "0x00000000", "0xffffffff"},
                              f"Invalid boolean {name}")
            value = "false" if raw in {"false", "0", "0x0", "0x00000000"} else "true"
        elif name in integers:
            value = str(artifacts.manifest_value(node, name, int))
            if name == "android:launchMode" and value == "2":
                value = "singleTask"
        else:
            value = raw
        element.set(key, value)
    element.extend(tree_element(child) for child in node["children"])
    return element


def names(element, tag):
    return {node.get(ANDROID + "name") for node in element.findall(tag)}


def check_manifest(case, manifest, variant):
    package, version, label = VARIANTS[variant]
    case.assertEqual(manifest.tag, "manifest")
    case.assertEqual(manifest.get("package"), package)
    case.assertEqual(manifest.get(ANDROID + "versionCode"), str(VERSION_CODE))
    case.assertEqual(manifest.get(ANDROID + "versionName"), version)
    case.assertEqual(len(manifest.findall("uses-sdk")), 1)
    sdk = manifest.find("uses-sdk")
    case.assertEqual(sdk.get(ANDROID + "minSdkVersion"), "26")
    case.assertEqual(sdk.get(ANDROID + "targetSdkVersion"), "37")
    case.assertIsNone(sdk.get(ANDROID + "maxSdkVersion"))
    case.assertEqual(len(manifest.findall("application")), 1)
    application = manifest.find("application")
    case.assertEqual(application.get(ANDROID + "name"), "com.splitfree.SplitFreeApp")
    case.assertEqual(application.get(ANDROID + "label"), label)
    case.assertEqual(application.get(ANDROID + "debuggable", "false"), str(variant == "debug").lower())
    case.assertEqual(application.get(ANDROID + "testOnly", "false"), "false")
    case.assertEqual(application.get(ANDROID + "allowBackup"), "false")
    case.assertFalse(any(key.startswith(TOOLS) for node in manifest.iter() for key in node.attrib))
    activities = application.findall("activity") + application.findall("activity-alias")
    main = [node for node in activities if node.get(ANDROID + "name") == "com.splitfree.MainActivity"]
    case.assertEqual(len(main), 1)
    case.assertEqual(main[0].get(ANDROID + "exported"), "true")
    case.assertEqual(main[0].get(ANDROID + "launchMode"), "singleTask")
    filters = main[0].findall("intent-filter")
    launchers = [node for node in filters if "android.intent.action.MAIN" in names(node, "action")
                 and "android.intent.category.LAUNCHER" in names(node, "category")]
    case.assertEqual(len(launchers), 1)
    case.assertEqual(len(filters), 1 if variant == "debug" else 2)
    if variant == "debug":
        for activity in activities:
            for node in activity.findall("intent-filter"):
                case.assertNotIn("android.intent.action.VIEW", names(node, "action"))
                case.assertNotIn("android.intent.category.BROWSABLE", names(node, "category"))
    else:
        invites = [node for node in filters if "android.intent.action.VIEW" in names(node, "action")]
        case.assertEqual(len(invites), 1)
        case.assertEqual(names(invites[0], "category"), {
            "android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"})
        case.assertEqual([node.attrib for node in invites[0].findall("data")], [{
            ANDROID + "scheme": "splitfree", ANDROID + "host": "join"}])
    authorities = []
    for provider in application.findall("provider"):
        values = provider.get(ANDROID + "authorities", "").split(";")
        case.assertTrue(all(value.startswith(package + ".") for value in values))
        authorities.extend(values)
    case.assertIn(package + ".androidx-startup", authorities)
    case.assertEqual(len(authorities), len(set(authorities)))
    return set(authorities)


def check_build_config(case, text, variant):
    package, version, _ = VARIANTS[variant]
    case.assertRegex(text, r"(?m)^package com\.splitfree;$")
    for name, value in {"APPLICATION_ID": package, "BUILD_TYPE": variant, "VERSION_NAME": version}.items():
        case.assertEqual(re.findall(r"public static final String " + name + r' = "([^"]+)";', text), [value])
    case.assertEqual(re.findall(r"public static final int VERSION_CODE = ([0-9]+);", text), [str(VERSION_CODE)])
    expected = 'Boolean.parseBoolean("true")' if variant == "debug" else "false"
    case.assertEqual(re.findall(r"public static final boolean DEBUG = (.*);", text), [expected])


def manifest_fixture(variant):
    package, version, label = VARIANTS[variant]
    invite = '''<intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="splitfree" android:host="join" />
    </intent-filter>''' if variant == "release" else ""
    return ET.fromstring(f'''<manifest xmlns:android="{ANDROID[1:-1]}" package="{package}"
        android:versionCode="{VERSION_CODE}" android:versionName="{version}">
        <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="37" />
        <application android:name="com.splitfree.SplitFreeApp" android:label="{label}"
            android:debuggable="{str(variant == 'debug').lower()}" android:allowBackup="false">
            <activity android:name="com.splitfree.MainActivity" android:exported="true" android:launchMode="singleTask">
                <intent-filter>
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                </intent-filter>
                {invite}
            </activity>
            <provider android:name="androidx.startup.InitializationProvider"
                android:authorities="{package}.androidx-startup" android:exported="false" />
        </application>
    </manifest>''')


class VariantConfigurationTests(unittest.TestCase):
    def test_compiled_registrar_metadata_contract_rejects_missing_duplicate_and_wrong_values(self):
        manifest = manifest_fixture("debug")
        service = ET.SubElement(manifest.find("application"), "service", {
            ANDROID + "name": "com.google.mlkit.common.internal.MlKitComponentDiscoveryService"})
        for registrar in dex_contract.REGISTRARS:
            ET.SubElement(service, "meta-data", {
                ANDROID + "name": "com.google.firebase.components:" + registrar,
                ANDROID + "value": "com.google.firebase.components.ComponentRegistrar"})
        check_registrar_metadata(self, manifest)
        for mode in ("missing-service", "duplicate-service", "missing", "duplicate", "wrong-name", "wrong-value"):
            with self.subTest(mode=mode), self.assertRaises(AssertionError):
                changed = copy.deepcopy(manifest)
                application = changed.find("application")
                target = application.find("service")
                if mode == "missing-service":
                    application.remove(target)
                elif mode == "duplicate-service":
                    application.append(copy.deepcopy(target))
                elif mode == "missing":
                    target.remove(target[0])
                elif mode == "duplicate":
                    target.append(copy.deepcopy(target[0]))
                else:
                    target[0].set(ANDROID + ("name" if mode == "wrong-name" else "value"), "wrong")
                check_registrar_metadata(self, changed)

    def test_production_metadata_stays_compatible_with_release_validator(self):
        source = (ROOT / "app/build.gradle.kts").read_text()
        identity = artifacts.gradle_identity(source)
        self.assertEqual(identity["applicationId"], "com.splitfree")
        self.assertGreaterEqual(int(identity["versionCode"]), 2)
        self.assertEqual(identity["minSdk"], "26")
        self.assertEqual(re.findall(r"(?m)^\s*targetSdk = (\d+)\s*$", source), ["37"])
        self.assertEqual(re.findall(r'(?m)^\s*namespace = "([^"]+)"\s*$', source), ["com.splitfree"])
        debug = re.search(r"\bdebug\s*\{([^{}]*)\}", source).group(1)
        self.assertIn('applicationIdSuffix = ".debug"', debug)
        self.assertIn('versionNameSuffix = "-debug"', debug)
        self.assertEqual(source.count("applicationIdSuffix"), 1)
        self.assertEqual(source.count("versionNameSuffix"), 1)

    def test_release_policy_tracks_display_version_without_approving_publication(self):
        policy = json.loads((ROOT / "release/policy.json").read_text())
        self.assertEqual(policy["versionName"], VERSION_NAME)
        self.assertIsInstance(policy["approved"], bool)
        if not policy["approved"]:
            with self.assertRaises(artifacts.ReleaseError):
                artifacts.policy_check(policy, b"Fixture notices\n", VERSION_NAME)

    def test_debug_overlay_removes_inherited_filters_and_restores_only_launcher(self):
        main = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        overlay = ET.parse(ROOT / "app/src/debug/AndroidManifest.xml").getroot()
        application = overlay.find("application")
        self.assertEqual(application.get(ANDROID + "label"), "SplitFree Debug")
        self.assertEqual(application.get(TOOLS + "replace"), "android:label")
        self.assertEqual(main.find("application").get(ANDROID + "label"), "SplitFree")
        self.assertEqual(len(application), 1)
        activity = application.find("activity")
        self.assertEqual(activity.attrib, {ANDROID + "name": "com.splitfree.MainActivity", ANDROID + "exported": "true"})
        filters = activity.findall("intent-filter")
        self.assertEqual(len(filters), 2)
        self.assertEqual(filters[0].attrib, {TOOLS + "node": "removeAll"})
        self.assertEqual(len(filters[0]), 0)
        self.assertEqual(names(filters[1], "action"), {"android.intent.action.MAIN"})
        self.assertEqual(names(filters[1], "category"), {"android.intent.category.LAUNCHER"})
        self.assertEqual(filters[1].findall("data"), [])
        self.assertEqual(main.find("application/provider").get(ANDROID + "authorities"),
                         "${applicationId}.androidx-startup")


class VariantPolicyTests(unittest.TestCase):
    def test_both_manifest_fixtures_pass_with_disjoint_authorities(self):
        debug = check_manifest(self, manifest_fixture("debug"), "debug")
        release = check_manifest(self, manifest_fixture("release"), "release")
        self.assertTrue(debug.isdisjoint(release))

    def test_wrong_identity_labels_sdk_and_runtime_classes_are_rejected(self):
        mutations = [(".", "package", "com.splitfree"), (".", ANDROID + "versionCode", "1"),
                     (".", ANDROID + "versionName", VERSION_NAME),
                     ("uses-sdk", ANDROID + "minSdkVersion", "27"),
                     ("uses-sdk", ANDROID + "targetSdkVersion", "Baklava"),
                     ("uses-sdk", ANDROID + "maxSdkVersion", "37"),
                     ("application", ANDROID + "label", "SplitFree"),
                     ("application", ANDROID + "debuggable", "false"),
                     ("application", ANDROID + "testOnly", "true"),
                     ("application", ANDROID + "name", "com.splitfree.debug.SplitFreeApp"),
                     ("application/activity", ANDROID + "name", "com.splitfree.debug.MainActivity"),
                     ("application/activity", ANDROID + "exported", "false"),
                     ("application/activity", ANDROID + "launchMode", "standard"),
                     ("application/provider", ANDROID + "authorities", "com.splitfree.androidx-startup")]
        for path, key, value in mutations:
            with self.subTest(path=path, key=key, value=value):
                manifest = manifest_fixture("debug")
                manifest.find(path).set(key, value)
                with self.assertRaises(AssertionError):
                    check_manifest(self, manifest, "debug")

    def test_debug_external_handler_on_activity_or_alias_is_rejected(self):
        for tag in ("activity", "activity-alias"):
            with self.subTest(tag=tag):
                manifest = manifest_fixture("debug")
                activity = ET.SubElement(manifest.find("application"), tag, {
                    ANDROID + "name": "com.splitfree.InviteAlias", ANDROID + "exported": "true"})
                activity.append(copy.deepcopy(manifest_fixture("release").findall("application/activity/intent-filter")[1]))
                with self.assertRaises(AssertionError):
                    check_manifest(self, manifest, "debug")

    def test_missing_or_duplicated_launcher_and_lost_release_invite_are_rejected(self):
        for variant, mode in (("debug", "missing"), ("debug", "duplicate"), ("release", "invite")):
            with self.subTest(variant=variant, mode=mode):
                manifest = manifest_fixture(variant)
                activity = manifest.find("application/activity")
                filters = activity.findall("intent-filter")
                if mode == "duplicate":
                    activity.append(copy.deepcopy(filters[0]))
                else:
                    activity.remove(filters[-1] if mode == "invite" else filters[0])
                with self.assertRaises(AssertionError):
                    check_manifest(self, manifest, variant)

    def test_aapt2_tree_adapter_preserves_scopes_and_typed_attributes(self):
        for variant in VARIANTS:
            manifest = manifest_fixture(variant)
            def dump(element, depth=0):
                lines = ["  " * depth + f"E: {element.tag}"]
                for key, value in element.attrib.items():
                    name = key.replace(ANDROID, "android:")
                    if value in {"true", "false"}:
                        raw = "(type 0x12)" + ("0xffffffff" if value == "true" else "0x00000000")
                    elif name == "android:launchMode":
                        raw = "(type 0x10)0x00000002"
                    elif name in {"android:versionCode", "android:minSdkVersion", "android:targetSdkVersion"}:
                        raw = "(type 0x10)" + hex(int(value))
                    else:
                        raw = json.dumps(value)
                    lines.append("  " * (depth + 1) + f"A: {name}={raw}")
                for child in element:
                    lines.extend(dump(child, depth + 1))
                return lines
            tree = artifacts.manifest_tree("\n".join(dump(manifest)))
            check_manifest(self, tree_element(tree), variant)
            application = next(node for node in tree["children"] if node["name"] == "application")
            for raw in ('"true"', "(type 0x12)0xffffffff", "true"):
                application["attributes"]["android:testOnly"] = raw
                with self.subTest(variant=variant, raw=raw), self.assertRaises(AssertionError):
                    check_manifest(self, tree_element(tree), variant)

    def test_build_config_checks_all_generated_identity_fields(self):
        for variant, (package, version, _) in VARIANTS.items():
            debug = 'Boolean.parseBoolean("true")' if variant == "debug" else "false"
            text = f'''package com.splitfree;
public final class BuildConfig {{
  public static final boolean DEBUG = {debug};
  public static final String APPLICATION_ID = "{package}";
  public static final String BUILD_TYPE = "{variant}";
  public static final int VERSION_CODE = {VERSION_CODE};
  public static final String VERSION_NAME = "{version}";
}}'''
            check_build_config(self, text, variant)
            for field in ("APPLICATION_ID", "BUILD_TYPE", "VERSION_CODE", "VERSION_NAME", "DEBUG"):
                with self.subTest(variant=variant, field=field), self.assertRaises(AssertionError):
                    check_build_config(self, text.replace(field, "WRONG_FIELD"), variant)


@unittest.skipUnless(os.environ.get("SPLITFREE_VARIANT_BUILD_DIR"),
                     "Set SPLITFREE_VARIANT_BUILD_DIR after assembling both variants")
class BuiltVariantTests(unittest.TestCase):
    def test_apks_metadata_and_generated_build_config_agree(self):
        build = Path(os.environ["SPLITFREE_VARIANT_BUILD_DIR"])
        aapt2 = os.environ.get("AAPT2")
        self.assertTrue(aapt2, "AAPT2 must name the SDK build-tools aapt2 executable")
        authorities = {}
        for variant, (package, version, _) in VARIANTS.items():
            with self.subTest(variant=variant):
                directory = build / "outputs/apk" / variant
                metadata = json.loads((directory / "output-metadata.json").read_text())
                self.assertEqual(metadata["applicationId"], package)
                self.assertEqual(metadata["variantName"], variant)
                self.assertEqual(len(metadata["elements"]), 1)
                element = metadata["elements"][0]
                self.assertEqual(element["type"], "SINGLE")
                self.assertEqual(element["filters"], [])
                self.assertEqual(element["versionCode"], VERSION_CODE)
                self.assertEqual(element["versionName"], version)
                apk = directory / element["outputFile"]
                self.assertTrue(apk.is_file(), str(apk))
                result = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)],
                                        capture_output=True, text=True, check=True, timeout=60)
                manifest = tree_element(artifacts.manifest_tree(result.stdout))
                authorities[variant] = check_manifest(self, manifest, variant)
                check_registrar_metadata(self, manifest)
                self.assertEqual(set(dex_contract.check_apk(apk)), set(dex_contract.REGISTRARS))
                config = build / "generated/source/buildConfig" / variant / "com/splitfree/BuildConfig.java"
                check_build_config(self, config.read_text(), variant)
        self.assertEqual(set(authorities), set(VARIANTS))
        self.assertTrue(authorities["debug"].isdisjoint(authorities["release"]))


if __name__ == "__main__":
    unittest.main()
