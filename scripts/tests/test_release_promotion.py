import argparse
import json
import subprocess
import sys
import tempfile
import unittest
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
                                       output=str(self.root / "outputs"))

    def fake_command(self, *args, **kwargs):
        if args[:3] == ("gh", "release", "download"):
            Path(args[-1]).write_bytes(self.checksum if args[-3] == self.checksum_name else self.jar)
        return ""

    def test_retry_skips_matching_github_and_resumes_absent_registries(self):
        release = {"target_commitish": "a" * 40, "prerelease": True,
                   "assets": [{"name": self.jar_name}, {"name": self.checksum_name}]}
        with patch.object(release_promotion, "recheck"), patch.object(release_promotion, "api", return_value=release), \
             patch.object(release_promotion, "public_json", side_effect=[[], None]), \
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
        not_found = subprocess.CalledProcessError(1, ["gh"], stderr="HTTP 404")
        remote = [{"version_number": "1.2.3-beta.1", "version_type": "beta",
                   "files": [{"filename": self.jar_name, "hashes": {"sha256": "0" * 64}}]}]
        with patch.object(release_promotion, "recheck"), patch.object(release_promotion, "api", side_effect=not_found), \
             patch.object(release_promotion, "public_json", return_value=remote):
            with self.assertRaisesRegex(ValueError, "Modrinth"):
                release_promotion.destinations(self.args)

    def test_retry_stops_on_conflicting_hangar_digest(self):
        not_found = subprocess.CalledProcessError(1, ["gh"], stderr="HTTP 404")
        remote = {"name": "1.2.3-beta.1", "channel": {"name": "Beta"},
                  "downloads": {"PAPER": {"fileInfo": {"name": self.jar_name,
                                                        "sha256Hash": "0" * 64}}}}
        with patch.object(release_promotion, "recheck"), patch.object(release_promotion, "api", side_effect=not_found), \
             patch.object(release_promotion, "public_json", side_effect=[[], remote]):
            with self.assertRaisesRegex(ValueError, "Hangar"):
                release_promotion.destinations(self.args)


if __name__ == "__main__":
    unittest.main()
