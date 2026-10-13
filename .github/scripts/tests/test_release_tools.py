"""Release safety regressions; these tests never access credentials or GitHub."""

import copy
from contextlib import redirect_stderr, redirect_stdout
import hashlib
import importlib.util
from io import StringIO
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import subprocess
import zipfile


SCRIPTS = Path(__file__).resolve().parents[1]


def load_script(name):
    specification = importlib.util.spec_from_file_location(name, SCRIPTS / (name + ".py"))
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


apk_check = load_script("verify_release_apk")
publication = load_script("publish_release")


class ApkIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.apk = self.directory / "txtoi-v0.7.0.apk"
        self.metadata = self.directory / "output-metadata.json"
        self.metadata.write_text(json.dumps({"applicationId": apk_check.APPLICATION_ID, "elements": [{"versionCode": 18, "versionName": "0.7.0"}]}), encoding="utf-8")
        self.signer = "Signer #1 certificate SHA-256 digest: " + apk_check.CERTIFICATE_SHA256 + "\n"
        self.badging = f"package: name='{apk_check.APPLICATION_ID}' versionCode='18' versionName='0.7.0' platformBuildVersionName='16'\n"
        self.make_apk()

    def make_apk(self, scope="system", entry=apk_check.ENTRY_POINT, properties="staticScope=true\n", legacy=False):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("META-INF/xposed/scope.list", scope)
            archive.writestr("META-INF/xposed/java_init.list", entry)
            archive.writestr("META-INF/xposed/module.prop", properties)
            if legacy:
                archive.writestr("assets/xposed_init", entry)

    def run_tool(self, arguments):
        return self.signer if arguments[1] == "verify" else self.badging

    def verify(self, tag="v0.7.0"):
        return apk_check.verify_apk(self.apk, self.metadata, tag, self.directory, runner=self.run_tool)

    def test_valid_release_identity_and_digest(self):
        result = self.verify()
        self.assertEqual(result["version_code"], 18)
        self.assertEqual(result["version_name"], "0.7.0")
        self.assertEqual(result["apk_sha256"], hashlib.sha256(self.apk.read_bytes()).hexdigest())

    def test_rejects_changed_signing_certificate(self):
        self.signer = self.signer.replace(apk_check.CERTIFICATE_SHA256, "0" * 64)
        with self.assertRaisesRegex(ValueError, "signing identity"):
            self.verify()

    def test_rejects_multiple_signers(self):
        self.signer += self.signer.replace("#1", "#2")
        with self.assertRaisesRegex(ValueError, "signing identity"):
            self.verify()

    def test_rejects_debuggable_manifest(self):
        self.badging += "application-debuggable\n"
        with self.assertRaisesRegex(ValueError, "debuggable"):
            self.verify()

    def test_rejects_actual_version_mismatch(self):
        self.badging = self.badging.replace("versionCode='18'", "versionCode='17'")
        with self.assertRaisesRegex(ValueError, "APK identity"):
            self.verify()

    def test_rejects_actual_package_mismatch(self):
        self.badging = self.badging.replace(apk_check.APPLICATION_ID, "example.debug")
        with self.assertRaisesRegex(ValueError, "APK identity"):
            self.verify()

    def test_rejects_wrong_tag_and_name(self):
        with self.assertRaisesRegex(ValueError, "tag does not match"):
            self.verify("v0.6.2")

    def test_rejects_multiple_apks(self):
        data = json.loads(self.metadata.read_text(encoding="utf-8"))
        data["elements"] *= 2
        self.metadata.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "one universal"):
            self.verify()

    def test_scope_entry_static_and_legacy_are_checked(self):
        cases = [
            ({"scope": "system\ncom.example.app"}, "only system"),
            ({"entry": "example.Missing"}, "entry point"),
            ({"properties": "# staticScope=true\nstaticScope=false\n"}, "staticScope=true"),
            ({"legacy": True}, "Legacy"),
        ]
        for options, expected_error in cases:
            with self.subTest(options=options):
                self.make_apk(**options)
                with self.assertRaisesRegex(ValueError, expected_error):
                    self.verify()


class TagVerificationTest(unittest.TestCase):
    def setUp(self):
        self.commit = "a" * 40
        self.reference = {"object": {"type": "tag", "sha": "b" * 40}}
        self.data = {"tag": "v0.7.0", "object": {"type": "commit", "sha": self.commit}, "verification": {"verified": True, "signature": "-----BEGIN SSH SIGNATURE-----\nverified-test"}}
        self.repo = publication.GitHubRepo("owner/repository", "test-placeholder")
        self.repo.api = lambda endpoint: self.reference if endpoint.startswith("git/ref/") else self.data

    def test_valid_ssh_annotated_tag(self):
        self.repo.verify_tag("v0.7.0", self.commit)

    def test_rejects_unsigned_lightweight_or_other_commit(self):
        changes = [
            ("reference", ("object", "type"), "commit"),
            ("data", ("verification", "verified"), False),
            ("data", ("verification", "signature"), "-----BEGIN PGP SIGNATURE-----"),
            ("data", ("object", "sha"), "c" * 40),
        ]
        for name, path, value in changes:
            original = copy.deepcopy(getattr(self, name))
            with self.subTest(path=path, value=value):
                getattr(self, name)[path[0]][path[1]] = value
                with self.assertRaises(ValueError):
                    self.repo.verify_tag("v0.7.0", self.commit)
            setattr(self, name, original)


