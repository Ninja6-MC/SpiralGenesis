import argparse
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_promotion


class RetryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.previous = Path.cwd()
        import os
        os.chdir(self.root)
        self.addCleanup(os.chdir, self.previous)
        self.jar = b"candidate bytes"
        self.jar_name = "SpiralGenesis-1.2.3-beta.1.jar"
        self.checksum_name = self.jar_name + ".sha256"
        self.jar_hash = __import__("hashlib").sha256(self.jar).hexdigest()
        self.checksum = f"{self.jar_hash}  {self.jar_name}\n".encode()
        checksum_hash = __import__("hashlib").sha256(self.checksum).hexdigest()
        (self.root / "candidate-manifest.json").write_text(json.dumps({
            "source": "a" * 40, "files": {self.jar_name: self.jar_hash, self.checksum_name: checksum_hash}
        }))
        self.args = argparse.Namespace(tag="v1.2.3-beta.1", repository="Ninja6-MC/SpiralGenesis",
                                       output=str(self.root / "outputs"), modrinth_absence_confirmed=True,
                                       hangar_absence_confirmed=True)

    def modrinth(self, status="listed"):
        return {"id": "IIJJKKLL", "version_number": "1.2.3-beta.1", "version_type": "beta", "status": status,
                "files": [{"filename": self.jar_name, "url": "https://cdn.modrinth.com/data/project/version/file.jar",
                           "primary": True, "size": len(self.jar),
                           "hashes": {"sha1": hashlib.sha1(self.jar).hexdigest(),
                                      "sha512": hashlib.sha512(self.jar).hexdigest()}}]}

    def hangar(self, visibility="public"):
        return {"name": "1.2.3-beta.1", "visibility": visibility, "channel": {"name": "Beta"},
                "downloads": {"PAPER": {"fileInfo": {"name": self.jar_name, "sha256Hash": self.jar_hash},
                                          "downloadUrl": "https://hangarcdn.papermc.io/plugins/project/version/file.jar"}}}

    def reconcile(self, modrinth=None, hangar=None, data=None):
        not_found = subprocess.CalledProcessError(1, ["gh"], stderr="HTTP 404")
        with patch.object(release_promotion, "recheck"), patch.object(release_promotion, "api", side_effect=not_found), \
             patch.object(release_promotion, "modrinth_versions", return_value=modrinth or []), \
             patch.object(release_promotion, "hangar_version", return_value=hangar), \
             patch.object(release_promotion, "registry_json", side_effect=lambda url: hangar if "hangar" in url else (modrinth or [None])[0]), \
             patch.object(release_promotion.urllib.request, "urlopen", side_effect=lambda *a, **k: io.BytesIO(self.jar if data is None else data)):
            release_promotion.destinations(self.args)

    def fake_command(self, *args, **kwargs):
        if args[:3] == ("gh", "release", "download"):
            Path(args[-1]).write_bytes(self.checksum if args[-3] == self.checksum_name else self.jar)
        return ""

    def test_retry_skips_matching_github_and_resumes_absent_registries(self):
        release = {"target_commitish": "a" * 40, "prerelease": True,
                   "assets": [{"name": self.jar_name}, {"name": self.checksum_name}]}
        with patch.object(release_promotion, "recheck"), patch.object(release_promotion, "api", return_value=release), \
             patch.object(release_promotion, "modrinth_versions", return_value=[]), \
             patch.object(release_promotion, "hangar_version", return_value=None), \
             patch.object(release_promotion, "command", side_effect=self.fake_command):
            release_promotion.destinations(self.args)
        output = (self.root / "outputs").read_text()
        self.assertIn("github=complete", output)
        self.assertIn("modrinth=absent", output)
        self.assertIn("hangar=absent", output)

    def test_retry_stops_on_conflicting_github_asset(self):
        release = {"target_commitish": "a" * 40, "prerelease": True,
                   "assets": [{"name": "different.jar"}]}
        with patch.object(release_promotion, "recheck"), patch.object(release_promotion, "api", return_value=release):
            with self.assertRaisesRegex(ValueError, "asset list"):
                release_promotion.destinations(self.args)

    def test_retry_stops_on_conflicting_modrinth_digest(self):
        with self.assertRaisesRegex(ValueError, "Modrinth consumer JAR"):
            self.reconcile([self.modrinth()], data=b"wrong bytes")
        self.assertFalse(Path(self.args.output).exists())

    def test_retry_after_modrinth_success_resumes_hangar(self):
        self.reconcile([self.modrinth()])
        output = Path(self.args.output).read_text()
        self.assertIn("modrinth=complete", output)
        self.assertIn("hangar=absent", output)

    def test_hangar_absence_requires_explicit_confirmation(self):
        for confirmed in (False, None):
            with self.subTest(confirmed=confirmed):
                if confirmed is None:
                    del self.args.hangar_absence_confirmed
                else:
                    self.args.hangar_absence_confirmed = confirmed
                with self.assertRaisesRegex(ValueError, "absence is uncertain"):
                    self.reconcile([self.modrinth()])
                self.assertFalse(Path(self.args.output).exists())

    def test_modrinth_absence_requires_explicit_confirmation(self):
        for confirmed in (False, None):
            with self.subTest(confirmed=confirmed):
                if confirmed is None:
                    del self.args.modrinth_absence_confirmed
                else:
                    self.args.modrinth_absence_confirmed = confirmed
                with self.assertRaisesRegex(ValueError, "absence is uncertain"):
                    self.reconcile()
                self.assertFalse(Path(self.args.output).exists())

    def test_retry_matches_both_registry_downloads(self):
        self.reconcile([self.modrinth()], self.hangar())
        self.assertIn("hangar=complete", Path(self.args.output).read_text())

    def test_draft_and_hidden_versions_stop_without_retrying_upload(self):
        cases = [([self.modrinth(status)], None) for status in ("draft", "scheduled", "unlisted", "archived", "unknown")]
        cases.append(([], self.hangar("hidden")))
        for modrinth, hangar in cases:
            with self.subTest(modrinth=bool(modrinth)), self.assertRaisesRegex(ValueError, "nonpublic"):
                self.reconcile(modrinth, hangar)
            self.assertFalse(Path(self.args.output).exists())

    def test_anonymous_consumer_visibility_failure_stops(self):
        not_found = subprocess.CalledProcessError(1, ["gh"], stderr="HTTP 404")
        for modrinth, hangar in (([self.modrinth()], None), ([], self.hangar())):
            with self.subTest(modrinth=bool(modrinth)), \
                 patch.object(release_promotion, "recheck"), patch.object(release_promotion, "api", side_effect=not_found), \
                 patch.object(release_promotion, "modrinth_versions", return_value=modrinth), \
                 patch.object(release_promotion, "hangar_version", return_value=hangar), \
                 patch.object(release_promotion, "consumer_digest", return_value=self.jar_hash), \
                 patch.object(release_promotion, "registry_json", return_value={"status": "draft", "visibility": "hidden"}):
                with self.assertRaisesRegex(ValueError, "not publicly available"):
                    release_promotion.destinations(self.args)
                self.assertFalse(Path(self.args.output).exists())

    def test_modrinth_ambiguous_versions_stop(self):
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            self.reconcile([self.modrinth(), self.modrinth()])

    def test_untrusted_download_stops(self):
        remote = self.modrinth()
        remote["files"][0]["url"] = "https://example.com/file.jar"
        with self.assertRaisesRegex(ValueError, "trusted"):
            self.reconcile([remote])

    def test_retry_stops_on_conflicting_hangar_digest(self):
        not_found = subprocess.CalledProcessError(1, ["gh"], stderr="HTTP 404")
        remote = {"name": "1.2.3-beta.1", "channel": {"name": "Beta"},
                  "downloads": {"PAPER": {"fileInfo": {"name": self.jar_name,
                                                        "sha256Hash": "0" * 64}}}}
        with patch.object(release_promotion, "recheck"), patch.object(release_promotion, "api", side_effect=not_found), \
             patch.object(release_promotion, "modrinth_versions", return_value=[]), \
             patch.object(release_promotion, "hangar_version", return_value=remote):
            with self.assertRaisesRegex(ValueError, "Hangar"):
                release_promotion.destinations(self.args)

    def test_hangar_download_conflict_stops(self):
        with self.assertRaisesRegex(ValueError, "Hangar consumer JAR"):
            self.reconcile(hangar=self.hangar(), data=b"wrong bytes")


