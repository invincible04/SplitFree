#!/usr/bin/env python3
"""Validate a release bundle and create/resume an unpublished GitHub draft only.

The package job verifies APK signing/identity and exact source provenance. This job
checks that its six immutable inputs agree and never builds or loads signing keys.
Use --check-version-code with a read-only token before building. Upload requires
GH_TOKEN and an existing protected tag; serialize release jobs in the workflow.
"""

import argparse
import hashlib
import json
import os
import re
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"
DOWNLOAD_HOSTS = frozenset({
    "release-assets.githubusercontent.com",
    "objects.githubusercontent.com",
    "github-releases.githubusercontent.com",
})
METADATA = "release-info.json"
CHECKSUMS = "SHA256SUMS.txt"
METADATA_LIMIT = 1024 * 1024
JSON_LIMIT = 2 * 1024 * 1024
TEXT_LIMIT = 16 * 1024 * 1024
ASSET_LIMIT = 256 * 1024 * 1024
BUNDLE_LIMIT = 512 * 1024 * 1024
CHECKSUM_LIMIT = 16 * 1024
MAX_PAGES = 1000
TAG_PATTERN = r"v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
HISTORICAL_TAG_PATTERN = TAG_PATTERN + r"(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?"
COMMIT_PATTERN = r"[0-9a-f]{40}"
DIGEST_PATTERN = r"[0-9a-f]{64}"
MARKER_PREFIX = "<!-- splitfree-source-commit:"
BUNDLE_MARKER_PREFIX = "<!-- splitfree-bundle-sha256:"


class ReleaseError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def matches(pattern, value):
    return isinstance(value, str) and re.fullmatch(pattern, value) is not None


def positive_int(value):
    return type(value) is int and value > 0


def object_id(value):
    require(isinstance(value, dict) and positive_int(value.get("id")), "Invalid API object id")
    return value["id"]


def parse_json(data):
    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate JSON key")
            result[key] = value
        return result

    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=unique_pairs)
    except (UnicodeDecodeError, ValueError, RecursionError) as error:
        raise ReleaseError("Invalid UTF-8 JSON") from error


def artifact_order(artifacts):
    require(isinstance(artifacts, dict) and len(artifacts) == 4, "Exactly four artifacts required")
    for name, digest in artifacts.items():
        require(matches(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", name)
                and not name.endswith("."), "Unsafe artifact filename")
        require(matches(DIGEST_PATTERN, digest), "Artifact SHA-256 must be normalized lowercase hex")
    apks = [name for name in artifacts if name.endswith(".apk")]
    sources = [name for name in artifacts if name.endswith((".tar.gz", ".zip"))]
    require(len(apks) == len(sources) == 1, "Exactly one APK and one source archive required")
    order = [sources[0], "LICENSE.txt", "THIRD-PARTY-NOTICES.txt", apks[0]]
    require(set(order) == set(artifacts), "Unexpected or missing artifact")
    return order


def validate_metadata(info, tag=None, commit=None):
    fields = {"schemaVersion", "tag", "commit", "versionName", "versionCode", "packageName",
              "certificateSha256", "artifacts"}
    require(isinstance(info, dict) and set(info) == fields, "Invalid release-info.json fields")
    require(type(info["schemaVersion"]) is int and info["schemaVersion"] == 1,
            "Unsupported metadata schemaVersion")
    require(matches(HISTORICAL_TAG_PATTERN, info["tag"]), "Invalid metadata tag")
    require(matches(COMMIT_PATTERN, info["commit"]), "Invalid metadata commit")
    require(isinstance(info["versionName"], str) and info["versionName"] == info["tag"][1:],
            "versionName must match tag without v")
    require(positive_int(info["versionCode"]), "versionCode must be a positive integer")
    require(info["packageName"] == "com.splitfree", "Unexpected packageName")
    require(matches(DIGEST_PATTERN, info["certificateSha256"]),
            "certificateSha256 must be normalized lowercase hex")
    order = artifact_order(info["artifacts"])
    require(order[0] == f"SplitFree-{info['tag']}-source.tar.gz", "Source archive name must bind the tag")
    require(tag is None or info["tag"] == tag, "Metadata tag mismatch")
    require(commit is None or info["commit"] == commit, "Metadata commit mismatch")
    return info


def read_file(path, limit):
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, "rb") as stream:
            details = os.fstat(stream.fileno())
            require(stat.S_ISREG(details.st_mode), "Bundle assets must be regular files")
            require(details.st_size <= limit, "Bundle file exceeds size limit")
            data = stream.read(limit + 1)
            require(len(data) <= limit, "Bundle file exceeds size limit")
            return data
    except OSError as error:
        raise ReleaseError(f"Cannot safely read bundle file: {path.name}") from error


@dataclass(frozen=True)
class Bundle:
    info: dict
    files: dict