class GitHubReleaseLookupTest(unittest.TestCase):
    """Model actual REST responses, including tag-404 for an existing draft."""

    def setUp(self):
        self.repo = publication.GitHubRepo("owner/repository", "test-placeholder")
        self.tag = "v0.7.0"
        self.calls = []
        self.responses = []

    def response(self, endpoint, value=None, error=None):
        self.responses.append((endpoint, value, error))

    def request(self, arguments, **options):
        self.calls.append(arguments)
        expected_endpoint, value, error = self.responses.pop(0)
        self.assertEqual(["gh", "api", "repos/owner/repository/" + expected_endpoint], arguments)
        self.assertTrue(options["capture_output"])
        if error:
            return subprocess.CompletedProcess(arguments, 1, stdout="", stderr=error)
        return subprocess.CompletedProcess(arguments, 0, stdout=json.dumps(value), stderr="")

    def lookup(self):
        with patch.object(publication.subprocess, "run", side_effect=self.request):
            return self.repo.release(self.tag)

    def published_missing(self):
        self.response("releases/tags/" + self.tag, error="gh: Not Found (HTTP 404)")

    def test_existing_published_release_uses_tag_lookup(self):
        published = {"id": 42, "tag_name": self.tag, "draft": False, "assets": []}
        self.response("releases/tags/" + self.tag, published)
        self.assertEqual(published, self.lookup())
        self.assertEqual(1, len(self.calls))
        self.assertEqual([], self.responses)

    def test_tag_404_finds_existing_draft_and_reads_its_assets_by_id(self):
        self.published_missing()
        listed = {"id": 42, "tag_name": self.tag, "draft": True, "assets": []}
        current = dict(listed, assets=[{"name": "txtoi-v0.7.0.apk", "digest": "sha256:" + "a" * 64}])
        self.response("releases?per_page=100&page=1", [listed])
        self.response("releases/42", current)
        self.assertEqual(current, self.lookup())
        self.assertEqual(3, len(self.calls))
        self.assertEqual([], self.responses)

    def test_draft_beyond_the_first_page_is_not_misclassified_as_missing(self):
        self.published_missing()
        earlier = [{"id": index + 1, "tag_name": "v0.1." + str(index), "draft": False} for index in range(100)]
        draft = {"id": 142, "tag_name": self.tag, "draft": True, "assets": []}
        self.response("releases?per_page=100&page=1", earlier)
        self.response("releases?per_page=100&page=2", [draft])
        self.response("releases/142", draft)
        self.assertEqual(draft, self.lookup())
        self.assertEqual([], self.responses)

    def test_absence_requires_a_successful_complete_release_list(self):
        self.published_missing()
        self.response("releases?per_page=100&page=1", [])
        self.assertIsNone(self.lookup())
        self.assertEqual(2, len(self.calls))
        self.assertEqual([], self.responses)

    def test_tag_permission_error_is_not_treated_as_absence(self):
        self.response("releases/tags/" + self.tag, error="gh: Resource not accessible by integration (HTTP 403)")
        with self.assertRaisesRegex(ValueError, "HTTP 403"):
            self.lookup()
        self.assertEqual(1, len(self.calls))

    def test_list_permission_or_network_failure_does_not_authorize_creation(self):
        for error in ["gh: Forbidden (HTTP 403)", "error connecting to api.github.com"]:
            with self.subTest(error=error):
                self.calls.clear()
                self.responses.clear()
                self.published_missing()
                self.response("releases?per_page=100&page=1", error=error)
                with self.assertRaisesRegex(ValueError, "GitHub operation failed"):
                    self.lookup()
                self.assertEqual(2, len(self.calls))

    def test_disappearing_draft_id_is_an_error_and_cannot_create_a_duplicate(self):
        self.published_missing()
        self.response("releases?per_page=100&page=1", [{"id": 42, "tag_name": self.tag, "draft": True}])
        self.response("releases/42", error="gh: Not Found (HTTP 404)")
        with self.assertRaisesRegex(ValueError, "GitHub operation failed"):
            self.lookup()
        self.assertEqual(3, len(self.calls))

    def test_multiple_drafts_across_pages_are_rejected_instead_of_picking_one(self):
        self.published_missing()
        first_page = [{"id": 1, "tag_name": self.tag, "draft": True}]
        first_page += [{"id": index + 2, "tag_name": "other-" + str(index), "draft": False} for index in range(99)]
        self.response("releases?per_page=100&page=1", first_page)
        self.response("releases?per_page=100&page=2", [{"id": 142, "tag_name": self.tag, "draft": True}])
        with self.assertRaisesRegex(ValueError, "multiple matching drafts"):
            self.lookup()
        self.assertEqual(3, len(self.calls))

    def test_malformed_list_is_a_failure_rather_than_absence(self):
        self.published_missing()
        self.response("releases?per_page=100&page=1", {"message": "unexpected object"})
        with self.assertRaisesRegex(ValueError, "invalid release list"):
            self.lookup()

    def test_draft_id_response_must_match_requested_tag(self):
        self.published_missing()
        self.response("releases?per_page=100&page=1", [{"id": 42, "tag_name": self.tag, "draft": True}])
        self.response("releases/42", {"id": 42, "tag_name": "different-tag", "draft": True})
        with self.assertRaisesRegex(ValueError, "mismatched release"):
            self.lookup()


