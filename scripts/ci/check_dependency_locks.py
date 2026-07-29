#!/usr/bin/env python3
"""Validate committed dependency authorities without installing dependencies."""

from __future__ import annotations

import json
import re
import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PYPROJECT = ROOT / "executor-python" / "pyproject.toml"
PYTHON_LOCK = ROOT / "executor-python" / "requirements.lock"
PACKAGE_JSON = ROOT / "dashboard" / "package.json"
PACKAGE_LOCK = ROOT / "dashboard" / "package-lock.json"
MAVEN_WRAPPER = ROOT / ".mvn" / "wrapper" / "maven-wrapper.properties"
POM = ROOT / "pom.xml"

EXACT_REQUIREMENT = re.compile(
    r"^(?P<name>[A-Za-z0-9_.-]+)(?:\[[A-Za-z0-9_.-]+(?:,[A-Za-z0-9_.-]+)*\])?"
    r"==(?P<version>[^\s;]+)(?:\s*;\s*.+)?$"
)
EXACT_TOOL_VERSION = re.compile(r"^[A-Za-z0-9_.-]+@\d+\.\d+\.\d+$")
MAVEN_DISTRIBUTION = re.compile(
    r"^https://repo\.maven\.apache\.org/maven2/org/apache/maven/apache-maven/"
    r"(?P<version>\d+\.\d+\.\d+)/apache-maven-(?P=version)-bin\.zip$"
)


def canonical_name(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def parse_exact_requirement(spec: str, source: str, failures: list[str]) -> tuple[str, str] | None:
    match = EXACT_REQUIREMENT.fullmatch(spec.strip())
    if match is None:
        failures.append(f"{source}: dependency must use an exact == pin: {spec}")
        return None
    return canonical_name(match.group("name")), match.group("version")


def validate_python(failures: list[str]) -> None:
    project = tomllib.loads(PYPROJECT.read_text(encoding="utf-8"))["project"]
    declared: dict[str, str] = {}

    groups = {
        "project.dependencies": project.get("dependencies", []),
        "project.optional-dependencies.test": project.get("optional-dependencies", {}).get("test", []),
    }
    for group, requirements in groups.items():
        for spec in requirements:
            parsed = parse_exact_requirement(spec, f"executor-python/pyproject.toml {group}", failures)
            if parsed is None:
                continue
            name, version = parsed
            previous = declared.get(name)
            if previous is not None and previous != version:
                failures.append(f"executor-python/pyproject.toml: conflicting pins for {name}: {previous}, {version}")
            declared[name] = version

    locked: dict[str, str] = {}
    for line_number, raw_line in enumerate(PYTHON_LOCK.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        parsed = parse_exact_requirement(
            line,
            f"executor-python/requirements.lock:{line_number}",
            failures,
        )
        if parsed is None:
            continue
        name, version = parsed
        if name in locked:
            failures.append(f"executor-python/requirements.lock:{line_number}: duplicate package {name}")
        locked[name] = version

    for name, version in sorted(declared.items()):
        if locked.get(name) != version:
            failures.append(
                f"executor-python/requirements.lock: {name} must be locked at {version}, found {locked.get(name)!r}"
            )


def validate_angular(failures: list[str]) -> None:
    package = json.loads(PACKAGE_JSON.read_text(encoding="utf-8"))
    lock = json.loads(PACKAGE_LOCK.read_text(encoding="utf-8"))

    package_manager = package.get("packageManager", "")
    if not EXACT_TOOL_VERSION.fullmatch(package_manager) or not package_manager.startswith("npm@"):
        failures.append("dashboard/package.json: packageManager must be an exact npm@x.y.z version")

    if lock.get("lockfileVersion") != 3:
        failures.append("dashboard/package-lock.json: lockfileVersion must be 3")

    root_package = lock.get("packages", {}).get("", {})
    for key in ("dependencies", "devDependencies"):
        if package.get(key, {}) != root_package.get(key, {}):
            failures.append(f"dashboard/package-lock.json: root {key} does not match package.json")


def validate_maven(failures: list[str]) -> None:
    properties: dict[str, str] = {}
    for raw_line in MAVEN_WRAPPER.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()

    distribution_url = properties.get("distributionUrl", "")
    if MAVEN_DISTRIBUTION.fullmatch(distribution_url) is None:
        failures.append(".mvn/wrapper/maven-wrapper.properties: distributionUrl must pin an exact Maven release")

    root = ET.parse(POM).getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    parent_version = root.findtext("m:parent/m:version", namespaces=namespace)
    if not parent_version or any(token in parent_version.upper() for token in ("SNAPSHOT", "LATEST", "RELEASE")):
        failures.append("pom.xml: parent version must be an exact non-snapshot release")

    for dependency in root.findall(".//m:dependency", namespace):
        version = dependency.findtext("m:version", namespaces=namespace)
        if version is None:
            continue
        normalized = version.strip().upper()
        if any(token in normalized for token in ("SNAPSHOT", "LATEST", "RELEASE")) or any(
            token in version for token in ("[", "]", "(", ")", "+")
        ):
            artifact = dependency.findtext("m:artifactId", default="unknown", namespaces=namespace)
            failures.append(f"pom.xml: dependency {artifact} does not use a stable exact version/property")


def main() -> int:
    failures: list[str] = []
    validate_python(failures)
    validate_angular(failures)
    validate_maven(failures)

    if failures:
        print("Dependency lock checks failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("Dependency lock checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
