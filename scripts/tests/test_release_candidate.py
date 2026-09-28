import argparse
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from release_candidate import create, evidence, verify


SOURCE = "a" * 40
TAG = "v1.2.3-beta.1"


class CandidateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = self.root / "candidate"
        self.directory.mkdir()
        self.jar = self.directory / "SpiralGenesis-1.2.3-beta.1.jar"
        self.manifest = self.root / "manifest.json"
        self.evidence = self.root / "evidence.json"
        self.make_jar("1.2.3-beta.1")
        create(argparse.Namespace(tag=TAG, source=SOURCE, run_id=42, attempt=1,
                                  artifact_id=87, jar=str(self.jar), output=str(self.manifest)))

    def make_jar(self, version):
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("plugin.yml", f"name: SpiralGenesis\nversion: {version}\n")
            archive.writestr("com/ninja6/spiralgenesis/SpiralGenesisPlugin.class", b"class")
        from release_candidate import digest
        self.jar.with_name(self.jar.name + ".sha256").write_text(f"{digest(self.jar)}  {self.jar.name}\n")

    def check(self, with_evidence=False):
        verify(argparse.Namespace(manifest=str(self.manifest), directory=str(self.directory),
                                  tag=TAG, source=SOURCE, run_id=42, attempt=1,
                                  evidence=str(self.evidence) if with_evidence else None))

    def test_valid_candidate_and_evidence(self):
        self.check()
        evidence(argparse.Namespace(manifest=str(self.manifest), directory=str(self.directory),
                                    output=str(self.evidence)))
        self.check(True)

    def test_reject_extra_and_missing_jar(self):
        (self.directory / "extra.jar").write_bytes(b"extra")
        with self.assertRaisesRegex(ValueError, "inventory"):
            self.check()
        (self.directory / "extra.jar").unlink()
        self.jar.unlink()
        with self.assertRaisesRegex(ValueError, "inventory"):
            self.check()

    def test_reject_changed_bytes_and_embedded_version(self):
        self.make_jar("9.9.9")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            self.check()
        data = json.loads(self.manifest.read_text())
        from release_candidate import digest
        data["files"][self.jar.name] = digest(self.jar)
        checksum = self.jar.with_name(self.jar.name + ".sha256")
        data["files"][checksum.name] = digest(checksum)
        self.manifest.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "Embedded plugin version"):
            self.check()

    def test_reject_tag_and_source_mismatch(self):
        with self.assertRaisesRegex(ValueError, "identity"):
            verify(argparse.Namespace(manifest=str(self.manifest), directory=str(self.directory),
                                      tag="v1.2.4", source=SOURCE, run_id=42, attempt=1,
                                      evidence=None))
        with self.assertRaisesRegex(ValueError, "identity"):
            verify(argparse.Namespace(manifest=str(self.manifest), directory=str(self.directory),
                                      tag=TAG, source="b" * 40, run_id=42, attempt=1,
                                      evidence=None))

    def test_reject_missing_and_tampered_evidence(self):
        with self.assertRaises(FileNotFoundError):
            self.check(True)
        evidence(argparse.Namespace(manifest=str(self.manifest), directory=str(self.directory),
                                    output=str(self.evidence)))
        data = json.loads(self.evidence.read_text())
        data["result"] = "failed"
        self.evidence.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "evidence"):
            self.check(True)


if __name__ == "__main__":
    unittest.main()
