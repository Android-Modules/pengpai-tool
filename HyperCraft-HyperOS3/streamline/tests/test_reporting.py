"""Execute the production reporting controller against isolated PackageManager state."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import shutil

from shell_support import install_flock, install_stat

BACKEND = Path(__file__).resolve().parents[1] / "src/main/assets/backend"
HELPER = "com.mobiletools.systemhelper"
GUARD = "com.miui.guardprovider"


class ReportingTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        install_flock(self.bin)
        self.state = self.root / "reporting"
        self.packages = self.root / "packages"
        self.packages.mkdir()
        self.calls = self.root / "calls"
        self.runtime = self.root / "runtime"
        self.runtime.mkdir()
        # env.sh sets the Android PATH; the fixture supplies the same real flock
        # semantics and fake OS commands, never overriding controller functions.
        (self.runtime / "env.sh").write_text("")
        source = (BACKEND / "reporting.sh").read_text().replace(
            "/data/adb/hypercraft/state/reporting", str(self.state))
        (self.runtime / "reporting.sh").write_text(source)
        self.env = {**os.environ, "PATH": str(self.bin) + ":" + os.environ["PATH"]}
        self.command("pm", f'''#!/bin/sh
if [ "$1 $2" = 'list packages' ]; then
  [ ! -f "{self.root}/pm-failed" ] || exit 1
  echo package:android
  for file in "{self.packages}/"*; do [ ! -f "$file" ] || echo "package:${{file##*/}}"; done
  exit 0
fi
printf '%s\\n' "$*" >> "{self.calls}"
[ ! -f "{self.root}/fail-$1-$4" ] || exit 1
case "$1" in
  default-state) value=0 ;; enable) value=1 ;; disable) value=2 ;;
  disable-user) value=3 ;; disable-until-used) value=4 ;; *) exit 1 ;;
esac
printf '%s' "$value" > "{self.packages}/$4"
''')
        self.command("dumpsys", f'''#!/bin/sh
[ ! -f "{self.root}/dump-failed" ] || {{ echo 'unavailable'; exit 0; }}
[ -f "{self.packages}/$2" ] || exit 1
state=$(cat "{self.packages}/$2")
echo "  User 0: installed=true hidden=false stopped=false enabled=$state"
echo '  User 10: installed=true hidden=false enabled=1'
''')

    def command(self, name, source):
        file = self.bin / name
        file.write_text(source)
        file.chmod(0o700)

    def package(self, name, value):
        (self.packages / name).write_text(str(value))

    def run_controller(self, action):
        return subprocess.run(["sh", str(self.runtime / "reporting.sh"), action],
                              text=True, capture_output=True, env=self.env, timeout=10)

    def check_ok(self, action):
        result = self.run_controller(action)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("ERR|", result.stdout)
        return result

    def values(self):
        return {p.name: p.read_text() for p in self.packages.iterdir()}

    def test_all_five_enable_states_restore_exactly_and_repeated_on_keeps_baseline(self):
        for initial in range(5):
            with self.subTest(initial=initial):
                self.package(HELPER, initial)
                self.package(GUARD, 4 - initial)
                expected = self.values()
                self.check_ok("on")
                baseline = (self.state / "original").read_bytes()
                self.check_ok("on")
                self.assertEqual((self.state / "original").read_bytes(), baseline)
                self.assertEqual(self.values(), {HELPER: "3", GUARD: "3"})
                self.check_ok("off")
                self.assertEqual(self.values(), expected)
                self.assertFalse((self.state / "original").exists())

    def test_absent_target_and_qualcomm_helper_are_never_touched(self):
        self.package(GUARD, 0)
        self.package("com.qualcomm.qti.services.systemhelper", 1)
        self.package("com.xiaomi.simactivate.service", 1)
        self.check_ok("on")
        self.package(HELPER, 1)  # newly installed while active is outside our baseline
        self.check_ok("boot")
        self.assertEqual(self.values()[HELPER], "1")
        self.check_ok("off")
        self.assertEqual(self.values(), {GUARD: "0", HELPER: "1",
                         "com.qualcomm.qti.services.systemhelper": "1", "com.xiaomi.simactivate.service": "1"})
        self.assertNotIn("com.qualcomm", self.calls.read_text())
        self.assertNotIn("com.xiaomi.simactivate", self.calls.read_text())

    def test_missing_targets_or_failed_inventory_or_dump_never_write_packages(self):
        self.assertNotEqual(self.run_controller("on").returncode, 0)
        self.package(HELPER, 0)
        for failure in ("pm-failed", "dump-failed"):
            flag = self.root / failure
            flag.touch()
            result = self.run_controller("on")
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertFalse(self.calls.exists())
            self.assertNotEqual((self.state / "mode").read_text() if (self.state / "mode").exists() else "off", "on\n")
            flag.unlink()

    def test_partial_disable_failure_rolls_back_the_already_changed_package(self):
        self.package(HELPER, 1)
        self.package(GUARD, 0)
        (self.root / ("fail-disable-user-" + GUARD)).touch()
        self.assertNotEqual(self.run_controller("on").returncode, 0)
        self.assertEqual(self.values(), {HELPER: "1", GUARD: "0"})
        self.assertEqual((self.state / "mode").read_text(), "off\n")
        self.assertFalse((self.state / "original").exists())

    def test_restore_failure_keeps_journal_and_boot_finishes_recovery(self):
        self.package(HELPER, 0)
        self.package(GUARD, 2)
        self.check_ok("on")
        baseline = (self.state / "original").read_bytes()
        flag = self.root / ("fail-default-state-" + HELPER)
        flag.touch()
        self.assertNotEqual(self.run_controller("off").returncode, 0)
        self.assertEqual((self.state / "original").read_bytes(), baseline)
        self.assertEqual((self.state / "mode").read_text(), "restoring\n")
        self.assertNotEqual(self.run_controller("on").returncode, 0)
        flag.unlink()
        self.check_ok("boot")
        self.assertEqual(self.values(), {HELPER: "0", GUARD: "2"})
        self.assertFalse((self.state / "original").exists())

    def test_interrupted_enable_replays_without_changing_baseline(self):
        self.package(HELPER, 0)
        self.package(GUARD, 1)
        self.state.mkdir()
        (self.state / "original").write_text(f"{HELPER}|0\n{GUARD}|1\n")
        (self.state / "mode").write_text("on\n")
        self.package(HELPER, 3)
        self.check_ok("boot")
        self.assertEqual(self.values(), {HELPER: "3", GUARD: "3"})
        self.check_ok("off")
        self.assertEqual(self.values(), {HELPER: "0", GUARD: "1"})

    def test_stale_baseline_while_off_is_refreshed_before_new_enable(self):
        self.package(HELPER, 2)
        self.package(GUARD, 4)
        self.state.mkdir()
        (self.state / "original").write_text(f"{HELPER}|0\n{GUARD}|1\n")
        (self.state / "mode").write_text("off\n")
        self.check_ok("on")
        self.check_ok("off")
        self.assertEqual(self.values(), {HELPER: "2", GUARD: "4"})

    def test_corrupt_or_missing_journal_cannot_enable_arbitrary_package(self):
        self.package(HELPER, 0)
        self.state.mkdir()
        (self.state / "mode").write_text("on\n")
        self.assertNotEqual(self.run_controller("on").returncode, 0)
        for content in (f"{HELPER}|0\ncom.android.phone|0\n", f"{HELPER}|0\n{HELPER}|0\n",
                        f"{HELPER}|0\n{GUARD}|invalid\n"):
            (self.state / "original").write_text(content)
            self.assertNotEqual(self.run_controller("boot").returncode, 0)
            self.assertNotEqual(self.run_controller("off").returncode, 0)
            self.assertFalse(self.calls.exists())
            (self.state / "mode").write_text("on\n")

    def test_status_distinguishes_absent_disabled_and_read_failure(self):
        self.package(HELPER, 2)
        self.assertEqual(self.check_ok("status").stdout, "off|2|x\n")
        (self.root / "pm-failed").touch()
        result = self.run_controller("status")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "unknown|unknown|unknown\n")

    def test_silent_disable_failure_cannot_report_success(self):
        self.package(HELPER, 0)
        self.command("pm", f'''#!/bin/sh
if [ "$1 $2" = 'list packages' ]; then
  printf 'package:android\\npackage:{HELPER}\\n'
fi
exit 0
''')
        result = self.run_controller("on")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("OK|", result.stdout)
        self.assertEqual(self.values(), {HELPER: "0"})

    def test_disappeared_package_retains_recovery_state_until_it_returns(self):
        self.package(HELPER, 0)
        self.check_ok("on")
        (self.packages / HELPER).unlink()
        self.assertNotEqual(self.run_controller("off").returncode, 0)
        self.assertTrue((self.state / "original").exists())
        self.package(HELPER, 3)
        self.check_ok("off")
        self.assertEqual(self.values(), {HELPER: "0"})


class ReportingHostsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        install_flock(self.bin)
        install_stat(self.bin)
        self.runtime = self.root / "runtime"
        self.runtime.mkdir()
        shutil.copytree(BACKEND / "hosts", self.runtime / "hosts")
        self.hosts = self.root / "system-hosts"
        self.hosts.write_text("127.0.0.1 localhost\n")
        source, boundary, _ = (BACKEND / "adclean.sh").read_text().rpartition('\ncase "$1" in\n')
        assert boundary
        self.source = source.replace('MODDIR=${0%/*}', f'MODDIR="{self.runtime}"', 1).replace(
            '. "$MODDIR/env.sh"', f'. "{BACKEND}/env.sh"\nexport PATH="{self.bin}:$PATH"').replace(
            "/data/adb", str(self.root / "adb")).replace("/system/etc/hosts", str(self.hosts))
        self.source += '\nchcon() { :; }\nbk_sync() { :; }\nin_init_ns() { "$@"; }\n'

    def shell(self, code):
        return subprocess.run(["sh", "-c", self.source + "\n" + code], capture_output=True,
                              text=True, timeout=10)

    def test_dual_stack_rules_restore_without_removing_other_group(self):
        result = self.shell('''
conf_set hosts_reporting 1
conf_set hosts_antifraud 1
hosts_build || exit 1
cp "$HOSTS" "''' + str(self.hosts) + '''"
cmd_reporting_hosts_status
conf_set hosts_reporting 0
hosts_build || exit 1
cp "$HOSTS" "''' + str(self.hosts) + '''"
cmd_reporting_hosts_status
''')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual([s.split('|')[::2] for s in result.stdout.splitlines()], [["1", "3"], ["0", "0"]])
        hosts = self.hosts.read_text()
        self.assertIn("0.0.0.0 fraud-detection.engine.miui.com", hosts)
        self.assertIn("0.0.0.0 tracking.miui.com", hosts)
        self.assertNotIn("dm.wo.com.cn", hosts)
        self.assertNotIn("flash.sec.miui.com", hosts)

    def test_status_rejects_ipv4_only_rules_and_conflicting_mappings(self):
        for address in ("0.0.0.0", "::"):
            self.hosts.write_text(f"{address} dm.wo.com.cn\n")
            result = self.shell("cmd_reporting_hosts_status")
            self.assertEqual(result.stdout.split('|')[2].strip(), "0")
        self.hosts.write_text("0.0.0.0 dm.wo.com.cn\n:: dm.wo.com.cn\n192.0.2.1 dm.wo.com.cn\n")
        self.assertEqual(self.shell("cmd_reporting_hosts_status").stdout.split('|')[2].strip(), "0")
        self.hosts.write_text("0.0.0.0 dm.wo.com.cn\n:: dm.wo.com.cn # known rule\n")
        self.assertEqual(self.shell("cmd_reporting_hosts_status").stdout.split('|')[2].strip(), "1")

    def test_missing_rule_file_and_mount_failure_never_report_success(self):
        (self.runtime / "hosts/reporting.txt").unlink()
        result = self.shell("cmd_hosts_set reporting 1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ERR|reporting|", result.stdout)
        self.assertNotIn("OK|", result.stdout)
        shutil.copyfile(BACKEND / "hosts/reporting.txt", self.runtime / "hosts/reporting.txt")
        result = self.shell("hosts_mount() { return 1; }; cmd_hosts_set reporting 1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("未确认生效", result.stdout)

    def test_invalid_group_or_value_never_enables_reporting(self):
        for cmd in ("cmd_hosts_set reporting anything", "cmd_hosts_set unknown 1"):
            result = self.shell(cmd)
            self.assertIn("ERR|", result.stdout)
            self.assertFalse((self.root / "adb/hypercraft/state/config").exists())

    def test_uninstall_propagates_reporting_restore_failure_and_continues_other_cleanup(self):
        calls = self.root / "restored"
        (self.runtime / "reporting.sh").write_text("#!/bin/sh\necho 'ERR|reporting|restore failed'\nexit 1\n")
        state = self.root / "adb/hypercraft/state/reporting"
        state.mkdir(parents=True)
        (state / "mode").write_text("on\n")
        # A lost baseline must also trigger reporting-off and an honest failure.
        result = self.shell('''
CHG=/dev/null; CYCLE=/dev/null; DM=/dev/null
pgrep() { return 1; }; ota_watch_stop() { :; }
cmd_restore_all() { :; }; cmd_cpu_enable() { :; }
cmd_fps_set() { echo restored > "''' + str(calls) + '''"; }
hosts_mounted() { return 1; }
cmd_uninstall_restore
''')
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("ERR|reporting|", result.stdout)
        self.assertTrue(calls.exists())
        self.assertEqual((state / "mode").read_text(), "on\n")


if __name__ == "__main__":
    unittest.main()
