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
        os.chdir(self.root)
        self.addCleanup(os.chdir, self.previous)
        self.jar = b"candidate bytes"
        self.jar_name = "SpiralGenesis-1.2.3-beta.1.jar"
        self.checksum_name = self.jar_name + ".sha256"
        self.jar_hash = hashlib.sha256(self.jar).hexdigest()
        self.checksum = f"{self.jar_hash}  {self.jar_name}\n".encode()
        checksum_hash = hashlib.sha256(self.checksum).hexdigest()
        (self.root / "candidate-manifest.json").write_text(json.dumps({
            "source": "a" * 40, "files": {self.jar_name: self.jar_hash, self.checksum_name: checksum_hash}
        }))
        self.args = argparse.Namespace(tag="v1.2.3-beta.1", repository="Ninja6-MC/SpiralGenesis",
                                       output=str(self.root / "outputs"), destination="github", attested="true")

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

    def reconcile(self, destination, modrinth=None, hangar=None, data=None, attested="true"):
        self.args.destination = destination
        self.args.attested = attested
        not_found = subprocess.CalledProcessError(1, ["gh"], stderr="HTTP 404")
        with patch.object(release_promotion, "recheck"), patch.object(release_promotion, "api", side_effect=not_found), \
             patch.object(release_promotion, "command", return_value=""), \
             patch.object(release_promotion, "modrinth_versions", return_value=modrinth or []), \
             patch.object(release_promotion, "hangar_version", return_value=hangar), \
             patch.object(release_promotion, "registry_json", side_effect=lambda url: hangar if "hangar" in url else (modrinth or [None])[0]), \
             patch.object(release_promotion.urllib.request, "urlopen", side_effect=lambda *a, **k: io.BytesIO(self.jar if data is None else data)):
            release_promotion.reconcile(self.args)
        return Path(self.args.output).read_text()

    def fake_command(self, *args, **kwargs):
        if args[:3] == ("gh", "release", "download"):
            Path(args[-1]).write_bytes(self.checksum if args[-3] == self.checksum_name else self.jar)
        return ""

    def test_matching_github_release_is_complete(self):
        release = {"target_commitish": "a" * 40, "prerelease": True,
                   "assets": [{"name": self.jar_name}, {"name": self.checksum_name}]}
        with patch.object(release_promotion, "recheck"), patch.object(release_promotion, "api", return_value=release), \
             patch.object(release_promotion, "command", side_effect=self.fake_command):
            release_promotion.reconcile(self.args)
        self.assertIn("state=complete", (self.root / "outputs").read_text())

    def test_missing_github_release_is_absent_without_attestation(self):
        self.assertIn("state=absent", self.reconcile("github", attested="false"))

    def test_github_draft_stops_without_second_create(self):
        draft = {"tag_name": self.args.tag, "draft": True, "id": 42,
                 "name": "Unfinished beta", "html_url": "https://github.com/owner/repo/releases/42"}
        missing = subprocess.CalledProcessError(1, ["gh"], stderr="HTTP 404")
        for lookup in (draft, missing):
            with self.subTest(lookup=type(lookup).__name__), \
                 patch.object(release_promotion, "recheck"), \
                 patch.object(release_promotion, "api", side_effect=[lookup]), \
                 patch.object(release_promotion, "command", return_value=json.dumps(draft)) as command:
                with self.assertRaisesRegex(ValueError, "Unfinished beta.*releases/42.*delete that draft manually"):
                    release_promotion.reconcile(self.args)
                self.assertFalse(Path(self.args.output).exists())
                for call in command.call_args_list:
                    self.assertEqual(call.args[:3], ("gh", "api", "--paginate"))

    def test_unrelated_draft_does_not_block_absent_tag(self):
        missing = subprocess.CalledProcessError(1, ["gh"], stderr="HTTP 404")
        with patch.object(release_promotion, "recheck"), \
             patch.object(release_promotion, "api", side_effect=missing), \
             patch.object(release_promotion, "command", return_value=json.dumps({"draft": True, "tag_name": "v0.0.1"})):
            release_promotion.reconcile(self.args)
        self.assertIn("state=absent", Path(self.args.output).read_text())

    def test_retry_stops_on_conflicting_github_asset(self):
        release = {"target_commitish": "a" * 40, "prerelease": True,
                   "assets": [{"name": "different.jar"}]}
        with patch.object(release_promotion, "recheck"), patch.object(release_promotion, "api", return_value=release):
            with self.assertRaisesRegex(ValueError, "asset list"):
                release_promotion.reconcile(self.args)

    def test_retry_stops_on_conflicting_modrinth_digest(self):
        with self.assertRaisesRegex(ValueError, "Modrinth consumer JAR"):
            self.reconcile("modrinth", [self.modrinth()], data=b"wrong bytes")
        self.assertFalse(Path(self.args.output).exists())

    def test_complete_modrinth_is_skipped_without_attestation(self):
        self.assertIn("state=complete", self.reconcile("modrinth", [self.modrinth()], attested="false"))

    def test_attested_absent_registries_are_absent(self):
        self.assertIn("state=absent", self.reconcile("modrinth"))
        Path(self.args.output).unlink()
        self.assertIn("state=absent", self.reconcile("hangar"))

    def test_registry_absence_requires_attestation(self):
        for destination in ("modrinth", "hangar"):
            with self.subTest(destination=destination):
                with self.assertRaisesRegex(ValueError, "absence is uncertain"):
                    self.reconcile(destination, attested="false")
                self.assertFalse(Path(self.args.output).exists())

    def test_retry_matches_hangar_download(self):
        self.assertIn("state=complete", self.reconcile("hangar", hangar=self.hangar(), attested="false"))

    def test_draft_and_hidden_versions_stop_without_retrying_upload(self):
        cases = [("modrinth", [self.modrinth(status)], None) for status in ("draft", "scheduled", "unlisted", "archived", "unknown")]
        cases.append(("hangar", [], self.hangar("hidden")))
        for destination, modrinth, hangar in cases:
            with self.subTest(destination=destination, modrinth=bool(modrinth)), self.assertRaisesRegex(ValueError, "nonpublic"):
                self.reconcile(destination, modrinth, hangar)
            self.assertFalse(Path(self.args.output).exists())

    def test_anonymous_consumer_visibility_failure_stops(self):
        for destination, modrinth, hangar in (("modrinth", [self.modrinth()], None), ("hangar", [], self.hangar())):
            self.args.destination = destination
            with self.subTest(destination=destination), \
                 patch.object(release_promotion, "recheck"), \
                 patch.object(release_promotion, "modrinth_versions", return_value=modrinth), \
                 patch.object(release_promotion, "hangar_version", return_value=hangar), \
                 patch.object(release_promotion, "consumer_digest", return_value=self.jar_hash), \
                 patch.object(release_promotion, "registry_json", return_value={"status": "draft", "visibility": "hidden"}):
                with self.assertRaisesRegex(ValueError, "not publicly available"):
                    release_promotion.reconcile(self.args)
                self.assertFalse(Path(self.args.output).exists())

    def test_modrinth_ambiguous_versions_stop(self):
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            self.reconcile("modrinth", [self.modrinth(), self.modrinth()])

    def test_untrusted_download_stops(self):
        remote = self.modrinth()
        remote["files"][0]["url"] = "https://example.com/file.jar"
        with self.assertRaisesRegex(ValueError, "trusted"):
            self.reconcile("modrinth", [remote])

    def test_retry_stops_on_conflicting_hangar_digest(self):
        remote = {"name": "1.2.3-beta.1", "channel": {"name": "Beta"},
                  "downloads": {"PAPER": {"fileInfo": {"name": self.jar_name,
                                                        "sha256Hash": "0" * 64}}}}
        with self.assertRaisesRegex(ValueError, "Hangar"):
            self.reconcile("hangar", hangar=remote)

    def test_hangar_download_conflict_stops(self):
        with self.assertRaisesRegex(ValueError, "Hangar consumer JAR"):
            self.reconcile("hangar", hangar=self.hangar(), data=b"wrong bytes")

    def test_public_lookups_send_no_credentials(self):
        with patch.dict(os.environ, {"MODRINTH_TOKEN": "modrinth-secret", "HANGAR_API_TOKEN": "hangar-secret"}), \
             patch.object(release_promotion.urllib.request, "urlopen",
                          side_effect=lambda request, **k: io.BytesIO(b"[]")) as opened:
            self.assertIsNone(release_promotion.public_state("modrinth", self.args.repository, self.args.tag))
        for call in opened.call_args_list:
            self.assertIsNone(call.args[0].get_header("Authorization"))

    def test_verify_published_retries_then_accepts_complete(self):
        self.args.destination = "modrinth"
        with patch.object(release_promotion, "owner_state", side_effect=[None, ValueError("not publicly available"), "complete"]) as state, \
             patch.object(release_promotion.time, "sleep") as sleep:
            release_promotion.verify_published(self.args)
        self.assertEqual(state.call_count, 3)
        self.assertEqual(sleep.call_count, 2)

    def test_verify_published_fails_when_consumers_never_get_the_bytes(self):
        self.args.destination = "hangar"
        with patch.object(release_promotion, "owner_state", side_effect=ValueError("Existing Hangar consumer JAR bytes differ")), \
             patch.object(release_promotion.time, "sleep"):
            with self.assertRaisesRegex(ValueError, "could not be verified.*bytes differ"):
                release_promotion.verify_published(self.args)


