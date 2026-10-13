"""Verify signed tags and stage both release assets before making drafts public.

GitHub cannot atomically publish across repositories. A failed final publication
reports the remaining state; rerunning safely resumes the same verified release.
Published assets are immutable here and are never silently replaced.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import quote


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


class GitHubRepo:
    def __init__(self, repository, token):
        require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository), "Invalid repository")
        require(bool(token), f"Missing release credential for {repository}")
        self.repository = repository
        self.environment = dict(os.environ, GH_TOKEN=token)
        self.environment.pop("MIRROR_TOKEN", None)

    def command(self, *arguments, allow_missing=False):
        result = subprocess.run(["gh", *arguments], env=self.environment, capture_output=True, text=True, timeout=300)
        if allow_missing and result.returncode != 0 and "(HTTP 404)" in result.stderr:
            return None
        require(result.returncode == 0, f"GitHub operation failed for {self.repository}: {result.stderr.strip()}")
        return result.stdout

    def api(self, endpoint, allow_missing=False):
        output = self.command("api", f"repos/{self.repository}/{endpoint}", allow_missing=allow_missing)
        return None if output is None else json.loads(output)

    def verify_tag(self, tag, commit):
        reference = self.api("git/ref/tags/" + quote(tag, safe=""))
        require(reference["object"]["type"] == "tag", f"{self.repository}:{tag} must be an annotated tag")
        data = self.api("git/tags/" + reference["object"]["sha"])
        verification = data.get("verification", {})
        require(verification.get("verified") is True, f"{self.repository}:{tag} is not verified by GitHub")
        require((verification.get("signature") or "").startswith("-----BEGIN SSH SIGNATURE-----"), f"{self.repository}:{tag} must use an SSH signature")
        target = data.get("object", {})
        require(data.get("tag") == tag and target.get("type") == "commit" and target.get("sha") == commit, f"{self.repository}:{tag} points to a different source commit")

    def release(self, tag):
        # REST's tag endpoint only finds published releases. Drafts keep a pending
        # tag name and must be discovered separately; treating their 404 as absence
        # would recreate a draft or stop immediately after creating one.
        release = self.api("releases/tags/" + quote(tag, safe=""), allow_missing=True)
        if release is not None:
            self._check_release(release, tag)
            return release

        matches = []
        page = 1
        while True:
            records = self.api(f"releases?per_page=100&page={page}")
            require(isinstance(records, list), f"{self.repository} returned an invalid release list")
            for record in records:
                require(isinstance(record, dict), f"{self.repository} returned an invalid release record")
                if record.get("tag_name") == tag and record.get("draft") is True:
                    matches.append(record)
            if len(records) < 100:
                break
            page += 1
        require(len(matches) <= 1, f"{self.repository}:{tag} has multiple matching drafts; resolve the duplicate drafts before publishing")
        if not matches:
            return None
        release_id = matches[0].get("id")
        require(type(release_id) is int and release_id > 0, f"{self.repository}:{tag} draft ID is invalid")
        # Read the current assets by ID instead of trusting a possibly stale list.
        # A failure here is an API error, never permission to create another draft.
        release = self.api(f"releases/{release_id}")
        self._check_release(release, tag)
        return release

    def _check_release(self, release, tag):
        require(isinstance(release, dict) and release.get("tag_name") == tag
                and type(release.get("draft")) is bool,
                f"{self.repository}:{tag} returned an invalid or mismatched release")

    def create_draft(self, tag, title, notes):
        self.command("release", "create", tag, "--repo", self.repository, "--draft", "--verify-tag", "--latest=false", "--title", title, "--notes-file", str(notes))

    def update_draft(self, tag, title, notes):
        self.command("release", "edit", tag, "--repo", self.repository, "--title", title, "--notes-file", str(notes))

    def upload(self, tag, apk):
        self.command("release", "upload", tag, str(apk), "--repo", self.repository, "--clobber")

    def verify_asset(self, tag, apk, expected_digest):
        release = self.release(tag)
        matches = [asset for asset in release["assets"] if asset["name"] == apk.name]
        require(len(matches) == 1, f"{self.repository}:{tag} has no unique release APK")
        require(matches[0].get("digest") == "sha256:" + expected_digest, f"{self.repository}:{tag} asset digest does not match the verified build")
        with tempfile.TemporaryDirectory(prefix="txtoi-release-verify-") as temporary:
            self.command("release", "download", tag, "--repo", self.repository, "--pattern", apk.name, "--dir", temporary)
            require(sha256_file(Path(temporary) / apk.name) == expected_digest, f"{self.repository}:{tag} downloaded asset differs from the verified build")

    def make_public(self, tag):
        self.command("release", "edit", tag, "--repo", self.repository, "--draft=false", "--latest")


def publish_releases(repositories, commit, apk, expected_digest, notes):
    apk = Path(apk)
    require(re.fullmatch(r"[0-9a-f]{64}", expected_digest), "Invalid expected APK digest")
    require(sha256_file(apk) == expected_digest, "Downloaded CI artifact differs from the verified build")
    require(Path(notes).is_file() and Path(notes).read_text(encoding="utf-8").strip(), "Release notes are required")

    # Validate both tags before creating either draft or touching any asset.
    for repository, tag, _ in repositories:
        repository.verify_tag(tag, commit)
    try:
        for repository, tag, title in repositories:
            release = repository.release(tag)
            if release is None:
                repository.create_draft(tag, title, notes)
                release = repository.release(tag)
            require(release is not None, f"{repository.repository}:{tag} draft could not be confirmed")
            if not release["draft"]:
                # A rerun may resume after one repository was already published.
                # Never clobber a public APK, including a rebuild with different bytes.
                repository.verify_asset(tag, apk, expected_digest)
            else:
                repository.update_draft(tag, title, notes)
                repository.upload(tag, apk)

        # Both the API digest and a freshly downloaded copy must agree before public.
        for repository, tag, _ in repositories:
            repository.verify_asset(tag, apk, expected_digest)
            repository.verify_tag(tag, commit)
        for repository, tag, _ in repositories:
            if repository.release(tag)["draft"]:
                repository.make_public(tag)
        for repository, tag, _ in repositories:
            require(repository.release(tag)["draft"] is False, f"{repository.repository}:{tag} is still a draft")
            repository.verify_asset(tag, apk, expected_digest)
            repository.verify_tag(tag, commit)
    except Exception:
        print("Release publication stopped. Public assets were not replaced. Resolve the error and rerun the failed publish job with the same verified artifact.", file=sys.stderr)
        for repository, tag, _ in repositories:
            try:
                release = repository.release(tag)
                state = "missing" if release is None else ("draft" if release["draft"] else "public")
            except Exception:
                state = "unavailable"
            print(f"{repository.repository}:{tag}: {state}", file=sys.stderr)
        raise
    print("Both verified releases are public; their APK digests match the verified CI build.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    tag_parser = commands.add_parser("verify-tag")
    tag_parser.add_argument("--repo", required=True)
    tag_parser.add_argument("--tag", required=True)
    tag_parser.add_argument("--commit", required=True)
    publish = commands.add_parser("publish")
    for argument in ("repo", "mirror-repo", "tag", "mirror-tag", "commit", "version", "apk", "sha256", "notes"):
        publish.add_argument("--" + argument, required=True)
    args = parser.parse_args()
    require(re.fullmatch(r"[0-9a-f]{40}", args.commit), "Invalid source commit")
    main_repo = GitHubRepo(args.repo, os.environ.get("GH_TOKEN"))
    if args.command == "verify-tag":
        main_repo.verify_tag(args.tag, args.commit)
        print(f"{args.repo}:{args.tag}: GitHub-verified SSH tag matches the source commit")
        return
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", args.version), "Invalid release version")
    require(args.tag == "v" + args.version, "Main tag does not match the release version")
    require(re.fullmatch(r"[1-9][0-9]*-" + re.escape(args.version), args.mirror_tag), "Mirror tag does not match the release version")
    require(Path(args.apk).name == f"txtoi-{args.tag}.apk", "APK name does not match the release tag")
    mirror_repo = GitHubRepo(args.mirror_repo, os.environ.get("MIRROR_TOKEN"))
    publish_releases([(main_repo, args.tag, args.tag), (mirror_repo, args.mirror_tag, args.version)], args.commit, args.apk, args.sha256, args.notes)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print(f"Release publication stopped: {error}", file=sys.stderr)
        sys.exit(1)
