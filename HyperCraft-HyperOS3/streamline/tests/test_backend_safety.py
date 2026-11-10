"""回归配置隔离、保护名单和充电默认状态；所有节点均为临时文件。"""
from concurrent.futures import ThreadPoolExecutor
import os
import signal
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from shell_support import install_flock, install_stat

BACKEND = Path(__file__).resolve().parents[1] / "src/main/assets/backend"


class BackendSafetyTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        install_flock(self.bin)
        install_stat(self.bin)
        self.env = {**os.environ, "PATH": str(self.bin) + ":" + os.environ["PATH"]}

    def shell(self, code, *args):
        return subprocess.run(["sh", "-c", code, "test", *args], env=self.env,
                              text=True, capture_output=True, timeout=10)

    def test_concurrent_config_writes_preserve_all_keys(self):
        config = self.root / "config"
        config.write_text("existing=keep\n")
        # 调用生产入口，包含真实 RMW 和 fcntl.flock，不能用总返回成功的 stub。
        code = f'. "{BACKEND}/env.sh"\nexport PATH="{self.bin}:$PATH"\nconfig_set "$1" "$2" "$3"'
        with ThreadPoolExecutor(max_workers=16) as pool:
            results = list(pool.map(lambda i: self.shell(code, str(config), f"key_{i}", str(i)), range(40)))
        for result in results:
            self.assertEqual(result.returncode, 0, result.stderr)
        values = dict(line.split("=", 1) for line in config.read_text().splitlines())
        self.assertEqual(values, {"existing": "keep", **{f"key_{i}": str(i) for i in range(40)}})

    def test_first_write_and_empty_filter_result_succeed(self):
        config = self.root / "config"
        code = f'. "{BACKEND}/env.sh"\nexport PATH="{self.bin}:$PATH"\nconfig_set "$1" key "$2"'
        for value in ("one", "two words=three"):
            result = self.shell(code, str(config), value)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(config.read_text(), f"key={value}\n")

    def backend_functions(self):
        # The first dispatch branch can change as commands are added. Extract the
        # final top-level dispatcher rather than relying on a particular command.
        source, boundary, dispatch = (BACKEND / "adclean.sh").read_text().rpartition('\ncase "$1" in\n')
        self.assertTrue(boundary, "Production backend dispatcher is missing")
        self.assertIn("info)", dispatch)
        runtime = self.root / "function-runtime"
        runtime.mkdir(exist_ok=True)
        return source.replace('MODDIR=${0%/*}', f'MODDIR="{runtime}"', 1).replace(
            '. "$MODDIR/env.sh"', f'. "{BACKEND}/env.sh"').replace(
            "/data/adb", str(self.root / "adb"))

    def test_boot_replay_cannot_disable_protected_hosts_or_dependencies(self):
        state = self.root / "adb/hypercraft/state"
        state.mkdir(parents=True)
        protected = ("android", "com.android.systemui", "com.android.settings", "com.miui.home",
                     "com.miui.personalassistant", "io.github.dalong.hypercraft",
                     "io.github.dalong.hyperhome", "com.dalong.pengpaitool.hyperos4")
        records = [f"{pkg}|{mode}|e|1" for pkg in protected for mode in ("disable", "uninstall")]
        records += ["com.example.allowed|disable|e|1", "com.example.uninstall|uninstall|e|1"]
        (state / "applied.list").write_text("\n".join(records) + "\n")
        calls = self.root / "pm-calls"
        code = self.backend_functions() + f'''
legacy_active() {{ return 0; }}
guard_start() {{ :; }}
wlog_start() {{ :; }}
rot_start() {{ :; }}
adb_diag_start() {{ :; }}
fps_boot() {{ :; }}
CHG=/dev/null
load_lists() {{ :; }}
pkg_state() {{ echo e; }}
pm() {{ printf '%s\\n' "$*" >> "{calls}"; }}
log() {{ :; }}
bk_ready() {{ return 0; }}
bk_sync() {{ :; }}
cmd_boot
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(calls.read_text().splitlines(), [
            "disable-user --user 0 com.example.allowed", "uninstall -k --user 0 com.example.uninstall"])

    def test_first_boot_does_not_import_or_enable_legacy_module_controls(self):
        modules = self.root / "adb/modules"
        fixtures = {
            "changephone/state/config": "enabled=1\nmodel=legacy\n",
            "fake_battery_cycle/config.txt": "MODE=fixed\nFIXED=20\n",
            "baize_remote_cpu_guard/config.conf": "CPU_THRESHOLD=20\nCHECK_INTERVAL=2\n",
        }
        for relative, value in fixtures.items():
            file = modules / relative
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(value)
        guard_state = self.root / "guard-state"
        # 使用生产 cmd_boot 和 cmd_migrate；模拟外围硬件动作，不覆盖迁移函数。
        code = self.backend_functions() + f'''
