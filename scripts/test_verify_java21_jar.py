"""Exercise full-distribution bytecode validation on synthetic, read-only JARs."""

import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import warnings
from zipfile import ZipFile

from verify_java21_jar import MAIN_CLASS, verify

CHECKER = Path(__file__).with_name("verify_java21_jar.py")


def class_header(major, minor=0):
    return bytes.fromhex("cafebabe") + minor.to_bytes(2, "big") + major.to_bytes(2, "big")


class ShadedBytecodePolicyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.jar = Path(self.directory.name) / "candidate.jar"

    def package(self, *, main=class_header(65), extra=(), plugin=True):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with ZipFile(self.jar, "w") as jar:
                if plugin:
                    jar.writestr("plugin.yml", b"name: WorldEditSlimefun\n")
                if main is not None:
                    jar.writestr(MAIN_CLASS, main)
                for name, data in extra:
                    jar.writestr(name, data)

    def cli(self):
        return subprocess.run(
            [sys.executable, str(CHECKER), str(self.jar)],
            capture_output=True, text=True, check=False, timeout=10,
        )

    def test_allows_stable_java21_and_historical_shaded_classes(self):
        self.package(extra=(("shaded/Java8.class", class_header(52)),
                            ("shaded/Java1.class", class_header(45, 3))))
        original = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.assertEqual(3, verify(self.jar))
        self.assertEqual(0, self.cli().returncode)
        self.assertEqual(original, hashlib.sha256(self.jar.read_bytes()).hexdigest())

    def test_rejects_preview_in_shaded_or_main_class(self):
        for name in (MAIN_CLASS, "shaded/OldPreview.class", "shaded/Java21Preview.class"):
            with self.subTest(name=name):
                if name == MAIN_CLASS:
                    self.package(main=class_header(65, 65535))
                else:
                    major = 61 if name.endswith("OldPreview.class") else 65
                    self.package(extra=((name, class_header(major, 65535)),))
                with self.assertRaisesRegex(ValueError, "preview bytecode"):
                    verify(self.jar)
                self.assertNotEqual(0, self.cli().returncode)

    def test_rejects_nonpreview_invalid_modern_minors(self):
        for major, minor in ((56, 1), (61, 42), (65, 65534)):
            with self.subTest(major=major, minor=minor):
                self.package(extra=(("shaded/Invalid.class", class_header(major, minor)),))
                with self.assertRaisesRegex(ValueError, "invalid minor version"):
                    verify(self.jar)

    def test_rejects_too_new_or_below_minimum_class(self):
        for major in (44, 66, 69):
            with self.subTest(major=major):
                self.package(extra=(("shaded/Wrong.class", class_header(major)),))
                with self.assertRaisesRegex(ValueError, "outside Java 21 support"):
                    verify(self.jar)

    def test_rejects_invalid_magic_and_truncated_headers(self):
        for corrupt in (b"notclass", class_header(65)[:7]):
            with self.subTest(payload=corrupt):
                self.package(extra=(("shaded/Corrupt.class", corrupt),))
                with self.assertRaisesRegex(ValueError, "Invalid Java class header"):
                    verify(self.jar)

    def test_rejects_duplicate_zip_members(self):
        self.package(extra=((MAIN_CLASS, class_header(65)),))
        with self.assertRaisesRegex(ValueError, "Duplicate distribution ZIP entry"):
            verify(self.jar)

    def test_requires_canonical_main_class_and_plugin_manifest(self):
        self.package(main=None)
        with self.assertRaisesRegex(ValueError, "missing WorldEditSlimefun"):
            verify(self.jar)
        self.package(plugin=False)
        with self.assertRaisesRegex(ValueError, "missing WorldEditSlimefun"):
            verify(self.jar)

    def test_rejects_malformed_zip(self):
        self.jar.write_bytes(b"not zip")
        self.assertNotEqual(0, self.cli().returncode)


if __name__ == "__main__":
    unittest.main()
