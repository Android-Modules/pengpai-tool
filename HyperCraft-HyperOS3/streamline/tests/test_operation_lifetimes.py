"""Exercise real backend ownership with host kernel locks and stub Android services."""
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

from shell_support import install_flock, install_stat, wait_file

BACKEND = Path(__file__).resolve().parents[1] / "src/main/assets/backend"
SOURCE = (BACKEND / "adclean.sh").read_text()


def function(name):
    start = SOURCE.index(name + "() ")
    close = "\n)" if SOURCE[start + len(name + "() ")] == "(" else "\n}"
    return SOURCE[start:SOURCE.index(close, start) + len(close)] + "\n"


class OperationLifetimesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="hypercraft-lifetime-")
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.env = os.environ | {"PATH": str(self.bin) + ":" + os.environ["PATH"],
                                 "FIXTURE": str(self.root),
                                 "HYPERCRAFT_LAUNCH_DIR": str(self.root / "ready")}
        install_flock(self.bin)
        install_stat(self.bin)
        self.stub("setsid", "args=sys.argv[1:]\nif args[0]=='-w': args=args[1:]\nos.setsid()\nos.execvp(args[0],args)\n")
        self.processes = []

    def tearDown(self):
        (self.root / "release").touch()
        for process in self.processes:
            try:
                process.communicate(timeout=3)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate(timeout=2)
        # Detached compile runners finish after release; wait for their owned lock to close.
        if (self.root / "dexopt.flock").exists():
            result = self.run_shell('exec 6>>"$FIXTURE/dexopt.flock"; flock -x 6 6>&6')
            self.assertEqual(0, result.returncode, result.stderr)
        self.temp.cleanup()

    def stub(self, name, body):
        path = self.bin / name
        path.write_text(f"#!{sys.executable}\nimport os,sys,time\nfrom pathlib import Path\nroot=Path(os.environ['FIXTURE'])\n" + body)
        path.chmod(0o700)

    def run_shell(self, code):
        return subprocess.run(["sh", "-c", code], env=self.env, capture_output=True, text=True, timeout=8)

    def dexopt_script(self):
        code = f'. "{BACKEND}/env.sh"\nexport PATH="{self.bin}:$PATH"\n'
        code += 'DATA="$FIXTURE"; MODDIR="$FIXTURE"; DEXOPT_QUEUE="$DATA/dexopt.queue"; DEXOPT_LOG="$DATA/dexopt.log"\n'
        code += 'valid_pkg() { return 0; }; log() { :; }\n'
        code += function("cmd_dexopt") + function("cmd_dexopt_run")
        code += 'case "$1" in dexopt-run) shift; cmd_dexopt_run "$@";; fail) start_detached() { return 1; }; cmd_dexopt bg;; *) cmd_dexopt bg;; esac\n'
        script = self.root / "adclean.sh"
        script.write_text(code)
        return script

    def test_concurrent_compile_requests_start_one_runner_and_keep_lock_until_done(self):
        self.stub("cmd", "(root/'running').touch()\nwith (root/'calls').open('a') as f: f.write('compile\\n')\nwhile not (root/'release').exists(): time.sleep(.02)\nprint('Success')\n")
        script = self.dexopt_script()
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: self.run_shell(f'sh "{script}" start'), range(8)))
        self.assertEqual(1, sum('OK|dexopt|' in result.stdout for result in results), results)
        self.assertEqual(7, sum('ERR|dexopt|已有编译任务' in result.stdout for result in results), results)
        wait_file(self.root / "running")
        self.assertEqual(["compile"], (self.root / "calls").read_text().splitlines())
        # The successful launcher has exited; the detached runner still owns the same lock.
        rejected = self.run_shell(f'sh "{script}" start')
        self.assertIn("已有编译任务", rejected.stdout)
        (self.root / "release").touch()
        self.assertEqual(0, self.run_shell('exec 6>>"$FIXTURE/dexopt.flock"; flock -x 6 6>&6').returncode)
        retry = self.run_shell(f'sh "{script}" start')
        self.assertIn("OK|dexopt|", retry.stdout)

    def test_failed_compile_launcher_does_not_claim_success_or_hold_lock(self):
        script = self.dexopt_script()
        result = self.run_shell(f'sh "{script}" fail')
        self.assertNotEqual(0, result.returncode)
        self.assertIn("后台编译启动失败", result.stdout)
        self.assertNotIn("OK|", result.stdout)
        self.assertEqual(0, self.run_shell('exec 6>>"$FIXTURE/dexopt.flock"; flock -xn 6 6>&6').returncode)

    def split_script(self, mode):
        self.stub("pm", """
command=sys.argv[1]
with (root/'pm.calls').open('a') as f: f.write(command+'\\n')
if command=='install-create': print('Success: created install session [71]')
elif command=='install-write':
    sys.stdin.buffer.read()
    (root/'writing').touch()
    if os.environ.get('WRITE_MODE')=='wait':
        while not (root/'release').exists(): time.sleep(.02)
    if os.environ.get('WRITE_MODE')=='fail':
        print('Failure [fixture write error]'); sys.exit(1)
    print('Success')
else: print('Success')
""")
        self.env["WRITE_MODE"] = mode
        apk = self.root / "base.apk"
        apk.write_bytes(b"apk payload")
        return function("restore_split_apks") + f'restore_split_apks "{apk}"\n'

    def test_split_install_cancel_abandons_system_session(self):
        process = subprocess.Popen(["sh", "-c", self.split_script("wait")], env=self.env,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                   start_new_session=True)
        self.processes.append(process)
        wait_file(self.root / "writing", process)
        os.killpg(process.pid, signal.SIGTERM)
        process.communicate(timeout=5)
        self.assertEqual(["install-create", "install-write", "install-abandon"],
                         (self.root / "pm.calls").read_text().splitlines())
        self.assertNotEqual(0, process.returncode)

    def test_split_install_failure_abandons_and_preserves_error(self):
        result = self.run_shell(self.split_script("fail"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("fixture write error", result.stdout)
        self.assertEqual(["install-create", "install-write", "install-abandon"],
                         (self.root / "pm.calls").read_text().splitlines())

    def test_split_install_success_commits_without_abandon(self):
        result = self.run_shell(self.split_script("ok"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["install-create", "install-write", "install-commit"],
                         (self.root / "pm.calls").read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
