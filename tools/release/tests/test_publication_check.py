"""Publication checks against disposable local Git repositories; no real credentials."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import publication_check as c


def synthetic_key():
    return ("AK" + "IA" + "Q" * 16).encode()


class PublicationCheckTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name).resolve()
        self.root = self.directory / "repo"
        self.root.mkdir()
        self.git("init", "-q", "--initial-branch=main")
        self.write("README.md", b"Public fixture\n")
        self.commit()

    def git(self, *args, data=None):
        command = ["git", "-C", str(self.root), "-c", "user.name=Publication Fixture",
                   "-c", "user.email=fixture@localhost", "-c", "commit.gpgsign=false",
                   "-c", "core.hooksPath=" + os.devnull, *args]
        result = subprocess.run(command, input=data, capture_output=True, timeout=15, env=c.git_environment())
        self.assertEqual(result.returncode, 0, "Disposable Git fixture operation failed")
        return result.stdout

    def write(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def commit(self):
        self.git("add", "--all")
        self.git("commit", "-qm", "fixture: local test snapshot")
        return self.git("rev-parse", "HEAD").decode().strip()

    def cli(self, *args, root=None, env=None):
        result = subprocess.run([sys.executable, "-B", str(TOOLS / "publication_check.py"),
                                 "--root", str(root or self.root), *args], capture_output=True,
                                timeout=20, env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1", **(env or {})})
        self.assertEqual(result.stderr, b"")
        self.assertNotIn(synthetic_key(), result.stdout)
        report = json.loads(result.stdout)
        self.assertEqual(result.returncode, 0 if report["ok"] else 1)
        for finding in report["findings"]:
            self.assertLessEqual(set(finding), {"path", "line", "rule", "commit", "blob"})
            self.assertIsInstance(finding["line"], int)
        return report

    def scan(self, source="worktree", **limits):
        with contextlib.ExitStack() as stack:
            for name, value in limits.items():
                stack.enter_context(patch.object(c, name, value))
            return c.Scanner(self.root, source).run(["refs/heads/main"] if source == "history" else None)

    def rules(self, report):
        return {finding["rule"] for finding in report["findings"]}

    def test_all_real_modes_clean_and_read_only(self):
        before_index = (self.root / ".git/index").read_bytes()
        before_head = self.git("rev-parse", "HEAD")
        for source in ("worktree", "index", "history"):
            args = ["--source", source]
            if source == "history":
                args += ["--refs", "refs/heads/main"]
            report = self.cli(*args)
            self.assertTrue(report["ok"], report)
            self.assertEqual(report["files_checked"], 1)
            self.assertEqual(report["bytes_read"], len(b"Public fixture\n"))
        self.assertEqual((self.root / ".git/index").read_bytes(), before_index)
        self.assertEqual(self.git("rev-parse", "HEAD"), before_head)
        self.assertFalse((self.root / ".git/index.lock").exists())

    def test_staged_secret_with_clean_worktree_is_rejected_only_by_index(self):
        self.write("README.md", b"Heading\n" + synthetic_key() + b"\n")
        self.git("add", "README.md")
        self.write("README.md", b"Clean worktree\n")
        report = self.cli("--source", "index")
        self.assertIn("AwsAccessKey", self.rules(report))
        self.assertEqual(report["findings"][0]["line"], 2)
        self.assertTrue(self.cli("--source", "worktree")["ok"])
        self.assertTrue(self.cli("--source", "history", "--refs", "refs/heads/main")["ok"])

    def test_unstaged_secret_and_untracked_file_are_scanned(self):
        self.write("README.md", synthetic_key())
        self.write("app/src/main/Fixture.kt", synthetic_key())
        report = self.cli("--source", "worktree")
        self.assertEqual({finding["path"] for finding in report["findings"]},
                         {"README.md", "app/src/main/Fixture.kt"})
        self.assertTrue(self.cli("--source", "index")["ok"])

    def test_worktree_deletion_including_sensitive_tracked_path(self):
        self.write("local.properties", synthetic_key())
        self.commit()
        (self.root / "local.properties").unlink()
        (self.root / "README.md").unlink()
        self.assertTrue(self.cli("--source", "worktree")["ok"])
        self.assertIn("SensitiveSourcePath", self.rules(self.cli("--source", "index")))
        self.assertEqual(c.snapshot_worktree(self.root), {})

    def test_ignored_files_under_all_source_roots_are_not_omitted(self):
        self.write(".gitignore", b"app/src/\napp/schemas/\ngradle/\n")
        names = ["app/src/main/Fixture.kt", "app/schemas/Fixture/1.json", "gradle/libs.versions.toml"]
        for name in names:
            self.write(name, synthetic_key())
        report = self.cli("--source", "worktree")
        self.assertEqual({finding["path"] for finding in report["findings"]}, set(names))

    def test_ignored_files_outside_source_roots_are_not_publication_inputs(self):
        self.write(".gitignore", b"local.properties\n")
        self.write("local.properties", synthetic_key())
        self.assertTrue(self.cli("--source", "worktree")["ok"])

    def test_sensitive_path_is_rejected_before_content_open(self):
        self.write("local.properties", synthetic_key())
        original_open = c.os.open
        opened = []

        def recording_open(path, flags, *args, **kwargs):
            opened.append(os.fspath(path))
            return original_open(path, flags, *args, **kwargs)

        with patch.object(c.os, "open", side_effect=recording_open):
            report = self.scan()
        self.assertIn("SensitiveSourcePath", self.rules(report))
        self.assertNotIn("local.properties", opened)
        self.assertEqual(report["bytes_read"], len(b"Public fixture\n"))

    def test_index_sensitive_path_never_reads_blob_metadata_or_content(self):
        self.write("local.properties", synthetic_key())
        self.git("add", "local.properties")
        digest = self.git("rev-parse", ":local.properties").decode().strip()
        original_header = c.BlobReader.header
        commands = []

        def recording_header(reader, command, oid):
            commands.append(oid.decode())
            return original_header(reader, command, oid)

        with patch.object(c.BlobReader, "header", recording_header):
            report = self.scan("index")
        self.assertIn("SensitiveSourcePath", self.rules(report))
        self.assertNotIn(digest, commands)

    def test_symlink_file_is_never_followed_in_worktree_or_index(self):
        outside = self.directory / "outside"
        outside.write_bytes(synthetic_key())
        (self.root / "app").mkdir()
        (self.root / "app/link.md").symlink_to(outside)
        self.git("add", "app/link.md")
        for source in ("worktree", "index"):
            report = self.cli("--source", source)
            self.assertFalse(report["ok"])
            self.assertNotIn("AwsAccessKey", self.rules(report))

    def test_ignored_base_directory_symlink_is_rejected(self):
        outside = self.directory / "outside"
        outside.mkdir()
        (outside / "Fixture.kt").write_bytes(synthetic_key())
        self.write(".gitignore", b"app/src\n")
        (self.root / "app").mkdir()
        (self.root / "app/src").symlink_to(outside, target_is_directory=True)
        report = self.cli("--source", "worktree")
        self.assertIn("source_link_or_special_file", self.rules(report))
        self.assertIn("app/src", {finding["path"] for finding in report["findings"]})
        self.assertNotIn("AwsAccessKey", self.rules(report))

    def test_ignored_source_ancestor_symlink_is_rejected(self):
        outside = self.directory / "outside"
        outside.mkdir()
        self.write(".gitignore", b"app\n")
        (self.root / "app").symlink_to(outside, target_is_directory=True)
        report = self.cli("--source", "worktree")
        self.assertIn("source_link_or_special_file", self.rules(report))

    def test_ignored_descendant_directory_symlink_is_rejected(self):
        outside = self.directory / "outside"
        outside.mkdir()
        self.write(".gitignore", b"app/src/\n")
        (self.root / "app/src/main").mkdir(parents=True)
        (self.root / "app/src/main/link").symlink_to(outside, target_is_directory=True)
        report = self.cli("--source", "worktree")
        self.assertIn("source_link_or_special_file", self.rules(report))
        self.assertEqual(report["findings"][0]["path"], "app/src/main/link")

    def test_special_file_is_rejected_without_blocking(self):
        (self.root / "app/src/main").mkdir(parents=True)
        os.mkfifo(self.root / "app/src/main/Fixture.kt")
        report = self.cli("--source", "worktree")
        self.assertIn("source_link_or_special_file", self.rules(report))

    def test_snapshot_returns_exact_bytes_and_refuses_any_finding(self):
        self.write("app/src/main/Fixture.kt", b"// exact trailing spaces  \n")
        files = c.snapshot_worktree(self.root)
        self.assertEqual(files["app/src/main/Fixture.kt"], b"// exact trailing spaces  \n")
        self.write("README.md", synthetic_key())
        with self.assertRaises(c.PublicationError) as failure:
            c.snapshot_worktree(self.root)
        self.assertNotIn(synthetic_key().decode(), str(failure.exception))

    def test_file_replaced_with_symlink_before_open_fails_closed(self):
        outside = self.directory / "outside"
        outside.write_bytes(synthetic_key())
        original_open = c.os.open

        def replaced(path, flags, *args, **kwargs):
            if path == "README.md":
                (self.root / "README.md").unlink()
                (self.root / "README.md").symlink_to(outside)
            return original_open(path, flags, *args, **kwargs)

        with patch.object(c.os, "open", side_effect=replaced):
            report = self.scan()
        self.assertFalse(report["ok"])
        self.assertEqual(report["bytes_read"], 0)

    def test_file_deleted_after_metadata_is_not_clean_deletion(self):
        original_open = c.os.open

        def removed(path, flags, *args, **kwargs):
            if path == "README.md":
                (self.root / "README.md").unlink()
            return original_open(path, flags, *args, **kwargs)

        with patch.object(c.os, "open", side_effect=removed):
            report = self.scan()
        self.assertIn("source_changed", self.rules(report))

    def test_file_mutated_during_read_is_rejected_and_charged(self):
        original_fdopen = c.os.fdopen

        class MutatingStream:
            def __init__(self, descriptor, mode):
                self.stream = original_fdopen(descriptor, mode)

            def __enter__(self):
                return self

            def __exit__(self, *args):
                self.stream.close()

            def fileno(self):
                return self.stream.fileno()

            def read(inner, limit):
                data = inner.stream.read(limit)
                self.write("README.md", b"mutated\n")
                return data

        with patch.object(c.os, "fdopen", MutatingStream):
            report = self.scan()
        self.assertIn("source_changed", self.rules(report))
        self.assertEqual(report["bytes_read"], len(b"Public fixture\n"))

    def test_source_base_deleted_after_metadata_is_not_clean(self):
        base = self.root / "app/src"
        base.mkdir(parents=True)
        original_open = c.os.open

        def removed(path, flags, *args, **kwargs):
            if path == "src":
                base.rmdir()
            return original_open(path, flags, *args, **kwargs)

        with patch.object(c.os, "open", side_effect=removed):
            report = self.scan()
        self.assertIn("source_changed", self.rules(report))

    def test_source_directory_changes_during_enumeration_are_rejected(self):
        self.write(".gitignore", b"app/src/\n")
        self.write("app/src/main/Fixture.kt", b"// clean\n")
        original_entry = c.Scanner.entry
        changed = False

        def changed_entry(scanner):
            nonlocal changed
            original_entry(scanner)
            if not changed and scanner.entries > 2:
                changed = True
                self.write("app/src/late.kt", synthetic_key())

        with patch.object(c.Scanner, "entry", changed_entry):
            report = self.scan()
        self.assertIn("source_changed", self.rules(report))

    def test_history_finds_deleted_secret_and_continues_unknown_paths(self):
        self.write("README.md", synthetic_key())
        self.write("unknown.txt", b"Unreviewed historical path\n")
        bad_commit = self.commit()
        (self.root / "unknown.txt").unlink()
        self.write("README.md", b"Clean latest revision\n")
        self.commit()
        report = self.cli("--source", "history", "--refs", "refs/heads/main")
        self.assertEqual(self.rules(report), {"AwsAccessKey", "UnreviewedSourcePath"})
        self.assertEqual({finding["commit"] for finding in report["findings"]}, {bad_commit})
        self.assertEqual(report["files_checked"], 4)
        self.assertTrue(self.cli("--source", "index")["ok"])

    def test_history_deduplicates_path_and_oid_but_not_different_paths(self):
        self.write("README.md", synthetic_key())
        self.write("app/src/main/Fixture.kt", synthetic_key())
        self.commit()
        self.git("commit", "--allow-empty", "-qm", "fixture: unchanged tree")
        report = self.cli("--source", "history", "--refs", "refs/heads/main")
        self.assertEqual(len(report["findings"]), 2)
        self.assertEqual(report["files_checked"], 3)

    def test_history_rejects_same_oid_symlink_after_clean_regular_blob(self):
        (self.root / "README.md").unlink()
        (self.root / "README.md").symlink_to("LICENSE")
        self.commit()
        (self.root / "README.md").unlink()
        self.write("README.md", b"LICENSE")
        self.commit()
        report = self.cli("--source", "history", "--refs", "refs/heads/main")
        self.assertIn("source_link_or_special_file", self.rules(report))

    def test_history_scans_only_explicit_refs_and_accepts_annotated_tags(self):
        self.git("tag", "-a", "-m", "fixture tag", "safe")
        self.git("checkout", "-qb", "unpublished")
        self.write("README.md", synthetic_key())
        self.commit()
        self.assertTrue(self.cli("--source", "history", "--refs", "refs/heads/main", "refs/tags/safe")["ok"])
        self.assertIn("AwsAccessKey", self.rules(self.cli("--source", "history", "--refs", "refs/heads/unpublished")))

    def test_history_does_not_allow_replace_refs_to_hide_old_secret(self):
        clean = self.git("rev-parse", "HEAD").decode().strip()
        self.write("README.md", synthetic_key())
        bad = self.commit()
        self.git("replace", bad, clean)
        report = self.cli("--source", "history", "--refs", "refs/heads/main")
        self.assertIn("AwsAccessKey", self.rules(report))

    def test_history_ref_and_argument_failures_are_json_only(self):
        for args in ([], ["--source", "history"], ["--source", "bad"], ["--source", "worktree", "--refs", "refs/heads/main"],
                     ["--source", "history", "--refs", "--all"], ["--source", "history", "--refs", "HEAD"],
                     ["--source", "history", "--refs", "refs/heads/main~1"],
                     ["--source", "history", "--refs", "refs/heads/main..refs/heads/main"],
                     ["--source", "history", "--refs", "refs/heads/main^{tree}"],
                     ["--source", "history", "--refs", "refs/heads/missing"],
                     ["--source", "history", "--refs", "refs/heads/main", "--unexpected"],
                     ["--sour", "index"]):
            with self.subTest(args=args):
                self.assertFalse(self.cli(*args)["ok"])

    def test_history_rejects_blob_ref_shallow_and_grafts(self):
        digest = self.git("rev-parse", "HEAD:README.md").decode().strip()
        self.git("update-ref", "refs/tags/blob", digest)
        self.assertIn("invalid_ref", self.rules(self.cli("--source", "history", "--refs", "refs/tags/blob")))
        shallow = self.root / ".git/shallow"
        shallow.write_bytes(self.git("rev-parse", "HEAD"))
        self.assertIn("shallow_history", self.rules(self.cli("--source", "history", "--refs", "refs/heads/main")))
        shallow.unlink()
        (self.root / ".git/info/grafts").write_bytes(self.git("rev-parse", "HEAD"))
        self.assertIn("grafted_history", self.rules(self.cli("--source", "history", "--refs", "refs/heads/main")))

    def test_index_rejects_unmerged_entries_and_gitlinks(self):
        digest = self.git("rev-parse", "HEAD:README.md").strip()
        self.git("update-index", "--force-remove", "README.md")
        self.git("update-index", "--index-info", data=b"100644 " + digest + b" 1\tREADME.md\n100644 " + digest + b" 2\tREADME.md\n")
        self.assertIn("unmerged_index", self.rules(self.cli("--source", "index")))
        self.git("update-index", "--force-remove", "README.md")
        commit = self.git("rev-parse", "HEAD").decode().strip()
        self.git("update-index", "--add", "--cacheinfo", "160000," + commit + ",README.md")
        self.assertIn("source_link_or_special_file", self.rules(self.cli("--source", "index")))

    def test_unknown_binary_is_not_silently_clean(self):
        self.write("README.md", b"\xff\x00arbitrary binary")
        self.commit()
        for source in ("worktree", "index", "history"):
            args = ["--source", source] + (["--refs", "refs/heads/main"] if source == "history" else [])
            self.assertIn("UnreviewedBinaryBytes", self.rules(self.cli(*args)))

    def test_path_control_characters_are_json_escaped(self):
        name = "app/src/main/line\n\"quoted.kt"
        self.write(name, synthetic_key())
        report = self.cli("--source", "worktree")
        self.assertEqual(report["findings"][0]["path"], name)
        self.assertEqual(report["findings"][0]["rule"], "UnsafeSourcePath")

    def test_size_limit_in_real_worktree_and_index(self):
        path = self.root / "README.md"
        with path.open("wb") as stream:
            stream.truncate(c.MAX_SOURCE_BYTES + 1)
        self.git("add", "README.md")
        for source in ("worktree", "index"):
            report = self.cli("--source", source)
            self.assertIn("source_size_limit", self.rules(report))
            self.assertEqual(report["bytes_read"], 0)

    def test_batch_reader_preserves_empty_and_multichunk_exact_bytes(self):
        expected = {"README.md": b"", "app/src/main/Fixture.kt": b"// unchanged  \n" * 20000}
        for name, data in expected.items():
            self.write(name, data)
        self.git("add", "--all")
        scanner = c.Scanner(self.root, "index", include_data=True)
        original_start = scanner.start_git
        commands = []

        def recording_start(*args, **kwargs):
            commands.append(args)
            return original_start(*args, **kwargs)

        with patch.object(scanner, "start_git", side_effect=recording_start):
            report = scanner.run()
        self.assertTrue(report["ok"], report)
        self.assertEqual(scanner.files, expected)
        self.assertEqual(sum(command[0] == "cat-file" for command in commands), 1)
        self.assertIsNotNone(scanner.blobs.process.returncode)

    def test_batch_reader_rejects_missing_local_object_and_stops(self):
        digest = self.git("rev-parse", "HEAD:README.md").decode().strip()
        (self.root / ".git/objects" / digest[:2] / digest[2:]).unlink()
        report = self.cli("--source", "index")
        self.assertIn("invalid_git_metadata", self.rules(report))
        self.assertEqual(report["bytes_read"], 0)

    def test_batch_size_check_never_requests_oversized_contents(self):
        original_header = c.BlobReader.header
        commands = []

        def recording_header(reader, command, digest):
            commands.append(command)
            return original_header(reader, command, digest)

        with patch.object(c.BlobReader, "header", recording_header):
            report = self.scan("index", MAX_SOURCE_BYTES=1)
        self.assertIn("source_size_limit", self.rules(report))
        self.assertEqual(commands, [b"info"])

    def test_total_file_entry_commit_finding_and_runtime_limits(self):
        limits = [("MAX_TOTAL_BYTES", 1, "total_size_limit"), ("MAX_FILES", 0, "file_count_limit"),
                  ("MAX_ENTRIES", 0, "entry_count_limit"), ("MAX_SECONDS", 0, "runtime_limit")]
        for source in ("worktree", "index", "history"):
            for name, limit, expected in limits:
                self.assertIn(expected, self.rules(self.scan(source, **{name: limit})))
        self.assertIn("commit_count_limit", self.rules(self.scan("history", MAX_COMMITS=0)))
        self.write("README.md", synthetic_key())
        self.assertIn("finding_count_limit", self.rules(self.scan(MAX_FINDINGS=0)))

    def test_bounded_git_output_and_redacted_failures(self):
        scanner = c.Scanner(self.root, "index")
        with self.assertRaises(c.ScanAbort) as failure:
            scanner.git("rev-parse", "HEAD", limit=2)
        self.assertEqual(failure.exception.finding["rule"], "git_output_limit")
        missing = self.directory / synthetic_key().decode()
        report = self.cli("--source", "index", root=missing)
        self.assertIn("git_command_failed", self.rules(report))

    def test_git_environment_blocks_override_of_index_and_history(self):
        self.write("README.md", synthetic_key())
        self.git("add", "README.md")
        report = self.cli("--source", "index", env={"GIT_INDEX_FILE": str(self.directory / "empty-index"),
                                                    "GIT_DIR": str(self.directory / "missing"),
                                                    "GIT_OPTIONAL_LOCKS": "1", "GIT_NO_LAZY_FETCH": "0"})
        self.assertIn("AwsAccessKey", self.rules(report))
        env = c.git_environment()
        for name in ("GIT_OPTIONAL_LOCKS", "GIT_TERMINAL_PROMPT"):
            self.assertEqual(env[name], "0")
        self.assertEqual(env["GIT_NO_LAZY_FETCH"], "1")
        self.assertEqual(env["GIT_NO_REPLACE_OBJECTS"], "1")

    def test_nonroot_and_symlink_root_are_rejected(self):
        subdirectory = self.root / "app"
        subdirectory.mkdir()
        self.assertIn("invalid_repository_root", self.rules(self.cli("--source", "index", root=subdirectory)))
        link = self.directory / "linked"
        link.symlink_to(self.root, target_is_directory=True)
        self.assertIn("invalid_repository_root", self.rules(self.cli("--source", "worktree", root=link)))

    def test_default_root_and_unexpected_exception_are_metadata_only(self):
        output = io.StringIO()
        with patch.object(c, "ROOT", self.root), contextlib.redirect_stdout(output):
            self.assertEqual(c.main(["--source", "index"]), 0)
        self.assertTrue(json.loads(output.getvalue())["ok"])
        output = io.StringIO()
        with patch.object(c.Scanner, "run", side_effect=RuntimeError(synthetic_key().decode())), contextlib.redirect_stdout(output):
            self.assertEqual(c.main(["--source", "index"]), 1)
        self.assertNotIn(synthetic_key().decode(), output.getvalue())
        self.assertIn("scan_error", self.rules(json.loads(output.getvalue())))


if __name__ == "__main__":
    unittest.main()
