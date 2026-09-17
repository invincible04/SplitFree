import copy
import hashlib
import importlib.util
import io
import json
import socket
import sys
import tempfile
import unittest
import urllib.parse
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).resolve().parents[1] / "github_release.py"
SPEC = importlib.util.spec_from_file_location("github_release", MODULE_PATH)
release = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release
SPEC.loader.exec_module(release)

REPOSITORY = "invincible04/SplitFree"
TAG = "v1.0.0"
COMMIT = "a" * 40
OTHER_COMMIT = "b" * 40
SOURCE = f"SplitFree-{TAG}-source.tar.gz"
APK = "SplitFree-v1.0.0.apk"
ORDER = [SOURCE, "LICENSE.txt", "THIRD-PARTY-NOTICES.txt", APK, release.METADATA, release.CHECKSUMS]


def encoded(value):
    return json.dumps(value, sort_keys=True).encode("utf-8")


def make_info(tag=TAG, code=1):
    payloads = {
        f"SplitFree-{tag}-source.tar.gz": b"exact source fixture",
        "LICENSE.txt": b"license fixture",
        "THIRD-PARTY-NOTICES.txt": b"notices fixture",
        APK: b"signed apk fixture",
    }
    info = {
        "schemaVersion": 1, "tag": tag, "commit": COMMIT,
        "versionName": tag[1:], "versionCode": code, "packageName": "com.splitfree",
        "certificateSha256": "c" * 64,
        "artifacts": {name: hashlib.sha256(data).hexdigest() for name, data in payloads.items()},
    }
    return info, payloads


def write_bundle(directory, info=None, payloads=None):
    default_info, default_payloads = make_info()
    info = default_info if info is None else info
    payloads = default_payloads if payloads is None else payloads
    files = dict(payloads)
    files[release.METADATA] = encoded(info)
    files[release.CHECKSUMS] = "".join(
        f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in files.items()
    ).encode("ascii")
    for name, data in files.items():
        (directory / name).write_bytes(data)
    return files


class FakeTransport:
    def __init__(self, handler):
        self.handler = handler
        self.calls = []

    def request(self, method, url, headers, data, limit):
        self.calls.append((method, url, dict(headers), data, limit))
        return self.handler(method, url, headers, data, limit)


