import argparse
import contextlib
import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
SPEC = importlib.util.spec_from_file_location("artifacts", Path(__file__).parents[1] / "artifacts.py")
a = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(a)
META = dict(schemaVersion=1, tag="v1.0.0", commit="a" * 40, versionName="1.0.0", versionCode=1, packageName="com.splitfree")
CERT = "b" * 64
GRADLE = b'applicationId = "com.splitfree"\nminSdk = 26\nversionCode = 1\nversionName = "1.0.0"\n'
BADGING = "package: name='com.splitfree' versionCode='1' versionName='1.0.0'\nminSdkVersion:'26'\ntargetSdkVersion:'37'\n"
SIGNATURE = (f"Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: {CERT}\n"
             "Verified using v1 scheme (JAR signing): false\n"
             "Verified using v2 scheme (APK Signature Scheme v2): true\n"
             "Verified using v3 scheme (APK Signature Scheme v3): false\n")
XML = '''E: manifest (line=2)
  A: package="com.splitfree" (Raw: "com.splitfree")
  A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=1
  A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="1.0.0" (Raw: "1.0.0")
  E: uses-sdk (line=7)
    A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=26
    A: http://schemas.android.com/apk/res/android:targetSdkVersion(0x01010270)=37
  E: application (line=39)
'''
LIB = "lib/arm64-v8a/libfixture.so"


def elf(alignment=16384, offset=0, address=0, endian="<", abi="arm64-v8a"):
    elf_class, machine = a.NATIVE_ABIS[abi]
    header_size, program_size = (64, 56) if elf_class == 2 else (52, 32)
    data = bytearray(header_size + program_size)
    data[:7] = b"\x7fELF" + bytes([elf_class, 1 if endian == "<" else 2, 1])
    struct.pack_into(endian + "HHI", data, 16, 3, machine, 1)
    struct.pack_into(endian + ("Q" if elf_class == 2 else "I"), data, 32 if elf_class == 2 else 28, header_size)
    struct.pack_into(endian + "HHH", data, 52 if elf_class == 2 else 40, header_size, program_size, 1)
    if elf_class == 2:
        struct.pack_into(endian + "IIQQQQQQ", data, header_size, 1, 5, offset, address, 0, 0, len(data), alignment)
    else:
        struct.pack_into(endian + "IIIIIIII", data, header_size, 1, offset, address, 0, 0, len(data), 5, alignment)
    return bytes(data)


def apk(path, native=None, aligned=True, entries=None):
    natives = {f"lib/{abi}/libfixture.so": elf(abi=abi) for abi in a.NATIVE_ABIS}
    if isinstance(native, dict):
        natives = native
    elif native is not None:
        natives[LIB] = native
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in natives.items():
            entry = zipfile.ZipInfo(name)
            if aligned:
                padding = -(archive.fp.tell() + 30 + len(name)) % 16384
                if padding < 4:
                    padding += 16384
                entry.extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4)
            archive.writestr(entry, data)
        archive.writestr("AndroidManifest.xml", b"fixture manifest")
        for name, data in (entries or {"classes.dex": b"fixture dex"}).items():
            archive.writestr(name, data)


def manifest_attribute(scope, name, value):
    indent = "  " if scope == "manifest" else "    "
    marker = f"E: {scope} (line="
    lines = XML.splitlines(keepends=True)
    index = next(index for index, line in enumerate(lines) if marker in line)
    lines.insert(index + 1, f"{indent}A: {name}={value}\n")
    return "".join(lines)


