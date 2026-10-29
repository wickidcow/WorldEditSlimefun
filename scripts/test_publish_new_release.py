"""Regression tests for an immutable, source-pinned WorldEditSlimefun release."""

import os
import pathlib
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("publish_new_release.sh")
SHA = "a" * 40


class PublishNewReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = pathlib.Path(self.temp.name)
        self.jar = root / "SF_SFWorldEdit1.0.7_1.21.11-26.3.jar"
        self.jar.write_bytes(b"nonempty jar fixture")
        self.log = root / "calls.log"
        fake = root / "gh"
        fake.write_text(
            "#!/usr/bin/env python3\n"
            "import os, sys\n"
            "with open(os.environ['GH_TEST_LOG'], 'a', encoding='utf-8') as out:\n"
            "    out.write(' '.join(sys.argv[1:]) + '\\n')\n"
            "if sys.argv[1:3] == ['release', 'view']:\n"
            "    sys.exit(0 if os.environ.get('GH_TEST_RELEASE_EXISTS') == 'true' else 1)\n"
            "if sys.argv[1:3] == ['release', 'create']:\n"
            "    sys.exit(0)\n"
            "sys.exit(2)\n",
            encoding="utf-8",
        )
        fake.chmod(0o755)
        self.env = os.environ.copy()
        self.env.update({
            "PATH": f"{root}{os.pathsep}{self.env.get('PATH', '')}",
            "GH_TEST_LOG": str(self.log),
            "GITHUB_REPOSITORY": "wickidcow/WorldEditSlimefun",
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "RELEASE_CONFIRMED": "true",
            "GITHUB_SHA": SHA,
            "EXPECTED_SOURCE_SHA": SHA,
            "GITHUB_REF": "refs/tags/v1.0.7",
            "GITHUB_REF_NAME": "v1.0.7",
            "VERSION": "1.0.7",
            "JAR": str(self.jar),
        })

    def run_script(self, **changes):
        env = {**self.env, **changes}
        result = subprocess.run(["bash", str(SCRIPT)], env=env, text=True, capture_output=True, check=False)
        calls = self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []
        return result, calls

    def test_only_fresh_authorized_tag_creates_release(self):
        result, calls = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("release view v1.0.7", calls[0])
        self.assertIn("release create v1.0.7", calls[1])
        self.assertIn("--verify-tag", calls[1])
        self.assertNotIn("--clobber", calls[1])
        self.assertNotIn("delete", "\n".join(calls))
        self.assertEqual(2, len(calls))

    def test_existing_release_is_immutable(self):
        result, calls = self.run_script(GH_TEST_RELEASE_EXISTS="true")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["release view v1.0.7"], calls)
        self.assertIn("already exists", result.stderr)

    def test_default_branch_or_push_cannot_publish(self):
        for event, ref in (("push", "refs/tags/v1.0.7"), ("workflow_dispatch", "refs/heads/main")):
            with self.subTest(event=event, ref=ref):
                result, calls = self.run_script(GITHUB_EVENT_NAME=event, GITHUB_REF=ref)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], calls)

    def test_confirmation_is_mandatory(self):
        result, calls = self.run_script(RELEASE_CONFIRMED="false")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)

    def test_expected_sha_must_match_exact_source(self):
        for authorized in ("b" * 40, "", "a" * 39):
            with self.subTest(authorized=authorized):
                result, calls = self.run_script(EXPECTED_SOURCE_SHA=authorized)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], calls)

    def test_bad_ref_name_does_not_publish(self):
        result, calls = self.run_script(GITHUB_REF_NAME="v1.0.6")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)

    def test_cannot_publish_missing_or_empty_jar(self):
        for payload in ("missing", "empty"):
            with self.subTest(payload=payload):
                jar = self.jar.with_suffix(".missing")
                if payload == "empty":
                    jar.write_bytes(b"")
                result, calls = self.run_script(JAR=str(jar))
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], calls)

    def test_wrong_repo_and_invalid_sha_are_rejected(self):
        for key, value in (("GITHUB_REPOSITORY", "someone/else"), ("GITHUB_SHA", "abc")):
            with self.subTest(key=key):
                result, calls = self.run_script(**{key: value})
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