legacy_active() {{ return 0; }}
guard_start() {{ conf_get guard_enable 0 > "{guard_state}"; }}
wlog_start() {{ :; }}
rot_start() {{ :; }}
adb_diag_start() {{ :; }}
fps_boot() {{ :; }}
CHG=/dev/null
load_lists() {{ :; }}
log() {{ :; }}
bk_ready() {{ return 0; }}
bk_sync() {{ :; }}
cmd_boot
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        data = self.root / "adb/hypercraft/state"
        for relative in ("devmodel/config", "battery/config.txt", "guard"):
            self.assertFalse((data / relative).exists(), relative)
        self.assertEqual(guard_state.read_text(), "0\n")
        for relative, value in fixtures.items():
            self.assertEqual((modules / relative).read_text(), value)



    def test_import_skips_protected_records(self):
        backup = self.root / "backup"
        backup.mkdir()
        (backup / "applied.list").write_text("io.github.dalong.hypercraft|disable|e|1\n"
            "com.miui.personalassistant|uninstall|e|1\ncom.example.allowed|disable|e|1\n")
        result = self.shell(self.backend_functions() + f'\nbk_dir() {{ echo "{backup}"; }}\ncmd_import')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        records = (self.root / "adb/hypercraft/state/applied.list").read_text()
        self.assertEqual(records, "com.example.allowed|disable|e|1\n")

    def test_restore_never_refreezes_protected_component_from_old_record(self):
        state = self.root / "adb/hypercraft/state"
        state.mkdir(parents=True)
        (state / "applied.list").write_text("io.github.dalong.hypercraft|disable|d|1\n")
        calls = self.root / "pm-calls"
        code = self.backend_functions() + f'''
pm() {{
  case "$1" in
    list) case " $* " in *" -d "*) : ;; *) echo package:io.github.dalong.hypercraft ;; esac ;;
    *) printf '%s\\n' "$*" >> "{calls}" ;;
  esac
}}
log() {{ :; }}
restore_one io.github.dalong.hypercraft
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(calls.read_text().splitlines(), ["enable --user 0 io.github.dalong.hypercraft"])
        self.assertIn("受保护组件，已保持启用", result.stdout)
        self.assertEqual((state / "applied.list").read_text(), "")

    def test_restore_failure_preserves_record_even_if_package_is_installed(self):
        state = self.root / "adb/hypercraft/state"
        state.mkdir(parents=True)
        record = "com.example.frozen|disable|e|1\n"
        (state / "applied.list").write_text(record)
        code = self.backend_functions() + '''
pm() {
  case "$1" in
    list) echo package:com.example.frozen ;;
    enable) echo 'SecurityException: denied'; return 1 ;;
  esac
}
log() { :; }
restore_one com.example.frozen
'''
        result = self.shell(code)
        self.assertIn("ERR|com.example.frozen|", result.stdout)
        self.assertIn("SecurityException: denied", result.stdout)
        self.assertEqual((state / "applied.list").read_text(), record)

    def test_apply_protected_system_ui_has_diagnostic_and_cannot_disable_it(self):
        calls = self.root / "pm-calls"
        code = self.backend_functions() + f'''
