"""Public-source policy, exact archives and redacted failures using synthetic data."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import publication_policy as p
import artifacts as a
from test_artifacts import archive_fixture

ROOT = Path(__file__).resolve().parents[3]
SOURCE = 'app/src/main/assets/settings.json'


def literal(value=b'disposable-unit-test-value'):
    return b'store' + b'Pass' + b'word = "' + value + b'"\n'


class PublicationPolicyTests(unittest.TestCase):
    def rejected(self, name, data, rule=None):
        with self.assertRaises(p.PublicationError) as result:
            p.inspect_source(name, data)
        findings = json.loads(str(result.exception))
        self.assertEqual(findings, result.exception.findings)
        for finding in findings:
            self.assertEqual(set(finding), {'path', 'line', 'rule'})
            self.assertEqual(finding['path'], name)
            self.assertIsInstance(finding['line'], int)
        if rule:
            self.assertIn(rule, {f['rule'] for f in findings})
        return findings

    def test_all_current_baseline_paths_and_bytes_are_accounted_for(self):
        names = subprocess.check_output(['git', '-C', str(ROOT), 'ls-files', '-z']).decode().split('\0')
        for name in filter(None, names):
            path = ROOT / name
            if path.is_file():
                with self.subTest(path=name):
                    p.inspect_source(name, path.read_bytes())

    def test_generated_descendants_cannot_hide_under_source_roots(self):
        for name in ('app/build/result.txt', 'app/src/build/result.kt', 'assets/out/image.svg',
                     'tools/release/__pycache__/x.py', 'app/src/main/assets/backups/x.json'):
            with self.subTest(path=name):
                self.rejected(name, b'public', 'GeneratedSourcePath')

    def test_private_names_nested_force_tracking_cannot_bypass(self):
        for leaf in ('.env.local', 'key.p12', 'key.PFX', 'key.pem', 'local.properties',
                     'personal.db', 'personal.db-wal', 'personal.sqlite', 'mapping.txt',
                     'log.log', 'personal.backup', 'app.apk', 'private_key.txt',
                     'credentials.json', 'signing-password.txt'):
            with self.subTest(leaf=leaf):
                self.rejected('app/src/main/assets/' + leaf, b'public', 'SensitiveSourcePath')

    def test_full_path_allowlist_not_only_top_level(self):
        for name in ('app/unknown.txt', 'release/more.json', 'gradle/random.xml',
                     '.vscode/more.json', 'tools/other.py', 'assets/unreviewed.png'):
            self.rejected(name, b'public', 'UnreviewedSourcePath')

    def test_path_normalization_and_control_character_rejection(self):
        for name in ('', '/LICENSE', './LICENSE', 'app//x.kt', 'app/../LICENSE',
                     'app/src/a\\b.kt', 'app/src/a\nb.kt', 'app/src/a\tb.kt', 'app/src/a:secret.kt'):
            self.rejected(name, b'public', 'UnsafeSourcePath')

    def test_public_hashes_and_literal_build_metadata_are_allowed(self):
        p.inspect_source('release/signing-certificate.sha256', b'a' * 64 + b'\n')
        p.inspect_source('app/build.gradle.kts', b'versionName = "1.0.0"\nversionCode = 2\n')
        p.inspect_source(SOURCE, json.dumps({'sha256': 'f' * 64, 'commit': 'a' * 40}).encode())

    def test_dynamic_secret_references_are_not_literal_credentials(self):
        p.inspect_source('app/build.gradle.kts', b'storePassword = localProperties.getProperty("storePassword")\n')
        p.inspect_source('.github/workflows/ci.yml', b'PASSWORD: ${{ secrets.PASSWORD }}\n')
        p.inspect_source(SOURCE, json.dumps({'password': '${{ secrets.PASSWORD }}'}).encode())

    def test_hardcoded_signing_literals_in_gradle_json_and_other_source(self):
        for name in ('app/build.gradle.kts', SOURCE, 'tools/release/a.py'):
            value = b'disposable-value-not-for-logs'
            findings = self.rejected(name, b'\n' + literal(value), 'CredentialLiteral')
            self.assertEqual(findings[0]['line'], 2)
            self.assertNotIn(value.decode(), json.dumps(findings))
        self.rejected(SOURCE, json.dumps({'api_' + 'key': 'unit-test-literal'}).encode(), 'CredentialLiteral')

    def test_every_recognizable_token_format(self):
        cases = [('AwsAccessKey', b'AK' + b'IA' + b'Q' * 16),
                 ('GitHubToken', b'gh' + b'p_' + b'Q' * 36),
                 ('GitHubToken', b'github_' + b'pat_' + b'Q' * 50),
                 ('SlackToken', b'xox' + b'b-' + b'Q' * 24),
                 ('GoogleApiKey', b'AI' + b'za' + b'Q' * 35),
                 ('CredentialUrl', b'https://' + b'unit:fixture@' + b'example.invalid')]
        for rule, value in cases:
            with self.subTest(rule=rule):
                result = self.rejected(SOURCE, value, rule)
                self.assertNotIn(value.decode(), json.dumps(result))

    def test_native_unquoted_properties_yaml_and_block_scalars(self):
        field = b'store' + b'Password'
        for name in ('gradle.properties', '.github/workflows/ci.yml'):
            for tail in (b'= unit-test-value', b': unit-test-value', b': |\n  unit-test-value', b' unit-test-value'):
                self.rejected(name, field + tail, 'CredentialLiteral')
            p.inspect_source(name, field + b': ${{ secrets.PASSWORD }}')

    def test_multiline_quoted_yaml_and_finding_limit(self):
        field = b'RELEASE_STORE_' + b'PASSWORD'
        self.rejected('.github/workflows/ci.yml', field + b': "unit\n  fixture"', 'CredentialLiteral')
        self.rejected('.github/workflows/ci.yml', field + b": 'unit\n  fixture'", 'CredentialLiteral')
        p.inspect_source('.github/workflows/ci.yml', field + b': ""')
        self.rejected(SOURCE, literal() * 1001, 'SourceFindingLimit')
        self.rejected('gradle.properties', (field + b'=unit\n') * 1001, 'SourceFindingLimit')

    def test_private_key_headers_and_service_account_json(self):
        for algorithm in (b'', b'RSA ', b'EC ', b'OPENSSH ', b'ENCRYPTED '):
            self.rejected(SOURCE, b'-----BEGIN ' + algorithm + b'PRIVATE KEY-----', 'PrivateKeyHeader')
        self.rejected(SOURCE, json.dumps({'type': 'service_' + 'account'}).encode(), 'ServiceAccount')

    def test_all_findings_not_just_first_and_no_values(self):
        result = self.rejected(SOURCE, literal() + literal() + b'AK' + b'IA' + b'Q' * 16)
        self.assertEqual(len(result), 3)

    def test_known_binary_exact_bytes_only(self):
        for name, digest in p.REVIEWED_BINARIES.items():
            data = (ROOT / name).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), digest)
            p.inspect_source(name, data)
            self.rejected(name, data + b'changed', 'UnreviewedBinaryBytes')

    def test_unknown_binary_renamed_as_text_rejected(self):
        for data in (b'\x00key', b'\xffsecret', b'a\x01b', b'SQLite format 3\x00'):
            self.rejected(SOURCE, data, 'UnreviewedBinaryBytes')

    def test_oversized_input_refused(self):
        with patch.object(p, 'MAX_SOURCE_BYTES', 3):
            self.rejected(SOURCE, b'1234', 'SourceSizeLimit')

    def test_total_bytes_and_count_limits(self):
        files = {'README.md': b'123', 'LICENSE': b'456'}
        for setting, value in (('MAX_TOTAL_BYTES', 5), ('MAX_FILES', 1)):
            with patch.object(p, setting, value), self.assertRaises(p.PublicationError):
                p.inspect_sources(files)

    def test_lfs_pointer_not_valid_source(self):
        self.rejected(SOURCE, b'version https://git-lfs.github.com/spec/v1\noid sha256:abc', 'UnresolvedLfsPointer')

    def test_fixture_exceptions_are_exact_match_and_path_scoped(self):
        name = 'tools/release/tests/test_workflow.py'
        text = (ROOT / name).read_bytes()
        p.inspect_source(name, text)
        self.rejected(name, text.replace(b'fake-store', b'changed-value'), 'CredentialLiteral')
        self.rejected('tools/release/tests/renamed.py', text, 'CredentialLiteral')
        self.rejected(name, text + literal(), 'CredentialLiteral')

    def test_no_blanket_test_directory_or_placeholder_exemption(self):
        for value in (b'placeholder', b'example', b'fake-password'):
            self.rejected('tools/release/tests/new_test.py', literal(value), 'CredentialLiteral')

    def test_bip340_public_vectors_still_scanned_not_skipped(self):
        name = 'app/src/test/resources/bip340-test-vectors.csv'
        data = (ROOT / name).read_bytes()
        p.inspect_source(name, data)
        self.rejected(name, data + literal(), 'CredentialLiteral')

    def test_gitignore_private_data_and_public_inputs(self):
        denied = ['app/src/main/assets/.env.prod', 'release/private.p12', 'app/a.sqlite3-wal',
                  'app/mapping.txt', 'release/signing-password.txt', 'app/service-account.json']
        allowed = ['release/signing-certificate.sha256', 'gradle/wrapper/gradle-wrapper.jar',
                   'app/src/test/resources/bip340-test-vectors.csv']
        for name in denied + allowed:
            result = subprocess.run(['git', '-C', str(ROOT), 'check-ignore', '--no-index', '-q', name])
            self.assertEqual(result.returncode, 0 if name in denied else 1, name)


class ArchivePolicyTests(unittest.TestCase):
    def test_release_archive_checks_exact_content_even_with_matching_git_hash(self):
        archive, tree = archive_fixture({'app/build.gradle.kts': literal()})
        with self.assertRaisesRegex(a.ReleaseError, 'CredentialLiteral'):
            a.archive_files(archive, tree, 'v1.0.0')

    def test_release_archive_size_rejection_precedes_extraction(self):
        archive, tree = archive_fixture({'README.md': b'1234'})
        with patch.object(p, 'MAX_SOURCE_BYTES', 3), patch.object(tarfile.TarFile, 'extractfile') as read:
            with self.assertRaisesRegex(a.ReleaseError, 'SourceSizeLimit'):
                a.archive_files(archive, tree, 'v1.0.0')
            read.assert_not_called()

    def test_release_archive_total_limit(self):
        archive, tree = archive_fixture({'README.md': b'123', 'LICENSE': b'456'})
        with patch.object(p, 'MAX_TOTAL_BYTES', 5), self.assertRaisesRegex(a.ReleaseError, 'SourceTotalLimit'):
            a.archive_files(archive, tree, 'v1.0.0')

    def test_source_tree_checks_size_before_archiving(self):
        output = b'100644 blob ' + b'a' * 40 + b' 99\tREADME.md\0'
        with patch.object(a, 'run', return_value=output), patch.object(p, 'MAX_SOURCE_BYTES', 3):
            with self.assertRaisesRegex(a.ReleaseError, 'SourceSizeLimit'):
                a.source_tree('a' * 40)

    def test_canonical_output_preserves_only_checked_content_and_modes(self):
        import io
        sentinel = b'unchecked-envelope-unit-fixture'
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w', pax_headers={'comment': sentinel.decode()}) as archive:
            member = tarfile.TarInfo('SplitFree-v1.0.0/README.md')
            member.size = 4
            archive.addfile(member, io.BytesIO(b'safe'))
        original = buffer.getvalue() + sentinel
        tree = {'README.md': ('100644', hashlib.sha1(b'blob 4\0safe').hexdigest())}
        files = a.archive_files(original, tree, 'v1.0.0')
        clean = a.canonical_source_archive(files, tree, 'v1.0.0')
        self.assertNotIn(sentinel, clean)
        self.assertEqual(a.archive_files(clean, tree, 'v1.0.0'), files)

    def test_local_archive_checks_caller_supplied_bytes_again(self):
        import distribute as d
        for files in ({SOURCE: literal()}, {'app/src/main/assets/personal.db': b'private'}):
            with self.assertRaises(a.ReleaseError):
                d.source_archive(files)


if __name__ == '__main__':
    unittest.main()
