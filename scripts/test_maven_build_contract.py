#!/usr/bin/env python3
"""Lock modern Maven plugins without changing WESF's Java 21 distribution."""
from pathlib import Path
import unittest
from xml.etree import ElementTree as ET

POM = Path(__file__).resolve().parents[1] / "pom.xml"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def check_build_contract(source: str) -> None:
    root = ET.fromstring(source)
    plugins = {}
    for plugin in root.findall("m:build/m:plugins/m:plugin", NS):
        group = plugin.findtext("m:groupId", namespaces=NS)
        artifact = plugin.findtext("m:artifactId", namespaces=NS)
        version = plugin.findtext("m:version", namespaces=NS)
        if group == "org.apache.maven.plugins":
            if artifact in plugins:
                raise ValueError(f"Duplicate Maven plugin: {artifact}")
            plugins[artifact] = version

    expected = {
        "maven-compiler-plugin": "3.16.0",
        "maven-shade-plugin": "3.6.2",
        "maven-surefire-plugin": "3.6.1",
    }
    for name, version in expected.items():
        if plugins.get(name) != version:
            raise ValueError(f"{name} must be pinned to {version}")

    if root.findtext("m:properties/m:java.version", namespaces=NS) != "21":
        raise ValueError("The distributable must remain Java 21")
    # Maven JAR and Shade use this fixed timestamp for reproducible ZIP entries.
    if root.findtext("m:properties/m:project.build.outputTimestamp", namespaces=NS) != "2026-10-29T00:00:00Z":
        raise ValueError("The canonical build must have a fixed checked-in output timestamp")
    shade = next(p for p in root.findall("m:build/m:plugins/m:plugin", NS)
                 if p.findtext("m:artifactId", namespaces=NS) == "maven-shade-plugin")
    # Shade's wildcard *:* also matches the project's own artifact and strips its
    # canonical manifest. Restrict exclusions to known bundled dependencies only.
    filters = shade.findall("m:configuration/m:filters/m:filter", NS)
    filtered_artifacts = [f.findtext("m:artifact", namespaces=NS) for f in filters]
    if filtered_artifacts != ["co.aikar:acf-paper", "org.bstats:bstats-base", "org.bstats:bstats-bukkit"]:
        raise ValueError("Shade must filter only the three known dependency manifests")
    for entry in filters:
        excluded = [e.text for e in entry.findall("m:excludes/m:exclude", NS)]
        if excluded != ["META-INF/MANIFEST.MF"]:
            raise ValueError("Shade exclusions must remove only duplicate dependency manifests")

    compiler = next(p for p in root.findall("m:build/m:plugins/m:plugin", NS)
                    if p.findtext("m:artifactId", namespaces=NS) == "maven-compiler-plugin")
    if compiler.findtext("m:configuration/m:release", namespaces=NS) != "${java.version}":
        raise ValueError("Compiler --release must use the pinned Java 21 property")
    if root.findtext("m:build/m:finalName", namespaces=NS) != "SF_SFWorldEdit${project.version}_(1.21.11-26.3)":
        raise ValueError("Canonical WESF output identity changed")

    for dependency in root.findall("m:dependencies/m:dependency", NS):
        if dependency.findtext("m:artifactId", namespaces=NS) == "junit-jupiter":
            if dependency.findtext("m:scope", namespaces=NS) != "test":
                raise ValueError("JUnit must remain test-only")
            break
    else:
        raise ValueError("JUnit verification dependency is missing")


class MavenBuildContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.source = POM.read_text(encoding="utf-8")

    def test_exact_current_compiler_shade_and_distribution_contract(self):
        check_build_contract(self.source)

    def test_compiler_downgrade_is_rejected(self):
        with self.assertRaises(ValueError):
            check_build_contract(self.source.replace(
                "<artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version>",
                "<artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version>"))

    def test_shade_downgrade_is_rejected(self):
        with self.assertRaises(ValueError):
            check_build_contract(self.source.replace(
                "<artifactId>maven-shade-plugin</artifactId><version>3.6.2</version>",
                "<artifactId>maven-shade-plugin</artifactId><version>3.6.0</version>"))

    def test_broad_filter_would_strip_the_project_manifest_and_is_rejected(self):
        with self.assertRaises(ValueError):
            check_build_contract(self.source.replace(
                "<artifact>co.aikar:acf-paper</artifact>", "<artifact>*:*</artifact>"))

    def test_required_dependency_manifest_filter_cannot_be_removed(self):
        with self.assertRaises(ValueError):
            check_build_contract(self.source.replace(
                "<artifact>org.bstats:bstats-bukkit</artifact>", "<artifact>org.bstats:other</artifact>"))

    def test_runtime_resources_cannot_be_excluded_with_duplicate_manifests(self):
        with self.assertRaises(ValueError):
            check_build_contract(self.source.replace(
                "<exclude>META-INF/MANIFEST.MF</exclude>",
                "<exclude>META-INF/MANIFEST.MF</exclude><exclude>plugin.yml</exclude>"))

    def test_shade_manifest_filter_cannot_be_deleted(self):
        with self.assertRaises(ValueError):
            check_build_contract(self.source.replace(
                "<filters>", "<removedFilters>").replace("</filters>", "</removedFilters>"))

    def test_reproducible_timestamp_cannot_be_removed(self):
        with self.assertRaises(ValueError):
            check_build_contract(self.source.replace(
                "<project.build.outputTimestamp>2026-10-29T00:00:00Z</project.build.outputTimestamp>", ""))

    def test_reproducible_timestamp_cannot_be_dynamic(self):
        with self.assertRaises(ValueError):
            check_build_contract(self.source.replace(
                "2026-10-29T00:00:00Z", "${maven.build.timestamp}"))

    def test_java_floor_is_rejected_if_changed(self):
        with self.assertRaises(ValueError):
            check_build_contract(self.source.replace(
                "<java.version>21</java.version>", "<java.version>25</java.version>"))

    def test_canonical_jar_name_is_preserved(self):
        with self.assertRaises(ValueError):
            check_build_contract(self.source.replace(
                "SF_SFWorldEdit${project.version}_(1.21.11-26.3)",
                "WorldEditSlimefun${project.version}"))

    def test_junit_cannot_become_a_runtime_dependency(self):
        with self.assertRaises(ValueError):
            check_build_contract(self.source.replace(
                "<version>5.12.2</version><scope>test</scope>",
                "<version>5.12.2</version><scope>compile</scope>"))


if __name__ == "__main__":
    unittest.main()