pm() {{
  printf '%s\\n' "$*" >> "{calls}"
  case "$1" in list) echo package:com.android.systemui ;; esac
}}
bk_sync() {{ :; }}
cmd_apply disable com.android.systemui
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("ERR|com.android.systemui|受保护组件", result.stdout)
        self.assertNotIn("disable-user", calls.read_text())

    def test_cancelled_package_write_keeps_recovery_record_before_binder_returns(self):
        simulated = self.root / "package-state"
        ready = self.root / "binder-applied"
        simulated.write_text("e")
        code = self.backend_functions() + f'''
pm() {{
  case "$1" in
    list)
      case " $* " in *" -d "*) [ "$(cat "{simulated}")" = d ] && echo package:com.example.fixture ;; *) echo package:com.example.fixture ;; esac
      return 0 ;;
    disable-user)
      printf d > "{simulated}"
      touch "{ready}"
      sleep 30
      echo disabled ;;
    enable) printf e > "{simulated}" ;;
  esac
}}
backup_apk() {{ :; }}
am() {{ :; }}
log() {{ :; }}
bk_sync() {{ :; }}
'''
        process = subprocess.Popen(["sh", "-c", code + "\ncmd_apply disable com.example.fixture"],
                                   env=self.env, start_new_session=True, text=True,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.addCleanup(lambda: process.poll() is None and os.killpg(process.pid, signal.SIGKILL))
        from shell_support import wait_file
        wait_file(ready, process)
        os.killpg(process.pid, signal.SIGTERM)
        process.communicate(timeout=5)
        record = self.root / "adb/hypercraft/state/applied.list"
        self.assertTrue(record.read_text().startswith("com.example.fixture|disable|e|"))
        self.assertEqual(simulated.read_text(), "d")
        restored = self.shell(code + "\nrestore_one com.example.fixture")
        self.assertIn("OK|com.example.fixture|已恢复", restored.stdout)
        self.assertEqual(simulated.read_text(), "e")
        self.assertEqual(record.read_text(), "")

    def test_apk_restore_streams_backup_instead_of_exposing_private_file_path(self):
        backup = self.root / "backup/apk/com.example.restore"
        backup.mkdir(parents=True)
        payload = b"synthetic-apk-for-stdin-regression"
        (backup / "base.apk").write_bytes(payload)
        received, args = self.root / "received", self.root / "arguments"
        code = self.backend_functions() + f'''
bk_dir() {{ echo "{self.root / 'backup'}"; }}
pm() {{ [ -p /dev/stdin ] || return 2; printf '%s\\n' "$*" > "{args}"; cat > "{received}"; echo Success; }}
restore_from_apk com.example.restore
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(received.read_bytes(), payload)
        self.assertEqual(args.read_text().strip(), f"install -r -d --user 0 -S {len(payload)} -- -")

    def test_parallel_apply_is_one_transaction_with_original_recovery_state(self):
        state = self.root / "package-state"
        state.write_text("e")
        calls = self.root / "writes"
        code = self.backend_functions() + f'''
pm() {{
  case "$1" in
    list)
      case " $* " in *" -d "*) [ "$(cat "{state}")" = d ] && echo package:com.example.fixture ;; *) echo package:com.example.fixture ;; esac
      return 0 ;;
    disable-user)
      printf '%s\\n' "$*" >> "{calls}"
      sleep 0.04
      printf d > "{state}"
      echo disabled ;;
  esac
}}
backup_apk() {{ :; }}
am() {{ :; }}
log() {{ :; }}
bk_sync() {{ :; }}
cmd_apply disable com.example.fixture
'''
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: self.shell(code), range(8)))
        for result in results:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(calls.read_text().splitlines()), 1)
        applied = self.root / "adb/hypercraft/state/applied.list"
        self.assertEqual(len(applied.read_text().splitlines()), 1)
        self.assertTrue(applied.read_text().startswith("com.example.fixture|disable|e|"))

    def test_parallel_recovery_and_export_preserve_every_other_record(self):
        data = self.root / "adb/hypercraft/state"
        data.mkdir(parents=True)
        packages = [f"com.example.fixture{i}" for i in range(16)]
        applied = data / "applied.list"
        applied.write_text("".join(f"{p}|disable|e|1\n" for p in packages))
        backup = self.root / "backup"
        code = self.backend_functions() + f'''
pm() {{
  case "$1" in
    list) case " $* " in *" -d "*) : ;; *) printf '%s\\n' {" ".join('package:' + p for p in packages)} ;; esac ;;
    enable) return 0 ;;
  esac
}}
log() {{ :; }}
bk_dir() {{ printf '%s\\n' "{backup}"; }}
bk_ready() {{ return 0; }}
bk_fix() {{ :; }}
cmd_restore "$1"
'''
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda p: self.shell(code, p), packages))
        for result in results:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("|已恢复", result.stdout)
        self.assertEqual(applied.read_text(), "")
        self.assertEqual((backup / "applied.list").read_text(), "")
        self.assertEqual(list(backup.glob(".sync.*")), [])

    def test_failed_split_apk_write_abandons_session(self):
        backup = self.root / "backup/apk/com.example.restore"
        backup.mkdir(parents=True)
        for name in ("base.apk", "split_config.apk"):
            (backup / name).write_text(name)
        calls = self.root / "calls"
        code = self.backend_functions() + f'''
