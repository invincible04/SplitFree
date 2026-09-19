"""Website source paths retain the shared fail-closed publication checks."""
import hashlib
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import publication_policy as p


EXACT_FILES = (
    'website/index.html', 'website/package.json', 'website/package-lock.json',
    'website/site.config.json', 'website/README.md', 'website/DESIGN.md',
    'website/THIRD_PARTY_NOTICES.md', 'website/.gitignore', 'website/.prettierignore',
    'website/.prettierrc.json', 'website/playwright.config.mjs',
)
DIRECTORY_SUFFIXES = {
    'src': {'.css', '.js'},
    'scripts': {'.mjs'},
    'tests': {'.mjs'},
    'public': {'.svg', '.txt', '.vtt'},
    'sections': {'.html'},
}
SOURCE_FILES = EXACT_FILES + tuple(
    f'website/{directory}/nested/source{suffix}'
    for directory, suffixes in DIRECTORY_SUFFIXES.items() for suffix in sorted(suffixes)
)


class WebsitePolicyTests(unittest.TestCase):
    def rejected(self, name, data, rule):
        with self.assertRaises(p.PublicationError) as result:
            p.inspect_source(name, data)
        findings = result.exception.findings
        self.assertEqual(json.loads(str(result.exception)), findings)
        self.assertIn(rule, {finding['rule'] for finding in findings})
        for finding in findings:
            self.assertEqual(set(finding), {'path', 'line', 'rule'})
            self.assertEqual(finding['path'], name)
            self.assertIsInstance(finding['line'], int)
        return findings

    def test_exact_root_files_allowed(self):
        for name in EXACT_FILES:
            with self.subTest(path=name):
                p.inspect_source(name, b'Public source\n')

    def test_exact_root_files_do_not_allow_other_locations(self):
        for name in EXACT_FILES:
            for prefix in ('', 'website/nested/', 'website-copy/'):
                candidate = prefix + name.split('/')[-1]
                with self.subTest(path=candidate):
                    if candidate in p.ROOT_FILES:
                        continue
                    self.rejected(candidate, b'public', 'UnreviewedSourcePath')

    def test_directory_and_suffix_pairs_are_narrow(self):
        suffixes = {'.css', '.js', '.mjs', '.svg', '.txt', '.vtt', '.html', '.json',
                    '.md', '.map', '.png', '.webp', '.woff2', '.mp4', '.JS', '.SVG'}
        for directory, allowed in DIRECTORY_SUFFIXES.items():
            for nested in ('', 'nested/'):
                for suffix in sorted(suffixes):
                    name = f'website/{directory}/{nested}source{suffix}'
                    with self.subTest(path=name):
                        if suffix in allowed:
                            p.inspect_source(name, b'public')
                        else:
                            self.rejected(name, b'public', 'UnreviewedSourcePath')

    def test_unreviewed_roots_and_similar_prefixes_are_rejected(self):
        for name in ('website/other.json', 'website/other.html', 'website/other.md',
                     'website/vite.config.js', 'website/src.js', 'website/public.svg',
                     'website/src-copy/source.js', 'website/scripts-copy/source.mjs',
                     'website/tests-copy/source.mjs', 'website/public-copy/source.svg',
                     'website/vendor/source.js', 'website/assets/source.svg',
                     'website/nested/src/source.js', 'website-copy/src/source.js',
                     'Website/src/source.js', 'website/Src/source.js'):
            with self.subTest(path=name):
                self.rejected(name, b'public', 'UnreviewedSourcePath')

    def test_sensitive_files_and_parent_components_stay_rejected(self):
        for name in ('website/src/.env.js', 'website/src/.ENV.production.js',
                     'website/public/private_key.svg', 'website/public/PRIVATE-KEY.txt',
                     'website/public/signing-password.txt', 'website/public/mapping.txt',
                     'website/public/user.sqlite', 'website/public/key.pem',
                     'website/local.properties', 'website/credentials.json',
                     'website/public/.env/public.svg', 'website/src/credentials.json/source.js'):
            with self.subTest(path=name):
                self.rejected(name, b'public', 'SensitiveSourcePath')

    def test_generated_descendants_stay_rejected(self):
        generated = ('build', 'node_modules', '__pycache__', 'out', '.git', 'backups',
                     'dist', 'coverage', '.cache', '.vite', 'playwright-report', 'test-results')
        for directory, suffixes in DIRECTORY_SUFFIXES.items():
            for generated_directory in generated:
                for spelling in (generated_directory, generated_directory.upper()):
                    name = f'website/{directory}/nested/{spelling}/source{sorted(suffixes)[0]}'
                    with self.subTest(path=name):
                        self.rejected(name, b'public', 'GeneratedSourcePath')

    def test_traversal_and_unsafe_paths_stay_rejected(self):
        for name in ('/website/index.html', './website/index.html', 'website//index.html',
                     'website/../README.md', 'website/src/../index.html',
                     'website/src/./source.js', 'website/src/source.js/',
                     'website/src/a\\b.js', 'website/src/a:b.js',
                     'website/src/a\nb.js', 'website/src/a\tb.js', 'website/src/a\x7fb.js'):
            with self.subTest(path=name):
                self.rejected(name, b'public', 'UnsafeSourcePath')

    def test_every_allowed_text_path_is_scanned_and_findings_are_redacted(self):
        value = b'website-synthetic-value'
        data = b'Public source\n' + b'api_' + b'key = "' + value + b'"\n'
        for name in SOURCE_FILES:
            with self.subTest(path=name):
                findings = self.rejected(name, data, 'CredentialLiteral')
                self.assertEqual(findings[0]['line'], 2)
                self.assertNotIn(value.decode(), json.dumps(findings))

    def test_recognizable_tokens_are_scanned_in_website_tests(self):
        data = b'AK' + b'IA' + b'Q' * 16
        findings = self.rejected('website/tests/source.mjs', data, 'AwsAccessKey')
        self.assertNotIn(data.decode(), json.dumps(findings))

    def test_binary_bytes_cannot_hide_under_allowed_text_suffixes(self):
        for name in SOURCE_FILES:
            for data in (b'\x00binary', b'\xffbinary', b'control\x01byte'):
                with self.subTest(path=name, data=data):
                    self.rejected(name, data, 'UnreviewedBinaryBytes')

    def test_lfs_pointers_remain_rejected(self):
        data = b'version https://git-lfs.github.com/spec/v1\noid sha256:abc'
        for name in SOURCE_FILES:
            with self.subTest(path=name):
                self.rejected(name, data, 'UnresolvedLfsPointer')

    def test_binary_review_is_exact_path_and_exact_bytes(self):
        name = 'website/public/reviewed.webp'
        data = b'\x00synthetic-binary\xff'
        self.rejected(name, data, 'UnreviewedSourcePath')
        with patch.dict(p.REVIEWED_BINARIES, {name: hashlib.sha256(data).hexdigest()}):
            p.inspect_source(name, data)
            self.rejected(name, data + b'changed', 'UnreviewedBinaryBytes')
            self.rejected('website/public/renamed.webp', data, 'UnreviewedSourcePath')
            self.rejected('website/src/reviewed.webp', data, 'UnreviewedSourcePath')
        self.rejected(name, data, 'UnreviewedSourcePath')

    def test_binary_pins_do_not_bypass_path_guards_or_size_limits(self):
        data = b'\x00synthetic-binary'
        for name, rule in (
            ('website/public/../reviewed.webp', 'UnsafeSourcePath'),
            ('website/public/private_key.webp', 'SensitiveSourcePath'),
            ('website/public/build/reviewed.webp', 'GeneratedSourcePath'),
            ('website/public/dist/reviewed.webp', 'GeneratedSourcePath'),
        ):
            with self.subTest(path=name), patch.dict(
                p.REVIEWED_BINARIES, {name: hashlib.sha256(data).hexdigest()}
            ):
                self.rejected(name, data, rule)
        name = 'website/public/reviewed.webp'
        with patch.dict(p.REVIEWED_BINARIES, {name: hashlib.sha256(data).hexdigest()}), \
                patch.object(p, 'MAX_SOURCE_BYTES', 3):
            self.rejected(name, data, 'SourceSizeLimit')

    def test_source_and_total_limits_still_apply(self):
        with patch.object(p, 'MAX_SOURCE_BYTES', 3):
            self.rejected('website/index.html', b'1234', 'SourceSizeLimit')
        files = {'website/index.html': b'123', 'website/src/source.js': b'456'}
        for setting, value in (('MAX_TOTAL_BYTES', 5), ('MAX_FILES', 1)):
            with self.subTest(setting=setting), patch.object(p, setting, value), \
                    self.assertRaises(p.PublicationError) as result:
                p.inspect_sources(files)
            self.assertEqual(result.exception.findings,
                             [{'path': '', 'line': 0, 'rule': 'SourceTotalLimit'}])

    def test_other_source_roots_are_unchanged(self):
        for name in ('README.md', 'app/src/main/Source.kt', 'assets/logo.svg',
                     'tools/release/source.py', '.github/workflows/ci.yml',
                     'app/src/main/assets/dist/source.json'):
            with self.subTest(path=name):
                p.inspect_source(name, b'public')
        for name in ('app/unknown.js', 'assets/source.js', 'tools/release/source.mjs',
                     'release/site.config.json', '.github/workflows/nested/ci.yml'):
            with self.subTest(path=name):
                self.rejected(name, b'public', 'UnreviewedSourcePath')

    def test_test_module_itself_passes_content_policy(self):
        p.inspect_source('tools/release/tests/test_website_policy.py', Path(__file__).read_bytes())


if __name__ == '__main__':
    unittest.main()