class FakeGitHub:
    def __init__(self, bundle):
        self.repository = REPOSITORY
        self.prefix = f"/repos/{REPOSITORY}"
        self.token = "unit-test-not-a-token"
        self.bundle = bundle
        self.release_list = []
        self.asset_lists = {}
        self.contents = {}
        self.calls = []
        self.uploaded = []
        self.deleted = []
        self.tag_commit = COMMIT
        self.annotated = False
        self.tag_cycle = False
        self.mainline_status = "ahead"
        self.missing_tag = False
        self.on_read = None
        self.on_assets = None
        self.on_upload = None
        self.read_count = 0

    def draft(self):
        digest = hashlib.sha256(self.bundle.files[release.CHECKSUMS]).hexdigest()
        return {"id": 10, "tag_name": TAG, "draft": True, "prerelease": False,
                "target_commitish": "mainline",
                "body": release.source_marker(REPOSITORY, COMMIT) + "\n"
                + f"{release.BUNDLE_MARKER_PREFIX} {digest} -->\n"}

    def add_draft(self):
        draft = self.draft()
        self.release_list.append(draft)
        return draft

    def add_asset(self, release_id, name, data):
        identifier = 100 + len(self.contents)
        asset = {"id": identifier, "name": name, "state": "uploaded", "size": len(data)}
        self.asset_lists.setdefault(release_id, []).append(asset)
        self.contents[identifier] = data
        return asset

    def add_published(self, code, tag="v0.9.0", prerelease=False, metadata=True):
        identifier = 20 + len(self.release_list)
        self.release_list.append({"id": identifier, "tag_name": tag, "draft": False,
                                  "prerelease": prerelease})
        if metadata:
            info, _ = make_info(tag, code)
            self.add_asset(identifier, release.METADATA, encoded(info))
        return identifier

    def releases(self):
        self.calls.append(("GET", "releases"))
        return copy.deepcopy(self.release_list)

    def assets(self, release_id):
        self.calls.append(("GET", f"assets/{release_id}"))
        if self.on_assets:
            self.on_assets(self)
        return copy.deepcopy(self.asset_lists.get(release_id, []))

    def download(self, asset, limit):
        release.require(asset["state"] == "uploaded", "Existing asset is not fully uploaded")
        data = self.contents[asset["id"]]
        release.require(len(data) <= limit, "Invalid asset size")
        return data

    def delete_asset(self, asset):
        identifier = release.object_id(asset)
        for release_id, assets in self.asset_lists.items():
            self.asset_lists[release_id] = [a for a in assets if a["id"] != identifier]
        self.deleted.append(identifier)

    def json(self, method, path, payload=None, *, upload=None):
        self.calls.append((method, path))
        if method == "GET" and "/git/ref/tags/" in path:
            release.require(not self.missing_tag, "GitHub GET failed (HTTP 404)")
            return {"ref": f"refs/tags/{TAG}", "object": {
                "type": "tag" if self.annotated else "commit",
                "sha": OTHER_COMMIT if self.annotated else self.tag_commit,
            }}
        if method == "GET" and "/git/tags/" in path:
            return {"sha": OTHER_COMMIT, "object": {
                "type": "tag" if self.tag_cycle else "commit",
                "sha": OTHER_COMMIT if self.tag_cycle else self.tag_commit,
            }}
        if method == "GET" and "/compare/" in path:
            return {"status": self.mainline_status, "merge_base_commit": {"sha": self.tag_commit}}
        if method == "GET" and path == self.prefix + "/releases/10":
            self.read_count += 1
            if self.on_read:
                self.on_read(self)
            return copy.deepcopy(next(item for item in self.release_list if item["id"] == 10))
        if method == "POST" and path == self.prefix + "/releases":
            result = {"id": 10, **payload}
            self.release_list.append(result)
            return copy.deepcopy(result)
        if method == "POST" and "/releases/10/assets?" in path:
            name = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)["name"][0]
            if self.on_upload:
                self.on_upload(self, name)
            self.uploaded.append(name)
            return self.add_asset(10, name, upload)
        raise AssertionError(f"Unexpected fake API call: {method} {path}")


class NoNetworkTest(unittest.TestCase):
    def setUp(self):
        guard = patch.object(socket, "create_connection", side_effect=AssertionError("Network forbidden"))
        guard.start()
        self.addCleanup(guard.stop)


class BundleFixture(NoNetworkTest):
    def setUp(self):
        super().setUp()
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.files = write_bundle(self.directory)

    def load(self):
        return release.load_bundle(self.directory, TAG, COMMIT)