bk_dir() {{ echo "{self.root / 'backup'}"; }}
pm() {{
  printf '%s\\n' "$*" >> "{calls}"
  case "$1" in
    install-create) echo 'Success: created install session [123]' ;;
    install-write) cat >/dev/null; echo 'Failure: synthetic write error'; return 1 ;;
  esac
}}
restore_from_apk com.example.restore
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 1)
        commands = calls.read_text()
        self.assertIn("install-write -S 8 123 base.apk -", commands)
        self.assertIn("install-abandon 123", commands)
        self.assertNotIn("install-commit", commands)

    def test_guard_status_without_state_is_clean_and_defaults_off(self):
        result = self.shell(self.backend_functions() + "\ncmd_guard_status")
        self.assertEqual(result.stderr, "")
        self.assertEqual(result.stdout, "0|0|100|5|0|||0\n")

    def tweak_fixture(self, original="1", mode="normal", with_record=True):
        data = self.root / "adb/hypercraft/state"
        data.mkdir(parents=True)
        baseline = data / "tweaks.bak"
        baseline.write_text(f"debuglog|{original}\n" if with_record else "")
        state = self.root / "setting-state"
        state.write_text("0" if with_record else original)
        calls = self.root / "setting-writes"
        get_body = "return 1" if mode == "get-fail" else f'cat "{state}"'
        write_body = ("return 1" if mode == "write-fail" else "return 0" if mode == "silent" else
                      f'''if [ "$1" = delete ]; then printf null > "{state}"; else printf '%s' "$4" > "{state}"; fi''')
        code = self.backend_functions() + f'''
settings() {{
  case "$1" in
    get) {get_body} ;;
    put|delete)
      printf '%s\\n' "$*" >> "{calls}"
      {write_body} ;;
  esac
}}
log() {{ :; }}
'''
        return code, baseline, state, calls

    def test_tweak_restore_write_failure_retains_original_record(self):
        code, baseline, state, _ = self.tweak_fixture(mode="write-fail")
        result = self.shell(code + "\ntweak_off debuglog")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ERR|debuglog|", result.stdout)
        self.assertEqual(baseline.read_text(), "debuglog|1\n")
        self.assertEqual(state.read_text(), "0")

    def test_tweak_restore_silent_failure_retains_original_record(self):
        code, baseline, state, _ = self.tweak_fixture(mode="silent")
        result = self.shell(code + "\ntweak_off debuglog")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ERR|debuglog|", result.stdout)
        self.assertEqual(baseline.read_text(), "debuglog|1\n")
        self.assertEqual(state.read_text(), "0")

    def test_tweak_restore_success_retires_record_after_exact_readback(self):
        code, baseline, state, _ = self.tweak_fixture()
        result = self.shell(code + "\ntweak_off debuglog")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("OK|debuglog|", result.stdout)
        self.assertEqual(baseline.read_text(), "")
        self.assertEqual(state.read_text(), "1")

    def test_tweak_restore_without_record_does_not_change_real_setting(self):
        code, _, state, calls = self.tweak_fixture(original="7", with_record=False)
        result = self.shell(code + "\ntweak_off debuglog")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(state.read_text(), "7")
        self.assertFalse(calls.exists())

    def test_tweak_enable_refuses_unreadable_original_without_writing(self):
        code, baseline, state, calls = self.tweak_fixture(mode="get-fail", with_record=False)
        result = self.shell(code + "\ntweak_on debuglog")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ERR|debuglog|", result.stdout)
        self.assertEqual(baseline.read_text(), "")
        self.assertEqual(state.read_text(), "1")
        self.assertFalse(calls.exists())

    def test_tweak_enable_silent_failure_keeps_write_ahead_baseline(self):
        code, baseline, state, _ = self.tweak_fixture(mode="silent", with_record=False)
        result = self.shell(code + "\ntweak_on debuglog")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ERR|debuglog|", result.stdout)
        self.assertEqual(baseline.read_text(), "debuglog|1\n")
        self.assertEqual(state.read_text(), "1")

    def test_parallel_tweak_enable_preserves_first_empty_original_and_restores_it(self):
        code, baseline, state, _ = self.tweak_fixture(original="", with_record=False)
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: self.shell(code + "\ntweak_on debuglog"), range(8)))
        for result in results:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(baseline.read_text(), "debuglog|\n")
        self.assertEqual(state.read_text(), "0")
        result = self.shell(code + "\ntweak_off debuglog")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(baseline.read_text(), "")
        self.assertEqual(state.read_text(), "")

    def test_daemon_ownership_is_atomic_and_released_on_exit(self):
        lock, owner, release = self.root / "daemon.lock", self.root / "owner", self.root / "release"
        code = f'. "{BACKEND}/env.sh"\nexport PATH="{self.bin}:$PATH"\ndaemon_lock "$1" || exit 42\necho $$ > "$2"\nwhile [ ! -f "$3" ]; do sleep 0.02; done'
        process = subprocess.Popen(["sh", "-c", code, "test", str(lock), str(owner), str(release)],
                                   env=self.env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.addCleanup(lambda: process.poll() is None and process.kill())
        import time
        deadline = time.monotonic() + 5
        while not owner.exists() and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertTrue(owner.exists())
        contender = self.shell(f'. "{BACKEND}/env.sh"\nexport PATH="{self.bin}:$PATH"\ndaemon_lock "$1"', str(lock))
        self.assertNotEqual(contender.returncode, 0)
        release.touch()
        process.communicate(timeout=5)
        after = self.shell(f'. "{BACKEND}/env.sh"\nexport PATH="{self.bin}:$PATH"\ndaemon_lock "$1"', str(lock))
        self.assertEqual(after.returncode, 0, after.stderr)

    def test_active_charge_csv_is_bounded_without_losing_cursor_or_integrals(self):
        source = (BACKEND / "battery/charge.sh").read_text().split('\ncase "$1" in\n  status)', 1)[0]
        source = source.replace('. "${0%/*}/../env.sh"', f'. "{BACKEND}/env.sh"').replace(
            "/data/adb", str(self.root / "adb"))
        log = self.root / "csv"
        log.mkdir()
        csv = log / "1000.csv"
        # 1000mA、4000mV 每2秒，电量/平均功率应在多次降采样后仍守恒。
        csv.write_text("".join(f"{1000 + i * 2},50,1000,4000,300,300,1000,0,0,1,0,system\n" for i in range(200)))
        code = source + f'''
LOG="{log}"
sess=1000
SESSION_ROWS=8
rec_rows=$(wc -l < "$LOG/$sess.csv")
compact_session
cmd_sessions
cmd_session 1000 1380
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        lines = csv.read_text().splitlines()
        self.assertLess(len(lines), 8)
        self.assertTrue(lines[0].startswith("1000,"))
        self.assertTrue(lines[-1].startswith("1398,"))
        summary = result.stdout.splitlines()[0].split("|")
        self.assertEqual(summary[0:3], ["1000", "1000", "1398"])
        self.assertAlmostEqual(float(summary[6]), 4.0, places=1)
        self.assertAlmostEqual(int(summary[10]), 110, delta=1)
        self.assertTrue(result.stdout.splitlines()[-1].startswith("1398,"))

    def legacy_functions(self):
        source = (BACKEND / "legacy.sh").read_text().split('\ncase "${1:-status}" in', 1)[0]
        return source.replace('. "$SELF_DIR/env.sh"', f'. "{BACKEND}/env.sh"').replace(
            "/data/adb", str(self.root / "adb")).replace("/dev/hyperos4-streamline", str(self.root / "dev/old"))

    def test_passive_legacy_configuration_does_not_block_new_runtime(self):
        old = self.root / "adb/hyperos4-streamline/state"
        old.mkdir(parents=True)
        (old / "config").write_text("cpu_enable=0\n")
        result = self.shell(self.legacy_functions() + "\nlegacy_check")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_legacy_startup_is_detected_and_explicitly_archived_with_original_path(self):
        entry = self.root / "adb/service.d/hyperos4-streamline.sh"
        entry.parent.mkdir(parents=True)
        contents = f'#!/system/bin/sh\nexec sh "{self.root}/adb/hyperos4-streamline/runtime/boot.sh" boot\n'
        entry.write_text(contents)
        before = self.shell(self.legacy_functions() + "\nlegacy_check")
        self.assertEqual(before.returncode, 1)
        result = self.shell(self.legacy_functions() + "\nid() { echo 0; }\nlegacy_deactivate\nlegacy_check")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(entry.exists())
        records = list((self.root / "adb/hypercraft/state/legacy-disabled").glob("*/entries.tsv"))
        self.assertEqual(len(records), 1)
        original, saved = records[0].read_text().strip().split("\t")
        self.assertEqual(original, str(entry))
        self.assertEqual(Path(saved).read_text(), contents)

    def test_cpu_write_preserves_node_permissions_and_restores_exact_limit(self):
        policy = self.root / "cpufreq/policy0"
        policy.mkdir(parents=True)
        node = policy / "scaling_max_freq"
        node.write_text("2000000\n")
        node.chmod(0o640)
        code = self.backend_functions() + f'''
export PATH="{self.bin}:$PATH"
CPUFREQ="{policy.parent}"
cpu_limit policy0 1000000
printf 'limit=%s\\n' "$(cat "$CPUFREQ/policy0/scaling_max_freq")"
cpu_unlimit policy0
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout, "limit=1000000\n")
        self.assertEqual(node.read_text(), "2000000\n")
        self.assertEqual(node.stat().st_mode & 0o777, 0o640)
        config = self.root / "adb/hypercraft/state/config"
        self.assertIn("cpu_policy0_orig=0", config.read_text())

    def test_cpu_restore_failure_keeps_original_limit_for_retry(self):
        state = self.root / "adb/hypercraft/state"
        state.mkdir(parents=True)
        (state / "config").write_text("cpu_policy0_orig=2000000\n")
        result = self.shell(self.backend_functions() + "\ncpu_write() { return 1; }\ncpu_unlimit policy0")
        self.assertEqual(result.returncode, 1)
        self.assertEqual((state / "config").read_text(), "cpu_policy0_orig=2000000\n")

    def component_fixture(self):
        nodes = self.root / "components"
        nodes.mkdir()
        for name, state in (("com.example.A", "1"), ("com.example.B", "2"), ("com.example.C", "0")):
            (nodes / name).write_text(state)
        code = self.backend_functions() + f'''
dumpsys() {{
  echo '    User 0: ceDataInode=1 installed=true enabled=0'
  echo '      disabledComponents:'
  for dc in "{nodes}/"*; do [ "$(cat "$dc")" = 2 ] && echo "        ${{dc##*/}}"; done
  echo '      enabledComponents:'
  for dc in "{nodes}/"*; do [ "$(cat "$dc")" = 1 ] && echo "        ${{dc##*/}}"; done
  echo '    User 10: ceDataInode=0 installed=false enabled=0'
  echo '      disabledComponents:'
  echo '        com.example.A'
  echo '  Permission Manager:'
  echo '    User 0:'
  echo '      runtime permissions:'
  echo '        android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SET ]'
  echo '    User 10:'
  echo '      runtime permissions:'
  echo '        android.permission.POST_NOTIFICATIONS: granted=true, flags=[ SYSTEM_FIXED ]'
}}
pm() {{
  case "$1" in default-state) cs=0 ;; enable) cs=1 ;; disable) cs=2 ;; *) return 1 ;; esac
  printf '%s' "$cs" > "{nodes}/${{4#*/}}"
}}
log() {{ :; }}
bk_sync() {{ :; }}
am() {{ :; }}
'''
        return code, nodes

    def test_component_baseline_restores_default_enabled_and_disabled_states(self):
        code, nodes = self.component_fixture()
        snapshot = self.root / "components.bak"
        code += f'''
component_snapshot "{snapshot}" com.example.package com.example.package/com.example.A com.example.package/com.example.B com.example.package/com.example.C
for fc in "{nodes}/"*; do printf '2' > "$fc"; done
component_restore "{snapshot}"
permission_state com.example.package android.permission.POST_NOTIFICATIONS
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout, "false\n")
        self.assertEqual({p.name: p.read_text() for p in nodes.iterdir()},
                         {"com.example.A": "1", "com.example.B": "2", "com.example.C": "0"})

    def test_component_disabled_count_ignores_enabled_overrides_and_other_users(self):
        code, _ = self.component_fixture()
        result = self.shell(code + "\ncomponent_disabled_count com.example.package com.example.A com.example.B com.example.C")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout, "1\n")

    def test_antifraud_repeated_enable_preserves_exact_component_and_setting_baseline(self):
        code, nodes = self.component_fixture()
        settings = self.root / "settings"
        prefs = self.root / "prefs"
        settings.mkdir()
        prefs.mkdir()
        (settings / "secure_a").write_text("1")
        (settings / "secure_b").touch()
        (prefs / "pref_a").write_text("true")
        (prefs / "pref_b").write_text("false")
        code += f'''
