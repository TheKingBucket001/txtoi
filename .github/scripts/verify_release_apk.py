"""Check the actual signed APK before it can become a release artifact."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile


APPLICATION_ID = "io.github.selectionmenucontrol"
CERTIFICATE_SHA256 = "e02cb80dbf26663f4a85afb7bcd0b7a35e9bbaa255082e8d3436b5121f861d2d"
ENTRY_POINT = APPLICATION_ID + ".SelectionMenuModule"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run_tool(arguments):
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=120)
    require(result.returncode == 0, f"{Path(arguments[0]).name} verification failed: {result.stderr.strip()}")
    return result.stdout


def verify_apk(apk, metadata_path, tag, build_tools, runner=run_tool):
    apk = Path(apk)
    metadata = json.loads(Path(metadata_path).read_text(encoding="utf-8"))
    require(metadata.get("applicationId") == APPLICATION_ID, "Build metadata application ID is incorrect")
    elements = metadata.get("elements", [])
    require(len(elements) == 1, "Exactly one universal release APK is required")
    version_code = elements[0].get("versionCode")
    version_name = elements[0].get("versionName")
    require(isinstance(version_code, int) and version_code > 0, "Release version code is invalid")
    require(isinstance(version_name, str) and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version_name), "Release version name is invalid")
    require(tag == "v" + version_name, "Release tag does not match build version")
    require(apk.name == f"txtoi-{tag}.apk", "Release APK name does not match the tag")

    suffix = ".bat" if sys.platform == "win32" else ""
    signer = Path(build_tools) / ("apksigner" + suffix)
    aapt = Path(build_tools) / ("aapt.exe" if sys.platform == "win32" else "aapt")
    signature = runner([str(signer), "verify", "--verbose", "--print-certs", str(apk)])
    certificates = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", signature, re.MULTILINE)
    require([value.lower() for value in certificates] == [CERTIFICATE_SHA256], "Release APK certificate does not match the established signing identity")

    badging = runner([str(aapt), "dump", "badging", str(apk)])
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.MULTILINE)
    require(package is not None, "Cannot read APK package identity")
    require(package.groups() == (APPLICATION_ID, str(version_code), version_name), "APK identity does not match build metadata")
    require(not re.search(r"^application-debuggable(?:\s|$)", badging, re.MULTILINE), "A debuggable APK cannot be released")

    with zipfile.ZipFile(apk) as archive:
        require("assets/xposed_init" not in archive.namelist(), "Legacy Xposed entry is forbidden")
        scope = archive.read("META-INF/xposed/scope.list").decode("utf-8").strip()
        entry = archive.read("META-INF/xposed/java_init.list").decode("utf-8").strip()
        properties = archive.read("META-INF/xposed/module.prop").decode("utf-8")
        require(scope == "system", "Release static scope must contain only system")
        require(entry == ENTRY_POINT, "Release Modern Xposed entry point is incorrect")
        properties = dict(line.strip().split("=", 1) for line in properties.splitlines() if "=" in line and not line.lstrip().startswith(("#", "!")))
        require(properties.get("staticScope", "").strip() == "true", "Release must declare staticScope=true")

    return {"apk_name": apk.name, "apk_sha256": sha256_file(apk), "version_code": version_code, "version_name": version_name}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--metadata", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--build-tools", required=True)
    parser.add_argument("--github-output")
    args = parser.parse_args()
    verified = verify_apk(args.apk, args.metadata, args.tag, args.build_tools)
    if args.github_output:
        with Path(args.github_output).open("a", encoding="utf-8") as output:
            for key, value in verified.items():
                output.write(f"{key}={value}\n")
    print(json.dumps(verified, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"Release APK rejected: {error}", file=sys.stderr)
        sys.exit(1)
