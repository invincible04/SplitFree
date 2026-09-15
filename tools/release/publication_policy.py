"""Fail-closed source publication policy, not a proof that arbitrary data is public.

Every rejection contains metadata only. Binary exceptions pin exact reviewed bytes;
text fixture exceptions pin individual matches, never whole test directories.
"""
from __future__ import annotations

import hashlib
from bisect import bisect_right
from array import array
import json
from pathlib import PurePosixPath
import re

MAX_SOURCE_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 128 * 1024 * 1024
MAX_FILES = 20_000
ROOT_FILES = {
    '.editorconfig', '.gitattributes', '.gitignore', 'gradlew', 'gradlew.bat',
    'gradle.properties', 'build.gradle.kts', 'settings.gradle.kts', 'LICENSE',
    'README.md', 'RELEASING.md', 'CONTRIBUTING.md', 'CODE_OF_CONDUCT.md',
    'PRIVACY.md', 'SECURITY.md', 'app/build.gradle.kts', 'app/proguard-rules.pro',
    'gradle/libs.versions.toml', 'gradle/wrapper/gradle-wrapper.properties',
    'gradle/wrapper/gradle-wrapper.jar', '.vscode/settings.json',
    'release/policy.json', 'release/signing-certificate.sha256',
    'release/THIRD-PARTY-NOTICES.txt',
    'app/src/test/resources/bip340-test-vectors.csv',
}
PRIVATE_SUFFIXES = ('.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.der',
                    '.db', '.sqlite', '.sqlite3', '.db-wal', '.db-shm', '.db-journal',
                    '.sqlite-wal', '.sqlite-shm', '.sqlite-journal', '.sqlite3-wal', '.sqlite3-shm',
                    '.sqlite3-journal', '.log', '.bak',
                    '.backup', '.zip', '.tar', '.gz', '.tgz', '.7z', '.gpg', '.enc', '.apk', '.aab',
                    '.dex', '.class', '.hprof', '.heapdump')
PRIVATE_NAMES = {'local.properties', 'key.properties', 'keystore.properties',
                 'signing.properties', 'credentials', 'credentials.json',
                 'google-services.json', 'service-account.json', 'service_account.json',
                 'id_rsa', 'id_ed25519', 'id_ecdsa', 'mapping.txt', 'local-release.json',
                 'signing-password.txt', 'backup-recovery-password.txt'}
GENERATED_DIRS = {'.git', '.gradle', '.kotlin', '.idea', '.cxx', '.externalnativebuild',
                  '__pycache__', 'build', 'bin', 'gen', 'out', 'node_modules',
                  'backups', 'backup', 'credentials', 'keystore'}

# These seven pre-existing binary inputs were inventoried locally. A changed image,
# font or wrapper needs a separate provenance/privacy review before updating a pin.
REVIEWED_BINARIES = {'app/src/main/res/drawable-nodpi/onboarding_backdrop.webp': '83e30de3ba24d01b9abe1322b10c57d6ffbb48ceec8f970b70b1809776ed8d8e',
 'app/src/main/res/font/inter_variable.ttf': '4989b125924991b90d05b2d16e0e388c48f7d5bb8b30539bbf9c755278d0ccaf',
 'assets/screenshots/add-expense.png': 'f1775fdbe379f349d7e38eba24ec2c8f71ce4d8dba2f61aba1a4f43b675cacad',
 'assets/screenshots/balances-dark.png': 'f70fd4ef0f02d90be1fabb95c61a27075e0dcfe1b12fce263bfec4a5b30f69f0',
 'assets/screenshots/groups.png': '67809bb97906549dfefb9fc516cb7c406b4eda61df2b9e0a8f0e6a5a2b5241f5',
 'assets/splitfree-logo.png': '71db91bc83b27495ea61269dd2c14e440afd852591ffc5759e5cf0a61f513b0e',
 'gradle/wrapper/gradle-wrapper.jar': '2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046'}