AF_COMPS='com.example.A com.example.B com.example.C'
AF_KEYS='secure_a secure_b secure_c'
AF_PREFS='pref_a pref_b'
settings() {{
  case "$1" in
    get) if [ -f "{settings}/$3" ]; then cat "{settings}/$3"; else echo null; fi ;;
    put) printf '%s' "$4" > "{settings}/$3" ;;
    delete) rm -f "{settings}/$3" ;;
  esac
}}
af_pref_get() {{ cat "{prefs}/$1"; }}
af_pref_set() {{ printf '%s' "$2" > "{prefs}/$1"; }}
cmd_af_on
cmd_af_on
cmd_af_off
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("ERR|", result.stdout)
        self.assertEqual({p.name: p.read_text() for p in nodes.iterdir()},
                         {"com.example.A": "1", "com.example.B": "2", "com.example.C": "0"})
        self.assertEqual({p.name: p.read_text() for p in settings.iterdir()}, {"secure_a": "1", "secure_b": ""})
        self.assertEqual({p.name: p.read_text() for p in prefs.iterdir()}, {"pref_a": "true", "pref_b": "false"})
        self.assertFalse((self.root / "adb/hypercraft/state/antifraud.bak").exists())

    def test_unreadable_component_baseline_refuses_antifraud_without_system_writes(self):
        calls = self.root / "writes"
        code = self.backend_functions() + f'''
dumpsys() {{ echo 'Unable to find package'; }}
af_pref_get() {{ echo true; }}
af_apply() {{ echo touched > "{calls}"; }}
cmd_af_on
'''
        result = self.shell(code)
        self.assertIn("ERR|af|无法读取组件基线", result.stdout)
        self.assertFalse(calls.exists())

    def test_ota_restore_preserves_denied_permission_component_overrides_and_badges(self):
        code, nodes = self.component_fixture()
        settings = self.root / "global-settings"
        settings.mkdir()
        original = {"miui_new_version": "OS4.example", "com.android.settings.superscript_map": "5",
                    "com.android.settings.superscript_count": "2"}
        for key, value in original.items():
            (settings / key).write_text(value)
        granted = self.root / "unexpected-grant"
        code += f'''
OTA_PKG=com.example
OTA_COMPS='A B C'
ota_uid() {{ echo 10001; }}
ota_fw() {{ :; }}
ota_watch_start() {{ :; }}
ota_watch_stop() {{ :; }}
pm() {{
  case "$1" in
    default-state) cs=0 ;; enable) cs=1 ;; disable) cs=2 ;;
    revoke) return 0 ;; grant) touch "{granted}"; return 0 ;;
    *) return 1 ;;
  esac
  printf '%s' "$cs" > "{nodes}/${{4#*/}}"
}}
settings() {{
  case "$1" in
    get) if [ -f "{settings}/$3" ]; then cat "{settings}/$3"; else echo null; fi ;;
    put) printf '%s' "$4" > "{settings}/$3" ;;
    delete) rm -f "{settings}/$3" ;;
  esac
}}
cmd_ota_on
cmd_ota_on
cmd_ota_off
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("ERR|", result.stdout)
        self.assertFalse(granted.exists(), "原本被拒绝的通知权限不能在关闭屏蔽时被授予")
        self.assertEqual({p.name: p.read_text() for p in nodes.iterdir()},
                         {"com.example.A": "1", "com.example.B": "2", "com.example.C": "0"})
        self.assertEqual({p.name: p.read_text() for p in settings.iterdir()}, original)

    def test_ota_firewall_changes_only_new_host_chain(self):
        calls = self.root / "firewall-calls"
        code = self.backend_functions() + f'''
