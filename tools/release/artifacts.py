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
import stat
import struct
import subprocess
import tarfile
import zipfile
import zlib

import publication_policy as publication


VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
METADATA_KEYS = {"schemaVersion", "tag", "commit", "versionName", "versionCode", "packageName"}
PACKAGE_FILES = {"unsigned.apk", "metadata.json", "source.tar.gz", "LICENSE.txt", "THIRD-PARTY-NOTICES.txt"}
SIGNING_CERTIFICATE = "release/signing-certificate.sha256"
MAX_APK_BYTES = 256 * 1024 * 1024
MAX_APK_UNCOMPRESSED_BYTES = 512 * 1024 * 1024
ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android:"
NATIVE_ABIS = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86": (1, 3), "x86_64": (2, 62)}
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
    try:
        result = subprocess.run([str(arg) for arg in command], capture_output=True, timeout=120,
                                env={**os.environ, "GIT_OPTIONAL_LOCKS": "0", "GIT_NO_LAZY_FETCH": "1"})
    except subprocess.TimeoutExpired as error:
        raise ReleaseError(f"{command[0]} timed out after 120 seconds") from error
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
    try:
        publication.safe_source_path(name)
    except publication.PublicationError as error:
        raise ReleaseError(str(error)) from None


def inspect_source(name, data):
    try:
        publication.inspect_source(name, data)
    except publication.PublicationError as error:
        raise ReleaseError(str(error)) from None


def source_tree(commit):
    tree = {}
    total = 0
    for entry in run("git", "ls-tree", "-rlz", "--full-tree", commit).split(b"\0"):
        if not entry:
            continue
        attributes, name = entry.decode().split("\t", 1)
        mode, kind, digest, size = attributes.split()
        safe_source_path(name)
        require(kind == "blob" and mode in {"100644", "100755"}, f"Source links/submodules are not allowed: {name}")
        require(size.isdecimal() and int(size) <= publication.MAX_SOURCE_BYTES, "SourceSizeLimit")
        total += int(size)
        require(total <= publication.MAX_TOTAL_BYTES and len(tree) < publication.MAX_FILES, "SourceTotalLimit")
        require(name not in tree, "Duplicate source path")
        tree[name] = (mode, digest)
    for name in ("app/build.gradle.kts", "LICENSE", "release/policy.json", NOTICES, SIGNING_CERTIFICATE):
        require(name in tree, f"Missing reviewed {name} in release commit; commit reviewed notices/policy before release")
    return tree


def archive_files(archive, tree, tag):
    prefix = f"SplitFree-{tag}/"
    files = {}
    total = 0
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as source:
        for member in source:
            require(member.name.startswith(prefix) or member.name == prefix[:-1], "Incorrect source archive prefix")
            name = member.name[len(prefix):]
            if member.isdir():
                require(not name or any(path.startswith(name.rstrip("/") + "/") for path in tree), "Unexpected archive directory")
                continue
            safe_source_path(name)
            require(member.isfile() and name in tree and name not in files, f"Unexpected archive member: {name}")
            require(0 <= member.size <= publication.MAX_SOURCE_BYTES, "SourceSizeLimit")
            total += member.size
            require(total <= publication.MAX_TOTAL_BYTES and len(files) < publication.MAX_FILES, "SourceTotalLimit")
            data = source.extractfile(member).read(publication.MAX_SOURCE_BYTES + 1)
            require(len(data) == member.size, "Source size differs from archive header")
            require(not data.startswith(b"version https://git-lfs.github.com/spec/v1\n"), f"Unresolved Git LFS pointer in source: {name}")
            inspect_source(name, data)
            mode, digest = tree[name]
            blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
            require(blob == digest and bool(member.mode & 0o111) == (mode == "100755"), f"Archive differs from commit: {name}")
            files[name] = data
    require(set(files) == set(tree), "Source archive omitted tracked files (check export-ignore attributes)")
    return files


def canonical_source_archive(files, tree, tag):
    """Emit only verified file bytes/modes, never input PAX metadata or tar trailers."""
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w", format=tarfile.PAX_FORMAT) as archive:
        for name, data in sorted(files.items()):
            member = tarfile.TarInfo(f"SplitFree-{tag}/{name}")
            member.size = len(data)
            member.mode = 0o755 if tree[name][0] == "100755" else 0o644
            archive.addfile(member, io.BytesIO(data))
    return buffer.getvalue()


