#!/usr/bin/env python3
"""Offline, fail-closed preparation and verification of SplitFree release artifacts."""

import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import struct
import subprocess
import tarfile
import zipfile


VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
METADATA_KEYS = {"schemaVersion", "tag", "commit", "versionName", "versionCode", "packageName"}
PACKAGE_FILES = {"unsigned.apk", "metadata.json", "source.tar.gz", "LICENSE.txt", "THIRD-PARTY-NOTICES.txt"}
SOURCE_ROOTS = {
    ".editorconfig", ".gitattributes", ".github", ".gitignore", ".vscode", "app", "assets",
    "gradle", "gradlew", "gradlew.bat", "gradle.properties", "build.gradle.kts",
    "settings.gradle.kts", "tools", "release", "LICENSE", "README.md", "RELEASING.md",
    "CONTRIBUTING.md", "CODE_OF_CONDUCT.md", "PRIVACY.md", "SECURITY.md",
}
NOTICES = "release/THIRD-PARTY-NOTICES.txt"
SIGNATURE_ENTRY = re.compile(r"META-INF/(?:MANIFEST\.MF|[A-Z0-9_-]{1,8}\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)", re.I)


class ReleaseError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, sort_keys=True, indent=2, ensure_ascii=True) + "\n").encode()


def run(*command):
    result = subprocess.run([str(arg) for arg in command], capture_output=True,
                            env={**os.environ, "GIT_OPTIONAL_LOCKS": "0", "GIT_NO_LAZY_FETCH": "1"})
    require(result.returncode == 0,
            f"{command[0]} {' '.join(map(str, command[1:]))} failed ({result.returncode}): "
            + result.stderr.decode(errors="replace").strip())
    return result.stdout


def metadata(value):
    require(isinstance(value, dict) and set(value) == METADATA_KEYS, "Invalid metadata fields")
    require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 1, "Invalid metadata schemaVersion")
    require(isinstance(value["tag"], str) and re.fullmatch("v" + VERSION, value["tag"]), "Tag must be stable vX.Y.Z")
    require(isinstance(value["commit"], str) and re.fullmatch(r"[0-9a-f]{40}", value["commit"]), "Commit must be full 40 lowercase hex characters")
    require(value["versionName"] == value["tag"][1:], "versionName must match tag")
    require(type(value["versionCode"]) is int and 0 < value["versionCode"] <= 2100000000, "Invalid versionCode")
    require(value["packageName"] == "com.splitfree", "packageName must be com.splitfree")
    return value


def gradle_identity(text):
    values = {}
    for name, pattern in {"versionName": '"(' + VERSION + ')"', "versionCode": r"([1-9][0-9]*)",
                          "applicationId": r'"(com\.splitfree)"', "minSdk": r"(26)"}.items():
        assignments = re.findall(r"^\s*" + name + r"\s*=\s*(.*?)\s*$", text, re.M)
        require(len(assignments) == 1, f"Expected one literal {name} in app/build.gradle.kts")
        match = re.fullmatch(pattern, assignments[0])
        require(match, f"Invalid literal {name} in app/build.gradle.kts")
        values[name] = match[1]
    return values


def policy_check(policy, notices, version):
    require(notices.strip(), f"Add reviewed, nonempty {NOTICES} before approving a release")
    require(isinstance(policy, dict) and type(policy.get("schemaVersion")) is int
            and policy["schemaVersion"] == 1, "release/policy.json schemaVersion must be 1")
    require(policy.get("approved") is True, "Release policy is not approved; review notices and set release/policy.json approved=true")
    require(policy.get("versionName") == version, "Release policy versionName mismatch")
    require(policy.get("noticesSha256") == sha256(notices), "Release policy noticesSha256 mismatch")


def safe_source_path(name):
    parts = PurePosixPath(name).parts
    require(parts and not name.startswith("/") and "\\" not in name and ".." not in parts,
            f"Unsafe source path: {name}")
    for part in parts:
        lower = part.lower()
        compact = re.sub(r"[-_.]", "", lower)
        require(not (lower == ".git" or lower.startswith(".env") or lower.endswith((".jks", ".keystore", ".pem", ".p12", ".pfx", ".key"))
                     or lower in {"local.properties", "key.properties", "keystore.properties", "credentials", "id_rsa", "id_ed25519"}
                     or "privatekey" in compact and not lower.endswith((".kt", ".java", ".py", ".md"))
                     or lower == "keystore"
                     or lower.endswith(".properties") and name not in {"gradle.properties", "gradle/wrapper/gradle-wrapper.properties"}), f"Sensitive source path rejected: {name}")
    require(parts[0] in SOURCE_ROOTS, f"Source path outside explicit allowlist: {name}")