iptables() {{
  printf 'v4 %s\\n' "$*" >> "{calls}"
  case " $* " in *" -D "*) return 1 ;; esac
  return 0
}}
ip6tables() {{
  printf 'v6 %s\\n' "$*" >> "{calls}"
  case " $* " in *" -D "*) return 1 ;; esac
  return 0
}}
ota_fw on 12345
ota_fw off
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        written = calls.read_text().splitlines()
        self.assertTrue(any('-N hcf_ota' in line for line in written))
        self.assertTrue(any('-I OUTPUT 1 -j hcf_ota' in line for line in written))
        self.assertTrue(any('-X hcf_ota' in line for line in written))
        self.assertTrue(all('hcf_ota' in line for line in written))
        self.assertNotIn('hac_ota', '\n'.join(written), '停用新版不能清除来源应用的防火墙规则')

    def test_charge_daemon_requires_explicit_control_or_record_opt_in(self):
        source = (BACKEND / "battery/charge.sh").read_text().split('\ncase "$1" in\n  status)', 1)[0]
        source = source.replace('. "${0%/*}/../env.sh"', f'. "{BACKEND}/env.sh"').replace(
            "/data/adb", str(self.root / "adb"))
        calls = self.root / "daemon-calls"
        code = source + f'''
supported() {{ return 0; }}
daemon_pid() {{ :; }}
start_detached() {{ printf '%s\\n' "$*" >> "{calls}"; }}
cmd_start
wait
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(calls.exists(), "默认不能启动常驻充电采样")
        config = self.root / "adb/hypercraft/state/charge/config"
        config.write_text("record=1\n")
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("daemon", calls.read_text())

    def test_background_watchers_default_off_even_without_config_file(self):
        calls = self.root / "watcher-calls"
        code = self.backend_functions() + f'''