RUN = {"id": 500, "status": "in_progress", "head_branch": "v1.2.3-beta.1",
       "created_at": "2026-11-10T12:00:00Z", "updated_at": "2026-11-10T12:00:05Z"}


class AttestationTests(unittest.TestCase):
    """The refusal paths N6-REL-05 names: later attempt, missing attestation, concurrent run."""

    def setUp(self):
        self.args = argparse.Namespace(tag="v1.2.3-beta.1", repository="Ninja6-MC/SpiralGenesis", run_id=500,
                                       attempt=1, destinations="github,modrinth,hangar", modrinth_attested="true",
                                       hangar_attested="true", output=None)

    def other(self, **fields):
        run = {"id": 400, "status": "completed", "head_branch": "v1.2.3-beta.1",
               "created_at": "2026-11-10T11:00:00Z", "updated_at": "2026-11-10T11:30:00Z"}
        run.update(fields)
        return run

    def test_true_attestation_refused_on_later_attempt(self):
        for attempt in (2, 3):
            with self.subTest(attempt=attempt), patch.object(release_promotion, "api") as api:
                with self.assertRaisesRegex(ValueError, f"attempt {attempt}"):
                    release_promotion.check_attestation("Ninja6-MC/SpiralGenesis", "v1.2.3-beta.1", 500, attempt)
                api.assert_not_called()

    def test_plan_refuses_true_attestation_on_later_attempt(self):
        self.args.attempt = 2
        with patch.object(release_promotion, "recheck"), \
             patch.object(release_promotion, "public_state", return_value=None):
            with self.assertRaisesRegex(ValueError, "attempt 2"):
                release_promotion.plan(self.args)

    def test_guard_refuses_true_attestation_on_rerun_before_upload(self):
        args = argparse.Namespace(destination="hangar", attested="true", repository="Ninja6-MC/SpiralGenesis",
                                  tag="v1.2.3-beta.1", run_id=500, attempt=2)
        with patch.object(release_promotion, "recheck") as recheck:
            with self.assertRaisesRegex(ValueError, "attempt 2"):
                release_promotion.guard(args)
            recheck.assert_not_called()

    def test_guard_refuses_concurrent_run_before_upload(self):
        args = argparse.Namespace(destination="modrinth", attested="true", repository="Ninja6-MC/SpiralGenesis",
                                  tag="v1.2.3-beta.1", run_id=500, attempt=1)
        with patch.object(release_promotion, "api", return_value=RUN), \
             patch.object(release_promotion, "publisher_runs", return_value=[RUN, self.other(status="in_progress")]), \
             patch.object(release_promotion, "recheck") as recheck:
            with self.assertRaisesRegex(ValueError, "publisher run 400"):
                release_promotion.guard(args)
            recheck.assert_not_called()

    def test_guard_without_attestation_only_rechecks(self):
        args = argparse.Namespace(destination="github", attested="false", repository="Ninja6-MC/SpiralGenesis",
                                  tag="v1.2.3-beta.1", run_id=500, attempt=2)
        with patch.object(release_promotion, "recheck") as recheck, patch.object(release_promotion, "api") as api:
            release_promotion.guard(args)
        recheck.assert_called_once()
        api.assert_not_called()

    def test_concurrent_or_later_finished_publisher_run_refuses_attestation(self):
        cases = {
            "earlier run still in progress": self.other(status="in_progress"),
            "earlier run waiting for approval": self.other(status="waiting"),
            "earlier run still queued": self.other(status="queued"),
            "run finished after this run was created": self.other(updated_at="2026-11-10T12:10:00Z"),
        }
        for name, run in cases.items():
            with self.subTest(name), patch.object(release_promotion, "api", return_value=RUN), \
                 patch.object(release_promotion, "publisher_runs", return_value=[RUN, run]):
                with self.assertRaisesRegex(ValueError, f"publisher run {run['id']} .*in progress"):
                    release_promotion.check_attestation("Ninja6-MC/SpiralGenesis", "v1.2.3-beta.1", 500, 1)

    def test_runs_that_cannot_have_written_do_not_refuse(self):
        runs = [RUN, self.other(), self.other(id=401, status="in_progress", head_branch="v1.2.2"),
                self.other(id=600, status="pending", created_at="2026-11-10T12:05:00Z", updated_at="2026-11-10T12:05:00Z")]
        with patch.object(release_promotion, "api", return_value=RUN), \
             patch.object(release_promotion, "publisher_runs", return_value=runs):
            release_promotion.check_attestation("Ninja6-MC/SpiralGenesis", "v1.2.3-beta.1", 500, 1)

    def test_later_run_cancelled_while_queued_does_not_refuse(self):
        # This run waits for approval, B queues behind it and C replaces B, so B completes
        # as cancelled after this run was created. B was created later and never ran.
        later = [self.other(id=601, conclusion="cancelled", created_at="2026-11-10T12:05:00Z",
                            updated_at="2026-11-10T12:20:00Z"),
                 self.other(id=602, status="pending", created_at="2026-11-10T12:20:00Z",
                            updated_at="2026-11-10T12:20:00Z")]
        self.assertEqual(release_promotion.conflicting_runs([RUN] + later, RUN, "v1.2.3-beta.1"), [])
        with patch.object(release_promotion, "api", return_value=RUN), \
             patch.object(release_promotion, "publisher_runs", return_value=[RUN] + later):
            release_promotion.check_attestation("Ninja6-MC/SpiralGenesis", "v1.2.3-beta.1", 500, 1)

    def test_later_run_of_any_status_is_ignored(self):
        for status in ("in_progress", "waiting", "queued", "pending", "completed"):
            run = self.other(id=600, status=status, created_at="2026-11-10T12:05:00Z", updated_at="2026-11-10T12:30:00Z")
            with self.subTest(status=status):
                self.assertEqual(release_promotion.conflicting_runs([RUN, run], RUN, "v1.2.3-beta.1"), [])

    def test_attestation_refused_off_the_tag_ref(self):
        with patch.object(release_promotion, "api", return_value=dict(RUN, head_branch="main")):
            with self.assertRaisesRegex(ValueError, "tag ref"):
                release_promotion.check_attestation("Ninja6-MC/SpiralGenesis", "v1.2.3-beta.1", 500, 1)

    def test_publisher_runs_reads_every_publisher_workflow(self):
        line = json.dumps(self.other())
        with patch.object(release_promotion, "command", return_value=line + "\n") as command:
            runs = release_promotion.publisher_runs("Ninja6-MC/SpiralGenesis")
        self.assertEqual(runs, [self.other()] * len(release_promotion.PUBLISHER_WORKFLOWS))
        self.assertIn("--paginate", command.call_args.args)

    def plan(self, public, attested=None):
        args = argparse.Namespace(**vars(self.args))
        for name, value in (attested or {}).items():
            setattr(args, f"{name}_attested", value)
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        args.output = str(Path(temp.name) / "outputs")
        with patch.object(release_promotion, "recheck"), \
             patch.object(release_promotion, "public_state", side_effect=lambda name, *_: public[name]), \
             patch.object(release_promotion, "check_attestation") as check, \
             patch.object(release_promotion, "project_checks"), \
             patch.object(release_promotion, "public_release_tags", return_value=["v1.2.2", "v1.2.3-beta.0"]):
            release_promotion.plan(args)
        return Path(args.output).read_text(), check

    def test_plan_refuses_missing_attestation_for_destination_not_publicly_complete(self):
        for name in ("modrinth", "hangar"):
            with self.subTest(name):
                with self.assertRaisesRegex(ValueError, f"{name}_absence_confirmed=true"):
                    self.plan({"github": "absent", "modrinth": None, "hangar": None}, {name: "false"})

    def test_plan_accepts_publicly_complete_destination_without_attestation(self):
        output, check = self.plan({"github": "complete", "modrinth": "complete", "hangar": "complete"},
                                  {"modrinth": "false", "hangar": "false"})
        check.assert_not_called()
        self.assertIn("modrinth_public=complete", output)
        self.assertIn("newest=true", output)

    def test_plan_checks_runs_and_attempt_for_any_true_attestation(self):
        output, check = self.plan({"github": "absent", "modrinth": None, "hangar": None})
        check.assert_called_once_with("Ninja6-MC/SpiralGenesis", "v1.2.3-beta.1", 500, 1)
        self.assertIn("modrinth_public=unproven", output)

    def test_plan_selects_one_destination(self):
        self.args.destinations = " hangar "
        output, _ = self.plan({"hangar": None}, {"modrinth": "false"})
        self.assertIn("destinations=hangar\n", output)
        self.assertIn("github=false", output)
        self.assertIn("modrinth=false", output)
        self.assertIn("hangar=true", output)

    def test_plan_refuses_attestation_for_unselected_destination(self):
        self.args.destinations = "github"
        with self.assertRaisesRegex(ValueError, "not a selected destination"):
            self.plan({"github": "absent"})

    def test_destination_list_is_validated(self):
        for text in ("", "github,,hangar", "github,github", "curseforge", "GitHub"):
            with self.subTest(text=text), self.assertRaisesRegex(ValueError, "comma-separated"):
                release_promotion.select_destinations(text)
        self.assertEqual(release_promotion.select_destinations("hangar, github"), ["github", "hangar"])

    def test_newest_public_version_on_channel(self):
        tags = ["v1.0.0", "v1.1.0-beta.2", "v1.1.0-rc.1", "v0.9.0-alpha.3", "not-a-tag"]
        self.assertTrue(release_promotion.newest_on_channel("v1.0.1", tags))
        self.assertTrue(release_promotion.newest_on_channel("v1.0.0", tags))
        self.assertFalse(release_promotion.newest_on_channel("v0.9.9", tags))
        self.assertFalse(release_promotion.newest_on_channel("v1.1.0-beta.3", tags))
        self.assertTrue(release_promotion.newest_on_channel("v1.1.0-rc.2", tags))
        self.assertTrue(release_promotion.newest_on_channel("v0.9.0-alpha.4", tags))
        self.assertTrue(release_promotion.newest_on_channel("v2.0.0", []))


class PageSyncTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.args = argparse.Namespace(repository="Ninja6-MC/SpiralGenesis", tag="v1.2.3-beta.1",
                                       destinations="hangar-page", modrinth_attested="false",
                                       hangar_attested="false", attempt=1, run_id=500,
                                       output=str(Path(self.temp.name) / "outputs"))

    def plan(self, tags=None, state="complete"):
        with patch.object(release_promotion, "recheck"), \
             patch.object(release_promotion, "public_release_tags", return_value=tags if tags is not None else [self.args.tag]), \
             patch.object(release_promotion, "public_state", return_value=state), \
             patch.object(release_promotion, "project_checks"):
            release_promotion.plan(self.args)

    def test_page_only_plan_selects_no_upload(self):
        self.plan()
        output = Path(self.args.output).read_text()
        self.assertIn("hangar_page=true", output)
        for destination in release_promotion.DESTINATIONS:
            self.assertIn(f"{destination}=false", output)

    def test_page_only_plan_refuses_older_tag(self):
        with self.assertRaisesRegex(ValueError, "not the newest public version on its channel"):
            self.plan([self.args.tag, "v1.2.3-rc.1"])
        self.assertFalse(Path(self.args.output).exists())

    def test_page_only_requires_public_github_and_complete_hangar(self):
        with self.assertRaisesRegex(ValueError, "already-published GitHub"):
            self.plan([])
        for state in (None, "absent"):
            with self.subTest(state=state), self.assertRaisesRegex(ValueError, "complete Hangar"):
                self.plan(state=state)

    def test_page_only_refuses_either_attestation(self):
        for name in release_promotion.ATTESTED:
            with self.subTest(name=name):
                setattr(self.args, f"{name}_attested", "true")
                with self.assertRaisesRegex(ValueError, "attestation given"):
                    self.plan()
                setattr(self.args, f"{name}_attested", "false")

    def test_page_only_cannot_be_combined_with_uploads(self):
        with self.assertRaisesRegex(ValueError, "selected alone"):
            release_promotion.select_destinations("hangar,hangar-page")

    def test_page_guard_rechecks_newest_and_owner_bytes_after_approval(self):
        with patch.object(release_promotion, "recheck") as recheck, \
             patch.object(release_promotion, "public_release_tags", return_value=[self.args.tag]) as tags, \
             patch.object(release_promotion, "owner_state", return_value="complete") as owner:
            release_promotion.page_guard(self.args)
            recheck.assert_called_once_with(self.args)
            tags.assert_called_once_with(self.args.repository)
            owner.assert_called_once_with("hangar", self.args.repository, self.args.tag)
        with patch.object(release_promotion, "recheck"), \
             patch.object(release_promotion, "public_release_tags", return_value=[self.args.tag, "v1.2.3-rc.1"]), \
             patch.object(release_promotion, "owner_state") as owner:
            with self.assertRaisesRegex(ValueError, "not the newest"):
                release_promotion.page_guard(self.args)
            owner.assert_not_called()


