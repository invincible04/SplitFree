"""Security regression checks for this repository's release workflow and inline signer.

Actionlint separately validates YAML and Actions expressions. These tests execute the
actual inline signing shell against disposable fake SDK tools, never a real key.
"""
import base64
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / '.github/workflows/release.yml'


def job(text, name):
    section = text.split(f'  {name}:\n', 1)[1]
    return re.split(r'^  [a-z]+:\n', section, maxsplit=1, flags=re.M)[0]


def signing_shell():
    section = job(WORKFLOW.read_text(), 'sign')
    return textwrap.dedent(section.split('        run: |\n', 1)[1].split('      - name: Cleanup', 1)[0])


class WorkflowBoundaryTests(unittest.TestCase):
    def test_signer_has_no_repository_code_or_build_cache(self):
        section = job(WORKFLOW.read_text(), 'sign')
        self.assertNotIn('actions/checkout@', section)
        self.assertNotIn('gradlew', section)
        self.assertNotIn('tools/release/', section)
        self.assertNotIn('setup-gradle@', section)
        self.assertIn('environment: release-signing', section)
        self.assertIn('needs: build', section)
        self.assertIn('path: ${{ runner.temp }}/signed.apk', section)

    def test_secrets_only_in_signer_and_write_token_only_in_draft(self):
        text = WORKFLOW.read_text()
        for name in ('build', 'package', 'draft'):
            self.assertNotIn('secrets.', job(text, name))
        for name in ('build', 'sign', 'package'):
            self.assertNotIn('contents: write', job(text, name))
        self.assertEqual(text.count('contents: write'), 1)
        self.assertIn('contents: read', text)
        self.assertIn('needs: [build, package]', job(text, 'draft'))

    def test_actions_are_pinned_and_checkout_does_not_persist_credentials(self):
        for path in (WORKFLOW, ROOT / '.github/workflows/ci.yml'):
            text = path.read_text()
            for ref in re.findall(r'uses: (\S+)', text):
                self.assertRegex(ref, r'^[\w/-]+@[0-9a-f]{40}$')
            self.assertEqual(text.count('actions/checkout@'), text.count('persist-credentials: false'))
            self.assertNotIn('pull_request_target', text)
            self.assertIn('timeout-minutes:', text)
        text = WORKFLOW.read_text()
        self.assertIn('github.ref_type == \'tag\'', text)
        self.assertIn('cancel-in-progress: false', text)
        self.assertIn('artifact-ids: ${{ needs.build.outputs.package-id }}', job(text, 'sign'))
        self.assertIn('ref: ${{ needs.build.outputs.commit }}', job(text, 'package'))
        self.assertIn('ref: ${{ needs.build.outputs.commit }}', job(text, 'draft'))

    def test_required_ci_check_has_unique_display_name(self):
        ci = job((ROOT / '.github/workflows/ci.yml').read_text(), 'build')
        release = job(WORKFLOW.read_text(), 'build')
        self.assertIn('    name: CI checks\n', ci)
        self.assertIn('    name: Release build\n', release)
        self.assertNotIn('    name: CI checks\n', WORKFLOW.read_text())

    def test_clean_runners_use_real_platform_id_and_modern_sdk_tools(self):
        for path in (WORKFLOW, ROOT / '.github/workflows/ci.yml'):
            text = path.read_text()
            self.assertIn("'platforms;android-37.0'", text)
            self.assertNotIn("'platforms;android-37'", text)
            self.assertEqual(text.count('uses: android-actions/setup-android@'),
                             text.count("cmdline-tools-version: '15859902'"))
            self.assertEqual(text.count('uses: android-actions/setup-android@'),
                             text.count("packages: ''"))

    def test_unsigned_gradle_mode_is_explicit_and_local_signing_remains(self):
        source = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('gradleProperty("splitfreeUnsignedRelease")', source)
        self.assertIn('value == "true" || value == "false"', source)
        self.assertIn('getOrElse(false)', source)
        self.assertRegex(source, r'if \(!unsignedRelease\) \{\s+signingConfigs')
        self.assertIn('if (!unsignedRelease) signingConfig = signingConfigs.getByName("release")', source)


class InlineSignerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        tools = self.root / 'sdk/build-tools/36.1.0'
        tools.mkdir(parents=True)
        (self.root / 'input').mkdir()
        (self.root / 'input/unsigned.apk').write_bytes(b'disposable fake apk')
        self.env = dict(os.environ, RUNNER_TEMP=str(self.root), ANDROID_HOME=str(self.root / 'sdk'),
                        BUILD_TOOLS_VERSION='36.1.0', GITHUB_OUTPUT=str(self.root / 'output'),
                        RELEASE_KEYSTORE_BASE64=base64.b64encode(b'fake keystore').decode(),
                        RELEASE_STORE_PASSWORD='fake-store', RELEASE_KEY_ALIAS='fake-alias',
                        RELEASE_KEY_PASSWORD='fake-key', RELEASE_CERT_SHA256='a' * 64,
                        FAKE_CERT='a' * 64)
        scripts = {
            'zipalign': '#!/bin/bash\nexit "${FAIL_ALIGNMENT:-0}"\n',
            'apksigner': '''#!/bin/bash
set -euo pipefail
if [[ "$1" == sign ]]; then
  [[ "${FAIL_SIGNING:-0}" == 0 ]] || exit 1
  [[ -f "$KEYDIR/release.jks" ]]
  [[ -n "$RELEASE_STORE_PASSWORD" && -n "$RELEASE_KEY_PASSWORD" ]]
  [[ -z "${RELEASE_KEYSTORE_BASE64:-}" ]]
  cp "$RUNNER_TEMP/input/unsigned.apk" "$RUNNER_TEMP/signed.apk"
else
  [[ -z "${RELEASE_STORE_PASSWORD:-}" && -z "${RELEASE_KEY_PASSWORD:-}" ]]
  echo "Signer #1 certificate SHA-256 digest: $FAKE_CERT"
fi
''',
        }
        for name, text in scripts.items():
            p = tools / name
            p.write_text(text)
            p.chmod(0o700)

    def run_signer(self, **changes):
        env = dict(self.env, **changes)
        result = subprocess.run(['bash', '-eu', '-o', 'pipefail', '-c', signing_shell()],
                                env=env, text=True, capture_output=True)
        self.assertEqual(list(self.root.glob('splitfree-signing.*')), [], 'temporary key survived')
        self.assertNotIn('fake-store', result.stdout + result.stderr)
        self.assertNotIn('fake-key', result.stdout + result.stderr)
        return result

    def test_success_records_expected_identity_and_cleans_key(self):
        result = self.run_signer()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / 'output').read_text(), 'certificate=' + 'a' * 64 + '\n')
        self.assertEqual((self.root / 'signed.apk').read_bytes(), b'disposable fake apk')

    def test_missing_credentials_fail_before_output(self):
        for name in ('RELEASE_KEYSTORE_BASE64', 'RELEASE_STORE_PASSWORD', 'RELEASE_KEY_ALIAS',
                     'RELEASE_KEY_PASSWORD', 'RELEASE_CERT_SHA256'):
            with self.subTest(name=name):
                self.assertNotEqual(self.run_signer(**{name: ''}).returncode, 0)
        self.assertFalse((self.root / 'signed.apk').exists())

    def test_invalid_fingerprint_and_base64_fail_closed(self):
        for env in ({'RELEASE_CERT_SHA256': 'not-a-certificate'}, {'RELEASE_KEYSTORE_BASE64': '!!!'}):
            self.assertNotEqual(self.run_signer(**env).returncode, 0)

    def test_different_signer_is_refused(self):
        self.assertNotEqual(self.run_signer(FAKE_CERT='b' * 64).returncode, 0)
        self.assertFalse((self.root / 'output').exists())

    def test_signing_failure_still_removes_key(self):
        self.assertNotEqual(self.run_signer(FAIL_SIGNING='1').returncode, 0)

    def test_alignment_failure_stops_signing(self):
        self.assertNotEqual(self.run_signer(FAIL_ALIGNMENT='1').returncode, 0)
        self.assertFalse((self.root / 'signed.apk').exists())


if __name__ == '__main__':
    unittest.main()