# (source path, rule, SHA-256 of matched text). No suppression comments or wildcards.
# Relay URL normalization uses fixed example userinfo. Signer tests use fake SDK
# passwords and intentionally invalid Base64. Only those exact matches are allowed.
REVIEWED_TEXT_MATCHES = {('app/src/test/java/com/splitfree/domain/util/RelayUrlTest.kt',
  'CredentialUrl',
  'e8a124bb1cbe5fa91b7c1e46bdef476f10438c0c03cb5b1076a603bffab2c4c0'),
 ('tools/release/tests/test_workflow.py',
  'CredentialLiteral',
  '4a3439657ad7a428032599742f9c49e1d6a9247098c3669beb603a64f1459006'),
 ('tools/release/tests/test_workflow.py',
  'CredentialLiteral',
  '7a78d09a59a013eeed9c97257d4867d90f8679d6fedaa24bad0b62e684366a07'),
 ('tools/release/tests/test_workflow.py',
  'CredentialLiteral',
  'c6a5c7fd66636ed76b0ed46d82e95541723424f9cdf6051dea5fd4bf8017db36')}


class PublicationError(ValueError):
    def __init__(self, findings):
        self.findings = list(findings)
        super().__init__(json.dumps(self.findings, sort_keys=True, ensure_ascii=True))


def reject(name, rule, line=0):
    raise PublicationError([{'path': name, 'line': line, 'rule': rule}])


def safe_source_path(name: str) -> None:
    if (not isinstance(name, str) or not name or len(name) > 1024
            or name.startswith('/') or '\\' in name or ':' in name
            or any(ord(c) < 32 or ord(c) == 127 for c in name)
            or any(p in {'', '.', '..'} for p in name.split('/'))):
        reject(name if isinstance(name, str) else '', 'UnsafeSourcePath')
    parts = PurePosixPath(name).parts
    for part in parts:
        lower = part.lower()
        compact = re.sub(r'[-_.]', '', lower)
        if (lower in PRIVATE_NAMES or lower.startswith('.env')
                or lower.endswith(PRIVATE_SUFFIXES)
                or 'privatekey' in compact and not lower.endswith(('.kt', '.java', '.py', '.md'))
                or lower.endswith('.properties') and name not in ROOT_FILES):
            reject(name, 'SensitiveSourcePath')
    if any(p.lower() in GENERATED_DIRS for p in parts[:-1]):
        reject(name, 'GeneratedSourcePath')
    suffix = PurePosixPath(name).suffix
    allowed = name in ROOT_FILES or name in REVIEWED_BINARIES
    if name.startswith('app/src/'):
        allowed |= suffix in {'.kt', '.java', '.xml', '.json', '.md', '.txt', '.svg'}
    if name.startswith('app/schemas/'):
        allowed |= bool(re.fullmatch(r'app/schemas/[A-Za-z0-9_.]+/[1-9][0-9]*\.json', name))
    if name.startswith('assets/'):
        allowed |= suffix in {'.md', '.svg'}
    if name.startswith('tools/release/'):
        allowed |= suffix in {'.py', '.md', '.sh'}
    if name.startswith('.github/workflows/'):
        allowed |= len(parts) == 3 and suffix in {'.yml', '.yaml'}
    if name.startswith('.github/ISSUE_TEMPLATE/'):
        allowed |= len(parts) == 3 and suffix in {'.md', '.yml', '.yaml'}
    if not allowed:
        reject(name, 'UnreviewedSourcePath')


# Deliberately recognizable formats and literal credential assignments. Dynamic
# lookups such as localProperties.getProperty(...) and secrets expressions are not
# literals. This does not attempt entropy-based proof or de-obfuscate arbitrary code.
CREDENTIAL_NAME = (r'(?:store[_-]?password|key[_-]?password|signing[_-]?password|'
                   r'password|passwd|api[_-]?key|access[_-]?token|auth[_-]?token|'
                   r'client[_-]?secret|aws[_-]?secret[_-]?access[_-]?key|'
                   r'release[_-]?(?:store[_-]?password|key[_-]?password|keystore[_-]?base64))')
