"""Charging regression tests use temporary configuration and hardware nodes only."""
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import unittest

from shell_support import install_flock

MODULE = Path(__file__).resolve().parents[1]
BACKEND = MODULE / "src/main/assets/backend"
PROBE = MODULE / "src/debug/assets/device-probes/charge.sh"


class ChargeSafetyTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        install_flock(self.bin)
        self.env = {**os.environ, "PATH": f"{self.bin}:{os.environ['PATH']}"}
        self.state = self.root / "data/adb/hypercraft/state/charge"
        self.state.mkdir(parents=True)
        (self.state.parent / "config").write_text("unrelated_setting=keep\n")
        self.config = self.state / "config"
        self.thermal = self.root / "sys/class/xm_power/charger/charger_thermal"
        self.interface = self.thermal.parent / "charge_interface"
        self.thermal.mkdir(parents=True)
        self.interface.mkdir()
        for name, value in {"wired_thermal_remove": "0", "wired_chg_curr": "22000000",
                            "wired_ctrl_limit": "0"}.items():
            (self.thermal / name).write_text(value + "\n")
        for name in ("charge_enable", "input_suspend"):
            (self.interface / name).write_text("hypercraft all 1\n")

    def source(self):
        source = (BACKEND / "battery/charge.sh").read_text().split('\ncase "$1" in\n  status)', 1)[0]
        return source.replace('. "${0%/*}/../env.sh"', f'. "{BACKEND}/env.sh"').replace(
            "/data/adb", str(self.root / "data/adb")).replace("/sys/class", str(self.root / "sys/class"))

    def shell(self, code):
        return subprocess.run(["sh", "-c", code], env=self.env, text=True, capture_output=True, timeout=10)

    def prepare_probe(self, command):
        backend = self.root / "data/adb/hypercraft/runtime/adclean.sh"
        backend.parent.mkdir(exist_ok=True)
        backend.write_text('''#!/bin/sh
if [ "$*" != 'charge status' ]; then
  echo "Unexpected mutation: $*" >&2
  echo altered > "''' + str(self.config) + '''"
  exit 99
fi
''' + command)
        # Mirror DeviceToolWrites: stage both actual assets and run the probe by
        # path, so accidentally sourcing the old common write helper is covered.
        for source, name in ((PROBE, "device-write-charge.sh"),
                             (PROBE.parent / "common.sh", "device-write-common.sh")):
            (self.root / name).write_text(source.read_text().replace(
                "/data/adb", str(self.root / "data/adb")))
        return self.root / "device-write-charge.sh"

    def hardware_snapshot(self):
        return {str(p.relative_to(self.root)): p.read_bytes() for p in self.root.rglob("*")
                if p.is_file() and ("/sys/" in str(p) or "/state/" in str(p))}

    def test_device_probe_only_reads_existing_or_absent_configuration(self):
        for configuration in (None, "enable=0\nrecord=0\ntiers=999:500\n",
                              "enable=1\nrecord=1\ntiers=33:10000,999:2000\n"):
            for status_code in (0, 7):
                with self.subTest(configuration=configuration, status_code=status_code):
                    self.config.unlink(missing_ok=True)
                    if configuration is not None:
                        self.config.write_text(configuration)
                    probe = self.prepare_probe(f"echo 'enable=1'\nexit {status_code}\n")
                    before = self.hardware_snapshot()
                    result = subprocess.run(["sh", str(probe)], env=self.env, text=True,
                                            capture_output=True, timeout=10)
                    self.assertEqual(result.returncode, status_code, result.stderr)
                    self.assertIn("PROBE|charge|read-only", result.stdout)
                    self.assertEqual(self.hardware_snapshot(), before)

    def test_killing_device_probe_cannot_leave_test_limits(self):
        self.config.write_text("enable=1\nrecord=1\ntiers=33:10000,999:2000\n")
        ready = self.root / "ready"
        probe = self.prepare_probe(f'touch "{ready}"\nwhile :; do sleep 0.05; done\n')
        before = self.hardware_snapshot()
        process = subprocess.Popen(["sh", str(probe)], env=self.env, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, start_new_session=True)
        try:
            deadline = time.monotonic() + 5
            while not ready.exists() and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(ready.exists(), "Probe did not reach its status read")
        finally:
            os.killpg(process.pid, signal.SIGKILL)
            process.communicate(timeout=5)
        self.assertEqual(self.hardware_snapshot(), before)

    def test_saving_defaults_replaces_old_limit_and_persists(self):
        self.config.write_text("enable=1\nthermal=tier\ntiers=999:500\nrecord=0\n")
        code = self.source() + '''
load_conf
t=288; soc=15; scr=1; full=0; hot=0
control
cat "$N_CURR"
# Exercise the same save entry as the UI, without starting a daemon.
wake() { :; }
cmd_set tiers "$TIERS_DEF" >/dev/null
load_conf
control
cat "$N_CURR"
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), ["500000", "10000000"])
        result = self.shell(self.source() + '\nload_conf\necho "$c_tiers"\n')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "33:10000,55:6000,65:4000,999:2000")
        self.assertIn("enable=1\n", self.config.read_text())

    def test_leaving_tier_restores_limit_and_keeps_stop_protections(self):
        for transition in ("system", "ceiling", "hot", "full", "restore"):
            with self.subTest(transition=transition):
                self.config.write_text("enable=1\nthermal=tier\ntiers=999:500\nrecord=0\n")
                change = {"system": "c_thermal=system", "ceiling": "c_fast_soc=15",
                          "hot": "t=460", "full": "c_limit=15", "restore": ""}[transition]
                code = self.source() + f'''
load_conf
t=288; soc=15; scr=1; full=0; hot=0
control
{change}
{'cmd_restore' if transition == 'restore' else 'control'}
cat "$N_CURR" "$N_REMOVE" "$CI/charge_enable"
'''
                result = self.shell(code)
                self.assertEqual(result.returncode, 0, result.stderr)
                vote = "0" if transition in ("hot", "full") else "1"
                self.assertEqual(result.stdout.splitlines(), ["22000000", "0", f"hypercraft all {vote}"])


if __name__ == "__main__":
    unittest.main()
