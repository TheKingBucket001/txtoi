"""Verify externally loaded classes and exact public methods in an R8 APK's DEX."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile


PACKAGE = "io.github.selectionmenucontrol."
ENTRY_NAMES = ("SelectionMenuModule", "EnvironmentViewModel", "UpdateViewModel", "RuleEditorViewModel")
MODULE_DESCRIPTOR = "Lio/github/selectionmenucontrol/SelectionMenuModule;"
PUBLIC = 0x0001
PRIVATE = 0x0002
PROTECTED = 0x0004
STATIC = 0x0008
ABSTRACT = 0x0400
CONSTRUCTOR = 0x10000
MAX_DEX_BYTES = 256 * 1024 * 1024

REQUIRED_METHODS = {
    "SelectionMenuModule": (
        ("<init>", "()V"),
        ("onModuleLoaded", "(Lio/github/libxposed/api/XposedModuleInterface$ModuleLoadedParam;)V"),
        ("onSystemServerStarting", "(Lio/github/libxposed/api/XposedModuleInterface$SystemServerStartingParam;)V"),
    ),
    "EnvironmentViewModel": (("<init>", "(Landroid/app/Application;)V"),),
    "UpdateViewModel": (("<init>", "(Landroid/app/Application;)V"),),
    "RuleEditorViewModel": (("<init>", "()V"),),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def mapped_descriptors(mapping):
    """Read class mappings, never method lines or arbitrary occurrences of a name."""
    expected = {PACKAGE + name for name in ENTRY_NAMES}
    mapped = {}
    for line in mapping.splitlines():
        match = re.fullmatch(r"(\S+) -> (\S+):", line)
        if not match or match[1] not in expected:
            continue
        original, renamed = match.groups()
        require(original not in mapped, f"Duplicate class mapping for {original}")
        require(re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*", renamed),
                f"Invalid mapped class name for {original}")
        mapped[original] = "L" + renamed.replace(".", "/") + ";"
    require(mapped.keys() == expected, "Mapping is missing a required entry class")
    require(len(set(mapped.values())) == len(expected), "Required entry classes share a mapped descriptor")
    require(mapped[PACKAGE + "SelectionMenuModule"] == MODULE_DESCRIPTOR,
            "Modern module class must retain its literal name")
    return {name: mapped[PACKAGE + name] for name in ENTRY_NAMES}


def parse_dexdump(output):
    """Parse class and method records, keeping fields and neighboring classes separate."""
    blocks = re.split(r"(?m)^Class #[0-9]+\s+-\s*$", output)
    require(len(blocks) > 1, "dexdump did not return class records")
    classes = {}
    for block in blocks[1:]:
        descriptor = re.search(r"(?m)^  Class descriptor\s+: '([^']+)'\s*$", block)
        flags = re.search(r"(?m)^  Access flags\s+: 0x([0-9a-fA-F]+)(?:\s+\([^\r\n]*\))?\s*$", block)
        require(descriptor is not None and flags is not None, "Malformed dexdump class record")
        name = descriptor[1]
        require(name.startswith("L") and name.endswith(";") and "." not in name,
                "Invalid DEX class descriptor")
        require(name not in classes, f"Duplicate DEX class {name}")
        methods = {}
        sections = re.finditer(r"(?ms)^  (?:Direct|Virtual) methods\s+-\s*\n(.*?)(?=^  (?:Direct|Virtual) methods\s+-|^  source_file_idx|\Z)", block)
        for section in sections:
            records = re.split(r"(?m)^    #[0-9]+\s+: \(in [^\r\n]+\)\s*$", section[1])
            for record in records[1:]:
                method_name = re.search(r"(?m)^      name\s+: '([^']+)'\s*$", record)
                signature = re.search(r"(?m)^      type\s+: '([^']+)'\s*$", record)
                access = re.search(r"(?m)^      access\s+: 0x([0-9a-fA-F]+)(?:\s+\([^\r\n]*\))?\s*$", record)
                require(method_name is not None and signature is not None and access is not None,
                        f"Malformed dexdump method record in {name}")
                key = (method_name[1], signature[1])
                require(key not in methods, f"Duplicate DEX method in {name}: {key}")
                methods[key] = int(access[1], 16)
        classes[name] = {"access": int(flags[1], 16), "methods": methods}
    return classes


def run_dexdump(arguments):
    result = subprocess.run(arguments, capture_output=True, text=True, encoding="utf-8",
                            errors="strict", timeout=120)
    require(result.returncode == 0, f"dexdump failed: {result.stderr.strip()}")
    return result.stdout


def verify_entrypoints(apk, mapping_path, dexdump, runner=run_dexdump):
    apk, mapping_path = Path(apk), Path(mapping_path)
    descriptors = mapped_descriptors(mapping_path.read_text(encoding="utf-8"))
    classes = {}
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(prefix="txtoi-r8-check-") as temporary:
        entries = {}
        total_size = 0
        for member in archive.infolist():
            if "/" in member.filename or not member.filename.endswith(".dex"):
                continue
            match = re.fullmatch(r"classes(?:([2-9]|[1-9][0-9]+))?\.dex", member.filename)
            require(match is not None, "APK contains a nonstandard root DEX name")
            index = int(match[1]) if match[1] else 1
            require(index not in entries, "APK contains duplicate DEX entries")
            total_size += member.file_size
            require(total_size <= MAX_DEX_BYTES, "APK DEX data exceeds the verification limit")
            entries[index] = member
        require(entries and sorted(entries) == list(range(1, len(entries) + 1)),
                "APK must contain a contiguous classes.dex sequence")
        for index in sorted(entries):
            # Use only our numeric filename; never extract an archive-provided path.
            path = Path(temporary) / f"classes-{index}.dex"
            path.write_bytes(archive.read(entries[index]))
            parsed = parse_dexdump(runner([str(dexdump), str(path)]))
            require(not classes.keys() & parsed.keys(), "Duplicate class across APK DEX files")
            classes.update(parsed)
        dex_files = [entries[index].filename for index in sorted(entries)]
    verified = {}
    for name, descriptor in descriptors.items():
        require(descriptor in classes, f"Missing DEX entry class {name}: {descriptor}")
        entry = classes[descriptor]
        require(entry["access"] & PUBLIC and not entry["access"] & (PRIVATE | PROTECTED | ABSTRACT),
                f"Entry class {name} must be public and concrete")
        checked_methods = []
        for method, signature in REQUIRED_METHODS[name]:
            flags = entry["methods"].get((method, signature))
            require(flags is not None, f"Missing exact method {name}.{method}{signature}")
            require(flags & PUBLIC and not flags & (PRIVATE | PROTECTED | STATIC | ABSTRACT),
                    f"Required method {name}.{method}{signature} must be public and instantiable")
            require(method != "<init>" or flags & CONSTRUCTOR,
                    f"Required constructor {name}{signature} lacks its DEX constructor flag")
            checked_methods.append({"name": method, "descriptor": signature})
        verified[name] = {"descriptor": descriptor, "methods": checked_methods}
    return {"apk_sha256": sha256_file(apk), "mapping_sha256": sha256_file(mapping_path),
            "dex_files": dex_files, "entrypoints": verified}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--mapping", required=True)
    parser.add_argument("--dexdump", required=True, help="Android SDK Build Tools dexdump executable")
    args = parser.parse_args()
    print(json.dumps(verify_entrypoints(args.apk, args.mapping, args.dexdump), ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, UnicodeError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"R8 APK rejected: {error}", file=sys.stderr)
        sys.exit(1)