def source_tree(commit):
    tree = {}
    for entry in run("git", "ls-tree", "-rz", "--full-tree", commit).split(b"\0"):
        if not entry:
            continue
        attributes, name = entry.decode().split("\t", 1)
        mode, kind, digest = attributes.split()
        safe_source_path(name)
        require(kind == "blob" and mode in {"100644", "100755"}, f"Source links/submodules are not allowed: {name}")
        tree[name] = (mode, digest)
    for name in ("app/build.gradle.kts", "LICENSE", "release/policy.json", NOTICES):
        require(name in tree, f"Missing reviewed {name} in release commit; commit reviewed notices/policy before release")
    return tree


def archive_files(archive, tree, tag):
    prefix = f"SplitFree-{tag}/"
    files = {}
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as source:
        for member in source:
            require(member.name.startswith(prefix) or member.name == prefix[:-1], "Incorrect source archive prefix")
            name = member.name[len(prefix):]
            if member.isdir():
                require(not name or any(path.startswith(name.rstrip("/") + "/") for path in tree), "Unexpected archive directory")
                continue
            safe_source_path(name)
            require(member.isfile() and name in tree and name not in files, f"Unexpected archive member: {name}")
            data = source.extractfile(member).read()
            require(not data.startswith(b"version https://git-lfs.github.com/spec/v1\n"), f"Unresolved Git LFS pointer in source: {name}")
            mode, digest = tree[name]
            blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
            require(blob == digest and bool(member.mode & 0o111) == (mode == "100755"), f"Archive differs from commit: {name}")
            files[name] = data
    require(set(files) == set(tree), "Source archive omitted tracked files (check export-ignore attributes)")
    return files


def source_bundle(meta):
    tree = source_tree(meta["commit"])
    archive = run("git", "archive", "--format=tar", f"--prefix=SplitFree-{meta['tag']}/", meta["commit"])
    files = archive_files(archive, tree, meta["tag"])
    identity = gradle_identity(files["app/build.gradle.kts"].decode())
    require(identity["versionName"] == meta["versionName"] and int(identity["versionCode"]) == meta["versionCode"], "Metadata differs from release commit")
    policy_check(json.loads(files["release/policy.json"]), files[NOTICES], meta["versionName"])
    require(files["LICENSE"].strip(), "LICENSE must be nonempty")
    compressed = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=compressed, mtime=0) as stream:
        stream.write(archive)
    return {"source.tar.gz": compressed.getvalue(), "LICENSE.txt": files["LICENSE"], "THIRD-PARTY-NOTICES.txt": files[NOTICES]}


def checkout_check(commit, clean=True):
    require(run("git", "rev-parse", "HEAD").decode().strip() == commit, "HEAD does not match release commit")
    if clean:
        require(not run("git", "status", "--porcelain", "--untracked-files=no"), "Tracked worktree must be clean")


def validate(args):
    require(re.fullmatch("v" + VERSION, args.tag), "Tag must be stable vX.Y.Z")
    require(re.fullmatch(r"[0-9a-f]{40}", args.commit), "Commit must be full 40 lowercase hex characters")
    require(re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?/[A-Za-z0-9_.-]{1,100}", args.repository)
            and args.repository.split("/")[1] not in {".", ".."}, "Repository must be owner/name")
    checkout_check(args.commit)
    require(run("git", "rev-parse", "--verify", f"refs/tags/{args.tag}^{{commit}}").decode().strip() == args.commit, "Release tag does not point to commit")
    run("git", "merge-base", "--is-ancestor", args.commit, "refs/remotes/origin/mainline")
    source_tree(args.commit)
    identity = gradle_identity(run("git", "show", f"{args.commit}:app/build.gradle.kts").decode())
    meta = metadata(dict(schemaVersion=1, tag=args.tag, commit=args.commit, versionName=identity["versionName"],
                         versionCode=int(identity["versionCode"]), packageName=identity["applicationId"]))
    policy_check(json.loads(run("git", "show", f"{args.commit}:release/policy.json")),
                 run("git", "show", f"{args.commit}:{NOTICES}"), meta["versionName"])
    with Path(args.output).open("xb") as output:
        output.write(json_bytes(meta))