class RegistryAccessTests(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {"MODRINTH_TOKEN": "modrinth-secret", "HANGAR_API_TOKEN": "hangar-secret"})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.jar_path = Path(self.temp.name) / "candidate.jar"
        self.jar_path.write_bytes(b"candidate bytes")

    def modrinth_responses(self, versions=None, hidden=None):
        return [{"id": "projectid"}, versions or [], hidden]

    def request_responses(self, responses):
        def respond(request, **kwargs):
            value = responses.pop(0)
            if isinstance(value, Exception):
                raise value
            return io.BytesIO(json.dumps(value).encode())
        return patch.object(release_promotion.urllib.request, "urlopen", side_effect=respond)

    def test_modrinth_auth_and_full_draft_inventory(self):
        hidden = {"id": "draftid", "project_id": "projectid", "status": "draft", "version_number": "1.2.3"}
        with self.request_responses(self.modrinth_responses(hidden=hidden)) as opened:
            self.assertEqual(release_promotion.modrinth_versions("1.2.3", self.jar_path), [hidden])
        for call in opened.call_args_list:
            self.assertEqual(call.args[0].get_header("Authorization"), "modrinth-secret")

    def test_modrinth_wrong_project_hash_is_ignored(self):
        hidden = {"id": "otherid", "project_id": "otherproject", "version_number": "1.2.3"}
        with self.request_responses(self.modrinth_responses(hidden=hidden)):
            self.assertEqual(release_promotion.modrinth_versions("1.2.3", self.jar_path), [])

    def test_modrinth_unknown_project_stops_before_inventory(self):
        with self.request_responses([{}]):
            with self.assertRaisesRegex(ValueError, "identity cannot"):
                release_promotion.modrinth_versions("1.2.3", self.jar_path)

    def test_modrinth_invalid_token_is_not_anonymous_success(self):
        error = urllib.error.HTTPError("https://api.modrinth.com/v2/project/spiralgenesis", 401, "Failed", {}, None)
        with self.request_responses([error]):
            with self.assertRaisesRegex(ValueError, "HTTP 401"):
                release_promotion.modrinth_versions("1.2.3", self.jar_path)

    def test_modrinth_scope_error_stops(self):
        error = urllib.error.HTTPError("https://api.modrinth.com/v2/project/spiralgenesis/version", 403, "Insufficient scope", {}, None)
        with self.request_responses([{"id": "projectid"}, error]):
            with self.assertRaisesRegex(ValueError, "HTTP 403"):
                release_promotion.modrinth_versions("1.2.3", self.jar_path)

    def test_hangar_hidden_lookup_uses_session_and_checks_membership(self):
        hidden = {"visibility": "hidden"}
        with self.request_responses([{"token": "session-secret"}, {"permissions": ["is_subject_member"]}, hidden]) as opened:
            self.assertEqual(release_promotion.hangar_version("1.2.3"), hidden)
        requests = [call.args[0] for call in opened.call_args_list]
        self.assertEqual(requests[0].get_method(), "POST")
        self.assertIn("apiKey=hangar-secret", requests[0].full_url)
        for request in requests[1:]:
            self.assertEqual(request.get_header("Authorization"), "HangarAuth session-secret")

    def test_hangar_no_member_access_stops_before_lookup(self):
        with self.request_responses([{"token": "session-secret"}, {"permissions": []}]) as opened:
            with self.assertRaisesRegex(ValueError, "visibility cannot"):
                release_promotion.hangar_version("1.2.3")
            self.assertEqual(opened.call_count, 2)

    def test_authenticated_hangar_missing_version(self):
        missing = urllib.error.HTTPError("https://hangar.papermc.io/api/v1/projects/SpiralGenesis/versions/new", 404, "Not found", {}, None)
        with self.request_responses([{"token": "session-secret"}, {"permissions": ["is_subject_member"]}, missing]):
            self.assertIsNone(release_promotion.hangar_version("new"))

    def test_auth_http_and_network_failures_are_sanitized(self):
        for error in (urllib.error.HTTPError("https://hangar.papermc.io/authenticate?apiKey=hangar-secret", 401, "hangar-secret", {}, None),
                      urllib.error.URLError("hangar-secret")):
            with self.subTest(error=type(error).__name__), self.request_responses([error]):
                with self.assertRaises(ValueError) as caught:
                    release_promotion.hangar_version("1.2.3")
                self.assertNotIn("hangar-secret", str(caught.exception))

    def test_lookup_auth_failure_is_never_absent(self):
        for code in (401, 403, 500):
            error = urllib.error.HTTPError("https://hangar.papermc.io/version", code, "Failed", {}, None)
            with self.subTest(code=code), self.request_responses([{"token": "session-secret"}, {"permissions": ["is_subject_member"]}, error]):
                with self.assertRaisesRegex(ValueError, f"HTTP {code}"):
                    release_promotion.hangar_version("1.2.3")

    def test_registry_tokens_are_required(self):
        with patch.dict(os.environ, {}, clear=True):
            for check in (lambda: release_promotion.modrinth_versions("1.2.3", self.jar_path), lambda: release_promotion.hangar_version("1.2.3")):
                with self.assertRaisesRegex(ValueError, "required"):
                    check()

    def test_download_never_sends_registry_tokens(self):
        with patch.object(release_promotion.urllib.request, "urlopen", return_value=io.BytesIO(b"jar")) as opened:
            release_promotion.consumer_digest("https://cdn.modrinth.com/file.jar", "cdn.modrinth.com")
        self.assertIsNone(opened.call_args.args[0].get_header("Authorization"))


if __name__ == "__main__":
    unittest.main()
