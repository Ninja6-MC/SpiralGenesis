"""The PowerShell wrapper hands comma lists to release.sh intact.

Windows only: it runs scripts/release.ps1 through Windows PowerShell and Git Bash, and is
skipped anywhere else. The publish mode validates its lists before touching the network,
so each case stops on a known refusal that shows how the list arrived.
"""

import os
import shutil
import subprocess
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "release.ps1"
POWERSHELL = shutil.which("powershell")
GIT_BASH = any(Path(base, "Git", "bin", "bash.exe").is_file()
               for base in filter(None, (os.environ.get("ProgramFiles"), os.environ.get("ProgramFiles(x86)"))))


@unittest.skipUnless(os.name == "nt" and POWERSHELL and GIT_BASH, "needs Windows PowerShell and Git Bash")
class WrapperTests(unittest.TestCase):
    def run_wrapper(self, *args):
        # -Command, not -File: an unquoted a,b is parsed as an array only at a PowerShell prompt.
        line = "& '{}' {}".format(SCRIPT, " ".join(args))
        result = subprocess.run([POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", line],
                                capture_output=True, text=True, timeout=120)
        return result.returncode, result.stdout + result.stderr

    def test_page_only_refuses_attestation_before_network(self):
        code, output = self.run_wrapper("publish", "1.2.3", "99", "--destinations", "shared-state",
                                        "--attest", "hangar", "--dry-run")
        self.assertEqual(code, 1)
        self.assertIn("shared-state takes no absence attestation", output)

    def test_page_only_refuses_combined_destinations_before_network(self):
        code, output = self.run_wrapper("publish", "1.2.3", "99", "--destinations", "hangar,shared-state", "--dry-run")
        self.assertEqual(code, 1)
        self.assertIn("shared-state must be selected alone", output)

    def test_unquoted_attest_list_reaches_release_sh_intact(self):
        code, output = self.run_wrapper("publish", "1.2.3", "99", "--destinations", "github",
                                        "--attest", "modrinth,hangar", "--dry-run")
        self.assertEqual(code, 1)
        self.assertIn("'modrinth' is attested but not selected", output)
        self.assertNotIn("modrinth hangar", output)

    def test_unquoted_destination_list_reaches_release_sh_intact(self):
        code, output = self.run_wrapper("publish", "1.2.3", "99", "--destinations", "github,github", "--dry-run")
        self.assertEqual(code, 1)
        self.assertIn("destination 'github' is listed twice", output)


if __name__ == "__main__":
    unittest.main()