class BundleTest(BundleFixture):
    def test_valid_bundle_has_deterministic_upload_order(self):
        bundle = self.load()
        self.assertEqual(ORDER, list(bundle.files))
        self.assertEqual(self.files, bundle.files)

    def test_invalid_metadata_fields(self):
        info, _ = make_info()
        bad_values = {
            "schemaVersion": [True, 2, "1"], "versionCode": [True, 0, -1, 1.5, "1"],
            "versionName": [1, "wrong"], "packageName": ["com.other"],
            "certificateSha256": ["C" * 64, "cc:" * 32, "c" * 63],
            "commit": [OTHER_COMMIT, "A" * 40, "a" * 39], "tag": ["v2.0.0", "1.0.0"],
        }
        for key, values in bad_values.items():
            for value in values:
                with self.subTest(key=key, value=value):
                    changed = copy.deepcopy(info)
                    changed[key] = value
                    write_bundle(self.directory, changed)
                    with self.assertRaises(release.ReleaseError):
                        self.load()

    def test_unknown_or_missing_metadata_field(self):
        info, _ = make_info()
        for changed in ({**info, "upload_url": "https://untrusted.invalid"},
                        {key: value for key, value in info.items() if key != "commit"}):
            with self.subTest(info=changed):
                write_bundle(self.directory, changed)
                with self.assertRaisesRegex(release.ReleaseError, "fields"):
                    self.load()

    def test_duplicate_json_key_refused(self):
        with self.assertRaisesRegex(release.ReleaseError, "Duplicate"):
            release.parse_json(b'{"versionCode":1,"versionCode":2}')

    def test_invalid_json_refused(self):
        for content in (b"not-json", b"\xff", b"[" * 2000):
            with self.subTest(content=content[:20]), self.assertRaises(release.ReleaseError):
                release.parse_json(content)

    def test_unsafe_or_wrong_artifact_names(self):
        info, _ = make_info()
        for name in ("../source.tar.gz", "other.zip", "other.tar.gz", "source.tar.gz.", "file\n.apk"):
            with self.subTest(name=name):
                changed = copy.deepcopy(info)
                changed["artifacts"][name] = changed["artifacts"].pop(SOURCE)
                with self.assertRaises(release.ReleaseError):
                    release.validate_metadata(changed)

    def test_missing_or_additional_artifact_metadata(self):
        info, _ = make_info()
        for extra in (False, True):
            with self.subTest(extra=extra):
                changed = copy.deepcopy(info)
                if extra:
                    changed["artifacts"]["secrets.txt"] = "d" * 64
                else:
                    del changed["artifacts"]["LICENSE.txt"]
                with self.assertRaises(release.ReleaseError):
                    release.validate_metadata(changed)

    def test_changed_bytes_refused(self):
        (self.directory / APK).write_bytes(b"tampered")
        with self.assertRaisesRegex(release.ReleaseError, "Artifact checksum mismatch"):
            self.load()

    def test_empty_artifact_refused(self):
        (self.directory / APK).write_bytes(b"")
        with self.assertRaisesRegex(release.ReleaseError, "Empty"):
            self.load()

    def test_extra_directory_entry_refused(self):
        (self.directory / "signing-key.jks").write_bytes(b"must not upload")
        with self.assertRaisesRegex(release.ReleaseError, "exactly the six"):
            self.load()

    def test_missing_file_refused(self):
        (self.directory / APK).unlink()
        with self.assertRaisesRegex(release.ReleaseError, "exactly the six"):
            self.load()

    def test_symlink_refused(self):
        (self.directory / APK).unlink()
        (self.directory / APK).symlink_to(self.directory / "LICENSE.txt")
        with self.assertRaisesRegex(release.ReleaseError, "safely read"):
            self.load()

    def test_directory_instead_of_asset_refused(self):
        (self.directory / APK).unlink()
        (self.directory / APK).mkdir()
        with self.assertRaises(release.ReleaseError):
            self.load()

    def test_file_and_bundle_size_caps(self):
        for constant in ("ASSET_LIMIT", "TEXT_LIMIT", "METADATA_LIMIT", "CHECKSUM_LIMIT", "BUNDLE_LIMIT"):
            with self.subTest(constant=constant), patch.object(release, constant, 1):
                with self.assertRaisesRegex(release.ReleaseError, "size limit"):
                    self.load()

    def test_checksum_must_cover_metadata_and_no_extras_or_duplicates(self):
        original = self.files[release.CHECKSUMS]
        alternatives = [
            b"\n".join(original.splitlines()[:-1]) + b"\n",
            original + original.splitlines()[0] + b"\n",
            original + b"d" * 64 + b"  extra.txt\n",
            b"e" * 64 + original[64:], b"\xff", original + b"\n",
        ]
        for changed in alternatives:
            with self.subTest(changed=changed[:30]):
                (self.directory / release.CHECKSUMS).write_bytes(changed)
                with self.assertRaises(release.ReleaseError):
                    self.load()

    def test_wrong_cli_tag_or_commit_refused(self):
        for tag, commit in (("v2.0.0", COMMIT), (TAG, OTHER_COMMIT), ("v1.0.0-rc.1", COMMIT), (TAG, "a")):
            with self.subTest(tag=tag, commit=commit), self.assertRaises(release.ReleaseError):
                release.load_bundle(self.directory, tag, commit)