def archive_fixture(files, prefix="SplitFree-v1.0.0/", mode="100644"):
    buffer = io.BytesIO()
    tree = {}
    with tarfile.open(fileobj=buffer, mode="w") as archive:
        for name, data in files.items():
            member = tarfile.TarInfo(prefix + name)
            member.size = len(data)
            member.mode = int(mode, 8) & 0o777
            archive.addfile(member, io.BytesIO(data))
            digest = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
            tree[name] = (mode, digest)
    return buffer.getvalue(), tree


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.meta = self.root / "metadata.json"
        self.meta.write_bytes(a.json_bytes(META))
        self.unsigned = self.root / "input.apk"
        apk(self.unsigned, native=elf())
        self.policy = dict(schemaVersion=1, approved=True, versionName="1.0.0", noticesSha256=a.sha256(b"Fixture reviewed notices\n"))
        self.files = {"LICENSE": b"Fixture license\n", a.NOTICES: b"Fixture reviewed notices\n",
                      "release/policy.json": a.json_bytes(self.policy), "app/build.gradle.kts": GRADLE,
                      a.SIGNING_CERTIFICATE: (CERT + "\n").encode()}
        self.archive, self.tree = archive_fixture(self.files)
        self.calls = []

    def runner(self, *command):
        self.calls.append(tuple(map(str, command)))
        if command[:2] == ("git", "ls-tree"):
            return b"".join(f"{mode} blob {digest} {len(self.files.get(name, b''))}\t{name}\0".encode() for name, (mode, digest) in self.tree.items())
        if command[:2] == ("git", "archive"):
            self.assertEqual(command[2:], ("--format=tar", "--prefix=SplitFree-v1.0.0/", META["commit"]))
            return self.archive
        if command[:2] == ("git", "show"):
            return self.files[command[2].split(":", 1)[1]]
        if command[:2] == ("git", "rev-parse"):
            return META["commit"].encode() + b"\n"
        if command[:2] in (("git", "status"), ("git", "merge-base")):
            return b""
        tool = Path(command[0]).name
        if tool == "aapt2":
            return (BADGING if command[2] == "badging" else XML).encode()
        if tool == "zipalign":
            self.assertEqual(command[1:6], ("-c", "-P", "16", "-v", "4"))
            return b"Verification successful\n"
        if tool == "apksigner":
            self.assertEqual(command[1:4], ("verify", "--verbose", "--print-certs"))
            self.assertEqual(command[4:6], ("--min-sdk-version", "26"))
            return SIGNATURE.encode()
        self.fail(f"Unexpected command {command}")

    def args(self, **extra):
        return argparse.Namespace(metadata=self.meta, apk=self.unsigned, output=self.root / "package", build_tools=self.root / "tools", **extra)

    def prepared(self):
        with patch.object(a, "run", side_effect=self.runner):
            a.prepare(self.args())
        return self.root / "package"

    def final_args(self):
        package = self.prepared()
        signed = self.root / "signed.apk"
        signed.write_bytes(self.unsigned.read_bytes())
        with zipfile.ZipFile(signed, "a") as archive:
            archive.writestr("META-INF/CERT.SF", b"fixture signature file")
            archive.writestr("META-INF/CERT.RSA", b"fixture signature block")
            archive.writestr("META-INF/MANIFEST.MF", b"fixture v1 manifest")
        return argparse.Namespace(metadata=self.meta, package=package, apk=signed, output=self.root / "final", build_tools=self.root / "tools", certificate=CERT)

    def test_metadata_valid_and_invalid(self):
        self.assertEqual(a.metadata(META.copy()), META)
        for field, values in {"tag": ["1.0.0", "v01.0.0", "v1.0.0-rc1", "v1.0.0+build", "v1.0", "v1.0.0\n", None],
                              "commit": ["a" * 39, "A" * 40, "g" * 40, None], "versionName": ["1.0.1", 1],
                              "versionCode": [0, -1, True, "1", 2100000001], "packageName": ["com.other", None],
                              "schemaVersion": [True, 2]}.items():
            for value in values:
                with self.subTest(field=field, value=value), self.assertRaises(a.ReleaseError):
                    a.metadata({**META, field: value})
        for value in ([], {**META, "extra": True}, {key: val for key, val in META.items() if key != "tag"}):
            with self.assertRaises(a.ReleaseError):
                a.metadata(value)

    def test_gradle_literals(self):
        self.assertEqual(a.gradle_identity(GRADLE.decode())["versionCode"], "1")
        for text in (GRADLE.decode().replace('"1.0.0"', '"01.0.0"'), GRADLE.decode().replace("versionCode = 1", "versionCode = getVersion()"),
                     GRADLE.decode() + "versionCode = 2\n", GRADLE.decode().replace("26", "27"),
                     GRADLE.decode().replace("com.splitfree", "com.evil"), GRADLE.decode().replace("versionCode = 1", "versionCode = 1 + 1")):
            with self.subTest(text=text), self.assertRaises(a.ReleaseError):
                a.gradle_identity(text)

    def test_policy(self):
        a.policy_check(self.policy, self.files[a.NOTICES], "1.0.0")
        for update in ({"approved": False}, {"approved": 1}, {"schemaVersion": True}, {"versionName": "1.0.1"}, {"noticesSha256": "0" * 64}):
            with self.subTest(update=update), self.assertRaises(a.ReleaseError):
                a.policy_check({**self.policy, **update}, self.files[a.NOTICES], "1.0.0")
        for notices in (b"", b" \n"):
            with self.assertRaisesRegex(a.ReleaseError, "reviewed, nonempty"):
                a.policy_check(self.policy, notices, "1.0.0")

    def test_validate_exact_metadata_and_git_guards(self):
        args = argparse.Namespace(tag=META["tag"], commit=META["commit"], repository="owner/SplitFree", output=self.root / "validated.json")
        with patch.object(a, "run", side_effect=self.runner):
            a.validate(args)
        self.assertEqual(args.output.read_bytes(), a.json_bytes(META))
        self.assertIn(("git", "rev-parse", "--verify", "refs/tags/v1.0.0^{commit}"), self.calls)
        self.assertIn(("git", "merge-base", "--is-ancestor", META["commit"], "refs/remotes/origin/mainline"), self.calls)
        self.assertIn(("git", "status", "--porcelain", "--untracked-files=no"), self.calls)
        for repo in ("owner", "a/b/c", "https://github.com/a/b", "../bad", "a/..", "-a/b", "a/b\n"):
            args.repository = repo
            with self.subTest(repo=repo), patch.object(a, "run") as runner, self.assertRaises(a.ReleaseError):
                a.validate(args)
            runner.assert_not_called()

    def test_validate_fails_dirty_head_tag_or_ancestry(self):
        args = argparse.Namespace(tag=META["tag"], commit=META["commit"], repository="owner/repo", output=self.root / "unused")
        for step in ("head", "tag", "dirty", "ancestry"):
            def reject(*command):
                if step == "head" and command == ("git", "rev-parse", "HEAD") or step == "tag" and "--verify" in command:
                    return b"0" * 40
                if step == "dirty" and command[1] == "status":
                    return b" M app/build.gradle.kts\n"
                if step == "ancestry" and command[1] == "merge-base":
                    raise a.ReleaseError("commit is not on origin/mainline")
                return self.runner(*command)
            with self.subTest(step=step), patch.object(a, "run", side_effect=reject), self.assertRaises(a.ReleaseError):
                a.validate(args)
            self.assertFalse(args.output.exists())

    def test_manifest_identity_min_sdk_and_debuggable(self):
        a.manifest_check(BADGING, XML, META)
        a.manifest_check(BADGING.replace("minSdkVersion", "sdkVersion"), XML + "    A: android:debuggable(0x0101000f)=false\n", META)
        for badging, xml in ((BADGING.replace("com.splitfree", "evil.id"), XML), (BADGING.replace("'1.0.0'", "'1.0.1'"), XML),
                             (BADGING.replace("'26'", "'25'"), XML), (BADGING + "application-debuggable\n", XML),
                             (BADGING, XML.replace('"com.splitfree"', '"evil.id"')), (BADGING, XML.replace("=26", "=27")),
                             (BADGING, XML + " A: android:debuggable(0x0101000f)=true\n"),
                             (BADGING, XML + " A: android:debuggable(0x0101000f)=@0x7f01\n"), (BADGING, "")):
            with self.subTest(xml=xml, badging=badging), self.assertRaises(a.ReleaseError):
                a.manifest_check(badging, xml, META)

    def test_elf_alignment_offset_headers_and_bounds(self):
        a.elf_check(elf(), LIB)
        for data in (b"", b"not ELF" * 20, elf(endian=">"), elf(4096), elf(24576), elf(address=1), elf()[:64], elf()[:110], elf(offset=121)):
            with self.subTest(data=data[:10]), self.assertRaises(a.ReleaseError):
                a.elf_check(data, LIB)
        for offset, format_, value in ((4, "B", 1), (5, "B", 0), (6, "B", 0), (54, "H", 55), (56, "H", 0), (64, "I", 2), (96, "Q", 121)):
            data = bytearray(elf())
            struct.pack_into("<" + format_, data, offset, value)
            with self.subTest(offset=offset), self.assertRaises(a.ReleaseError):
                a.elf_check(bytes(data), LIB)

    def test_apk_native_offsets_and_unsigned_structural_proof(self):
        a.apk_entries(self.unsigned, unsigned=True)
        apk(self.root / "unaligned.apk", native=elf(), aligned=False)
        with self.assertRaisesRegex(a.ReleaseError, "ZIP offset"):
            a.apk_entries(self.root / "unaligned.apk")
        with zipfile.ZipFile(self.unsigned, "a") as archive:
            archive.writestr("META-INF/CERT.SF", b"signature")
        with self.assertRaisesRegex(a.ReleaseError, "v1 signature"):
            a.apk_entries(self.unsigned, unsigned=True)
        apk(self.unsigned)
        raw = self.unsigned.read_bytes()
        end = raw.rfind(b"PK\x05\x06")
        central = struct.unpack_from("<I", raw, end + 16)[0]
        block = struct.pack("<Q", 24) + struct.pack("<Q", 24) + b"APK Sig Block 42"
        signed = bytearray(raw[:central] + block + raw[central:])
        struct.pack_into("<I", signed, end + len(block) + 16, central + len(block))
        self.unsigned.write_bytes(signed)
        with self.assertRaisesRegex(a.ReleaseError, "APK Signing Block"):
            a.apk_entries(self.unsigned, unsigned=True)

    def test_apk_rejects_duplicates_traversal_and_malformed_zip(self):
        for name in ("AndroidManifest.xml", "../evil", "/evil", "a\\evil"):
            apk(self.unsigned)
            with warnings.catch_warnings(), zipfile.ZipFile(self.unsigned, "a") as archive:
                warnings.simplefilter("ignore", UserWarning)
                archive.writestr(name, b"bad")
            with self.subTest(name=name), self.assertRaises(a.ReleaseError):
                a.apk_entries(self.unsigned)
        for raw in (b"", b"PK\x05\x06", self.unsigned.read_bytes() + b"extra"):
            self.unsigned.write_bytes(raw)
            with self.assertRaises(a.ReleaseError):
                a.apk_entries(self.unsigned)

    def test_manifest_accepts_real_indentation_namespaces_and_numeric_forms(self):
        xml = "N: android=http://schemas.android.com/apk/res/android (line=2)\n" + "\n".join(
            "  " + line if line.startswith(("E:", "  A:")) else "    " + line for line in XML.splitlines())
        report = a.manifest_check(BADGING, xml, META)
        self.assertEqual(report, {key: META[key] for key in ("packageName", "versionCode", "versionName")} |
                         {"minSdk": 26, "targetSdk": 37, "debuggable": False, "testOnly": False, "standalone": True})
        for minimum, target in (("0x0000001a", "0x25"), ("(type 0x10)0x1a", "(type 0x11)0x25")):
            a.manifest_check(BADGING, XML.replace("=26", "=" + minimum).replace("=37", "=" + target), META)
        for value in ("false", "0", "0x00000000", "(type 0x12)0x0"):
            for name in ("debuggable", "testOnly", "isSplitRequired"):
                a.manifest_check(BADGING, manifest_attribute("application", "android:" + name, value), META)
        a.manifest_check(BADGING, manifest_attribute("manifest", "android:versionCodeMajor", "0"), META)

    def test_manifest_rejects_test_debug_and_split_boolean_values(self):
        for scope, name in (("application", "testOnly"), ("application", "debuggable"),
                            ("manifest", "isSplitRequired"), ("application", "isSplitRequired"),
                            ("manifest", "isFeatureSplit"), ("manifest", "isolatedSplits")):
            for value in ("true", "1", "0xffffffff", "(type 0x12)0xffffffff", '"false"', "@0x7f000001", "(type 0x01)0x0"):
                with self.subTest(scope=scope, name=name, value=value), self.assertRaises(a.ReleaseError):
                    a.manifest_check(BADGING, manifest_attribute(scope, "android:" + name, value), META)

    def test_manifest_rejects_restrictions_split_names_and_split_metadata(self):
        for scope, name in (("uses-sdk", "android:maxSdkVersion"), ("manifest", "android:sharedUserId"),
                            ("manifest", "split"), ("manifest", "configForSplit"),
                            ("manifest", "android:requiredSplitTypes"), ("application", "android:requiredSplitTypes"),
                            ("manifest", "android:splitTypes"), ("application", "android:splitName")):
            for value in ('"required"', '""', "0"):
                with self.subTest(scope=scope, name=name, value=value), self.assertRaises(a.ReleaseError):
                    a.manifest_check(BADGING, manifest_attribute(scope, name, value), META)
        for extra in ('  E: uses-split (line=42)\n    A: android:name="feature"\n',
                      '    E: activity\n      A: android:splitName="feature"\n',
                      '    E: meta-data\n      A: android:name="com.android.vending.splits"\n',
                      '    E: meta-data\n      A: android:name="com.android.vending.splits.required"\n      A: android:value=false\n'):
            with self.subTest(extra=extra), self.assertRaises(a.ReleaseError):
                a.manifest_check(BADGING, XML + extra, META)

    def test_manifest_sdk_values_must_be_numeric_and_exact_in_both_sources(self):
        for field, expected in (("minSdkVersion", "26"), ("targetSdkVersion", "37")):
            original = next(line for line in XML.splitlines(keepends=True) if field in line)
            for value in ('"37"', '"Baklava"', "@0x7f000001", "(type 0x03)0x25", "-1", "10000", "0", "36"):
                with self.subTest(field=field, value=value), self.assertRaises(a.ReleaseError):
                    a.manifest_check(BADGING, XML.replace(original, original.replace("=" + expected, "=" + value)), META)
            for xml in (XML.replace(original, ""), XML.replace(original, original * 2),
                        XML.replace(original, "") + '    A: android:' + field + '=' + expected + '\n'):
                with self.subTest(xml=xml), self.assertRaises(a.ReleaseError):
                    a.manifest_check(BADGING, xml, META)
            for badging in (BADGING.replace("'" + expected + "'", "'Preview'"),
                            BADGING + f"{field}:'{expected}'\n", BADGING.replace(f"{field}:'{expected}'\n", "")):
                with self.subTest(badging=badging), self.assertRaises(a.ReleaseError):
                    a.manifest_check(badging, XML, META)

    def test_manifest_scope_prevents_nested_identity_and_flag_spoofing(self):
        nested = ('    E: activity\n      A: android:debuggable=true\n      A: android:versionCode=999\n'
                  '      A: package="unrelated"\n      A: android:maxSdkVersion=1\n')
        a.manifest_check(BADGING, XML + nested, META)
        root_package = '  A: package="com.splitfree" (Raw: "com.splitfree")\n'
        for xml in (XML.replace(root_package, "") + '    A: package="com.splitfree"\n',
                    XML.replace("  E: uses-sdk", "    E: uses-sdk"),
                    XML.replace("  E: application", "    E: application"),
                    XML + "    E: uses-sdk\n", XML + "    E: manifest\n", XML + "E: manifest\n",
                    XML.replace("  A: package", " A: package"), XML + " A: android:testOnly=true\n",
                    XML + '    E: activity\n    A: android:testOnly=false\n',
                    XML.replace(root_package, root_package * 2), "A: package=0\n" + XML,
                    XML + "unexpected text\n", XML.replace("  A:", "\tA:")):
            with self.subTest(xml=xml), self.assertRaises(a.ReleaseError):
                a.manifest_check(BADGING, xml, META)
        xml = manifest_attribute("application", "android:debuggable", "true") + '    E: activity\n      A: android:debuggable=false\n'
        with self.assertRaisesRegex(a.ReleaseError, "debuggable"):
            a.manifest_check(BADGING, xml, META)

    def test_manifest_identity_rejects_wrong_package_version_types_and_major(self):
        for xml in (XML.replace("com.splitfree", "com.splitfree.debug"), XML.replace('="1.0.0"', '=@0x7f000001'),
                    XML.replace("versionCode(0x0101021b)=1", "versionCode(0x0101021b)=0"),
                    XML.replace("versionCode(0x0101021b)=1", "versionCode(0x0101021b)=2100000001"),
                    manifest_attribute("manifest", "android:versionCodeMajor", "1"),
                    manifest_attribute("manifest", "android:versionCodeMajor", '"0"')):
            with self.subTest(xml=xml), self.assertRaises(a.ReleaseError):
                a.manifest_check(BADGING, xml, META)
        for meta in ({**META, "versionCode": True}, {**META, "versionName": "2.0.0"}, None):
            with self.subTest(meta=meta), self.assertRaises(a.ReleaseError):
                a.manifest_check(BADGING, XML, meta)

    def test_all_abis_require_actual_matching_class_machine_and_shared_object(self):
        for abi in a.NATIVE_ABIS:
            name = f"lib/{abi}/libfixture.so"
            a.elf_check(elf(abi=abi), name)
            for wrong in a.NATIVE_ABIS:
                if wrong != abi:
                    with self.subTest(abi=abi, wrong=wrong), self.assertRaises(a.ReleaseError):
                        a.elf_check(elf(abi=wrong), name)
            for offset, format_, value in ((16, "H", 2), (18, "H", 0), (20, "I", 0)):
                data = bytearray(elf(abi=abi))
                struct.pack_into("<" + format_, data, offset, value)
                with self.subTest(abi=abi, offset=offset), self.assertRaises(a.ReleaseError):
                    a.elf_check(data, name)
        for name in ("assets/lib.so", "lib/riscv64/lib.so", "lib/x86/nested/lib.so", "lib/../lib.so"):
            with self.subTest(name=name), self.assertRaises(a.ReleaseError):
                a.elf_check(elf(), name)

    def test_32bit_elf_header_load_bounds_and_alignment_are_validated(self):
        name = "lib/x86/libfixture.so"
        a.elf_check(elf(abi="x86", alignment=4096), name)
        for data in (elf(abi="x86")[:51], elf(abi="x86")[:80], elf(abi="x86", alignment=0),
                     elf(abi="x86", address=1), elf(abi="x86", offset=85)):
            with self.subTest(data=data), self.assertRaises(a.ReleaseError):
                a.elf_check(data, name)
        for offset, format_, value in ((28, "I", 51), (40, "H", 64), (42, "H", 31),
                                      (44, "H", 0), (52, "I", 0), (68, "I", 85)):
            data = bytearray(elf(abi="x86"))
            struct.pack_into("<" + format_, data, offset, value)
            with self.subTest(offset=offset), self.assertRaises(a.ReleaseError):
                a.elf_check(data, name)

    def test_apk_missing_abi_and_disguised_native_path_rejected(self):
        natives = {f"lib/{abi}/libfixture.so": elf(abi=abi) for abi in a.NATIVE_ABIS}
        for missing in natives:
            apk(self.unsigned, native={name: data for name, data in natives.items() if name != missing})
            with self.subTest(missing=missing), self.assertRaisesRegex(a.ReleaseError, "all four"):
                a.apk_entries(self.unsigned)
        apk(self.unsigned, native={})
        with self.assertRaisesRegex(a.ReleaseError, "all four"):
            a.apk_entries(self.unsigned)
        apk(self.unsigned, native={**natives, "assets/libfixture.so": elf()})
        with self.assertRaisesRegex(a.ReleaseError, "path/ABI"):
            a.apk_entries(self.unsigned)

    def test_apk_each_abi_must_include_the_same_native_libraries(self):
        natives = {f"lib/{abi}/{library}.so": elf(abi=abi)
                   for abi in a.NATIVE_ABIS for library in ("libfixture", "libsecond")}
        apk(self.unsigned, native=natives)
        a.apk_entries(self.unsigned)
        for abi in a.NATIVE_ABIS:
            missing = f"lib/{abi}/libsecond.so"
            apk(self.unsigned, native={name: data for name, data in natives.items() if name != missing})
            with self.subTest(abi=abi), self.assertRaisesRegex(a.ReleaseError, "library names must match"):
                a.apk_entries(self.unsigned)

    def test_apk_noncanonical_symlink_compression_and_crc_fail_closed(self):
        for name in ("a//b", "./a", "a/./b"):
            apk(self.unsigned, entries={name: b"bad"})
            with self.subTest(name=name), self.assertRaises(a.ReleaseError):
                a.apk_entries(self.unsigned)
        for compression, mode in ((zipfile.ZIP_BZIP2, 0), (zipfile.ZIP_STORED, 0o120777 << 16)):
            apk(self.unsigned)
            with zipfile.ZipFile(self.unsigned, "a") as archive:
                entry = zipfile.ZipInfo("extra")
                entry.compress_type, entry.external_attr = compression, mode
                archive.writestr(entry, b"bad")
            with self.assertRaises(a.ReleaseError):
                a.apk_entries(self.unsigned)
        apk(self.unsigned)
        raw = self.unsigned.read_bytes().replace(b"fixture dex", b"Fixture dex", 1)
        with self.assertRaisesRegex(a.ReleaseError, "CRC"):
            a.apk_payloads(raw)

    def test_apk_malformed_zip_records_and_local_central_disagreement(self):
        raw = self.unsigned.read_bytes()
        central, end = raw.find(b"PK\x01\x02"), raw.rfind(b"PK\x05\x06")
        for offset, format_, value in ((end + 4, "H", 1), (end + 8, "H", 0), (end + 10, "H", 65535),
                                      (central + 8, "H", 1), (central + 10, "H", 12),
                                      (central + 42, "I", 0xffffffff), (0, "I", 0), (8, "H", 8), (6, "H", 8),
                                      (26, "H", 65535), (28, "H", 65535)):
            changed = bytearray(raw)
            struct.pack_into("<" + format_, changed, offset, value)
            with self.subTest(offset=offset), self.assertRaises(a.ReleaseError):
                a.apk_payloads(changed)

    def test_apk_limits_reject_before_payload_read(self):
        raw = self.unsigned.read_bytes()
        with patch.object(a, "MAX_APK_BYTES", len(raw) - 1):
            for action in (lambda: a.apk_snapshot(self.unsigned), lambda: a.read_apk_bytes(self.unsigned), lambda: a.apk_payloads(raw)):
                with self.assertRaisesRegex(a.ReleaseError, "size limit"):
                    action()
        central = raw.find(b"PK\x01\x02")
        changed = bytearray(raw)
        struct.pack_into("<I", changed, central + 24, a.MAX_APK_BYTES + 1)
        with patch.object(zipfile.ZipFile, "read", side_effect=AssertionError("must reject before decompression")):
            with self.assertRaisesRegex(a.ReleaseError, "decompressed size"):
                a.apk_payloads(changed)
            with patch.object(a, "MAX_APK_UNCOMPRESSED_BYTES", 1), self.assertRaisesRegex(a.ReleaseError, "decompressed size"):
                a.apk_payloads(raw)

    def test_signed_inspection_exact_fresh_command_json_metadata_and_no_writes(self):
        before = a.apk_snapshot(self.unsigned)
        with patch.object(a, "run", side_effect=self.runner):
            report = a.inspect_signed_apk(self.unsigned, META, self.root / "tools", CERT.upper())
        expected_manifest = a.manifest_check(BADGING, XML, META)
        self.assertEqual(report, {"apkSha256": before[1], "apkBytes": before[2], "certificateSha256": CERT,
                                 "manifest": expected_manifest, "abis": sorted(a.NATIVE_ABIS), "payloadEntries": 6,
                                 "signingSchemes": ["v2"]})
        self.assertEqual(json.loads(a.json_bytes(report)), report)
        self.assertEqual(a.apk_snapshot(self.unsigned), before)
        self.assertEqual([Path(command[0]).name for command in self.calls], ["aapt2", "aapt2", "zipalign", "apksigner"])
        self.assertTrue(all(command[-1] == str(self.unsigned.absolute()) for command in self.calls))
        self.assertEqual(self.calls[-1][1:-1], ("verify", "--verbose", "--print-certs", "--min-sdk-version", "26"))

    def test_signed_inspection_certificate_pin_is_explicit_not_report_derived(self):
        for certificate in (None, "", "b" * 63, "g" * 64, CERT + "\n", ":".join(["bb"] * 32)):
            with self.subTest(certificate=certificate), patch.object(a, "run") as runner, self.assertRaisesRegex(a.ReleaseError, "approved signing certificate"):
                a.inspect_signed_apk(self.unsigned, META, self.root, certificate)
            runner.assert_not_called()
        with patch.object(a, "run", side_effect=self.runner), self.assertRaisesRegex(a.ReleaseError, "mismatch"):
            a.inspect_signed_apk(self.unsigned, META, self.root, "a" * 64)

    def test_signed_inspection_rejects_unsigned_invalid_or_ambiguous_signatures(self):
        variants = ["DOES NOT VERIFY\n", "", SIGNATURE.replace("Verifies\n", ""),
                    SIGNATURE.replace("Number of signers: 1", "Number of signers: 2"),
                    SIGNATURE.replace(CERT, "a" * 64),
                    SIGNATURE + f"Signer #2 certificate SHA-256 digest: {CERT}\n",
                    SIGNATURE + "Signer #2 certificate SHA-256 digest: malformed\n",
                    SIGNATURE.replace("Signer #1", "Signer #0"),
                    SIGNATURE.replace("v2): true", "v2): false"),
                    SIGNATURE.replace("v2): true", "v2): false").replace("v3): false", "v3): true"),
                    SIGNATURE + "Verified using v2 scheme (APK Signature Scheme v2): true\n"]
        for signature in variants:
            def run(*command):
                return signature.encode() if Path(command[0]).name == "apksigner" else self.runner(*command)
            with self.subTest(signature=signature), patch.object(a, "run", side_effect=run), self.assertRaises(a.ReleaseError):
                a.inspect_signed_apk(self.unsigned, META, self.root, CERT)
        (self.root / "release-signature.txt").write_text(SIGNATURE)
        def unsigned(*command):
            if Path(command[0]).name == "apksigner":
                raise a.ReleaseError("DOES NOT VERIFY: Missing META-INF/MANIFEST.MF")
            return self.runner(*command)
        with patch.object(a, "run", side_effect=unsigned), self.assertRaisesRegex(a.ReleaseError, "DOES NOT VERIFY"):
            a.inspect_signed_apk(self.unsigned, META, self.root, CERT)

    def test_signed_inspection_sdk_failure_missing_tool_and_timeout(self):
        for step in range(4):
            calls = []
            def run(*command):
                calls.append(command)
                if len(calls) == step + 1:
                    raise a.ReleaseError("SDK failed")
                return self.runner(*command)
            with self.subTest(step=step), patch.object(a, "run", side_effect=run), self.assertRaisesRegex(a.ReleaseError, "SDK failed"):
                a.inspect_signed_apk(self.unsigned, META, self.root, CERT)
            self.assertEqual(len(calls), step + 1)
        with patch.object(a.subprocess, "run", side_effect=FileNotFoundError("missing SDK")), self.assertRaises(FileNotFoundError):
            a.inspect_signed_apk(self.unsigned, META, self.root, CERT)
        with patch.object(a.subprocess, "run", side_effect=subprocess.TimeoutExpired(["sdk"], 120)) as runner, self.assertRaisesRegex(a.ReleaseError, "timed out"):
            a.inspect_signed_apk(self.unsigned, META, self.root, CERT)
        self.assertEqual(runner.call_args.kwargs["timeout"], 120)

    def test_signed_inspection_detects_tool_mutation_replacement_and_restored_bytes(self):
        for step in range(4):
            for mutation in ("same-size", "restore", "replace"):
                apk(self.unsigned)
                calls = []
                def run(*command):
                    calls.append(command)
                    output = self.runner(*command)
                    if len(calls) == step + 1:
                        original = self.unsigned.read_bytes()
                        if mutation == "replace":
                            replacement = self.root / "replacement.apk"
                            replacement.write_bytes(original)
                            replacement.replace(self.unsigned)
                        else:
                            self.unsigned.write_bytes(original.replace(b"fixture dex", b"Fixture dex", 1))
                            if mutation == "restore":
                                self.unsigned.write_bytes(original)
                    return output
                with self.subTest(step=step, mutation=mutation), patch.object(a, "run", side_effect=run), self.assertRaisesRegex(a.ReleaseError, "changed"):
                    a.inspect_signed_apk(self.unsigned, META, self.root, CERT)
                self.assertEqual(len(calls), step + 1)

    def test_signed_inspection_detects_change_between_structure_and_first_tool(self):
        original = a.apk_entries
        def changed(*args):
            entries = original(*args)
            self.unsigned.write_bytes(self.unsigned.read_bytes() + b"bad")
            return entries
        with patch.object(a, "apk_entries", side_effect=changed), patch.object(a, "run") as runner, self.assertRaisesRegex(a.ReleaseError, "changed"):
            a.inspect_signed_apk(self.unsigned, META, self.root, CERT)
        runner.assert_not_called()

    def test_signed_inspection_refuses_links_and_nonregular_input(self):
        link = self.root / "link.apk"
        link.symlink_to(self.unsigned)
        for path in (link, self.root):
            with self.subTest(path=path), patch.object(a, "run") as runner, self.assertRaisesRegex(a.ReleaseError, "regular file"):
                a.inspect_signed_apk(path, META, self.root, CERT)
            runner.assert_not_called()

    def test_identity_reader_is_neutral_but_crosschecks_compiled_root_and_badging(self):
        for package in ("com.splitfree", "com.splitfree.debug"):
            def run(*command):
                return self.runner(*command).replace(b"com.splitfree", package.encode())
            with patch.object(a, "run", side_effect=run):
                identity = a.read_apk_identity(self.unsigned, self.root)
            self.assertEqual(identity, {"packageName": package, "versionCode": 1, "versionName": "1.0.0"})
        def mismatch(*command):
            return self.runner(*command).replace(b"1.0.0", b"2.0.0") if command[2] == "badging" else self.runner(*command)
        with patch.object(a, "run", side_effect=mismatch), self.assertRaisesRegex(a.ReleaseError, "mismatch"):
            a.read_apk_identity(self.unsigned, self.root)

    def test_baseline_inspection_verifies_signer_and_identity_without_candidate_packaging_policy(self):
        # A predecessor built under an older packaging policy: different target SDK, one ABI, no alignment.
        baseline = self.root / "baseline.apk"
        apk(baseline, native={LIB: elf()}, aligned=False)
        def run(*command):
            return self.runner(*command).replace(b"37", b"36")
        before = a.apk_snapshot(baseline)
        with patch.object(a, "run", side_effect=run):
            report = a.inspect_signed_baseline(baseline, self.root / "tools", CERT.upper())
        self.assertEqual(report, {"apkSha256": before[1], "apkBytes": before[2], "certificateSha256": CERT,
                                 "manifest": {"packageName": "com.splitfree", "versionCode": 1, "versionName": "1.0.0"},
                                 "signingSchemes": ["v2"]})
        self.assertEqual([Path(command[0]).name for command in self.calls], ["aapt2", "aapt2", "apksigner"])
        self.assertEqual(a.apk_snapshot(baseline), before)
        with patch.object(a, "run", side_effect=run), self.assertRaises(a.ReleaseError):
            a.inspect_signed_apk(baseline, META, self.root / "tools", CERT)

    def test_baseline_inspection_still_requires_production_package_pinned_signer_and_verifying_signature(self):
        def debug(*command):
            return self.runner(*command).replace(b"com.splitfree", b"com.splitfree.debug")
        with patch.object(a, "run", side_effect=debug), self.assertRaisesRegex(a.ReleaseError, "com.splitfree"):
            a.inspect_signed_baseline(self.unsigned, self.root, CERT)
        with patch.object(a, "run", side_effect=self.runner), self.assertRaisesRegex(a.ReleaseError, "mismatch"):
            a.inspect_signed_baseline(self.unsigned, self.root, "a" * 64)
        def unsigned(*command):
            return b"DOES NOT VERIFY\n" if Path(command[0]).name == "apksigner" else self.runner(*command)
        with patch.object(a, "run", side_effect=unsigned), self.assertRaises(a.ReleaseError):
            a.inspect_signed_baseline(self.unsigned, self.root, CERT)
        def unverified(*command):
            return self.runner(*command).replace(b"Verifies\n", b"") if Path(command[0]).name == "apksigner" else self.runner(*command)
        with patch.object(a, "run", side_effect=unverified), self.assertRaisesRegex(a.ReleaseError, "did not verify"):
            a.inspect_signed_baseline(self.unsigned, self.root, CERT)
        with patch.object(a, "run") as runner, self.assertRaisesRegex(a.ReleaseError, "approved signing certificate"):
            a.inspect_signed_baseline(self.unsigned, self.root, "")
        runner.assert_not_called()

    def test_source_requires_committed_certificate_and_finalize_pin_match(self):
        args = self.final_args()
        args.certificate = "a" * 64
        with patch.object(a, "run", side_effect=self.runner), self.assertRaisesRegex(a.ReleaseError, "release commit pin"):
            a.finalize(args)
        self.assertFalse(args.output.exists())
        for content in (b"invalid", b"", b"a" * 63):
            self.files[a.SIGNING_CERTIFICATE] = content
            self.archive, self.tree = archive_fixture(self.files)
            with self.subTest(content=content), patch.object(a, "run", side_effect=self.runner), self.assertRaisesRegex(a.ReleaseError, "approved signing certificate"):
                a.source_bundle(META)
        del self.tree[a.SIGNING_CERTIFICATE]
        with patch.object(a, "run", side_effect=self.runner), self.assertRaisesRegex(a.ReleaseError, "signing-certificate"):
            a.source_tree(META["commit"])

    def test_finalize_rechecks_final_bytes_after_shared_inspector_returns(self):
        args = self.final_args()
        original = a.inspect_signed_apk
        def changed(*fields):
            report = original(*fields)
            args.apk.write_bytes(args.apk.read_bytes().replace(b"fixture dex", b"Fixture dex", 1))
            return report
        with patch.object(a, "run", side_effect=self.runner), patch.object(a, "inspect_signed_apk", side_effect=changed), self.assertRaisesRegex(a.ReleaseError, "changed after signed"):
            a.finalize(args)
        self.assertFalse(args.output.exists())

    def test_prepare_rechecks_bytes_changed_during_source_packaging(self):
        original = a.source_bundle
        def changed(*fields):
            source = original(*fields)
            self.unsigned.write_bytes(self.unsigned.read_bytes().replace(b"fixture dex", b"Fixture dex", 1))
            return source
        with patch.object(a, "run", side_effect=self.runner), patch.object(a, "source_bundle", side_effect=changed), self.assertRaisesRegex(a.ReleaseError, "changed after unsigned"):
            a.prepare(self.args())
        self.assertFalse((self.root / "package").exists())

    def test_sensitive_sources_rejected_before_content_reads(self):
        for name in (".env", "app/.env.prod", "release/signing.jks", "app/keys.keystore", "local.properties", "release/private_key.txt",
                     "release/key.properties", "app/key.pem", "app/../secret", "/LICENSE", "unknown/source.txt", "release/id_rsa"):
            with self.subTest(name=name), self.assertRaises(a.ReleaseError):
                a.safe_source_path(name)
        a.safe_source_path("app/src/KeystoreEncryptedStorage.kt")
        a.safe_source_path("app/src/PrivateKeyTest.kt")
        self.tree["release/privatekey.txt"] = ("100644", "0" * 40)
        with patch.object(a, "run", side_effect=self.runner), self.assertRaisesRegex(a.ReleaseError, "Sensitive"):
            a.source_bundle(META)
        self.assertEqual(len(self.calls), 1)

    def test_missing_notices_symlink_and_submodule_rejected(self):
        original = self.tree.copy()
        for change in ("missing", "120000", "160000"):
            self.tree = original.copy()
            if change == "missing":
                del self.tree[a.NOTICES]
            else:
                self.tree["app/link"] = (change, "0" * 40)
            with self.subTest(change=change), patch.object(a, "run", side_effect=self.runner), self.assertRaises(a.ReleaseError):
                a.source_bundle(META)

    def test_archive_exact_commit_bytes_and_deterministic_gzip(self):
        with patch.object(a, "run", side_effect=self.runner):
            first = a.source_bundle(META)
            second = a.source_bundle(META)
        self.assertEqual(first, second)
        self.assertEqual(first["source.tar.gz"][4:8], bytes(4))
        self.assertEqual(a.archive_files(gzip.decompress(first["source.tar.gz"]), self.tree, META["tag"]), self.files)
        self.assertEqual(first["LICENSE.txt"], self.files["LICENSE"])
        for files, prefix in (({**self.files, "LICENSE": b"tampered"}, "SplitFree-v1.0.0/"),
                              ({key: val for key, val in self.files.items() if key != "LICENSE"}, "SplitFree-v1.0.0/"),
                              (self.files, "wrong/"), ({**self.files, "release/.env": b"synthetic"}, "SplitFree-v1.0.0/")):
            archive, unused = archive_fixture(files, prefix)
            with self.subTest(prefix=prefix, files=list(files)), self.assertRaises(a.ReleaseError):
                a.archive_files(archive, self.tree, META["tag"])

    def test_prepare_and_finalize_exact_files_manifest_checksums(self):
        args = self.final_args()
        self.assertEqual({p.name for p in args.package.iterdir()}, a.PACKAGE_FILES)
        with patch.object(a, "run", side_effect=self.runner):
            a.finalize(args)
        payloads = {"SplitFree-v1.0.0.apk", "SplitFree-v1.0.0-source.tar.gz", "LICENSE.txt", "THIRD-PARTY-NOTICES.txt"}
        self.assertEqual({p.name for p in args.output.iterdir()}, payloads | {"release-info.json", "SHA256SUMS.txt"})
        expected_hashes = {name: a.sha256((args.output / name).read_bytes()) for name in payloads}
        expected = {**META, "certificateSha256": CERT, "artifacts": expected_hashes}
        self.assertEqual((args.output / "release-info.json").read_bytes(), a.json_bytes(expected))
        expected_hashes["release-info.json"] = a.sha256(a.json_bytes(expected))
        expected_sums = "".join(f"{digest}  {name}\n" for name, digest in sorted(expected_hashes.items())).encode()
        self.assertEqual((args.output / "SHA256SUMS.txt").read_bytes(), expected_sums)
        self.assertEqual((args.output / "SplitFree-v1.0.0.apk").read_bytes(), args.apk.read_bytes())
        self.assertFalse(any(command[0].endswith("apksigner") for command in self.calls[:3]))

    def test_finalize_rejects_missing_certificate_early(self):
        for certificate in ("", "a" * 63, "x" * 64, None):
            with self.subTest(certificate=certificate), patch.object(a, "run") as runner, self.assertRaisesRegex(a.ReleaseError, "approved signing certificate"):
                a.finalize(argparse.Namespace(certificate=certificate))
            runner.assert_not_called()

    def test_certificate_mismatch_multiple_signers_and_missing_digest(self):
        a.certificate_check(f"Signer #1 certificate SHA-256 digest: {CERT.upper()}\n", CERT)
        for output in ("tool failed", f"Signer #1 certificate SHA-256 digest: {'a' * 64}",
                       f"Signer #1 certificate SHA-256 digest: {CERT}\nSigner #2 certificate SHA-256 digest: {CERT}\n"):
            with self.assertRaises(a.ReleaseError):
                a.certificate_check(output, CERT)

    def test_finalize_rejects_changed_payload_or_compression(self):
        args = self.final_args()
        for entries in ({"classes.dex": b"changed"}, {"META-INF/not-a-signature.txt": b"extra"}):
            apk(args.apk, native=elf(), entries=entries)
            with self.subTest(entries=entries), patch.object(a, "run", side_effect=self.runner), self.assertRaisesRegex(a.ReleaseError, "payloads/compression"):
                a.finalize(args)
            self.assertFalse(args.output.exists())

    def test_finalize_rejects_package_tampering_and_extra_files(self):
        args = self.final_args()
        (args.package / "LICENSE.txt").write_bytes(b"tampered")
        with patch.object(a, "run", side_effect=self.runner), self.assertRaisesRegex(a.ReleaseError, "differ from release commit"):
            a.finalize(args)
        (args.package / "unwanted.properties").write_bytes(b"synthetic")
        with patch.object(a, "run", side_effect=self.runner), self.assertRaisesRegex(a.ReleaseError, "exactly"):
            a.finalize(args)

    def test_tool_failures_are_never_unsigned_proof(self):
        result = subprocess.CompletedProcess(["tool"], 1, b"", b"Java runtime missing")
        with patch.object(a.subprocess, "run", return_value=result), self.assertRaisesRegex(a.ReleaseError, "Java runtime missing"):
            a.run("apksigner", "verify", self.unsigned)
        with patch.object(a, "run", side_effect=a.ReleaseError("aapt2 failed")), self.assertRaisesRegex(a.ReleaseError, "aapt2 failed"):
            a.prepare(self.args())
        self.assertFalse((self.root / "package").exists())

    def test_prepare_and_finalize_reject_checkout_drift(self):
        args = self.final_args()
        for handler, command_args in ((a.prepare, self.args()), (a.finalize, args)):
            with self.subTest(handler=handler.__name__), patch.object(a, "run", return_value=b"0" * 40), self.assertRaisesRegex(a.ReleaseError, "HEAD"):
                handler(command_args)
        def dirty(*command):
            return b" M app/generated.kt" if command[1] == "status" else self.runner(*command)
        with patch.object(a, "run", side_effect=dirty), self.assertRaisesRegex(a.ReleaseError, "clean"):
            a.prepare(self.args())

    def test_native_compression_rejected_and_unsigned_manifest_allowed(self):
        with zipfile.ZipFile(self.unsigned, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr(LIB, elf(), compress_type=zipfile.ZIP_DEFLATED)
        with self.assertRaisesRegex(a.ReleaseError, "uncompressed"):
            a.apk_entries(self.unsigned)
        apk(self.unsigned, entries={"META-INF/MANIFEST.MF": b"ordinary dependency manifest"})
        a.apk_entries(self.unsigned, unsigned=True)

    def test_unresolved_lfs_pointer_rejected(self):
        files = {"LICENSE": b"version https://git-lfs.github.com/spec/v1\noid sha256:" + b"0" * 64 + b"\nsize 12\n"}
        archive, tree = archive_fixture(files)
        with self.assertRaisesRegex(a.ReleaseError, "LFS pointer"):
            a.archive_files(archive, tree, META["tag"])

    def test_real_tracked_filenames_fit_allowlist(self):
        repository = Path(__file__).resolve().parents[3]
        paths = a.run("git", "-C", str(repository), "ls-files", "-z").decode().split("\0")
        for name in filter(None, paths):
            with self.subTest(name=name):
                a.safe_source_path(name)

    def test_no_overwrite_and_actionable_cli_errors(self):
        with self.assertRaisesRegex(a.ReleaseError, "empty"):
            a.write_package(self.root, {"file": b"content"})
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as error:
            a.main(["finalize", "--metadata", "missing", "--package", "missing", "--apk", "missing", "--output", "missing", "--build-tools", "missing", "--certificate", ""])
        self.assertEqual(error.exception.code, 1)
        self.assertIn("approved signing certificate", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
