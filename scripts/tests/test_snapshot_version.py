from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "snapshot-version.py"


class SnapshotVersionTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.repo = Path(self.temporary_directory.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Snapshot Test")
        self.git("config", "user.email", "snapshot@example.invalid")
        self.commit()

    def git(self, *args):
        return subprocess.check_output(("git", *args), cwd=self.repo, text=True).strip()

    def commit(self):
        self.git("commit", "-q", "--allow-empty", "-m", "test")

    def version(self):
        return subprocess.check_output(
            (sys.executable, str(SCRIPT)), cwd=self.repo, text=True
        ).strip()

    def test_exact_release_tag_is_still_a_snapshot(self):
        self.git("tag", "v1.2.3-beta.4")
        self.assertRegex(self.version(), r"^1\.2\.3-beta\.4-0-g[0-9a-f]+$")

    def test_invalid_nearer_tags_cannot_replace_a_valid_release_tag(self):
        self.git("tag", "v1.2.3-alpha.1")
        self.commit()
        self.git("tag", "v9.9.9-preview.1")
        self.git("tag", "v2.0.0-rc.1-extra")
        self.git("tag", "v3.0")
        self.commit()
        self.assertRegex(self.version(), r"^1\.2\.3-alpha\.1-2-g[0-9a-f]+$")

    def test_no_valid_release_tag_uses_hash_fallback(self):
        self.git("tag", "v1.2.3-preview.1")
        self.git("tag", "v1.2.3.4")
        self.assertRegex(self.version(), r"^0\.0\.0-SNAPSHOT-g[0-9a-f]+$")


if __name__ == "__main__":
    unittest.main()