def load_bundle(directory, tag, commit):
    require(matches(TAG_PATTERN, tag), "Tag must be vX.Y.Z")
    require(matches(COMMIT_PATTERN, commit), "Commit must be 40 lowercase hex characters")
    directory = Path(directory)
    metadata_bytes = read_file(directory / METADATA, METADATA_LIMIT)
    info = validate_metadata(parse_json(metadata_bytes), tag, commit)
    order = artifact_order(info["artifacts"])
    expected = set(order) | {METADATA, CHECKSUMS}
    require({path.name for path in directory.iterdir()} == expected,
            "Bundle directory must contain exactly the six release files")
    files = {}
    for name in order:
        limit = TEXT_LIMIT if name in {"LICENSE.txt", "THIRD-PARTY-NOTICES.txt"} else ASSET_LIMIT
        data = read_file(directory / name, limit)
        require(bool(data), "Empty bundle artifact")
        require(hashlib.sha256(data).hexdigest() == info["artifacts"][name],
                f"Artifact checksum mismatch: {name}")
        files[name] = data
    files[METADATA] = metadata_bytes
    checksum_bytes = read_file(directory / CHECKSUMS, CHECKSUM_LIMIT)
    try:
        lines = checksum_bytes.decode("ascii").splitlines()
    except UnicodeDecodeError as error:
        raise ReleaseError("Checksums must be ASCII") from error
    sums = {}
    for line in lines:
        match = re.fullmatch(r"([0-9a-f]{64}) [ *]([A-Za-z0-9][A-Za-z0-9._-]{0,127})", line)
        require(match is not None, "Invalid SHA256SUMS.txt line")
        digest, name = match.groups()
        require(name not in sums, "Duplicate checksum filename")
        sums[name] = digest
    require(set(sums) == set(files), "Checksums must cover exactly artifacts and release-info.json")
    for name, data in files.items():
        require(sums[name] == hashlib.sha256(data).hexdigest(), f"Checksum mismatch: {name}")
    files[CHECKSUMS] = checksum_bytes
    require(sum(map(len, files.values())) <= BUNDLE_LIMIT, "Bundle exceeds total size limit")
    return Bundle(info, files)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


class Transport:
    def __init__(self):
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, method, url, headers, data, limit):
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=60)
        except urllib.error.HTTPError as error:
            response = error
        except (OSError, urllib.error.URLError, ValueError) as error:
            raise ReleaseError("GitHub transport failed; no automatic write retry") from error
        try:
            with response:
                length = response.headers.get("Content-Length")
                if length is not None:
                    require(length.isdigit() and int(length) <= limit, "HTTP response exceeds size limit")
                content = response.read(limit + 1)
                require(len(content) <= limit, "HTTP response exceeds size limit")
                return response.code, dict(response.headers), content
        except OSError as error:
            raise ReleaseError("GitHub response interrupted; retry only after inspection") from error


