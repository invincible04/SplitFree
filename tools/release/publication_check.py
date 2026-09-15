#!/usr/bin/env python3
"""Read-only, local publication checks for worktree, exact index, or explicit history."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import selectors
import stat
import subprocess
import sys
import time

sys.dont_write_bytecode = True

from publication_policy import MAX_FILES, MAX_SOURCE_BYTES, MAX_TOTAL_BYTES, PublicationError, inspect_source, safe_source_path

ROOT = Path(__file__).resolve().parents[2]
MAX_COMMITS = 2_000
MAX_ENTRIES = 1_000_000
MAX_FINDINGS = 20_000
MAX_METADATA_BYTES = 16 * 1024 * 1024
MAX_SECONDS = 180
SOURCE_DIRECTORIES = ("app/src", "app/schemas", "gradle")
OID = re.compile(rb"(?:[0-9a-f]{40}|[0-9a-f]{64})")
DIRECTORY_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW


class ScanFailure(ValueError):
    def __init__(self, rule, path=""):
        self.finding = {"path": path, "line": 0, "rule": rule}


class ScanAbort(ScanFailure):
    pass


class JsonParser(argparse.ArgumentParser):
    def error(self, message):
        raise ScanFailure("invalid_arguments")

    def print_help(self, file=None):
        print(json.dumps({"usage": "publication_check.py --source worktree|index|history [--root PATH] "
                                    "[--refs refs/heads/NAME ...]"}))


def git_environment():
    env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    env.update(GIT_OPTIONAL_LOCKS="0", GIT_NO_LAZY_FETCH="1", GIT_TERMINAL_PROMPT="0",
               GIT_NO_REPLACE_OBJECTS="1", GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
    return env


def identity(value):
    return value.st_dev, value.st_ino, value.st_mode, value.st_size, value.st_mtime_ns, value.st_ctime_ns


def directory_identity(value):
    return value.st_dev, value.st_ino, value.st_mode


@contextmanager
def parent_directory(root_fd, name):
    parts = name.split("/")
    if any(part in {"", ".", ".."} for part in parts) or "\\" in name:
        raise ScanFailure("unsafe_path", name)
    opened = []
    current = root_fd
    try:
        for part in parts[:-1]:
            before = os.stat(part, dir_fd=current, follow_symlinks=False)
            if not stat.S_ISDIR(before.st_mode):
                raise ScanFailure("source_link_or_special_file", name)
            descriptor = os.open(part, DIRECTORY_FLAGS, dir_fd=current)
            opened.append((current, part, descriptor, directory_identity(before)))
            if directory_identity(os.fstat(descriptor)) != directory_identity(before):
                raise ScanFailure("source_changed", name)
            current = descriptor
        yield current, parts[-1]
        for parent, part, descriptor, expected in opened:
            if (directory_identity(os.stat(part, dir_fd=parent, follow_symlinks=False)) != expected
                    or directory_identity(os.fstat(descriptor)) != expected):
                raise ScanFailure("source_changed", name)
    finally:
        for _, _, descriptor, _ in reversed(opened):
            os.close(descriptor)


class BlobReader:
    def __init__(self, scanner):
        self.scanner = scanner
        self.process = scanner.start_git("cat-file", "--batch-command", stdin=subprocess.PIPE)
        self.buffer = bytearray()
        self.selector = selectors.DefaultSelector()
        self.selector.register(self.process.stdout, selectors.EVENT_READ)

    def close(self):
        self.selector.close()
        if self.process.poll() is None:
            self.process.kill()
        self.process.wait()
        self.process.stdin.close()
        self.process.stdout.close()

    def receive(self, limit):
        if not self.selector.select(self.scanner.remaining()):
            raise ScanAbort("runtime_limit")
        data = os.read(self.process.stdout.fileno(), limit)
        if not data:
            raise ScanAbort("git_command_failed")
        self.buffer.extend(data)

    def header(self, command, digest):
        self.scanner.remaining()
        self.process.stdin.write(command + b" " + digest + b"\n")
        self.process.stdin.flush()
        while b"\n" not in self.buffer:
            if len(self.buffer) >= 128:
                raise ScanAbort("invalid_git_metadata")
            self.receive(128 - len(self.buffer))
        line, _, remainder = self.buffer.partition(b"\n")
        self.buffer = bytearray(remainder)
        fields = line.split()
        if len(fields) != 3 or fields[0] != digest or fields[1] != b"blob" or not fields[2].isdigit():
            raise ScanAbort("invalid_git_metadata")
        return int(fields[2])

    def read(self, name, digest):
        size = self.header(b"info", digest)
        self.scanner.check_size(size, name)
        if self.header(b"contents", digest) != size:
            raise ScanAbort("invalid_git_metadata", name)
        while len(self.buffer) < size + 1:
            self.receive(min(65536, size + 1 - len(self.buffer)))
        data = bytes(self.buffer[:size])
        if self.buffer[size:] != b"\n":
            raise ScanAbort("invalid_git_metadata", name)
        self.buffer.clear()
        self.scanner.bytes_read += size
        return data


class Scanner:
    def __init__(self, root, source, include_data=False):
        self.root = Path(root).absolute()
        self.source = source
        self.include_data = include_data
        self.files = {}
        self.findings = []
        self.files_checked = 0
        self.bytes_read = 0
        self.entries = 0
        self.blobs = None
        self.deadline = time.monotonic() + MAX_SECONDS

    def remaining(self):
        seconds = self.deadline - time.monotonic()
        if seconds <= 0:
            raise ScanAbort("runtime_limit")
        return seconds

    def entry(self):
        self.remaining()
        self.entries += 1
        if self.entries > MAX_ENTRIES:
            raise ScanAbort("entry_count_limit")

    def finding(self, value, context=None):
        if len(self.findings) >= MAX_FINDINGS:
            raise ScanAbort("finding_count_limit")
        self.findings.append({**value, **(context or {})})

    def policy_findings(self, error, context=None):
        for value in error.findings:
            self.finding({key: value[key] for key in ("path", "line", "rule")}, context)

    def start_git(self, *arguments, stdin=subprocess.DEVNULL):
        self.remaining()
        command = ["git", "--no-pager", "-C", str(self.root), "-c", "core.fsmonitor=false",
                   "-c", "core.untrackedCache=false", "-c", "protocol.allow=never", *arguments]
        return subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                stdin=stdin, env=git_environment(), bufsize=0)

    def git(self, *arguments, limit=MAX_METADATA_BYTES):
        with self.start_git(*arguments) as process:
            try:
                output = bytearray()
                with selectors.DefaultSelector() as selector:
                    selector.register(process.stdout, selectors.EVENT_READ)
                    while True:
                        if not selector.select(self.remaining()):
                            raise ScanAbort("runtime_limit")
                        chunk = os.read(process.stdout.fileno(), min(65536, limit + 1 - len(output)))
                        if not chunk:
                            break
                        output.extend(chunk)
                        if len(output) > limit:
                            raise ScanAbort("git_output_limit")
                if process.wait(timeout=self.remaining()) != 0:
                    raise ScanFailure("git_command_failed")
                return bytes(output)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()

    def check_size(self, size, name):
        if size > MAX_SOURCE_BYTES:
            raise ScanFailure("source_size_limit", name)
        if self.bytes_read + size > MAX_TOTAL_BYTES:
            raise ScanAbort("total_size_limit", name)

    def inspect(self, name, data):
        inspect_source(name, data)
        self.remaining()
        if self.include_data:
            self.files[name] = data

    def check_entry(self, name, reader, context=None):
        self.remaining()
        self.files_checked += 1
        if self.files_checked > MAX_FILES:
            raise ScanAbort("file_count_limit", name)
        try:
            data = reader()
            if data is not None:
                self.inspect(name, data)
        except PublicationError as error:
            self.policy_findings(error, context)
        except ScanAbort:
            raise
        except ScanFailure as error:
            self.finding(error.finding, context)
        except OSError:
            self.finding({"path": name, "line": 0, "rule": "source_io_error"}, context)

    def regular_bytes(self, root_fd, name):
        found = False
        try:
            with parent_directory(root_fd, name) as (parent, leaf):
                try:
                    before = os.stat(leaf, dir_fd=parent, follow_symlinks=False)
                except FileNotFoundError:
                    return None
                found = True
                if not stat.S_ISREG(before.st_mode):
                    raise ScanFailure("source_link_or_special_file", name)
                safe_source_path(name)
                self.check_size(before.st_size, name)
                descriptor = os.open(leaf, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
                with os.fdopen(descriptor, "rb") as stream:
                    if identity(os.fstat(stream.fileno())) != identity(before):
                        raise ScanFailure("source_changed", name)
                    data = stream.read(before.st_size)
                    self.bytes_read += len(data)
                    self.remaining()
                    if (identity(os.fstat(stream.fileno())) != identity(before)
                            or identity(os.stat(leaf, dir_fd=parent, follow_symlinks=False)) != identity(before)
                            or len(data) != before.st_size):
                        raise ScanFailure("source_changed", name)
                return data
        except FileNotFoundError:
            if found:
                raise ScanFailure("source_changed", name) from None
            # A missing parent is also a tracked worktree deletion; no bytes were opened.
            return None

    def directory_names(self, descriptor, prefix, names, depth=0):
        if depth > 64:
            raise ScanAbort("directory_depth_limit", prefix)
        directory_before = identity(os.fstat(descriptor))
        with os.scandir(descriptor) as entries:
            for entry in entries:
                self.entry()
                name = prefix + "/" + entry.name
                before = entry.stat(follow_symlinks=False)
                if stat.S_ISDIR(before.st_mode):
                    child = os.open(entry.name, DIRECTORY_FLAGS, dir_fd=descriptor)
                    try:
                        if directory_identity(os.fstat(child)) != directory_identity(before):
                            raise ScanFailure("source_changed", name)
                        self.directory_names(child, name, names, depth + 1)
                        if directory_identity(os.stat(entry.name, dir_fd=descriptor, follow_symlinks=False)) != directory_identity(before):
                            raise ScanFailure("source_changed", name)
                    finally:
                        os.close(child)
                else:
                    names.add(name)
                    if len(names) > MAX_FILES:
                        raise ScanAbort("file_count_limit")
        if identity(os.fstat(descriptor)) != directory_before:
            raise ScanFailure("source_changed", prefix)

    def worktree(self):
        names = set()
        for raw in self.git("ls-files", "-z", "--cached", "--others", "--exclude-standard").split(b"\0"):
            if raw:
                self.entry()
                names.add(os.fsdecode(raw))
                if len(names) > MAX_FILES:
                    raise ScanAbort("file_count_limit")
        root_fd = os.open(self.root, DIRECTORY_FLAGS)
        try:
            root_identity = directory_identity(os.fstat(root_fd))
            for base in SOURCE_DIRECTORIES:
                found = False
                try:
                    with parent_directory(root_fd, base) as (parent, leaf):
                        before = os.stat(leaf, dir_fd=parent, follow_symlinks=False)
                        found = True
                        if not stat.S_ISDIR(before.st_mode):
                            names.add(base)
                            continue
                        child = os.open(leaf, DIRECTORY_FLAGS, dir_fd=parent)
                        try:
                            if directory_identity(os.fstat(child)) != directory_identity(before):
                                raise ScanFailure("source_changed", base)
                            self.directory_names(child, base, names)
                            if directory_identity(os.stat(leaf, dir_fd=parent, follow_symlinks=False)) != directory_identity(before):
                                raise ScanFailure("source_changed", base)
                        finally:
                            os.close(child)
                except FileNotFoundError:
                    if found:
                        self.finding({"path": base, "line": 0, "rule": "source_changed"})
                except ScanAbort:
                    raise
                except ScanFailure as error:
                    self.finding(error.finding)
                except OSError:
                    self.finding({"path": base, "line": 0, "rule": "source_io_error"})
            for name in sorted(names):
                self.check_entry(name, lambda: self.regular_bytes(root_fd, name))
            if directory_identity(self.root.lstat()) != root_identity:
                raise ScanFailure("source_changed")
        finally:
            os.close(root_fd)

    def blob(self, name, mode, digest, kind=b"blob"):
        safe_source_path(name)
        if kind != b"blob" or mode not in {b"100644", b"100755"}:
            raise ScanFailure("source_link_or_special_file", name)
        if not OID.fullmatch(digest):
            raise ScanFailure("invalid_git_metadata", name)
        if self.blobs is None:
            self.blobs = BlobReader(self)
        return self.blobs.read(name, digest)

    def index(self):
        for entry in self.git("ls-files", "--stage", "-z").split(b"\0"):
            if not entry:
                continue
            self.entry()
            attributes, raw = entry.split(b"\t", 1)
            mode, digest, stage = attributes.split()
            name = os.fsdecode(raw)
            if stage != b"0":
                self.finding({"path": name, "line": 0, "rule": "unmerged_index"})
                continue
            self.check_entry(name, lambda: self.blob(name, mode, digest))

    def history(self, refs):
        if self.git("rev-parse", "--is-shallow-repository").strip() != b"false":
            raise ScanFailure("shallow_history")
        graft_path = os.fsdecode(self.git("rev-parse", "--git-path", "info/grafts").rstrip(b"\n"))
        if os.path.lexists(self.root / graft_path):
            raise ScanFailure("grafted_history")
        commits = []
        for ref in refs:
            if not ref.startswith("refs/") or len(ref) > 1024:
                raise ScanFailure("invalid_ref")
            try:
                self.git("check-ref-format", ref)
                digest = self.git("rev-parse", "--verify", "--end-of-options", ref + "^{commit}", limit=128).strip()
            except ScanFailure as error:
                if isinstance(error, ScanAbort):
                    raise
                raise ScanFailure("invalid_ref") from None
            if not OID.fullmatch(digest):
                raise ScanFailure("invalid_ref")
            commits.append(digest.decode("ascii"))
        reachable = self.git("rev-list", f"--max-count={MAX_COMMITS + 1}", *commits, "--").splitlines()
        if len(reachable) > MAX_COMMITS:
            raise ScanAbort("commit_count_limit")
        seen = set()
        for commit in reachable:
            if not OID.fullmatch(commit):
                raise ScanFailure("invalid_git_metadata")
            for entry in self.git("ls-tree", "-rz", "--full-tree", commit.decode("ascii")).split(b"\0"):
                if not entry:
                    continue
                self.entry()
                attributes, raw = entry.split(b"\t", 1)
                mode, kind, digest = attributes.split()
                name = os.fsdecode(raw)
                if not OID.fullmatch(digest):
                    raise ScanFailure("invalid_git_metadata", name)
                ordinary = kind == b"blob" and mode in {b"100644", b"100755"}
                if ordinary and (name, digest) in seen:
                    continue
                if ordinary:
                    seen.add((name, digest))
                context = {"commit": commit.decode("ascii"), "blob": digest.decode("ascii")}
                self.check_entry(name, lambda: self.blob(name, mode, digest, kind), context)

    def run(self, refs=None):
        try:
            if self.root.is_symlink() or self.git("rev-parse", "--show-prefix") != b"\n":
                raise ScanFailure("invalid_repository_root")
            if self.source == "worktree":
                self.worktree()
            elif self.source == "index":
                self.index()
            else:
                self.history(refs)
        except ScanFailure as error:
            self.findings.append(error.finding)
        except (OSError, ValueError, subprocess.SubprocessError):
            self.findings.append({"path": "", "line": 0, "rule": "scan_error"})
        finally:
            if self.blobs is not None:
                self.blobs.close()
        return {"source": self.source, "ok": not self.findings, "findings": self.findings,
                "files_checked": self.files_checked, "bytes_read": self.bytes_read}


def snapshot_worktree(root):
    scanner = Scanner(root, "worktree", include_data=True)
    scanner.run()
    if scanner.findings:
        raise PublicationError(scanner.findings)
    return scanner.files


def main(argv=None):
    source = None
    try:
        parser = JsonParser(description=__doc__, allow_abbrev=False)
        parser.add_argument("--source", required=True, choices=("worktree", "index", "history"))
        parser.add_argument("--root", type=Path, default=ROOT)
        parser.add_argument("--refs", nargs="+")
        args = parser.parse_args(argv)
        source = args.source
        if (source == "history") != bool(args.refs) or args.refs and len(args.refs) > 100:
            raise ScanFailure("invalid_arguments")
        report = Scanner(args.root, source).run(args.refs)
    except ScanFailure as error:
        report = {"source": source, "ok": False, "findings": [error.finding], "files_checked": 0, "bytes_read": 0}
    except Exception:
        # Diagnostics are metadata-only even for unexpected library/platform failures.
        report = {"source": source, "ok": False, "findings": [{"path": "", "line": 0, "rule": "scan_error"}],
                  "files_checked": 0, "bytes_read": 0}
    print(json.dumps(report, ensure_ascii=True, sort_keys=True))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
