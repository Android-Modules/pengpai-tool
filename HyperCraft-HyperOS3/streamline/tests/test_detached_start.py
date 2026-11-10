"""Exercise the real shell launcher with a deliberately slow setsid, without su or a phone."""
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[2]
ENV = ROOT / "streamline/src/main/assets/backend/env.sh"
OWNER = ROOT / "streamline/src/main/java/com/dalong/pengpaitool/ProcessOwnership.kt"


def quote(value):
    return "'" + str(value).replace("'", "'\\''") + "'"


class DetachedStartTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="hypercraft-detach-")
        self.folder = Path(self.temp.name)
        self.bin = self.folder / "bin"
        self.bin.mkdir()
        self.daemons = []
        self.environment = os.environ | {
            "PATH": str(self.bin) + ":" + os.environ["PATH"],
            "HYPERCRAFT_LAUNCH_DIR": str(self.folder / "ready"),
        }
        self.stub(
            "import time\ntime.sleep(.15)\n"
            "args=sys.argv[1:]\n"
            "if args[0]=='-w': args=args[1:]\n"
            "os.setsid()\nos.execvp(args[0],args)\n"
        )

    def tearDown(self):
        for pid in self.daemons:
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        self.temp.cleanup()

    def stub(self, body):
        path = self.bin / "setsid"
        path.write_text(f"#!{sys.executable}\nimport os,sys\n" + body)
        path.chmod(0o755)

    def command(self, command):
        # Use exactly the production wrapper, including its normal-exit group cleanup.
        script = re.search(r'val script = """(.*?)"""\.trimIndent\(\)', OWNER.read_text(), re.S).group(1)
        script = script.replace("${'$'}", "$").replace("${shq(command)}", quote(command))
        process = subprocess.Popen(
            ["/bin/sh", "-c", script], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, start_new_session=True, env=self.environment,
        )
        try:
            code = process.wait(timeout=4)
            process.stdin.close()
            time.sleep(.3)  # Let the ownership monitor finish its bounded group cleanup.
            return code, process.stdout.read().decode(), process.stderr.read().decode()
        finally:
            if not process.stdin.closed:
                process.stdin.close()
            if process.poll() is None:
                process.kill()
                process.wait(timeout=2)
            process.stdout.close()
            process.stderr.close()

    def assert_no_ready_files(self):
        base = self.folder / "ready"
        self.assertEqual([], list(base.iterdir()) if base.exists() else [])

    def test_slow_detach_survives_frontend_return_and_keeps_argv(self):
        marker = self.folder / "daemon.pid"
        argument = "space ' quote $variable"
        output = self.folder / "argument"
        body = f'echo $$ > {quote(marker)}; printf "%s" "$1" > {quote(output)}; exec sleep 30'
        command = f'. {quote(ENV)}; start_detached sh -c {quote(body)} fixture {quote(argument)}; echo started=$?'
        code, stdout, stderr = self.command(command)
        self.assertEqual((0, "started=0\n", ""), (code, stdout, stderr))
        pid = int(marker.read_text().strip())
        self.daemons.append(pid)
        self.assertEqual(pid, os.getsid(pid))
        self.assertEqual(argument, output.read_text())
        self.assert_no_ready_files()

    def test_short_lived_detached_target_is_acknowledged(self):
        marker = self.folder / "short"
        body = f'printf done > {quote(marker)}'
        result = self.command(f'. {quote(ENV)}; start_detached sh -c {quote(body)}; echo started=$?')
        self.assertEqual((0, "started=0\n", ""), result)
        self.assertEqual("done", marker.read_text())
        self.assert_no_ready_files()

    def test_launcher_failure_does_not_report_ready_or_leave_files(self):
        self.stub("sys.exit(17)\n")
        result = self.command(f'. {quote(ENV)}; start_detached sh -c "exit 0"; echo started=$?')
        self.assertEqual((0, "started=1\n", ""), result)
        self.assert_no_ready_files()


if __name__ == "__main__":
    unittest.main()
