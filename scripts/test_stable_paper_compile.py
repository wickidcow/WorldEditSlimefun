#!/usr/bin/env python3
"""Keep compatibility probes stable without changing the distributable API floor."""
import re
import unittest
from pathlib import Path

WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/java.yml'


def check_compile_contract(text):
    sections = dict(re.findall(
        r'^      - name: ([^\n]+)\n(.*?)(?=^      - |\Z)', text, re.MULTILINE | re.DOTALL))
    stable = sections.get('Verify Paper/Purpur 26.3 stable compilation', '')
    if not re.search(r'-Dpaper\.version=26\.3\.build\.[0-9]+-stable\s+clean package', stable):
        raise ValueError('26.3 compatibility must compile a pinned stable API')
    if re.search(r'26\.3\.build\.\S*(?:alpha|beta|rc)|-DskipTests|-Dmaven\.test\.skip', stable):
        raise ValueError('Prerelease API or skipped tests cannot satisfy stable validation')
    for step in ('Verify Paper/Purpur 1.21.11 compilation', 'Verify Paper/Purpur 26.2 compilation'):
        if not re.search(r'mvn --batch-mode.*clean package', sections.get(step, '')):
            raise ValueError('Existing supported baseline compile is missing')
    release = sections.get('Build universal release JAR from 1.21.11 baseline', '')
    if '-Dpaper.version=1.21.11-R0.1-SNAPSHOT clean package' not in release:
        raise ValueError('The distributable must remain rebuilt against 1.21.11')
    if text.index('Verify Paper/Purpur 26.3 stable compilation') >= text.index('Build universal release JAR'):
        raise ValueError('Stable API probe must precede the baseline release rebuild')
    if 'major version: 65' not in text or "api-version: '1.21.11'" not in text:
        raise ValueError('Java 21 and API floor checks must remain')


class StablePaperCompileTest(unittest.TestCase):
    def setUp(self):
        self.workflow = WORKFLOW.read_text(encoding='utf-8')

    def test_real_workflow_keeps_stable_probe_and_baseline_distribution(self):
        check_compile_contract(self.workflow)

    def test_prerelease_or_dynamic_versions_are_rejected(self):
        for replacement in ('26.3.build.49-alpha', '26.3.build.205-beta', '26.3.build.205-rc', '26.3.build.+'):
            with self.subTest(replacement=replacement), self.assertRaises(ValueError):
                check_compile_contract(self.workflow.replace('26.3.build.205-stable', replacement))

    def test_skipped_tests_are_rejected(self):
        for skip in ('-DskipTests', '-Dmaven.test.skip=true'):
            with self.subTest(skip=skip), self.assertRaises(ValueError):
                check_compile_contract(self.workflow.replace('26.3.build.205-stable clean', '26.3.build.205-stable ' + skip + ' clean'))

    def test_missing_baseline_build_is_rejected(self):
        with self.assertRaises(ValueError):
            check_compile_contract(self.workflow.replace('Verify Paper/Purpur 1.21.11 compilation', 'Removed baseline'))

    def test_newer_api_cannot_become_the_distributable(self):
        with self.assertRaises(ValueError):
            check_compile_contract(self.workflow.replace('-Dpaper.version=1.21.11-R0.1-SNAPSHOT clean package', '-Dpaper.version=26.3.build.205-stable clean package'))

    def test_existing_explicit_publication_gate_is_retained(self):
        self.assertIn("github.event_name == 'workflow_dispatch' && startsWith(github.ref, 'refs/tags/v') && inputs.publish_new_release", self.workflow)
        self.assertIn('EXPECTED_SOURCE_SHA: ${{ inputs.expected_source_sha }}', self.workflow)
        self.assertIn('bash scripts/publish_new_release.sh', self.workflow)
        self.assertNotIn('--clobber', self.workflow)


if __name__ == '__main__':
    unittest.main()