def source_bundle(meta, certificate=None):
    tree = source_tree(meta["commit"])
    archive = run("git", "archive", "--format=tar", f"--prefix=SplitFree-{meta['tag']}/", meta["commit"])
    files = archive_files(archive, tree, meta["tag"])
    identity = gradle_identity(files["app/build.gradle.kts"].decode())
    require(identity["versionName"] == meta["versionName"] and int(identity["versionCode"]) == meta["versionCode"], "Metadata differs from release commit")
    policy_check(json.loads(files["release/policy.json"]), files[NOTICES], meta["versionName"])
    committed_certificate = normalized_certificate(files[SIGNING_CERTIFICATE].decode().strip())
    if certificate is not None:
        require(committed_certificate == normalized_certificate(certificate), "Approved signing certificate differs from release commit pin")
    require(files["LICENSE"].strip(), "LICENSE must be nonempty")
    compressed = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=compressed, mtime=0) as stream:
        stream.write(canonical_source_archive(files, tree, meta["tag"]))
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


def manifest_tree(xml):
    roots, stack = [], []
    for line in xml.splitlines():
        if not line.strip():
            continue
        require("\t" not in line, "Invalid APK manifest indentation")
        indent = len(line) - len(line.lstrip(" "))
        text = line[indent:]
        if re.fullmatch(r"N: [^=\s]+=[^\s]+(?: \(line=\d+\))?", text):
            continue
        element = re.fullmatch(r"E: ([A-Za-z_][A-Za-z0-9_.:-]*)(?: \(line=\d+\))?", text)
        if element:
            while stack and indent <= stack[-1]["indent"]:
                stack.pop()
            node = {"name": element[1], "attributes": {}, "children": [], "indent": indent}
            (stack[-1]["children"] if stack else roots).append(node)
            stack.append(node)
            continue
        attribute = re.fullmatch(r"A: (.+?)(?:\(0x[0-9a-fA-F]+\))?=(.+)", text)
        require(attribute and stack and indent > stack[-1]["indent"], "Invalid APK manifest attribute or xmltree record")
        node = stack[-1]
        require(not node["children"] and indent == node.setdefault("attributeIndent", indent), "Invalid APK manifest attribute scope")
        name = attribute[1].replace(ANDROID_NAMESPACE, "android:", 1) if attribute[1].startswith(ANDROID_NAMESPACE) else attribute[1]
        require(name not in node["attributes"], f"Duplicate APK manifest attribute: {name}")
        node["attributes"][name] = re.sub(r"\s+\(Raw: .*\)$", "", attribute[2])
    require(len(roots) == 1 and roots[0]["name"] == "manifest", "Invalid APK manifest root")
    return roots[0]


def manifest_value(node, name, kind):
    value = node["attributes"].get(name)
    require(value is not None, f"Missing APK manifest {node['name']} {name}")
    if kind is str:
        require(re.fullmatch(r'"(?:[^"\\]|\\.)*"', value), f"Invalid APK manifest string {name}")
        try:
            return json.loads(value)
        except json.JSONDecodeError as error:
            raise ReleaseError(f"Invalid APK manifest string {name}") from error
    if kind is bool:
        require(re.fullmatch(r"(?:\(type 0x12\))?(?:false|0|0x0{1,8})", value), f"APK manifest {name} must be false")
        return False
    match = re.fullmatch(r"(?:\(type 0x1[01]\))?([0-9]+|0x[0-9a-fA-F]+)", value)
    require(match, f"APK manifest {name} must be numeric")
    return int(match[1], 16 if match[1].startswith("0x") else 10)


