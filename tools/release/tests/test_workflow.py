"""Security regression checks for this repository's release workflow and inline signer.

Actionlint separately validates YAML and Actions expressions. These tests execute the
actual inline signing shell against disposable fake SDK tools, never a real key.
"""
import ast
import base64
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / '.github/workflows/release.yml'
PAGES = ROOT / '.github/workflows/pages.yml'
sys.path.insert(0, str(ROOT / 'tools/release'))
import artifacts


def job(text, name):
    section = text.split(f'  {name}:\n', 1)[1]
    return re.split(r'^  [a-z]+:\n', section, maxsplit=1, flags=re.M)[0]


def signing_shell():
    section = job(WORKFLOW.read_text(), 'sign')
    return textwrap.dedent(section.split('        run: |\n', 1)[1].split('      - name: Cleanup', 1)[0])


class WorkflowBoundaryTests(unittest.TestCase):
    def test_current_source_checks_before_build_and_history_is_separate(self):
        for path in (WORKFLOW, ROOT / '.github/workflows/ci.yml'):
            text = path.read_text()
            for source in ('worktree', 'index'):
                check = text.index('publication_check.py --source ' + source)
                self.assertLess(check, text.index(':app:assembleDebug :app:assembleRelease'))
            self.assertNotIn('--source history', text)

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

    def test_compiled_variant_check_runs_after_both_builds(self):
        for path in (WORKFLOW, ROOT / '.github/workflows/ci.yml'):
            text = path.read_text()
            check = text.index('      - name: Verify compiled variant isolation')
            self.assertGreater(check, text.index(':app:assembleDebug :app:assembleRelease'))
            self.assertIn('SPLITFREE_VARIANT_BUILD_DIR: app/build', text[check:])
            self.assertIn('AAPT2="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/aapt2"', text[check:])
            self.assertIn('-p test_variants.py -v', text[check:])

    def test_workflows_install_and_use_the_build_tools_gradle_compiles_with(self):
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        versions = re.findall(r'^\s*buildToolsVersion\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"\s*$', gradle, re.M)
        self.assertEqual(len(versions), 1, 'app/build.gradle.kts must pin exactly one buildToolsVersion')
        for path in (WORKFLOW, ROOT / '.github/workflows/ci.yml'):
            text = path.read_text()
            self.assertEqual(re.findall(r"^\s*BUILD_TOOLS_VERSION: '([^']+)'", text, re.M), versions, path.name)
            self.assertNotRegex(text, r"build-tools[;/][0-9]", f'{path.name} hardcodes a build-tools version')
            self.assertIn('"build-tools;$BUILD_TOOLS_VERSION"', text)

    def test_unsigned_gradle_mode_is_explicit_and_local_signing_remains(self):
        source = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('gradleProperty("splitfreeUnsignedRelease")', source)
        self.assertIn('value == "true" || value == "false"', source)
        self.assertIn('getOrElse(false)', source)
        self.assertRegex(source, r'if \(!unsignedRelease\) \{\s+signingConfigs')
        self.assertIn('if (!unsignedRelease) signingConfig = signingConfigs.getByName("release")', source)


def website_condition(expression, event, ref, repository):
    """Evaluate only the comparison/boolean subset used by the actual Pages guards.

    This is an event-matrix regression test, not a GitHub Actions expression engine.
    Unknown syntax fails the test rather than silently assuming a safe condition.
    """
    context = {'event_name': event, 'ref': ref, 'repository': repository}
    tree = ast.parse(expression.replace('&&', ' and ').replace('||', ' or '), mode='eval')

    def evaluate(node):
        if isinstance(node, ast.Expression):
            return evaluate(node.body)
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            return node.value
        if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name):
            if node.value.id == 'github' and node.attr in context:
                return context[node.attr]
        if isinstance(node, ast.BoolOp) and isinstance(node.op, (ast.And, ast.Or)):
            values = [evaluate(value) for value in node.values]
            return all(values) if isinstance(node.op, ast.And) else any(values)
        if isinstance(node, ast.Compare) and len(node.ops) == len(node.comparators) == 1:
            if isinstance(node.ops[0], ast.Eq):
                return evaluate(node.left) == evaluate(node.comparators[0])
        raise AssertionError('Unsupported Pages guard syntax: ' + ast.dump(node))

    return evaluate(tree)


class WebsiteWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.text = PAGES.read_text()
        self.check = job(self.text, 'check')
        self.deploy = job(self.text, 'deploy')

    def test_push_and_every_mainline_pr_run_checks_without_path_skips(self):
        triggers = self.text.split('on:\n', 1)[1].split('\npermissions:', 1)[0]
        self.assertEqual(triggers.strip(),
                         'push:\n    branches: [mainline]\n  pull_request:\n'
                         '    branches: [mainline]\n  workflow_dispatch:')
        self.assertNotRegex(self.check.split('    steps:', 1)[0], r'(?m)^    if:')
        self.assertIn('    name: Build and check website\n', self.check)

    def test_actual_refresh_upload_and_deploy_guards_allow_only_mainline_publication(self):
        guards = re.findall(r'^        if: (.+)$', self.check, re.M)
        guards = [guard for guard in guards if guard != 'always()']
        guards += re.findall(r'^    if: (.+)$', self.deploy, re.M)
        self.assertEqual(len(guards), 4, 'refresh, archive, upload and deploy must each be gated')
        for guard in guards:
            for event in ('push', 'workflow_dispatch', 'pull_request', 'pull_request_target', 'release'):
                for ref in ('refs/heads/mainline', 'refs/heads/dev', 'refs/tags/v1.0.0', 'refs/pull/1/merge'):
                    for repository in ('invincible04/SplitFree', 'someone/SplitFree'):
                        expected = (event in ('push', 'workflow_dispatch')
                                    and ref == 'refs/heads/mainline' and repository == 'invincible04/SplitFree')
                        with self.subTest(guard=guard, event=event, ref=ref, repository=repository):
                            self.assertIs(website_condition(guard, event, ref, repository), expected)

    def test_failed_checks_cannot_upload_or_deploy(self):
        self.assertIn('    needs: check\n', self.deploy)
        self.assertNotIn('always()', self.deploy)
        self.assertNotIn('continue-on-error', self.text)
        self.assertNotIn('failure()', self.text)
        self.assertNotIn('cancelled()', self.text)
        for name in ('Refresh bundled release from the public GitHub API',
                     'Archive only the static website', 'Upload Pages archive'):
            step = self.check.split('      - name: ' + name + '\n', 1)[1].split('      - ', 1)[0]
            self.assertIn('        if: ', step)
            self.assertNotIn('always()', step)
        self.assertLess(self.check.index('npm run sync:release'), self.check.index('npm run check'))
        for gate in ('npm run check', 'publication_check.py --source worktree',
                     '-p test_website_policy.py', '-p test_workflow.py'):
            self.assertLess(self.check.index(gate), self.check.index('Archive only the static website'))

    def test_pr_code_has_no_signing_or_deployment_authority(self):
        top = self.text.split('jobs:', 1)[0]
        self.assertIn('permissions:\n  contents: read\n', top)
        self.assertNotIn(': write', top + self.check)
        self.assertNotIn('id-token:', top + self.check)
        self.assertNotIn('secrets.', self.text)
        self.assertNotIn('release-signing', self.text)
        self.assertNotIn('contents: write', self.deploy)
        self.assertIn('      pages: write\n      id-token: write\n', self.deploy)
        self.assertNotIn('pull_request_target', self.text)

    def test_only_static_output_is_uploaded(self):
        self.assertIn('tar --dereference --hard-dereference --directory dist -cf "$RUNNER_TEMP/artifact.tar" .', self.check)
        upload = self.check.split('      - name: Upload Pages archive\n', 1)[1]
        self.assertIn('          name: github-pages\n', upload)
        self.assertIn('          path: ${{ runner.temp }}/artifact.tar\n', upload)
        self.assertIn('          if-no-files-found: error\n', upload)
        self.assertIn('      name: github-pages\n', self.deploy)
        self.assertNotIn('actions/checkout@', self.deploy)

    def test_actions_are_pinned_and_deployments_are_serialized(self):
        for ref in re.findall(r'uses: (\S+)', self.text):
            self.assertRegex(ref, r'^[\w/-]+@[0-9a-f]{40}$')
        self.assertEqual(self.text.count('actions/checkout@'), self.text.count('persist-credentials: false'))
        self.assertIn('group: website-${{ github.ref }}\n  cancel-in-progress: false', self.text)
        self.assertIn('npm ci --ignore-scripts', self.text)
        self.assertIn('npx --no-install playwright install --with-deps chromium webkit', self.text)

    def test_android_release_remains_tag_only_and_draft_only(self):
        text = WORKFLOW.read_text()
        triggers = text.split('on:\n', 1)[1].split('\npermissions:', 1)[0]
        self.assertIn("tags: ['v*']", triggers)
        self.assertNotIn('branches:', triggers)
        self.assertIn("github.ref_type == 'tag'", job(text, 'build'))
        helper = (ROOT / 'tools/release/github_release.py').read_text()
        self.assertIn('"draft": True', helper)
        self.assertIn('"Published release must never be modified"', helper)

    def test_candidate_docs_match_source_without_requiring_release_approval(self):
        identity = artifacts.gradle_identity((ROOT / 'app/build.gradle.kts').read_text())
        self.assertIn('versionName = "' + identity['versionName'] + '"` and `versionCode = ' + identity['versionCode'],
                      (ROOT / 'RELEASING.md').read_text())
        self.assertIn('versionNameSuffix = "-debug"', (ROOT / 'app/build.gradle.kts').read_text())


    def test_release_guide_keeps_linked_acceptance_and_build_sections(self):
        guide = (ROOT / 'RELEASING.md').read_text()
        headings = re.findall(r'^#{1,6} (.+)$', guide, re.M)
        anchors = {re.sub(r'[^\w -]', '', heading.lower()).replace(' ', '-')
                   for heading in headings}
        references = re.findall(r'RELEASING\.md#([a-z0-9-]+)', '\n'.join(
            path.read_text() for path in (
                ROOT / 'README.md', ROOT / 'CONTRIBUTING.md',
                ROOT / 'tools/release/INSTALL_TESTING.md',
                ROOT / 'app/src/main/java/com/splitfree/sync/nearby/README.md')))
        self.assertTrue(references)
        self.assertTrue(set(references) <= anchors, set(references) - anchors)

    def test_release_guide_names_candidate_assets_and_preserves_published_releases(self):
        guide = (ROOT / 'RELEASING.md').read_text()
        version = artifacts.gradle_identity((ROOT / 'app/build.gradle.kts').read_text())['versionName']
        for suffix in ('.apk', '-source.tar.gz'):
            self.assertIn('SplitFree-v' + version + suffix, guide)
        self.assertIn('never move a published tag or overwrite its APK', guide)
        self.assertIn('**unpublished draft**', guide)
        self.assertNotIn('same-tag replacement', guide + (ROOT / 'website/README.md').read_text())
        self.assertIn('SPLITFREE_VARIANT_BUILD_DIR=app/build', guide)
        self.assertIn('-PsplitfreeUnsignedRelease=false', guide)


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