pgrep() {{ printf 'pgrep %s\\n' "$*" >> "{calls}"; }}
start_detached() {{ printf 'start_detached %s\\n' "$*" >> "{calls}"; }}
guard_start
wlog_start
rot_start
adb_diag_start
fps_boot
cmd_ota_early
wait
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(calls.exists(), "缺省配置不能启动守卫、日志、方向、高刷或诊断守护")
        config = self.root / "adb/hypercraft/state/config"
        config.write_text("adb_diag_enable=1\n")
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("adb-diag-daemon", calls.read_text())

    def test_disabling_cycle_spoofing_immediately_stops_its_watchdog(self):
        state = self.root / "adb/hypercraft/state/battery"
        state.mkdir(parents=True)
        config = state / "config.txt"
        config.write_text("MODE=fixed\nFIXED=20\n")
        cycle = self.root / "cycle.sh"
        cycle.write_text(f'printf "MODE=off\\n" > "{config}"\n')
        calls = self.root / "watchdog-calls"
        code = self.backend_functions() + f'''
CYCLE="{cycle}"
legacy_active() {{ return 1; }}
cycle_watch_pid() {{ echo 424242; }}
kill() {{ printf '%s\\n' "$*" >> "{calls}"; }}
start_detached() {{ printf 'unexpected-start\\n' >> "{calls}"; }}
cmd_cycle setmode off
wait
'''
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(calls.read_text().splitlines(), ["424242"])

    def test_system_mount_helper_rejects_ineffective_namespace_entry_before_write(self):
        current = self.root / "current-ns"
        current.write_text("mnt:[private]\n")
        marker = self.root / "write-marker"
        readlink = self.bin / "readlink"
        readlink.write_text(f'''#!/bin/sh
case "$1" in /proc/self/ns/mnt) cat "{current}" ;; /proc/1/ns/mnt) echo 'mnt:[init]' ;; *) exit 1 ;; esac
''')
        readlink.chmod(0o700)
        nsenter = self.bin / "nsenter"
        # 模拟工具返回成功但实际没有进入目标 namespace，生产 helper 必须再次验证。
        nsenter.write_text('#!/bin/sh\nshift 4\nexec "$@"\n')
        nsenter.chmod(0o700)
        code = self.backend_functions() + f'\nin_init_ns touch "{marker}"'
        result = self.shell(code)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(marker.exists())
        current.write_text("mnt:[init]\n")
        result = self.shell(code)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(marker.exists())

    def cycle_fixture(self, mode="fixed", bound=False):
        runtime = self.root / "runtime"
        shutil.copytree(BACKEND, runtime)
        state = self.root / "adb/hypercraft/state/battery"
        state.mkdir(parents=True)
        (state / "config.txt").write_text(f"MODE={mode}\nFIXED=20\n")
        bat, fg = self.root / "battery_cycle", self.root / "fg_cycle"
        bat.write_text("577\n")
        fg.write_text("577\n")
        mounts = self.root / "mounts"
        mounts.write_text(f"test {bat} none bind 0 0\n" if bound else "")
        self_ns, init_ns = self.root / "self-ns", self.root / "init-ns"
        self_ns.symlink_to("mnt:[test]")
        init_ns.symlink_to("mnt:[test]")
        for file in runtime.rglob("*.sh"):
            file.write_text(file.read_text().replace("/data/adb", str(self.root / "adb"))
                            .replace("/sys/class/power_supply/battery/cycle_count", str(bat))
                            .replace("/sys/class/xm_power/fg_master/cyclecount", str(fg))
                            .replace("/proc/mounts", str(mounts))
                            .replace("/proc/self/ns/mnt", str(self_ns))
                            .replace("/proc/1/ns/mnt", str(init_ns)))
        with (runtime / "env.sh").open("a") as env:
            env.write(f'\nexport PATH="{self.bin}:$PATH"\n')
        for command in ("mount", "umount"):
            file = self.bin / command
            file.write_text("#!/bin/sh\nexit 1\n")
            file.chmod(0o700)
        return runtime / "battery/cycle.sh", bat, fg

    def test_mount_failure_never_writes_fuel_gauge(self):
        cycle, bat, fg = self.cycle_fixture()
        original_mode = fg.stat().st_mode
        result = self.shell('sh "$1" apply', str(cycle))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("未改写电量计", result.stdout)
        self.assertEqual(bat.read_text(), "577\n")
        self.assertEqual(fg.read_text(), "577\n")
        self.assertEqual(fg.stat().st_mode, original_mode)
        result = self.shell('sh "$1" restore', str(cycle))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(fg.read_text(), "577\n")

    def test_restore_does_not_report_success_if_unmount_fails(self):
        for mode in ("fixed", "off"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as nested:
                old_root, old_bin = self.root, self.bin
                self.root = Path(nested).resolve()
                self.bin = old_bin
                cycle, _, _ = self.cycle_fixture(mode, bound=True)
                result = self.shell('sh "$1" "$2"', str(cycle), "apply" if mode == "off" else "restore")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("恢复失败", result.stdout)
                self.assertNotIn("OK:", result.stdout)
                self.root, self.bin = old_root, old_bin

    def test_cycle_namespace_entry_fails_closed_after_one_attempt(self):
        cycle, bat, fg = self.cycle_fixture()
        init_ns = self.root / "init-ns"
        init_ns.unlink()
        init_ns.symlink_to("mnt:[different]")
        calls = self.root / "nsenter-calls"
        helper = self.bin / "nsenter"
        helper.write_text(f'#!/bin/sh\necho attempted >> "{calls}"\nshift 4\nexec "$@"\n')
        helper.chmod(0o700)
        result = self.shell('sh "$1" apply', str(cycle))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("无法进入系统挂载命名空间", result.stdout)
        self.assertEqual(calls.read_text(), "attempted\n")
        self.assertEqual(bat.read_text(), "577\n")
        self.assertEqual(fg.read_text(), "577\n")

    def test_cycle_restore_cannot_unmount_foreign_binding(self):
        cycle, bat, fg = self.cycle_fixture(bound=True)
        calls = self.root / "umount-calls"
        helper = self.bin / "umount"
        helper.write_text(f'#!/bin/sh\nprintf "%s\\n" "$*" >> "{calls}"\nexit 0\n')
        helper.chmod(0o700)
        result = self.shell('sh "$1" restore', str(cycle))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(calls.exists(), "外部绑定不能当成本模块的挂载卸载")
        self.assertEqual(bat.read_text(), "577\n")
        self.assertEqual(fg.read_text(), "577\n")


if __name__ == "__main__":
    unittest.main()
