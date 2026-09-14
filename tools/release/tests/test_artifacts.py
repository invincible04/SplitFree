import argparse
import contextlib
import gzip
import hashlib
import importlib.util
import io
from pathlib import Path
import struct
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile


SPEC = importlib.util.spec_from_file_location("artifacts", Path(__file__).parents[1] / "artifacts.py")
a = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(a)
META = dict(schemaVersion=1, tag="v1.0.0", commit="a" * 40, versionName="1.0.0", versionCode=1, packageName="com.splitfree")
CERT = "b" * 64
GRADLE = b'applicationId = "com.splitfree"\nminSdk = 26\nversionCode = 1\nversionName = "1.0.0"\n'
BADGING = "package: name='com.splitfree' versionCode='1' versionName='1.0.0'\nminSdkVersion:'26'\n"
XML = '''E: manifest (line=2)
  A: package="com.splitfree" (Raw: "com.splitfree")
  A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=1
  A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="1.0.0" (Raw: "1.0.0")
  E: uses-sdk (line=7)
    A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=26
  E: application (line=39)
'''
LIB = "lib/arm64-v8a/libfixture.so"


def elf(alignment=16384, offset=0, address=0, endian="<"):
    data = bytearray(120)
    data[:7] = b"\x7fELF\x02" + (b"\x01" if endian == "<" else b"\x02") + b"\x01"
    struct.pack_into(endian + "Q", data, 32, 64)
    struct.pack_into(endian + "HHH", data, 52, 64, 56, 1)
    struct.pack_into(endian + "IIQQQQQQ", data, 64, 1, 5, offset, address, 0, 0, 120, alignment)
    return bytes(data)


def apk(path, native=None, aligned=True, entries=None):
    with zipfile.ZipFile(path, "w") as archive:
        if native is not None:
            entry = zipfile.ZipInfo(LIB)
            if aligned:
                padding = 16384 - 30 - len(LIB)
                entry.extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4)
            archive.writestr(entry, native)
        archive.writestr("AndroidManifest.xml", b"fixture manifest")
        for name, data in (entries or {"classes.dex": b"fixture dex"}).items():
            archive.writestr(name, data)


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
                      "release/policy.json": a.json_bytes(self.policy), "app/build.gradle.kts": GRADLE}
        self.archive, self.tree = archive_fixture(self.files)
        self.calls = []

    def runner(self, *command):
        self.calls.append(tuple(map(str, command)))
        if command[:2] == ("git", "ls-tree"):
            return b"".join(f"{mode} blob {digest}\t{name}\0".encode() for name, (mode, digest) in self.tree.items())
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
            return f"Verifies\nSigner #1 certificate SHA-256 digest: {CERT}\n".encode()
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
        a.elf_check(elf(endian=">"), LIB)
        for data in (b"", b"not ELF" * 20, elf(4096), elf(24576), elf(address=1), elf()[:64], elf()[:110], elf(offset=121)):
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
        self.assertEqual(gzip.decompress(first["source.tar.gz"]), self.archive)
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
        paths = a.run("git", "ls-files", "-z").decode().split("\0")
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