class CandidateRunTests(unittest.TestCase):
    """A candidate from another workflow, repository, attempt, commit or tag is refused."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.previous = Path.cwd()
        os.chdir(self.temp.name)
        self.addCleanup(os.chdir, self.previous)
        self.repository = "Ninja6-MC/SpiralGenesis"
        self.args = argparse.Namespace(repository=self.repository, run_id=42, tag="v1.2.3-beta.1")

    def run_record(self, **fields):
        run = {"name": "Verify Release Candidate", "path": ".github/workflows/release-candidate.yml",
               "repository": {"full_name": self.repository}, "head_repository": {"full_name": self.repository},
               "event": "workflow_dispatch", "head_branch": "main", "status": "completed",
               "conclusion": "success", "run_attempt": 1, "head_sha": "a" * 40}
        run.update(fields)
        return run

    def fetch(self, run, source="a" * 40, manifest=None):
        artifacts = {"artifacts": [{"name": name, "id": 87, "expired": False} for name in
                                   ("release-candidate-jar", "release-candidate-manifest", "release-candidate-evidence")]}
        Path("candidate-manifest.json").write_text(json.dumps(manifest or {
            "tag": self.args.tag, "source": "a" * 40, "run_id": 42, "attempt": 1, "artifact_id": 87}))
        with patch.object(release_promotion, "api", side_effect=[run, artifacts]), \
             patch.object(release_promotion, "tag_source", return_value=source), \
             patch.object(release_promotion, "command"), \
             patch.object(release_promotion, "verify") as verified:
            release_promotion.fetch(self.args)
        return verified

    def test_valid_candidate_run_is_verified(self):
        self.fetch(self.run_record()).assert_called_once()

    def test_candidate_from_another_workflow_repository_or_attempt_is_refused(self):
        for fields in ({"path": ".github/workflows/ci.yml"}, {"name": "CI"},
                       {"repository": {"full_name": "someone/SpiralGenesis"}},
                       {"head_repository": {"full_name": "someone/SpiralGenesis"}},
                       {"head_branch": "feature"}, {"event": "push"}, {"conclusion": "failure"},
                       {"run_attempt": 2}):
            with self.subTest(fields=fields), self.assertRaisesRegex(ValueError, "candidate workflow"):
                self.fetch(self.run_record(**fields))

    def test_candidate_from_another_commit_is_refused(self):
        with self.assertRaisesRegex(ValueError, "Tag does not resolve"):
            self.fetch(self.run_record(), source="b" * 40)

    def test_candidate_for_another_tag_or_attempt_is_refused(self):
        base = {"tag": "v1.2.3-beta.1", "source": "a" * 40, "run_id": 42, "attempt": 1, "artifact_id": 87}
        for fields in ({"tag": "v1.2.3-beta.2"}, {"source": "b" * 40}, {"run_id": 41}, {"attempt": 2}):
            with self.subTest(fields=fields), self.assertRaisesRegex(ValueError, "another tag"):
                self.fetch(self.run_record(), manifest=dict(base, **fields))

    def test_expired_candidate_is_refused(self):
        artifacts = {"artifacts": [{"name": name, "id": 87, "expired": name == "release-candidate-jar"} for name in
                                   ("release-candidate-jar", "release-candidate-manifest", "release-candidate-evidence")]}
        with patch.object(release_promotion, "api", side_effect=[self.run_record(), artifacts]), \
             patch.object(release_promotion, "tag_source", return_value="a" * 40), \
             patch.object(release_promotion, "command") as command:
            with self.assertRaisesRegex(ValueError, "expired"):
                release_promotion.fetch(self.args)
            command.assert_not_called()


class PreflightTests(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {"GH_TOKEN": "gh", "MODRINTH_TOKEN": "modrinth-secret",
                                           "HANGAR_API_TOKEN": "hangar-secret"})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.args = argparse.Namespace(repository="Ninja6-MC/SpiralGenesis", tag="v1.2.3",
                                       destinations="github,modrinth,hangar", page_sync="true")

    def run_preflight(self, permissions=("is_subject_member", "create_version", "edit_page")):
        with patch.object(release_promotion, "api", return_value=[]), \
             patch.object(release_promotion, "modrinth_project", return_value={"id": "p"}), \
             patch.object(release_promotion, "registry_json", return_value=[]), \
             patch.object(release_promotion, "hangar_session", return_value=("HangarAuth s", list(permissions))):
            release_promotion.preflight(self.args)

    def test_page_only_preflight_requires_hangar_key(self):
        self.args.destinations = "hangar-page"
        with patch.dict(os.environ, {"HANGAR_API_TOKEN": ""}):
            with self.assertRaisesRegex(ValueError, "HANGAR_API_TOKEN is required"):
                release_promotion.preflight(self.args)

    def test_page_only_preflight_requires_edit_page_without_create_version(self):
        self.args.destinations = "hangar-page"
        self.args.page_sync = "false"
        self.run_preflight(("is_subject_member", "edit_page"))
        with self.assertRaisesRegex(ValueError, "lacks edit_page"):
            self.run_preflight(("is_subject_member", "create_version"))

    def test_all_selected_credentials_pass(self):
        self.run_preflight()

    def test_missing_credential_for_any_selected_destination_stops_every_job(self):
        for name in ("MODRINTH_TOKEN", "GH_TOKEN"):
            with self.subTest(name), patch.dict(os.environ, {name: ""}):
                with self.assertRaisesRegex(ValueError, f"{name} is required"):
                    self.run_preflight()

    def test_unselected_destination_needs_no_credential(self):
        self.args.destinations = "github"
        with patch.dict(os.environ, {"MODRINTH_TOKEN": "", "HANGAR_API_TOKEN": ""}):
            self.run_preflight(permissions=())

    def test_hangar_upload_and_page_permissions_are_required(self):
        with self.assertRaisesRegex(ValueError, "lacks create_version"):
            self.run_preflight(("is_subject_member", "edit_page"))
        with self.assertRaisesRegex(ValueError, "lacks edit_page"):
            self.run_preflight(("is_subject_member", "create_version"))
        self.args.page_sync = "false"
        self.run_preflight(("is_subject_member", "create_version"))


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