class UploadTest(BundleFixture):
    def setUp(self):
        super().setUp()
        self.bundle = self.load()
        self.api = FakeGitHub(self.bundle)

    def upload(self):
        return release.upload_bundle(self.api, self.bundle)

    def assert_no_writes(self):
        self.assertFalse(any(method != "GET" for method, _ in self.api.calls))

    def test_new_draft_always_unpublished_with_correct_order(self):
        self.assertEqual(10, self.upload())
        self.assertEqual(ORDER, self.api.uploaded)
        draft = self.api.release_list[0]
        self.assertIs(draft["draft"], True)
        self.assertIs(draft["prerelease"], False)
        self.assertIn("assets may be incomplete", draft["body"])
        self.assertNotIn("completed", draft["body"])
        self.assertTrue(all(method in {"GET", "POST"} for method, _ in self.api.calls))
        self.assertFalse(any(method == "POST" and "/git/" in path for method, path in self.api.calls))

    def test_identical_complete_retry_performs_no_writes(self):
        self.api.add_draft()
        for name, data in self.bundle.files.items():
            self.api.add_asset(10, name, data)
        self.assertEqual(10, self.upload())
        self.assert_no_writes()

    def test_interrupted_retry_uploads_only_missing_assets(self):
        self.api.add_draft()
        for name in ORDER[:3]:
            self.api.add_asset(10, name, self.bundle.files[name])
        self.upload()
        self.assertEqual(ORDER[3:], self.api.uploaded)

    def test_existing_asset_bytes_mismatch_refused_before_writes(self):
        self.api.add_draft()
        self.api.add_asset(10, SOURCE, b"changed")
        with self.assertRaisesRegex(release.ReleaseError, "bytes differ"):
            self.upload()
        self.assert_no_writes()

    def test_changed_missing_asset_is_bound_by_bundle_marker(self):
        self.api.add_draft()
        info, payloads = make_info()
        payloads[APK] = b"new build bytes"
        info["artifacts"][APK] = hashlib.sha256(payloads[APK]).hexdigest()
        write_bundle(self.directory, info, payloads)
        self.bundle = self.load()
        with self.assertRaisesRegex(release.ReleaseError, "bundle differs"):
            self.upload()
        self.assert_no_writes()

    def test_published_same_tag_refused(self):
        self.api.add_draft()["draft"] = False
        with self.assertRaisesRegex(release.ReleaseError, "Published release"):
            self.upload()
        self.assert_no_writes()

    def test_wrong_marker_missing_marker_and_duplicate_marker_refused(self):
        draft = self.api.add_draft()
        original = draft["body"]
        for body in ("", original.replace(COMMIT, OTHER_COMMIT), original + original,
                     " " + original, original.replace(REPOSITORY, "someone/else"),
                     original + release.MARKER_PREFIX + " malformed -->"):
            with self.subTest(body=body):
                draft["body"] = body
                with self.assertRaisesRegex(release.ReleaseError, "source commit marker"):
                    self.upload()
                self.assert_no_writes()

    def test_target_commitish_alone_is_not_binding(self):
        draft = self.api.add_draft()
        draft["target_commitish"] = COMMIT
        draft["body"] = ""
        with self.assertRaisesRegex(release.ReleaseError, "source commit marker"):
            self.upload()
        self.assert_no_writes()

    def test_wrong_tag_commit_or_mainline_refused(self):
        for field, value in (("tag_commit", OTHER_COMMIT), ("missing_tag", True),
                             ("mainline_status", "behind"), ("mainline_status", "diverged")):
            with self.subTest(field=field, value=value):
                self.api = FakeGitHub(self.bundle)
                setattr(self.api, field, value)
                with self.assertRaises(release.ReleaseError):
                    self.upload()
                self.assert_no_writes()

    def test_annotated_tag_is_peeled(self):
        self.api.annotated = True
        self.upload()
        self.assertTrue(any("/git/tags/" in path for _, path in self.api.calls))

    def test_annotated_tag_cycle_refused(self):
        self.api.annotated = self.api.tag_cycle = True
        with self.assertRaisesRegex(release.ReleaseError, "cyclic"):
            self.upload()
        self.assert_no_writes()

    def test_unexpected_or_duplicate_asset_refused(self):
        for kind in ("unexpected", "duplicate"):
            with self.subTest(kind=kind):
                self.api = FakeGitHub(self.bundle)
                self.api.add_draft()
                if kind == "unexpected":
                    self.api.add_asset(10, "secrets.jks", b"never expose")
                else:
                    self.api.add_asset(10, SOURCE, self.bundle.files[SOURCE])
                    self.api.add_asset(10, SOURCE, self.bundle.files[SOURCE])
                with self.assertRaises(release.ReleaseError):
                    self.upload()
                self.assert_no_writes()

    def test_starter_asset_deleted_and_reuploaded(self):
        self.api.add_draft()
        asset = self.api.add_asset(10, SOURCE, self.bundle.files[SOURCE])
        asset["state"] = "starter"
        self.assertEqual(10, self.upload())
        self.assertIn(asset["id"], self.api.deleted)
        self.assertIn(SOURCE, self.api.uploaded)

    def test_reread_detects_publication_before_upload(self):
        draft = self.api.add_draft()
        self.api.on_read = lambda api: draft.update(draft=False)
        with self.assertRaisesRegex(release.ReleaseError, "Published release"):
            self.upload()
        self.assert_no_writes()

    def test_reread_after_asset_download_detects_publication(self):
        draft = self.api.add_draft()
        self.api.on_assets = lambda api: draft.update(draft=False)
        with self.assertRaisesRegex(release.ReleaseError, "Published release"):
            self.upload()
        self.assert_no_writes()

    def test_upload_failure_leaves_only_unadvertised_partial_draft(self):
        def fail_on_apk(api, name):
            if name == APK:
                raise release.ReleaseError("simulated connection loss")
        self.api.on_upload = fail_on_apk
        with self.assertRaisesRegex(release.ReleaseError, "connection loss"):
            self.upload()
        self.assertEqual(ORDER[:3], self.api.uploaded)
        self.assertNotIn(release.METADATA, self.api.uploaded)
        self.assertNotIn(release.CHECKSUMS, self.api.uploaded)
        self.assertIs(self.api.release_list[0]["draft"], True)

    def test_missing_upload_at_final_verification_is_not_success(self):
        def discard_first(api, name):
            if name == release.CHECKSUMS:
                api.asset_lists[10] = [a for a in api.asset_lists[10] if a["name"] != SOURCE]
        self.api.on_upload = discard_first
        with self.assertRaisesRegex(release.ReleaseError, "incomplete"):
            self.upload()

    def test_no_token_refused_before_api(self):
        self.api.token = None
        with self.assertRaisesRegex(release.ReleaseError, "GH_TOKEN"):
            self.upload()
        self.assertEqual([], self.api.calls)

    def test_monotonic_first_release(self):
        self.assertEqual(0, release.check_version_code(self.api, 1))
        self.assert_no_writes()

    def test_monotonic_all_published_including_prereleases(self):
        self.api.add_published(4)
        self.api.add_published(7, "v1.0.0-rc.1", prerelease=True)
        self.api.add_draft()
        for code in (4, 6, 7):
            with self.subTest(code=code), self.assertRaisesRegex(release.ReleaseError, "maximum 7"):
                release.check_version_code(self.api, code)
        self.assertEqual(7, release.check_version_code(self.api, 8))
        self.assert_no_writes()

    def test_published_legacy_without_metadata_fails_closed(self):
        self.api.add_published(1, metadata=False)
        with self.assertRaisesRegex(release.ReleaseError, "manual migration required"):
            release.check_version_code(self.api, 100)
        self.assert_no_writes()

    def test_published_metadata_tag_mismatch_fails_closed(self):
        identifier = self.api.add_published(1)
        self.api.release_list[0]["tag_name"] = "v0.8.0"
        with self.assertRaisesRegex(release.ReleaseError, "tag mismatch"):
            release.check_version_code(self.api, 2)
        self.assertIn(identifier, self.api.asset_lists)

    def test_duplicate_historical_metadata_fails_closed(self):
        identifier = self.api.add_published(1)
        self.api.add_asset(identifier, release.METADATA, b"{}")
        with self.assertRaisesRegex(release.ReleaseError, "unique"):
            release.check_version_code(self.api, 2)

    def test_nonpositive_or_boolean_version_code_refused(self):
        for code in (0, -1, True, "3", 1.5):
            with self.subTest(code=code), self.assertRaises(release.ReleaseError):
                release.check_version_code(self.api, code)
        self.assertEqual([], self.api.calls)

    def test_read_only_cli(self):
        with patch.object(release, "GitHub", return_value=self.api), redirect_stdout(io.StringIO()) as output:
            result = release.main(["--repository", REPOSITORY, "--check-version-code", "1"])
        self.assertEqual(0, result)
        self.assertIn("exceeds published maximum 0", output.getvalue())
        self.assert_no_writes()

    def test_cli_validation_has_no_api_calls(self):
        cases = [[], ["--check-version-code", "1", "--tag", TAG]]
        for extra in cases:
            with self.subTest(extra=extra), patch.object(release, "GitHub", return_value=self.api):
                with redirect_stderr(io.StringIO()) as errors:
                    self.assertEqual(1, release.main(["--repository", REPOSITORY, *extra]))
                self.assertIn("Release refused", errors.getvalue())
        self.assertEqual([], self.api.calls)