def manifest_identity(root):
    identity = {"packageName": manifest_value(root, "package", str),
                "versionCode": manifest_value(root, "android:versionCode", int),
                "versionName": manifest_value(root, "android:versionName", str)}
    require(re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", identity["packageName"]), "Invalid APK package identity")
    require(0 < identity["versionCode"] <= 2100000000 and identity["versionName"], "Invalid APK version identity")
    if "android:versionCodeMajor" in root["attributes"]:
        require(manifest_value(root, "android:versionCodeMajor", int) == 0, "APK versionCodeMajor must be zero")
    return identity


def badging_identity_check(badging, identity):
    packages = re.findall(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    require(packages == [(identity["packageName"], str(identity["versionCode"]), identity["versionName"])], "APK package/version mismatch")


def manifest_check(badging, xml, meta):
    root = manifest_tree(xml)
    identity = manifest_identity(root)
    require(identity["packageName"] == "com.splitfree", "APK package must be com.splitfree")
    require(isinstance(meta, dict) and type(meta.get("versionCode")) is int
            and identity == {key: meta.get(key) for key in identity}, "APK package/version mismatch")
    badging_identity_check(badging, identity)
    for names, expected in (("minSdkVersion|sdkVersion", "26"), ("targetSdkVersion", "37")):
        require(re.findall(r"^(?:" + names + r"):'([^']+)'$", badging, re.M) == [expected], f"APK SDK badging must be numeric {expected}")
    require(not re.search(r"^application-(?:debuggable|testOnly)(?:\s|$)", badging, re.M), "APK is debuggable or testOnly")
    nodes, pending = [], [root]
    while pending:
        node = pending.pop()
        nodes.append(node)
        pending.extend(node["children"])
    scoped = {}
    for element in ("uses-sdk", "application"):
        matches = [node for node in nodes if node["name"] == element]
        require(len(matches) == 1 and matches[0] in root["children"], f"Invalid APK manifest {element} scope")
        scoped[element] = matches[0]
    require(sum(node["name"] == "manifest" for node in nodes) == 1, "Invalid nested APK manifest")
    sdk, app = scoped["uses-sdk"], scoped["application"]
    require(manifest_value(sdk, "android:minSdkVersion", int) == 26, "APK minSDK must be 26")
    require(manifest_value(sdk, "android:targetSdkVersion", int) == 37, "APK targetSDK must be numeric 37")
    require("android:maxSdkVersion" not in sdk["attributes"], "APK maxSDK is forbidden")
    require("android:sharedUserId" not in root["attributes"], "APK sharedUserId is forbidden")
    for name in ("debuggable", "testOnly"):
        if "android:" + name in app["attributes"]:
            manifest_value(app, "android:" + name, bool)
    for node in (root, app):
        for name in ("split", "configForSplit", "android:splitTypes", "android:requiredSplitTypes"):
            require(name not in node["attributes"], f"APK split marker is forbidden: {name}")
        for name in ("android:isSplitRequired", "android:isFeatureSplit", "android:isolatedSplits"):
            if name in node["attributes"]:
                manifest_value(node, name, bool)
    for node in nodes:
        require(node["name"] != "uses-split" and "android:splitName" not in node["attributes"], "APK must be a standalone base application without splits")
    for node in app["children"]:
        if node["name"] == "meta-data":
            name = manifest_value(node, "android:name", str)
            require(name not in {"com.android.vending.splits", "com.android.vending.splits.required"}, "APK split metadata is forbidden")
    return {**identity, "minSdk": 26, "targetSdk": 37, "debuggable": False, "testOnly": False, "standalone": True}


def elf_check(data, name):
    parts = name.split("/")
    require(len(parts) == 3 and parts[0] == "lib" and parts[1] in NATIVE_ABIS
            and parts[2].endswith(".so"), f"Unsupported native library path/ABI: {name}")
    elf_class, machine = NATIVE_ABIS[parts[1]]
    require(len(data) >= 16 and data[:4] == b"\x7fELF" and data[4] == elf_class
            and data[5:7] == b"\x01\x01", f"Bad ELF class/encoding for ABI: {name}")
    header_size, program_size = (64, 56) if elf_class == 2 else (52, 32)
    require(len(data) >= header_size, f"Truncated ELF: {name}")
    require(struct.unpack_from("<HHI", data, 16) == (3, machine, 1), f"Bad ELF type/machine/version for ABI: {name}")
    phoff = struct.unpack_from("<Q" if elf_class == 2 else "<I", data, 32 if elf_class == 2 else 28)[0]
    ehsize, phsize, count = struct.unpack_from("<HHH", data, 52 if elf_class == 2 else 40)
    require(ehsize == header_size and phsize == program_size and 0 < count < 65535
            and phoff >= header_size and phoff + count * phsize <= len(data), f"Bad ELF program headers: {name}")
    loads = 0
    for index in range(count):
        position = phoff + index * phsize
        if elf_class == 2:
            kind, flags, offset, address, physical, size, memory, alignment = struct.unpack_from("<IIQQQQQQ", data, position)
        else:
            kind, offset, address, physical, size, memory, flags, alignment = struct.unpack_from("<IIIIIIII", data, position)
        if kind != 1:
            continue
        loads += 1
        require(size <= memory and offset + size <= len(data), f"Bad ELF PT_LOAD bounds: {name}")
        minimum = 16384 if elf_class == 2 else 4096
        require(alignment >= minimum and alignment & (alignment - 1) == 0, f"ELF PT_LOAD alignment below {minimum} or not power-of-two: {name}")
        require(offset % alignment == address % alignment, f"ELF PT_LOAD offset/vaddr incongruent: {name}")
    require(loads, f"ELF has no PT_LOAD: {name}")


def apk_entries(apk, unsigned=False):
    return apk_payloads(read_apk_bytes(apk), unsigned)


def apk_payloads(raw, unsigned=False):
    require(len(raw) <= MAX_APK_BYTES, "APK exceeds size limit")
    try:
        return zip_payloads(raw, unsigned)
    except (zipfile.BadZipFile, struct.error, zlib.error) as error:
        raise ReleaseError(f"Malformed APK ZIP: {error}") from error


def zip_payloads(raw, unsigned=False):
    eocd = raw.rfind(b"PK\x05\x06", max(0, len(raw) - 65557))
    require(eocd >= 0 and eocd + 22 <= len(raw), "Invalid APK ZIP end record")
    disk, central_disk, count_disk, count, size, central, comment = struct.unpack_from("<HHHHIIH", raw, eocd + 4)
    require(disk == central_disk == 0 and count_disk == count and count < 65535 and central + size == eocd and eocd + 22 + comment == len(raw), "Unsupported APK ZIP layout")
    if unsigned:
        require(raw[max(0, central - 16):central] != b"APK Sig Block 42", "Expected unsigned APK; found APK Signing Block")
    entries, libraries = {}, {abi: set() for abi in NATIVE_ABIS}
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        require(len(archive.infolist()) == count, "APK entry count mismatch")
        require(all(entry.file_size <= MAX_APK_BYTES for entry in archive.infolist())
                and sum(entry.file_size for entry in archive.infolist()) <= MAX_APK_UNCOMPRESSED_BYTES,
                "APK decompressed size exceeds limit")
        for entry in archive.infolist():
            name = entry.filename
            require(name not in entries and not name.startswith("/") and ".." not in PurePosixPath(name).parts and "\\" not in name and not entry.flag_bits & 1, f"Unsafe/duplicate APK entry: {name}")
            require(name == entry.orig_filename and name.rstrip("/") == str(PurePosixPath(name))
                    and not stat.S_ISLNK(entry.external_attr >> 16), f"Noncanonical APK entry: {name}")
            require(entry.compress_type in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}, f"Unsupported APK compression: {name}")
            signature = SIGNATURE_ENTRY.fullmatch(name)
            require(not (unsigned and signature and name.upper() != "META-INF/MANIFEST.MF"), "Expected unsigned APK; found v1 signature entry")
            header = entry.header_offset
            require(0 <= header and header + 30 <= central and raw[header:header + 4] == b"PK\x03\x04", "Invalid ZIP local header")
            flags, compression = struct.unpack_from("<HH", raw, header + 6)
            name_length, extra_length = struct.unpack_from("<HH", raw, header + 26)
            offset = header + 30 + name_length + extra_length
            require(flags == entry.flag_bits and compression == entry.compress_type
                    and offset + entry.compress_size <= central, "APK local/central ZIP header mismatch or bounds")
            data = archive.read(entry)
            if name.endswith(".so"):
                require(entry.compress_type == zipfile.ZIP_STORED, f"Native libraries must be uncompressed: {name}")
                elf_check(data, name)
                _, abi, library = name.split("/")
                libraries[abi].add(library)
                require(offset % 16384 == 0, f"Uncompressed native ZIP offset not aligned to 16KB: {name}")
            entries[name] = (sha256(data), entry.compress_type)
    require("AndroidManifest.xml" in entries, "APK has no AndroidManifest.xml")
    abis = {abi for abi, names in libraries.items() if names}
    require(abis == set(NATIVE_ABIS), f"APK must contain all four native ABIs: {sorted(NATIVE_ABIS)}; found {sorted(abis)}")
    require(len({frozenset(names) for names in libraries.values()}) == 1, "APK native library names must match across all four ABIs")
    return {name: value for name, value in entries.items() if not SIGNATURE_ENTRY.fullmatch(name)}


def file_identity(status):
    return (status.st_dev, status.st_ino, status.st_mode, status.st_size, status.st_mtime_ns, status.st_ctime_ns)


def read_apk_bytes(apk):
    with Path(apk).open("rb") as stream:
        require(stat.S_ISREG(os.fstat(stream.fileno()).st_mode), "APK must be a regular file")
        require(os.fstat(stream.fileno()).st_size <= MAX_APK_BYTES, "APK exceeds size limit")
        data = stream.read(MAX_APK_BYTES + 1)
    require(len(data) <= MAX_APK_BYTES, "APK exceeds size limit")
    return data


def apk_snapshot(apk):
    path = Path(apk)
    before = path.lstat()
    require(stat.S_ISREG(before.st_mode), "APK must be a regular file, not a link")
    require(before.st_size <= MAX_APK_BYTES, "APK exceeds size limit")
    with path.open("rb") as stream:
        require(file_identity(os.fstat(stream.fileno())) == file_identity(before), "APK changed while opening")
        data = stream.read(MAX_APK_BYTES + 1)
        require(len(data) <= MAX_APK_BYTES, "APK exceeds size limit")
        require(file_identity(os.fstat(stream.fileno())) == file_identity(before), "APK changed while reading")
    require(file_identity(path.lstat()) == file_identity(before), "APK changed while reading")
    return file_identity(before), sha256(data), len(data)


def apk_tool(apk, snapshot, *command):
    require(apk_snapshot(apk) == snapshot, "APK changed during verification")
    try:
        return run(*command, apk).decode()
    finally:
        require(apk_snapshot(apk) == snapshot, "APK changed during verification")


def read_apk_identity(apk, build_tools, snapshot=None):
    apk = Path(apk).absolute()
    snapshot = snapshot or apk_snapshot(apk)
    xml = apk_tool(apk, snapshot, Path(build_tools) / "aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml")
    identity = manifest_identity(manifest_tree(xml))
    badging = apk_tool(apk, snapshot, Path(build_tools) / "aapt2", "dump", "badging")
    badging_identity_check(badging, identity)
    return identity


def inspect_apk_contents(apk, meta, build_tools, unsigned, snapshot):
    tools = Path(build_tools)
    entries = apk_entries(apk, unsigned)
    badging = apk_tool(apk, snapshot, tools / "aapt2", "dump", "badging")
    xml = apk_tool(apk, snapshot, tools / "aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml")
    manifest = manifest_check(badging, xml, meta)
    apk_tool(apk, snapshot, tools / "zipalign", "-c", "-P", "16", "-v", "4")
    return entries, manifest


def inspect_apk(apk, meta, build_tools, unsigned=False):
    apk = Path(apk).absolute()
    return inspect_apk_contents(apk, meta, build_tools, unsigned, apk_snapshot(apk))[0]


def normalized_certificate(certificate):
    require(isinstance(certificate, str) and re.fullmatch(r"[0-9a-fA-F]{64}", certificate),
            "Provide the approved signing certificate SHA-256 (64 hex characters) before finalizing or inspecting")
    return certificate.lower()


def certificate_check(output, certificate):
    certificate = normalized_certificate(certificate)
    digests = re.findall(r"^Signer #([^\n]+) certificate SHA-256 digest: ([^\n]*)$", output, re.M)
    require(len(digests) == 1 and digests[0][0] == "1" and digests[0][1].lower() == certificate, "APK signing certificate SHA-256 mismatch or multiple signers")


def signing_schemes(output):
    require(re.findall(r"^Verifies$", output, re.M) == ["Verifies"], "APK signature did not verify")
    require(re.findall(r"^Number of signers: (\d+)$", output, re.M) == ["1"], "APK must have exactly one signer")
    matches = re.findall(r"^Verified using (v[0-9]+(?:\.[0-9]+)?) scheme \([^\n]+\): (true|false)$", output, re.M)
    schemes = dict(matches)
    require(len(matches) == len(schemes) and schemes.get("v2") == "true", "APK must verify with v2 signing for minSDK 26 / targetSDK 37")
    return sorted(name for name, verified in schemes.items() if verified == "true")


def inspect_signed_apk(apk, meta, build_tools, certificate):
    certificate = normalized_certificate(certificate)
    apk = Path(apk).absolute()
    snapshot = apk_snapshot(apk)
    entries, manifest = inspect_apk_contents(apk, meta, build_tools, False, snapshot)
    signature = apk_tool(apk, snapshot, Path(build_tools) / "apksigner", "verify", "--verbose", "--print-certs", "--min-sdk-version", "26")
    certificate_check(signature, certificate)
    schemes = signing_schemes(signature)
    require(apk_snapshot(apk) == snapshot, "APK changed during verification")
    return {"apkSha256": snapshot[1], "apkBytes": snapshot[2], "certificateSha256": certificate,
            "manifest": manifest, "abis": sorted(NATIVE_ABIS), "payloadEntries": len(entries), "signingSchemes": schemes}


def inspect_signed_baseline(apk, build_tools, certificate):
    """Verify an already distributed production APK as the predecessor of a new candidate.

    A baseline has to be the genuine, pinned-signer production package whose versionCode
    the candidate must exceed. Packaging policy (SDK levels, native ABIs, alignment) is
    the candidate's contract and is not applied here, so a policy change never rejects
    the release it is meant to supersede.
    """
    certificate = normalized_certificate(certificate)
    apk = Path(apk).absolute()
    snapshot = apk_snapshot(apk)
    identity = read_apk_identity(apk, build_tools, snapshot)
    require(identity["packageName"] == "com.splitfree", "Baseline APK package must be com.splitfree")
    signature = apk_tool(apk, snapshot, Path(build_tools) / "apksigner", "verify", "--verbose", "--print-certs", "--min-sdk-version", "26")
    certificate_check(signature, certificate)
    schemes = signing_schemes(signature)
    require(apk_snapshot(apk) == snapshot, "APK changed during verification")
    return {"apkSha256": snapshot[1], "apkBytes": snapshot[2], "certificateSha256": certificate,
            "manifest": identity, "signingSchemes": schemes}


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
    snapshot = apk_snapshot(args.apk)
    inspect_apk(args.apk, meta, args.build_tools, unsigned=True)
    files = source_bundle(meta)
    unsigned_bytes = read_apk_bytes(args.apk)
    require(apk_snapshot(args.apk) == snapshot and sha256(unsigned_bytes) == snapshot[1]
            and len(unsigned_bytes) == snapshot[2], "APK changed after unsigned verification")
    files.update({"unsigned.apk": unsigned_bytes, "metadata.json": json_bytes(meta)})
    write_package(args.output, files)


def finalize(args):
    certificate = normalized_certificate(args.certificate)
    meta = metadata(json.loads(Path(args.metadata).read_bytes()))
    checkout_check(meta["commit"], clean=False)
    package = Path(args.package)
    require(not package.is_symlink() and package.is_dir() and {p.name for p in package.iterdir()} == PACKAGE_FILES, "Prepared package must contain exactly the five allowlisted files")
    require(all(p.is_file() and not p.is_symlink() for p in package.iterdir()), "Prepared package must contain regular files, not links")
    require(metadata(json.loads((package / "metadata.json").read_bytes())) == meta, "Prepared metadata mismatch")
    source = source_bundle(meta, certificate)
    require(all((package / name).read_bytes() == data for name, data in source.items()), "Prepared source/license/notices differ from release commit")
    unsigned = inspect_apk(package / "unsigned.apk", meta, args.build_tools, unsigned=True)
    inspection = inspect_signed_apk(args.apk, meta, args.build_tools, certificate)
    signed_bytes = read_apk_bytes(args.apk)
    require(sha256(signed_bytes) == inspection["apkSha256"] and len(signed_bytes) == inspection["apkBytes"], "APK changed after signed verification")
    require(apk_payloads(signed_bytes) == unsigned, "Signed APK entry payloads/compression differ from unsigned APK")
    stem = f"SplitFree-{meta['tag']}"
    files = {f"{stem}.apk": signed_bytes, f"{stem}-source.tar.gz": source["source.tar.gz"],
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
