"""Host-only regression tests; never execute Android settings or connect a phone."""
import pathlib
import subprocess
import tempfile
import unittest


SOURCE = pathlib.Path(__file__).with_name("adclean.sh").read_text()
DIAG = SOURCE.split("ADB_DIAG=$DATA/adb-diagnostics", 1)[1].split(
    "# ---------- 无线调试配对码", 1
)[0]


class DiagnosticsTest(unittest.TestCase):
    def shell(self, body):
        result = subprocess.run(
            ["sh"], input=DIAG + "\n" + body, text=True, capture_output=True
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def test_unknown_port(self):
        for port in ("", "-1", "0", "65536", "not-ready"):
            with self.subTest(port=port):
                self.assertEqual(self.shell(
                    "getprop() { printf '%s' '" + port + "'; }; adb_diag_connections"
                ), "unknown")

    def test_ipv4_and_ipv6_connections(self):
        self.assertEqual(self.shell("""
getprop() { echo 5555; }
cat() { printf '%s\\n' \
  '0: 00000000:15B3 00000000:0000 01' \
  '1: 00000000000000000000000000000000:15B3 00000000:0000 01' \
  '2: 00000000:15B3 00000000:0000 0A' \
  '3: 00000000:15B4 00000000:0000 01'; }
adb_diag_connections
"""), "2")

    def test_unreadable_sockets(self):
        self.assertEqual(self.shell(
            "getprop() { echo 5555; }; cat() { return 1; }; adb_diag_connections"
        ), "unknown")

    def test_event_rotation(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp)
            (path / "events.log").write_text(("x" * 600 + "\n") * 600)
            self.shell("ADB_DIAG='" + tmp + "'\n"
                       "stat() { echo 300000; }\nadb_diag_event regression")
            lines = (path / "events.log").read_text().splitlines()
            self.assertEqual(len(lines), 500)
            self.assertTrue(lines[-1].endswith(" regression"))

    def test_snapshot_never_writes_settings(self):
        snapshot = DIAG.split("adb_diag_snapshot()", 1)[1].split("adb_diag_pid()", 1)[0]
        self.assertNotIn("settings put", snapshot)
        self.assertNotIn("service call", snapshot)
        self.assertIn("tcp_connections|", snapshot)


if __name__ == "__main__":
    unittest.main()