class TransportTest(NoNetworkTest):
    def api(self, handler):
        transport = FakeTransport(handler)
        return release.GitHub(REPOSITORY, "secret-unit-test-token", transport), transport

    def test_release_and_asset_pagination_uses_fixed_endpoint_not_link(self):
        def handler(method, url, headers, data, limit):
            parsed = urllib.parse.urlsplit(url)
            query = urllib.parse.parse_qs(parsed.query)
            page = int(query["page"][0])
            self.assertEqual("100", query["per_page"][0])
            self.assertEqual("api.github.com", parsed.netloc)
            return 200, {"Link": '<https://attacker.invalid/>; rel="next"'}, encoded([{"id": page}] if page < 3 else [])
        api, transport = self.api(handler)
        self.assertEqual([1, 2], [item["id"] for item in api.releases()])
        self.assertEqual([1, 2], [item["id"] for item in api.assets(10)])
        self.assertEqual(6, len(transport.calls))

    def test_exactly_one_hundred_items_still_fetches_next_page(self):
        def handler(method, url, headers, data, limit):
            page = int(urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)["page"][0])
            return 200, {}, encoded([{"id": value} for value in range(1, 101)] if page == 1 else [])
        api, transport = self.api(handler)
        self.assertEqual(100, len(api.releases()))
        self.assertEqual(2, len(transport.calls))

    def test_transport_api_has_no_publish_tag_or_overwrite_paths(self):
        api, transport = self.api(lambda *args: self.fail("Must reject before transport"))
        for method, suffix, payload in (
            ("PATCH", "/releases/10", {"draft": False}),
            ("DELETE", "/releases/10", None),
            ("POST", "/git/refs", {"ref": "refs/tags/v1.0.0", "sha": COMMIT}),
            ("POST", "/releases", {"draft": False}),
        ):
            with self.subTest(method=method, suffix=suffix), self.assertRaises(release.ReleaseError):
                api.json(method, api.prefix + suffix, payload)
        self.assertEqual([], transport.calls)

    def test_asset_delete_allowed_at_transport_level(self):
        api, transport = self.api(lambda *args: (204, {}, b""))
        api.json("DELETE", api.prefix + "/releases/assets/10")
        self.assertEqual(1, len(transport.calls))
        self.assertEqual("DELETE", transport.calls[0][0])

    def test_duplicate_or_unbounded_pagination_fails_closed(self):
        api, _ = self.api(lambda *args: (200, {}, encoded([{"id": 1}])))
        with self.assertRaisesRegex(release.ReleaseError, "repeated"):
            api.releases()
        with patch.object(release, "MAX_PAGES", 1):
            with self.assertRaisesRegex(release.ReleaseError, "Pagination limit"):
                api.releases()

    def test_version_gate_reads_later_release_and_asset_pages(self):
        historical_info, _ = make_info("v0.9.0-rc.1", 12)
        metadata = encoded(historical_info)
        def handler(method, url, headers, data, limit):
            parsed = urllib.parse.urlsplit(url)
            if parsed.path.endswith("/releases/assets/77"):
                return 200, {}, metadata
            page = int(urllib.parse.parse_qs(parsed.query)["page"][0])
            if parsed.path.endswith("/releases"):
                values = {1: [{"id": 1, "draft": True}], 2: [{"id": 2, "draft": False, "tag_name": "v0.9.0-rc.1"}]}
            else:
                values = {1: [{"id": 76, "name": "old.apk"}], 2: [{"id": 77, "name": release.METADATA,
                          "state": "uploaded", "size": len(metadata)}]}
            return 200, {}, encoded(values.get(page, []))
        api, transport = self.api(handler)
        with self.assertRaisesRegex(release.ReleaseError, "maximum 12"):
            release.check_version_code(api, 12)
        self.assertTrue(all(call[0] == "GET" for call in transport.calls))

    def test_api_redirect_refused_without_forwarding_token(self):
        api, transport = self.api(lambda *args: (302, {"Location": "https://attacker.invalid"}, b""))
        with self.assertRaisesRegex(release.ReleaseError, "HTTP 302"):
            api.releases()
        self.assertEqual(1, len(transport.calls))

    def test_cdn_redirect_strips_token_and_ignores_asset_urls(self):
        def handler(method, url, headers, data, limit):
            if url.startswith(release.API):
                self.assertIn("Authorization", headers)
                return 302, {"Location": "https://release-assets.githubusercontent.com/test?signature=test"}, b""
            self.assertNotIn("Authorization", headers)
            return 200, {}, b"data"
        api, transport = self.api(handler)
        asset = {"id": 1, "state": "uploaded", "size": 4, "url": "https://attacker.invalid",
                 "browser_download_url": "https://attacker.invalid"}
        self.assertEqual(b"data", api.download(asset, 4))
        self.assertEqual(2, len(transport.calls))

    def test_unsafe_download_redirects_refused(self):
        urls = ["https://attacker.invalid", "http://release-assets.githubusercontent.com/x",
                "https://release-assets.githubusercontent.com.attacker.invalid/x",
                "https://user@release-assets.githubusercontent.com/x",
                "https://release-assets.githubusercontent.com:443/x", "/relative",
                "https://release-assets.githubusercontent.com/x#fragment"]
        for url in urls:
            with self.subTest(url=url):
                api, transport = self.api(lambda *args: (302, {"location": url}, b""))
                with self.assertRaisesRegex(release.ReleaseError, "Unsafe"):
                    api.download({"id": 1, "state": "uploaded", "size": 4}, 4)
                self.assertEqual(1, len(transport.calls))

    def test_download_size_and_state_validated(self):
        api, transport = self.api(lambda *args: (200, {}, b"bad"))
        for asset in ({"id": 1, "state": "starter", "size": 4},
                      {"id": 1, "state": "uploaded", "size": 5},
                      {"id": 1, "state": "uploaded", "size": True}):
            with self.subTest(asset=asset), self.assertRaises(release.ReleaseError):
                api.download(asset, 4)
        self.assertEqual([], transport.calls)
        with self.assertRaisesRegex(release.ReleaseError, "size mismatch"):
            api.download({"id": 1, "state": "uploaded", "size": 4}, 4)

    def test_invalid_repository_refused(self):
        for repository in ("owner", "https://github.com/a/b", "a/b/../c", "a/b?x", "a/b#x", "a/.."):
            with self.subTest(repository=repository), self.assertRaises(release.ReleaseError):
                release.GitHub(repository)

    def test_upload_endpoint_fixed_and_redirect_refused(self):
        api, transport = self.api(lambda *args: (307, {"Location": "https://attacker.invalid"}, b""))
        with self.assertRaisesRegex(release.ReleaseError, "HTTP 307"):
            api.json("POST", api.prefix + "/releases/10/assets?name=LICENSE.txt", upload=b"license")
        self.assertEqual(1, len(transport.calls))
        self.assertEqual("uploads.github.com", urllib.parse.urlsplit(transport.calls[0][1]).netloc)

    def test_transport_size_limit_with_and_without_content_length(self):
        class Response(io.BytesIO):
            code = 200
        for headers in ({"Content-Length": "5"}, {}, {"Content-Length": "invalid"}):
            with self.subTest(headers=headers):
                response = Response(b"12345")
                response.headers = headers
                transport = release.Transport()
                with patch.object(transport.opener, "open", return_value=response):
                    with self.assertRaisesRegex(release.ReleaseError, "size limit"):
                        transport.request("GET", release.API, {}, None, 4)

    def test_no_redirect_handler_never_builds_redirect_request(self):
        self.assertIsNone(release.NoRedirect().redirect_request(None, None, 302, "", {}, "https://attacker.invalid"))


if __name__ == "__main__":
    unittest.main()
