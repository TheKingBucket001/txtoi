"""Compute the production input identity shared by Gradle and release checks.

The file list and framing are also implemented by app/build.gradle.kts. Text
inputs normalize CRLF/CR to LF; signing configuration, QA and build outputs are
never inputs. This script itself is a required input so changing the source
identity definition changes the identity as well.
"""

import argparse
import hashlib
import json
from pathlib import Path


SCHEMA = 1
TEXT_EXTENSIONS = frozenset(("java", "kt", "kts", "xml", "pro", "properties",
                             "prop", "list", "txt", "json", "toml", "py"))
REQUIRED_INPUTS = (
    "app/build.gradle.kts",
    "app/proguard-rules.pro",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradle/verification-metadata.xml",
    "gradle/wrapper/gradle-wrapper.properties",
    "gradle/wrapper/gradle-wrapper.jar",
    ".github/scripts/source_identity.py",
)
OPTIONAL_INPUTS = ("gradle/libs.versions.toml",)


def production_inputs(source_root):
    root = Path(source_root)
    main = root / "app/src/main"
    if not main.is_dir():
        raise ValueError("Production source directory is missing")
    for relative in REQUIRED_INPUTS:
        if not (root / relative).is_file():
            raise ValueError("Required production input is missing: " + relative)
    files = {path for path in main.rglob("*") if path.is_file()}
    files.update(root / relative for relative in REQUIRED_INPUTS)
    files.update(root / relative for relative in OPTIONAL_INPUTS if (root / relative).is_file())
    return sorted(files, key=lambda path: path.relative_to(root).as_posix())


def build_identity(source_root, variant, r8_candidate):
    if variant not in ("debug", "release") or type(r8_candidate) is not bool:
        raise ValueError("Invalid build identity parameters")
    root = Path(source_root)
    actual_r8 = variant == "release" and r8_candidate
    digest = hashlib.sha256()
    digest.update(f"txtoi-probe-build-v1\n{variant}\nr8={str(actual_r8).lower()}\n".encode("utf-8"))
    for path in production_inputs(root):
        relative = path.relative_to(root).as_posix()
        content = path.read_bytes()
        if path.suffix.removeprefix(".") in TEXT_EXTENSIONS:
            content = content.decode("utf-8", errors="strict").replace("\r\n", "\n").replace("\r", "\n").encode("utf-8")
        digest.update(f"{relative}\n{len(content)}\n".encode("utf-8"))
        digest.update(content)
        digest.update(b"\0")
    return digest.hexdigest()


def identity_record(source_root, variant="release", r8_candidate=False):
    return {"schema": SCHEMA, "variant": variant, "r8Candidate": variant == "release" and r8_candidate,
            "buildId": build_identity(source_root, variant, r8_candidate)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", required=True)
    parser.add_argument("--variant", choices=("debug", "release"), required=True)
    parser.add_argument("--r8-candidate", choices=("true", "false"), required=True)
    args = parser.parse_args()
    print(json.dumps(identity_record(args.source_root, args.variant, args.r8_candidate == "true"), sort_keys=True))


if __name__ == "__main__":
    main()