def manifest_check(badging, xml, meta):
    packages = re.findall(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    require(packages == [(meta["packageName"], str(meta["versionCode"]), meta["versionName"])], "APK package/version mismatch")
    require(re.findall(r"^(?:minSdkVersion|sdkVersion):'([^']+)'", badging, re.M) == ["26"], "APK minSDK must be 26")
    require("application-debuggable" not in badging, "APK is debuggable")
    for element in ("manifest", "uses-sdk", "application"):
        require(len(re.findall(r"^\s*E: " + element + r"(?:\s|$)", xml, re.M)) == 1, f"Invalid APK manifest {element}")
    for name, expected in {"package": meta["packageName"], "versionName": meta["versionName"],
                           "versionCode": meta["versionCode"], "minSdkVersion": 26, "debuggable": False}.items():
        values = re.findall(r"^\s*A: (?:http://schemas.android.com/apk/res/android:|android:)?" + name + r"(?:\(0x[0-9a-f]+\))?=(.*?)\s*$", xml, re.M)
        if name == "debuggable" and not values:
            continue  # Android's manifest default is false.
        require(len(values) == 1, f"Invalid APK manifest {name}")
        value = re.sub(r"\s+\(Raw: .*\)$", "", values[0])
        if isinstance(expected, str):
            require(value == json.dumps(expected), f"APK manifest {name} mismatch")
        else:
            value = re.sub(r"^\(type 0x[0-9a-f]+\)", "", value)
            require(value in ({"false", "0", "0x0", "0x00000000"} if name == "debuggable" else {str(expected), hex(expected), f"0x{expected:08x}"}), f"APK manifest {name} mismatch")


def elf_check(data, name):
    require(len(data) >= 16 and data[:4] == b"\x7fELF" and data[4] in {1, 2} and data[5] in {1, 2} and data[6] == 1, f"Bad ELF: {name}")
    if any(f"/{abi}/" in name for abi in ("arm64-v8a", "x86_64", "riscv64")):
        require(data[4] == 2, f"Expected 64-bit ELF: {name}")
    if data[4] != 2:
        return
    require(len(data) >= 64, f"Truncated ELF: {name}")
    endian = "<" if data[5] == 1 else ">"
    phoff = struct.unpack_from(endian + "Q", data, 32)[0]
    ehsize, phsize, count = struct.unpack_from(endian + "HHH", data, 52)
    require(ehsize == 64 and phsize == 56 and 0 < count < 65535 and phoff >= 64 and phoff + count * phsize <= len(data), f"Bad ELF program headers: {name}")
    loads = 0
    for index in range(count):
        kind, flags, offset, address, physical, size, memory, alignment = struct.unpack_from(endian + "IIQQQQQQ", data, phoff + index * phsize)
        if kind != 1:
            continue
        loads += 1
        require(size <= memory and offset + size <= len(data), f"Bad ELF PT_LOAD bounds: {name}")
        require(alignment >= 16384 and alignment & (alignment - 1) == 0, f"ELF PT_LOAD alignment below 16KB or not power-of-two: {name}")
        require(offset % alignment == address % alignment, f"ELF PT_LOAD offset/vaddr incongruent: {name}")
    require(loads, f"ELF has no PT_LOAD: {name}")


def apk_entries(apk, unsigned=False):
    raw = Path(apk).read_bytes()
    eocd = raw.rfind(b"PK\x05\x06", max(0, len(raw) - 65557))
    require(eocd >= 0 and eocd + 22 <= len(raw), "Invalid APK ZIP end record")
    disk, central_disk, count_disk, count, size, central, comment = struct.unpack_from("<HHHHIIH", raw, eocd + 4)
    require(disk == central_disk == 0 and count_disk == count and count < 65535 and central + size == eocd and eocd + 22 + comment == len(raw), "Unsupported APK ZIP layout")
    if unsigned:
        require(raw[max(0, central - 16):central] != b"APK Sig Block 42", "Expected unsigned APK; found APK Signing Block")
    entries = {}
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        require(len(archive.infolist()) == count, "APK entry count mismatch")
        for entry in archive.infolist():
            name = entry.filename
            require(name not in entries and not name.startswith("/") and ".." not in PurePosixPath(name).parts and "\\" not in name and not entry.flag_bits & 1, f"Unsafe/duplicate APK entry: {name}")
            signature = SIGNATURE_ENTRY.fullmatch(name)
            require(not (unsigned and signature and name.upper() != "META-INF/MANIFEST.MF"), "Expected unsigned APK; found v1 signature entry")
            data = archive.read(entry)
            if name.endswith(".so"):
                require(entry.compress_type == zipfile.ZIP_STORED, f"Native libraries must be uncompressed: {name}")
                elf_check(data, name)
                header = entry.header_offset
                require(raw[header:header + 4] == b"PK\x03\x04" and header + 30 <= len(raw), "Invalid ZIP local header")
                name_length, extra_length = struct.unpack_from("<HH", raw, header + 26)
                offset = header + 30 + name_length + extra_length
                require(offset % 16384 == 0, f"Uncompressed native ZIP offset not aligned to 16KB: {name}")
            entries[name] = (sha256(data), entry.compress_type)
    require("AndroidManifest.xml" in entries, "APK has no AndroidManifest.xml")
    return {name: value for name, value in entries.items() if not SIGNATURE_ENTRY.fullmatch(name)}


def inspect_apk(apk, meta, build_tools, unsigned=False):
    tools = Path(build_tools)
    entries = apk_entries(apk, unsigned)
    badging = run(tools / "aapt2", "dump", "badging", apk).decode()
    xml = run(tools / "aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", apk).decode()
    manifest_check(badging, xml, meta)
    run(tools / "zipalign", "-c", "-P", "16", "-v", "4", apk)
    return entries


def certificate_check(output, certificate):
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", output, re.M)
    require(len(digests) == 1 and digests[0].lower() == certificate, "APK signing certificate SHA-256 mismatch or multiple signers")


def write_package(output, files):
    output = Path(output)
    require(not output.is_symlink() and (not output.exists() or output.is_dir() and not any(output.iterdir())), "Output directory must be absent or empty (no overwrite)")
    output.mkdir(parents=True, exist_ok=True)
    for name, data in sorted(files.items()):
        with (output / name).open("xb") as stream:
            stream.write(data)


def prepare(args):
    meta = metadata(json.loads(Path(args.metadata).read_bytes()))
    checkout_check(meta["commit"])
    inspect_apk(args.apk, meta, args.build_tools, unsigned=True)
    files = source_bundle(meta)
    files.update({"unsigned.apk": Path(args.apk).read_bytes(), "metadata.json": json_bytes(meta)})
    write_package(args.output, files)


def finalize(args):
    require(isinstance(args.certificate, str) and re.fullmatch(r"[0-9a-fA-F]{64}", args.certificate), "Provide the approved signing certificate SHA-256 (64 hex characters) before finalizing")
    certificate = args.certificate.lower()
    meta = metadata(json.loads(Path(args.metadata).read_bytes()))
    checkout_check(meta["commit"], clean=False)
    package = Path(args.package)
    require(not package.is_symlink() and package.is_dir() and {p.name for p in package.iterdir()} == PACKAGE_FILES, "Prepared package must contain exactly the five allowlisted files")
    require(all(p.is_file() and not p.is_symlink() for p in package.iterdir()), "Prepared package must contain regular files, not links")
    require(metadata(json.loads((package / "metadata.json").read_bytes())) == meta, "Prepared metadata mismatch")
    source = source_bundle(meta)
    require(all((package / name).read_bytes() == data for name, data in source.items()), "Prepared source/license/notices differ from release commit")
    unsigned = inspect_apk(package / "unsigned.apk", meta, args.build_tools, unsigned=True)
    signed = inspect_apk(args.apk, meta, args.build_tools)
    require(signed == unsigned, "Signed APK entry payloads/compression differ from unsigned APK")
    signature = run(Path(args.build_tools) / "apksigner", "verify", "--verbose", "--print-certs", args.apk).decode()
    certificate_check(signature, certificate)
    stem = f"SplitFree-{meta['tag']}"
    files = {f"{stem}.apk": Path(args.apk).read_bytes(), f"{stem}-source.tar.gz": source["source.tar.gz"],
             "LICENSE.txt": source["LICENSE.txt"], "THIRD-PARTY-NOTICES.txt": source["THIRD-PARTY-NOTICES.txt"]}
    hashes = {name: sha256(data) for name, data in sorted(files.items())}
    files["release-info.json"] = json_bytes({**meta, "certificateSha256": certificate, "artifacts": hashes})
    hashes["release-info.json"] = sha256(files["release-info.json"])
    files["SHA256SUMS.txt"] = "".join(f"{digest}  {name}\n" for name, digest in sorted(hashes.items())).encode()
    write_package(args.output, files)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name, handler, fields in (
        ("validate", validate, ("tag", "commit", "repository", "output")),
        ("prepare", prepare, ("metadata", "apk", "output", "build-tools")),
        ("finalize", finalize, ("metadata", "package", "apk", "output", "build-tools", "certificate")),
    ):
        command = commands.add_parser(name)
        for field in fields:
            command.add_argument("--" + field, required=True)
        command.set_defaults(handler=handler)
    args = parser.parse_args(argv)
    try:
        args.handler(args)
    except (ReleaseError, OSError, UnicodeError, json.JSONDecodeError, zipfile.BadZipFile, tarfile.TarError, struct.error) as error:
        parser.exit(1, f"release: {error}\n")


if __name__ == "__main__":
    main()
