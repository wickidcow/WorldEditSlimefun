#!/usr/bin/env python3
"""Check every packaged WESF class header without rewriting the distributable JAR."""

import argparse
from pathlib import Path
from zipfile import BadZipFile, ZipFile

MAX_MAJOR = 65  # Java 21
MAIN_CLASS = "dev/j3fftw/worldeditslimefun/WorldEditSlimefun.class"


def verify(jar_path: Path) -> int:
    """Count stable class headers or reject an unsafe/noncanonical distribution."""
    names = set()
    classes = 0
    with ZipFile(jar_path) as archive:
        for member in archive.infolist():
            if member.is_dir():
                continue
            name = member.filename
            if name in names:
                raise ValueError(f"Duplicate distribution ZIP entry: {name}")
            names.add(name)
            if not name.endswith(".class"):
                continue
            with archive.open(member) as stream:
                header = stream.read(8)
            if len(header) != 8 or header[:4] != bytes.fromhex("cafebabe"):
                raise ValueError(f"Invalid Java class header: {name}")
            minor = int.from_bytes(header[4:6], "big")
            major = int.from_bytes(header[6:8], "big")
            if major < 45 or major > MAX_MAJOR:
                raise ValueError(f"Class outside Java 21 support (major {major}): {name}")
            # JVMS 4.1: major >=56 minor 65535 marks release-specific
            # preview features; other nonzero modern minors are invalid.
            # Historical majors 45..55 legitimately permit nonzero minors.
            if major >= 56 and minor != 0:
                reason = "preview bytecode" if minor == 65535 else "invalid minor version"
                raise ValueError(f"Non-distributable {reason} {major}.{minor}: {name}")
            classes += 1
    if MAIN_CLASS not in names or "plugin.yml" not in names:
        raise ValueError("Distribution is missing WorldEditSlimefun main class or plugin.yml")
    if classes == 0:
        raise ValueError("No Java classes found")
    return classes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    args = parser.parse_args()
    try:
        classes = verify(args.jar)
    except (BadZipFile, ValueError, OSError) as exc:
        parser.exit(1, f"Java 21 distribution check failed: {exc}\n")
    print(f"Verified {classes} unique, stable Java 21-or-earlier class headers in raw JAR")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