class GitHub:
    def __init__(self, repository, token=None, transport=None):
        require(matches(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}", repository),
                "Repository must be owner/name")
        self.repository = repository
        self.prefix = f"/repos/{repository}"
        self.token = token
        self.transport = transport if transport is not None else Transport()

    def _headers(self, binary=False):
        headers = {"Accept": "application/octet-stream" if binary else "application/vnd.github+json",
                   "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "SplitFree-release-helper"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return headers

    def json(self, method, path, payload=None, *, upload=None):
        require(path.startswith(self.prefix + "/"), "API path outside explicit repository")
        is_create = method == "POST" and path == self.prefix + "/releases" and upload is None
        is_upload = (method == "POST" and upload is not None
                     and re.fullmatch(re.escape(self.prefix) + r"/releases/[1-9][0-9]*/assets\?name=[A-Za-z0-9._-]+", path))
        is_asset_delete = (method == "DELETE" and upload is None and payload is None
                           and re.fullmatch(re.escape(self.prefix) + r"/releases/assets/[1-9][0-9]*", path))
        require(method == "GET" or is_create or is_upload or is_asset_delete,
                "Only reads, draft creation, asset uploads and incomplete asset deletion allowed")
        if is_create:
            require(isinstance(payload, dict) and payload.get("draft") is True, "Only draft creation allowed")
        headers = self._headers()
        if upload is not None:
            require(method == "POST", "Asset upload must be POST")
            url, data = UPLOADS + path, upload
            headers["Content-Type"] = "application/octet-stream"
        else:
            url = API + path
            data = None if payload is None else json.dumps(payload).encode("utf-8")
            if data is not None:
                headers["Content-Type"] = "application/json"
        status, _, content = self.transport.request(method, url, headers, data, JSON_LIMIT)
        if is_asset_delete:
            require(status == 204, f"GitHub DELETE failed (HTTP {status}); inspect before retrying")
            return None
        require(status == (200 if method == "GET" else 201),
                f"GitHub {method} failed (HTTP {status}); inspect before retrying")
        return parse_json(content)

    def paginate(self, suffix):
        result, seen = [], set()
        for page in range(1, MAX_PAGES + 1):
            values = self.json("GET", self.prefix + suffix + f"?per_page=100&page={page}")
            require(isinstance(values, list) and len(values) <= 100, "Invalid paginated API response")
            if not values:
                return result
            for value in values:
                identifier = object_id(value)
                require(identifier not in seen, "Pagination repeated an API object; retry safely")
                seen.add(identifier)
                result.append(value)
        raise ReleaseError("Pagination limit exceeded; refusing incomplete release history")

    def releases(self):
        return self.paginate("/releases")

    def assets(self, release_id):
        require(positive_int(release_id), "Invalid release id")
        return self.paginate(f"/releases/{release_id}/assets")

    def delete_asset(self, asset):
        identifier = object_id(asset)
        self.json("DELETE", self.prefix + f"/releases/assets/{identifier}")

    def download(self, asset, limit):
        identifier = object_id(asset)
        require(asset.get("state") == "uploaded", "Existing asset is not fully uploaded")
        size = asset.get("size")
        require(type(size) is int and 0 <= size <= limit, "Invalid asset size")
        url = API + self.prefix + f"/releases/assets/{identifier}"
        status, headers, data = self.transport.request("GET", url, self._headers(True), None, limit)
        for _ in range(3):
            if status not in {301, 302, 303, 307, 308}:
                break
            location = next((value for key, value in headers.items() if key.lower() == "location"), "")
            parsed = urllib.parse.urlsplit(location)
            require(parsed.scheme == "https" and parsed.netloc in DOWNLOAD_HOSTS
                    and not parsed.fragment, "Unsafe asset redirect refused")
            status, headers, data = self.transport.request(
                "GET", location, {"Accept": "application/octet-stream", "User-Agent": "SplitFree-release-helper"},
                None, limit)
        require(status == 200, f"Asset download failed (HTTP {status})")
        require(len(data) == size, "Downloaded asset size mismatch")
        return data


def check_version_code(api, version_code, releases=None):
    """Fail closed unless CODE exceeds every published release, including prereleases."""
    require(positive_int(version_code), "versionCode must be a positive integer")
    maximum = 0
    for release in api.releases() if releases is None else releases:
        require(type(release.get("draft")) is bool, "Release draft state missing")
        if release["draft"]:
            continue
        metadata = [asset for asset in api.assets(object_id(release)) if asset.get("name") == METADATA]
        require(len(metadata) == 1, "Published release is missing unique release-info.json; manual migration required")
        info = validate_metadata(parse_json(api.download(metadata[0], METADATA_LIMIT)), release.get("tag_name"))
        require(isinstance(release.get("tag_name"), str), "Published release tag missing")
        maximum = max(maximum, info["versionCode"])
    require(version_code > maximum, f"versionCode {version_code} must exceed published maximum {maximum}")
    return maximum


def verify_source(api, tag, commit):
    reference = api.json("GET", api.prefix + "/git/ref/tags/" + urllib.parse.quote(tag, safe=""))
    require(isinstance(reference, dict) and reference.get("ref") == f"refs/tags/{tag}", "Tag reference mismatch")
    obj = reference.get("object")
    seen = set()
    for _ in range(16):
        require(isinstance(obj, dict) and matches(COMMIT_PATTERN, obj.get("sha")), "Invalid tag object")
        if obj.get("type") == "commit":
            require(obj["sha"] == commit, "Existing tag resolves to a different commit")
            break
        require(obj.get("type") == "tag" and obj["sha"] not in seen, "Invalid or cyclic annotated tag")
        seen.add(obj["sha"])
        annotated = api.json("GET", api.prefix + "/git/tags/" + obj["sha"])
        require(isinstance(annotated, dict) and annotated.get("sha") == obj["sha"], "Annotated tag object mismatch")
        obj = annotated.get("object")
    else:
        raise ReleaseError("Annotated tag depth exceeded")
    comparison = api.json("GET", api.prefix + f"/compare/{commit}...mainline?per_page=1&page=1")
    require(isinstance(comparison, dict) and comparison.get("status") in {"ahead", "identical"}
            and isinstance(comparison.get("merge_base_commit"), dict)
            and comparison["merge_base_commit"].get("sha") == commit,
            "Release commit is not an ancestor of current mainline")


def source_marker(repository, commit):
    return f"{MARKER_PREFIX} https://github.com/{repository}/commit/{commit} -->"


def verify_draft(release, repository, tag, commit, bundle_digest):
    object_id(release)
    require(release.get("draft") is True, "Published release must never be modified")
    require(release.get("tag_name") == tag, "Draft tag mismatch")
    require(release.get("prerelease") is False, "Stable release must not reuse a prerelease draft")
    body = release.get("body")
    marker = source_marker(repository, commit)
    require(isinstance(body, str) and body.count(MARKER_PREFIX) == 1
            and body.splitlines().count(marker) == 1, "Draft lacks exact unique source commit marker")
    bundle_marker = f"{BUNDLE_MARKER_PREFIX} {bundle_digest} -->"
    require(body.count(BUNDLE_MARKER_PREFIX) == 1 and body.splitlines().count(bundle_marker) == 1,
            "Draft bundle differs or lacks exact unique bundle checksum marker")


def verify_assets(api, release_id, files):
    existing = {}
    for asset in api.assets(release_id):
        name = asset.get("name")
        require(isinstance(name, str) and name in files, "Draft contains an unexpected asset")
        require(name not in existing, "Draft contains duplicate asset names")
        if asset.get("state") != "uploaded":
            api.delete_asset(asset)
            continue
        require(api.download(asset, len(files[name])) == files[name], f"Existing asset bytes differ: {name}")
        existing[name] = asset
    return existing


def upload_bundle(api, bundle):
    require(bool(api.token), "GH_TOKEN is required for draft upload")
    info, files = bundle.info, bundle.files
    tag, commit = info["tag"], info["commit"]
    bundle_digest = hashlib.sha256(files[CHECKSUMS]).hexdigest()
    releases = api.releases()
    matching = [release for release in releases if release.get("tag_name") == tag]
    require(len(matching) <= 1, "Multiple releases use the requested tag")
    if matching:
        verify_draft(matching[0], api.repository, tag, commit, bundle_digest)
    check_version_code(api, info["versionCode"], releases)
    verify_source(api, tag, commit)
    if matching:
        release_id = object_id(matching[0])
    else:
        body = (source_marker(api.repository, commit) + "\n"
                f"{BUNDLE_MARKER_PREFIX} {bundle_digest} -->\n\n"
                "UNPUBLISHED DRAFT - assets may be incomplete. Do not distribute or advertise.\n"
                "Maintainer must verify the complete bundle, signing identity, source, notices, "
                "and device acceptance before manual publication.\n\n"
                f"Signing certificate SHA-256: `{info['certificateSha256']}`\n")
        created = api.json("POST", api.prefix + "/releases", {
            "tag_name": tag, "target_commitish": commit, "name": tag, "body": body,
            "draft": True, "prerelease": False, "generate_release_notes": False,
        })
        verify_draft(created, api.repository, tag, commit, bundle_digest)
        release_id = object_id(created)
    path = api.prefix + f"/releases/{release_id}"

    def reread_draft():
        current = api.json("GET", path)
        require(object_id(current) == release_id, "Draft id changed")
        verify_draft(current, api.repository, tag, commit, bundle_digest)

    reread_draft()
    verify_assets(api, release_id, files)
    for name, data in files.items():
        verify_source(api, tag, commit)
        reread_draft()
        existing = verify_assets(api, release_id, files)
        if name in existing:
            continue
        reread_draft()
        # GitHub has no conditional upload: the workflow must serialize this job.
        api.json("POST", path + "/assets?" + urllib.parse.urlencode({"name": name}), upload=data)
    verify_source(api, tag, commit)
    reread_draft()
    require(set(verify_assets(api, release_id, files)) == set(files), "Draft bundle remains incomplete")
    check_version_code(api, info["versionCode"])
    verify_source(api, tag, commit)
    reread_draft()
    return release_id


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True, help="Explicit GitHub owner/name")
    parser.add_argument("--directory", type=Path, help="Directory containing exactly six release files")
    parser.add_argument("--tag", help="Existing vX.Y.Z tag; never created or moved intentionally")
    parser.add_argument("--commit", help="Full lowercase 40-hex release commit")
    parser.add_argument("--check-version-code", type=int, metavar="CODE", help="Read-only pre-build version gate")
    args = parser.parse_args(argv)
    try:
        api = GitHub(args.repository, os.environ.get("GH_TOKEN"))
        if args.check_version_code is not None:
            require(args.directory is None and args.tag is None and args.commit is None,
                    "Version-code check cannot be combined with upload arguments")
            maximum = check_version_code(api, args.check_version_code)
            print(f"versionCode {args.check_version_code} exceeds published maximum {maximum}")
        else:
            require(args.directory is not None and args.tag is not None and args.commit is not None,
                    "Upload requires --directory, --tag, and --commit")
            bundle = load_bundle(args.directory, args.tag, args.commit)
            release_id = upload_bundle(api, bundle)
            print(f"Verified six assets in unpublished draft {release_id}; manual review/publication still required.")
        return 0
    except (ReleaseError, OSError) as error:
        print(f"Release refused: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
