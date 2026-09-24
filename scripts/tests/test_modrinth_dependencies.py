"""Tests for scripts/modrinth-dependencies.py.

Run: python -m unittest discover -s scripts/tests
"""

import importlib.util
import io
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(os.path.dirname(HERE), "modrinth-dependencies.py")

spec = importlib.util.spec_from_file_location("modrinth_dependencies", SCRIPT)
deps = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deps)

PLUGIN = """name: x
softdepend:
  - floodgate
  - AuthMe
  - GriefPrevention
  - Multiverse-Core

commands:
  sgen:
    description: y
"""

RELEASE = """      - uses: x
        with:
          dependencies: |
            floodgate(optional){modrinth:bWrNNfkb}
            griefprevention(optional){modrinth:O4o4mKaq}
            authmereloaded(optional){modrinth:9js4IEHC}
            multiverse-core(optional){modrinth:3wmN97b8}
          files: |
            build/libs/*.jar
"""


def compare(plugin=PLUGIN, release=RELEASE):
    return deps.compare(
        deps.plugin_dependencies(plugin), deps.release_dependencies(release)
    )


class Repository(unittest.TestCase):
    def test_repository_files_agree(self):
        with io.open(deps.PLUGIN_YML, encoding="utf-8") as handle:
            plugin = deps.plugin_dependencies(handle.read())
        with io.open(deps.RELEASE_YML, encoding="utf-8") as handle:
            release = deps.release_dependencies(handle.read())
        self.assertEqual(deps.compare(plugin, release), [])


class Compare(unittest.TestCase):
    def test_matching(self):
        self.assertEqual(compare(), [])

    def test_inline_list(self):
        plugin = "softdepend: [floodgate, AuthMe, 'GriefPrevention', Multiverse-Core]\n"
        self.assertEqual(compare(plugin=plugin), [])

    def test_softdepend_without_mapping(self):
        plugin = PLUGIN.replace("  - AuthMe\n", "  - AuthMe\n  - LuckPerms\n")
        problems = compare(plugin=plugin)
        self.assertEqual(len(problems), 1)
        self.assertIn("LuckPerms, which has no Modrinth id", problems[0])

    def test_mapping_no_longer_declared(self):
        plugin = PLUGIN.replace("  - AuthMe\n", "")
        problems = compare(plugin=plugin)
        self.assertIn("MODRINTH maps AuthMe, which plugin.yml no longer declares", problems)
        self.assertTrue(any("release.yml lists authmereloaded" in p for p in problems))

    def test_wrong_id(self):
        problems = compare(release=RELEASE.replace("O4o4mKaq", "XXXXXXXX"))
        self.assertEqual(len(problems), 2)
        self.assertTrue(any("missing griefprevention(optional){modrinth:O4o4mKaq}" in p for p in problems))

    def test_missing_release_entry(self):
        problems = compare(release=RELEASE.replace("            floodgate(optional){modrinth:bWrNNfkb}\n", ""))
        self.assertEqual(problems, ["release.yml is missing floodgate(optional){modrinth:bWrNNfkb}"])

    def test_required_when_plugin_says_optional(self):
        problems = compare(release=RELEASE.replace("floodgate(optional)", "floodgate(required)"))
        self.assertEqual(len(problems), 2)

    def test_hard_depend_must_be_required(self):
        plugin = "depend:\n  - floodgate\nsoftdepend:\n  - AuthMe\n  - GriefPrevention\n"
        problems = compare(plugin=plugin)
        self.assertIn("release.yml is missing floodgate(required){modrinth:bWrNNfkb}", problems)

    def test_duplicate_release_entry(self):
        release = RELEASE.replace(
            "          files:", "            floodgate(optional){modrinth:bWrNNfkb}\n          files:"
        )
        self.assertIn("release.yml lists the same dependency more than once", compare(release=release))


class Format(unittest.TestCase):
    def test_unreadable_release_line(self):
        with self.assertRaises(deps.FormatError):
            deps.release_dependencies(RELEASE.replace("{modrinth:bWrNNfkb}", ""))

    def test_missing_release_block(self):
        with self.assertRaises(deps.FormatError):
            deps.release_dependencies("with:\n  files: x\n")

    def test_column_zero_block_sequence(self):
        plugin = "softdepend:\n- floodgate\n- AuthMe\n- GriefPrevention\n- Multiverse-Core\ncommands: {}\n"
        self.assertEqual(compare(plugin=plugin), [])

    def test_key_with_no_entries(self):
        with self.assertRaises(deps.FormatError):
            deps.plugin_dependencies("softdepend:\ncommands:\n  x: y\n")

    def test_explicit_empty_list_is_allowed(self):
        self.assertEqual(deps.plugin_dependencies("softdepend: []\n"), {})

    def test_unreadable_plugin_list(self):
        with self.assertRaises(deps.FormatError):
            deps.plugin_dependencies("softdepend: floodgate\n")


if __name__ == "__main__":
    unittest.main()