class FakeRepo:
    def __init__(self, name, events):
        self.repository = name
        self.events = events
        self.record = None
        self.tag_valid = True
        self.asset_valid = True
        self.publication_fails = False

    def verify_tag(self, tag, commit):
        self.events.append((self.repository, "tag"))
        if not self.tag_valid:
            raise ValueError("unverified tag")

    def release(self, tag):
        return self.record

    def create_draft(self, tag, title, notes):
        self.events.append((self.repository, "draft"))
        self.record = {"draft": True, "assets": []}

    def update_draft(self, tag, title, notes):
        self.events.append((self.repository, "notes"))

    def upload(self, tag, apk):
        self.events.append((self.repository, "upload"))
        self.record["assets"] = [{"name": apk.name}]

    def verify_asset(self, tag, apk, expected_digest):
        self.events.append((self.repository, "verify"))
        if not self.asset_valid or not self.record["assets"]:
            raise ValueError("digest differs")

    def make_public(self, tag):
        self.events.append((self.repository, "publish"))
        if self.publication_fails:
            raise ValueError("temporary publication failure")
        self.record["draft"] = False


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        directory = Path(self.temporary.name)
        self.apk = directory / "txtoi-v0.7.0.apk"
        self.apk.write_bytes(b"verified CI artifact")
        self.digest = publication.sha256_file(self.apk)
        self.notes = directory / "notes.md"
        self.notes.write_text("Release improvements", encoding="utf-8")
        self.events = []
        self.main = FakeRepo("owner/main", self.events)
        self.mirror = FakeRepo("owner/mirror", self.events)
        self.repositories = [(self.main, "v0.7.0", "v0.7.0"), (self.mirror, "18-0.7.0", "0.7.0")]

    def publish(self):
        self.output = StringIO()
        self.error = StringIO()
        with redirect_stdout(self.output), redirect_stderr(self.error):
            publication.publish_releases(self.repositories, "a" * 40, self.apk, self.digest, self.notes)

    def test_both_assets_verified_before_either_is_public(self):
        self.publish()
        first_publication = self.events.index((self.main.repository, "publish"))
        preceding = self.events[:first_publication]
        self.assertIn((self.main.repository, "verify"), preceding)
        self.assertIn((self.mirror.repository, "verify"), preceding)
        self.assertEqual(self.events[:2], [(self.main.repository, "tag"), (self.mirror.repository, "tag")])
        self.assertFalse(self.main.record["draft"])
        self.assertFalse(self.mirror.record["draft"])

    def test_invalid_mirror_tag_does_not_create_either_release(self):
        self.mirror.tag_valid = False
        with self.assertRaisesRegex(ValueError, "unverified"):
            self.publish()
        self.assertIsNone(self.main.record)
        self.assertIsNone(self.mirror.record)

    def test_bad_asset_leaves_both_drafts_unpublished(self):
        self.mirror.asset_valid = False
        with self.assertRaisesRegex(ValueError, "digest"):
            self.publish()
        self.assertTrue(self.main.record["draft"])
        self.assertTrue(self.mirror.record["draft"])
        self.assertFalse(any(action == "publish" for _, action in self.events))

    def test_partial_publication_resumes_without_overwriting_public_asset(self):
        self.mirror.publication_fails = True
        with self.assertRaisesRegex(ValueError, "temporary"):
            self.publish()
        self.assertFalse(self.main.record["draft"])
        self.assertTrue(self.mirror.record["draft"])
        self.assertIn("owner/main:v0.7.0: public", self.error.getvalue())
        self.assertIn("owner/mirror:18-0.7.0: draft", self.error.getvalue())
        self.mirror.publication_fails = False
        self.events.clear()
        self.publish()
        self.assertNotIn((self.main.repository, "upload"), self.events)
        self.assertNotIn((self.main.repository, "publish"), self.events)
        self.assertFalse(self.mirror.record["draft"])

    def test_public_mismatched_asset_is_never_replaced(self):
        self.main.record = {"draft": False, "assets": [{"name": self.apk.name}]}
        self.main.asset_valid = False
        with self.assertRaisesRegex(ValueError, "digest"):
            self.publish()
        self.assertNotIn((self.main.repository, "upload"), self.events)
        self.assertIsNone(self.mirror.record)

    def test_modified_downloaded_ci_artifact_is_rejected_before_github(self):
        self.apk.write_bytes(b"tampered after verification")
        with self.assertRaisesRegex(ValueError, "CI artifact"):
            self.publish()
        self.assertEqual(self.events, [])


if __name__ == "__main__":
    unittest.main()
