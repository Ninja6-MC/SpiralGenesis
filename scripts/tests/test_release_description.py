import argparse
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_description as description


class DescriptionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.args = argparse.Namespace(repository="Ninja6-MC/SpiralGenesis", tag="v1.2.3",
                                       destinations="modrinth", output=str(Path(self.temp.name) / "outputs"))
        self.expected = "# Description\n\nCurrent text."

    def check(self, body, owner="v1.2.3", state="complete"):
        with patch.object(description, "recheck"), \
             patch.object(description, "public_state", return_value=state), \
             patch.object(description, "public_owner", return_value=owner), \
             patch.object(description, "source_body", return_value=self.expected) as source, \
             patch.object(description, "public_body", return_value=body), \
             patch.object(description, "summary") as summary:
            description.check(self.args)
        return Path(self.args.output).read_text(), source, summary

    def test_matching_body_needs_no_approval(self):
        output, _, _ = self.check(self.expected)
        self.assertIn("paste_needed=false", output)

    def test_differing_body_requests_paste_before_gate(self):
        output, _, summary = self.check("old text")
        self.assertIn("paste_needed=true", output)
        message = summary.call_args.args[0]
        self.assertIn("blob/v1.2.3/docs/modrinth-description.md", message)
        self.assertIn("then approve", message)
        self.assertIn("newer owner found at approval replaces", message)

    def test_line_endings_and_trailing_whitespace_only_match(self):
        output, _, _ = self.check("# Description  \r\n\r\nCurrent text.\t\r\n\r\n")
        self.assertIn("paste_needed=false", output)
        self.assertFalse(description.compare("one two", "one  two"))
        self.assertFalse(description.compare("  indented", "indented"))

    def test_nonowner_does_not_compare_or_request_paste(self):
        output, source, _ = self.check("old text", owner="v1.2.4")
        self.assertIn("paste_needed=false", output)
        source.assert_not_called()

    def test_unselected_incomplete_version_skips(self):
        self.args.destinations = "github"
        output, source, _ = self.check("old text", state=None)
        self.assertIn("paste_needed=false", output)
        source.assert_not_called()

    def test_already_complete_modrinth_is_checked_without_selection(self):
        self.args.destinations = "shared-state"
        output, _, _ = self.check("old text")
        self.assertIn("paste_needed=true", output)

    def test_confirm_superseded_tag_uses_new_owners_file(self):
        with patch.object(description, "public_owner", return_value="v1.2.4"), \
             patch.object(description, "source_body", return_value="new owner text") as source, \
             patch.object(description, "public_body", return_value="new owner text"), \
             patch.object(description, "summary") as summary:
            description.confirm(self.args)
        source.assert_called_once_with(self.args.repository, "v1.2.4")
        self.assertIn("superseded", summary.call_args_list[0].args[0])
        self.assertIn("v1.2.4", summary.call_args_list[-1].args[0])

    def test_confirm_superseded_tag_rejects_original_owners_body(self):
        with patch.object(description, "public_owner", return_value="v1.2.4"), \
             patch.object(description, "source_body", return_value="new owner text"), \
             patch.object(description, "public_body", return_value=self.expected), \
             patch.object(description, "summary") as summary:
            with self.assertRaisesRegex(ValueError, "v1.2.4.*paste.*re-run"):
                description.confirm(self.args)
        self.assertIn("```diff", summary.call_args.args[0])
        self.assertIn("blob/v1.2.4/", summary.call_args.args[0])

    def test_owner_change_during_read_is_retried(self):
        with patch.object(description, "public_owner", side_effect=["v1.2.3", "v1.2.4", "v1.2.4", "v1.2.4"]), \
             patch.object(description, "source_body", side_effect=["old", "new"]) as source, \
             patch.object(description, "public_body", side_effect=["old", "new"]), \
             patch.object(description, "summary"):
            description.confirm(self.args)
        self.assertEqual([c.args[1] for c in source.call_args_list], ["v1.2.3", "v1.2.4"])

    def test_source_strips_generated_comment_like_hangar(self):
        with patch.object(description, "tag_source") as verified, \
             patch.object(description, "command", return_value="<!-- generated file -->\n\n# Heading\n\nBody\n") as command:
            self.assertEqual(description.source_body(self.args.repository, self.args.tag), "# Heading\n\nBody")
        verified.assert_called_once_with(self.args.tag)
        self.assertEqual(command.call_args.args[-1], "refs/tags/v1.2.3:docs/modrinth-description.md")

    def test_public_body_never_sends_a_token(self):
        with patch.dict(os.environ, {"MODRINTH_TOKEN": "unused"}), \
             patch.object(description, "registry_json", return_value={"body": "public text"}) as registry:
            self.assertEqual(description.public_body(), "public text")
        registry.assert_called_once_with(description.PROJECT)

    def test_diff_summary_is_short(self):
        diff = description.difference("\n".join(["old" * 100] * 100), "\n".join(["new" * 100] * 100))
        self.assertLessEqual(len(diff.splitlines()), 16)
        self.assertLessEqual(max(map(len, diff.splitlines())), 200)


if __name__ == "__main__":
    unittest.main()