RULES = (
    ('PrivateKeyHeader', re.compile(r'-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY-----')),
    ('AwsAccessKey', re.compile(r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b')),
    ('GitHubToken', re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{40,})\b')),
    ('SlackToken', re.compile(r'\bxox[baprs]-[A-Za-z0-9-]{20,}\b')),
    ('GoogleApiKey', re.compile(r'\bAIza[0-9A-Za-z_-]{35}\b')),
    ('ServiceAccount', re.compile(r'["\']type["\']\s*:\s*["\']service_account["\']', re.I)),
    ('CredentialLiteral', re.compile(
        r'(?<![A-Za-z0-9_])' + CREDENTIAL_NAME + r'["\']?\s*(?:=|:|\()\s*'
        r'(?P<quote>["\'])(?P<value>[^\r\n]*?)(?P=quote)', re.I)),
    ('CredentialUrl', re.compile(r'\b(?:https?|wss?)://[^\s/:@]+:[^\s/@]+@')),
)


NATIVE_ASSIGNMENT = re.compile(
    r'^[ \t]*["\']?' + CREDENTIAL_NAME + r'["\']?(?:[ \t]*[=:][ \t]*|[ \t]+)(?P<value>[^\r\n]+)',
    re.I | re.M,
)


def dynamic_reference(value):
    return bool(re.fullmatch(r'\$\{\{\s*(?:secrets|vars)\.[A-Z0-9_]+\s*}}', value))


def inspect_source(name: str, data: bytes) -> None:
    safe_source_path(name)
    if not isinstance(data, bytes) or len(data) > MAX_SOURCE_BYTES:
        reject(name, 'SourceSizeLimit')
    if name in REVIEWED_BINARIES:
        if hashlib.sha256(data).hexdigest() != REVIEWED_BINARIES[name]:
            reject(name, 'UnreviewedBinaryBytes')
        return
    try:
        text = data.decode('utf-8')
    except UnicodeDecodeError:
        reject(name, 'UnreviewedBinaryBytes')
    if any(ord(c) < 32 and c not in '\t\r\n' for c in text):
        reject(name, 'UnreviewedBinaryBytes')
    if text.startswith('version https://git-lfs.github.com/spec/v1\n'):
        reject(name, 'UnresolvedLfsPointer')
    findings = []
    newlines = array('I', (m.start() for m in re.finditer('\n', text)))
    for rule, pattern in RULES:
        for match in pattern.finditer(text):
            if len(findings) >= 1000:
                reject(name, 'SourceFindingLimit')
            if rule == 'CredentialLiteral':
                value = match['value']
                # Empty assignments and literal Actions references contain no secret.
                if not value or dynamic_reference(value):
                    continue
            key = (name, rule, hashlib.sha256(match[0].encode()).hexdigest())
            if key not in REVIEWED_TEXT_MATCHES:
                findings.append({'path': name, 'line': bisect_right(newlines, match.start()) + 1, 'rule': rule})
    # Properties and YAML use unquoted native values. YAML block scalars are also
    # literals: reject the credential-bearing header without printing its content.
    if name.endswith(('.properties', '.yml', '.yaml')):
        for match in NATIVE_ASSIGNMENT.finditer(text):
            if len(findings) >= 1000:
                reject(name, 'SourceFindingLimit')
            value = match['value'].strip()
            if dynamic_reference(value):
                continue
            if value.startswith(('"', "'")) and len(value) > 1 and value[0] in value[1:]:
                continue  # Completed quoted values were handled above.
            line = bisect_right(newlines, match.start()) + 1
            if not any(f['line'] == line and f['rule'] == 'CredentialLiteral' for f in findings):
                findings.append({'path': name, 'line': line, 'rule': 'CredentialLiteral'})
    if findings:
        raise PublicationError(findings)


def inspect_sources(files: dict[str, bytes]) -> None:
    if len(files) > MAX_FILES or sum(len(data) for data in files.values()) > MAX_TOTAL_BYTES:
        reject('', 'SourceTotalLimit')
    for name, data in files.items():
        inspect_source(name, data)
