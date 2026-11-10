"""在临时目录模拟 Root 与 Android 命令，不接触设备或 /data/adb。"""
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import unittest
from shell_support import install_flock, wait_file

BACKEND = Path(__file__).resolve().parents[1] / "src/main/assets/backend"


class RuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.adb = self.root / "adb"
        self.base = self.adb / "hypercraft"
        self.data = self.base / "state"
        self.runtime = self.base / "runtime"
        self.source = self.root / "source"
        shutil.copytree(BACKEND, self.source)
        self.calls = self.root / "calls"
        self.packages = self.root / "packages"
        self.packages.write_text("package:android\npackage:io.github.dalong.hypercraft\n")
        hosts = self.root / "hosts"
        hosts.write_text("127.0.0.1 localhost\n")
        boot_id = self.root / "boot_id"
        boot_id.write_text("current-boot\n")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for file in self.source.rglob("*.sh"):
            file.write_text(file.read_text().replace("/data/adb", str(self.adb))
                            .replace("/system/etc/hosts", str(hosts))
                            .replace("/proc/sys/kernel/random/boot_id", str(boot_id)))
        with (self.source / "env.sh").open("a") as env:
            env.write(f'\nexport PATH="{self.bin}:$PATH"\n')
            # Runtime 测试显式调用 boot；真正 detached ready 握手由独立黑盒测试覆盖。
            env.write('start_detached() { :; }\n')
        install_flock(self.bin)
        # 实际部署逻辑照常执行，系统功能替换为仅记录参数的脚本。
        stub = f'#!/bin/sh\necho "$*" >> "{self.calls}"\nexit 0\n'
        for relative in ("adclean.sh", "battery/charge.sh", "devmodel/control.sh"):
            (self.source / relative).write_text(stub)
        self.command("id", "echo 0")
        self.command("getprop", "echo 1")
        self.command("pm", f'cat "{self.packages}"')
        self.command("pgrep", "exit 1")

    def command(self, name, body):
        file = self.bin / name
        file.write_text("#!/bin/sh\n" + body + "\n")
        file.chmod(0o700)

    def run_script(self, file, *args):
        return subprocess.run(["sh", str(file), *args], text=True, capture_output=True,
                              env={**os.environ, "PATH": str(self.bin) + ":" + os.environ["PATH"]},
                              timeout=10)

    def install(self, hash="a" * 64):
        result = self.run_script(self.source / "install.sh", str(self.source), hash, "4.0.0")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def test_fresh_install_registers_only_general_scripts(self):
        self.install()
        self.assertTrue((self.runtime / "adclean.sh").exists())
        self.assertFalse((self.runtime / "module.prop").exists())
        self.assertFalse((self.adb / "modules/hypercraft").exists())
        for phase in ("post-fs-data", "service"):
            entry = self.adb / f"{phase}.d/hypercraft.sh"
            self.assertTrue(entry.stat().st_mode & 0o100)
        self.assertIn("hosts_xiaomi=0", (self.data / "config").read_text())
        self.assertIn("backup_dir=/storage/emulated/0/HyperCraft", (self.data / "config").read_text())

    def test_source_application_and_runtime_are_not_adopted_or_modified(self):
        legacy = self.adb / "hyperos4-streamline"
        legacy_state = legacy / "state"
        legacy_runtime = legacy / "runtime"
        legacy_state.mkdir(parents=True)
        legacy_runtime.mkdir()
        config = legacy_state / "config"
        config.write_text("hosts_xiaomi=1\nkeep_on_boot=1\n")
        marker = legacy_runtime / "payload.sha256"
        marker.write_text("source-payload\n")
        self.packages.write_text("package:android\npackage:io.github.dalong.hypercraft\n"
                                 "package:com.dalong.pengpaitool.hyperos4\n")
        self.install()
        self.assertEqual(config.read_text(), "hosts_xiaomi=1\nkeep_on_boot=1\n")
        self.assertEqual(marker.read_text(), "source-payload\n")
        self.assertIn("hosts_xiaomi=0", (self.data / "config").read_text())
        self.assertNotEqual(self.runtime, legacy_runtime)

    def test_uninstall_new_host_only_restores_its_own_runtime(self):
        self.install()
        legacy = self.adb / "hyperos4-streamline/state"
        legacy.mkdir(parents=True)
        config = legacy / "config"
        config.write_text("source=keep\n")
        self.packages.write_text("package:android\npackage:com.dalong.pengpaitool.hyperos4\n")
        result = self.run_script(self.runtime / "boot.sh", "boot")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("uninstall-restore", self.calls.read_text())
        self.assertFalse((self.runtime / "payload.sha256").exists())
        self.assertEqual(config.read_text(), "source=keep\n")

    def test_upgrade_preserves_configuration_and_restarts_this_boot(self):
        self.install()
        config = self.data / "config"
        config.write_text("hosts_xiaomi=1\nrestore_on_uninstall=0\n")
        (self.data / "boot.id").write_text("already-applied")
        self.install("b" * 64)
        self.assertEqual(config.read_text(), "hosts_xiaomi=1\nrestore_on_uninstall=0\n")
        self.assertFalse((self.data / "boot.id").exists())
        self.assertEqual((self.runtime / "payload.sha256").read_text().strip(), "b" * 64)

    def test_same_payload_does_not_stop_running_daemons(self):
        self.install()
        (self.data / "boot.id").write_text("already-applied")
        self.calls.write_text("")
        self.install()
        self.assertTrue((self.data / "boot.id").exists())
        self.assertNotIn("stop", self.calls.read_text().splitlines())

    def test_rejects_active_hyperos3_backend(self):
        (self.adb / "modules/hyperadclean").mkdir(parents=True)
        result = self.run_script(self.source / "install.sh", str(self.source), "a" * 64, "4.0.0")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("HyperOS 3", result.stdout)
        self.assertFalse(self.runtime.exists())

    def test_bad_payload_does_not_replace_existing_scripts(self):
        self.install()
        original = (self.runtime / "adclean.sh").read_text()
        (self.source / "adclean.sh").write_text("if broken syntax\n")
        result = self.run_script(self.source / "install.sh", str(self.source), "b" * 64, "4.0.1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.runtime / "adclean.sh").read_text(), original)
        self.assertEqual((self.runtime / "payload.sha256").read_text().strip(), "a" * 64)

    def test_boot_replays_once_per_boot(self):
        self.install()
        self.calls.write_text("")
        for _ in range(2):
            result = self.run_script(self.runtime / "boot.sh", "start")
            self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls.read_text().splitlines().count("boot"), 1)

    def test_legacy_boot_lock_cannot_block_kernel_lock(self):
        self.install()
        lock = self.base / "boot.lock"
        lock.mkdir()
        (lock / "boot").write_text("previous-boot")
        (lock / "pid").write_text("9999999")
        result = self.run_script(self.runtime / "boot.sh", "start")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.data / "boot.id").exists())

    def test_uninstall_stops_runtime_without_reinstalling_apk(self):
        self.install()
        self.packages.write_text("package:android\n")
        result = self.run_script(self.runtime / "boot.sh", "boot")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("uninstall-restore", self.calls.read_text())
        self.assertFalse((self.runtime / "payload.sha256").exists())
        self.assertFalse((self.adb / "service.d/hypercraft.sh").exists())
        self.assertTrue((self.data / "config").exists())

    def test_package_manager_failure_never_triggers_uninstall(self):
        self.install()
        self.command("pm", "exit 1")
        result = self.run_script(self.runtime / "boot.sh", "boot")
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue((self.runtime / "payload.sha256").exists())
        self.assertNotIn("uninstall-restore", self.calls.read_text())

    def test_failed_uninstall_restore_preserves_boot_entry_and_retries(self):
        self.install()
        self.packages.write_text("package:android\n")
        marker = self.root / "restore-failed"
        marker.touch()
        (self.runtime / "adclean.sh").write_text(f'''#!/bin/sh
echo "$*" >> "{self.calls}"
if [ "$1" = uninstall-restore ] && [ -e "{marker}" ]; then exit 1; fi
''')
        result = self.run_script(self.runtime / "boot.sh", "boot")
        self.assertNotEqual(result.returncode, 0)
        for relative in ("runtime/payload.sha256", "../service.d/hypercraft.sh", "../post-fs-data.d/hypercraft.sh"):
            self.assertTrue((self.base / relative).exists(), relative)
        self.assertNotIn("boot", self.calls.read_text().splitlines())
        marker.unlink()
        result = self.run_script(self.runtime / "boot.sh", "boot")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls.read_text().splitlines().count("uninstall-restore"), 2)
        self.assertFalse((self.runtime / "payload.sha256").exists())
        self.assertFalse((self.adb / "service.d/hypercraft.sh").exists())

    def test_interrupted_upgrade_disables_boot_until_retry(self):
        self.install()
        config = (self.data / "config").read_text()
        self.command("cp", 'case "$1" in */adclean.sh) exit 1 ;; esac; exec /bin/cp "$@"')
        result = self.run_script(self.source / "install.sh", str(self.source), "b" * 64, "4.0.1")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.runtime / "payload.sha256").exists())
        self.assertEqual((self.data / "config").read_text(), config)
        self.calls.write_text("")
        self.assertEqual(self.run_script(self.runtime / "boot.sh", "start").returncode, 0)
        self.assertEqual(self.calls.read_text(), "")
        self.command("cp", 'exec /bin/cp "$@"')
        self.install("b" * 64)

    def test_missing_script_is_repaired_with_same_payload(self):
        self.install()
        (self.runtime / "adclean.sh").unlink()
        self.install()
        self.assertTrue((self.runtime / "adclean.sh").exists())

    def test_previous_boot_install_lock_does_not_block_upgrade(self):
        self.install()
        lock = self.base / "install.lock"
        lock.mkdir()
        (lock / "pid").write_text(str(os.getpid()))
        (lock / "boot").write_text("previous-boot")
        self.install("b" * 64)
        self.assertEqual((self.runtime / "payload.sha256").read_text().strip(), "b" * 64)

    def spawn(self, file, *args):
        process = subprocess.Popen(["sh", str(file), *args], text=True, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE,
                                   env={**os.environ, "PATH": str(self.bin) + ":" + os.environ["PATH"]},
                                   start_new_session=True)
        def cleanup():
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
            try:
                process.communicate(timeout=1)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate(timeout=2)
        self.addCleanup(cleanup)
        return process

    def test_concurrent_boot_executes_core_once(self):
        self.install()
        entered = self.root / "entered"
        release = self.root / "release"
        (self.runtime / "adclean.sh").write_text(f'''#!/bin/sh
echo "$*" >> "{self.calls}"
if [ "$1" = boot ]; then
  touch "{entered}"
  while [ ! -f "{release}" ]; do sleep 0.02; done
fi
''')
        self.calls.write_text("")
        first = self.spawn(self.runtime / "boot.sh", "start")
        wait_file(entered, first)
        contenders = [self.spawn(self.runtime / "boot.sh", "start") for _ in range(8)]
        for process in contenders:
            out, err = process.communicate(timeout=5)
            self.assertEqual(process.returncode, 0, out + err)
        release.touch()
        out, err = first.communicate(timeout=5)
        self.assertEqual(first.returncode, 0, out + err)
        self.assertEqual(self.calls.read_text().splitlines().count("boot"), 1)

    def test_boot_lock_is_released_when_owner_is_killed(self):
        self.install()
        entered = self.root / "entered"
        child_done = self.root / "child_done"
        (self.runtime / "adclean.sh").write_text(f'''#!/bin/sh
if [ "$1" = boot ]; then
  touch "{entered}"
  while [ ! -f "{child_done}" ]; do sleep 0.02; done
fi
''')
        first = self.spawn(self.runtime / "boot.sh", "start")
        wait_file(entered, first)
        first.kill()
        # 子脚本尚未退出时仍应能重新获取锁，证明它没有继承启动锁 fd。
        probe = self.root / "probe.sh"
        probe.write_text(f'exec 9>>"{self.base}/boot.flock"\nflock -xn 9\n')
        try:
            result = self.run_script(probe)
            self.assertEqual(result.returncode, 0, result.stderr)
        finally:
            child_done.touch()
            first.communicate(timeout=5)

    def test_upgrade_refuses_to_replace_backend_during_boot(self):
        self.install()
        import fcntl
        with (self.base / "boot.flock").open("a") as lock:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
            result = self.run_script(self.source / "install.sh", str(self.source), "b" * 64, "4.0.1")
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("正在启动", result.stdout)
        self.assertEqual((self.runtime / "payload.sha256").read_text().strip(), "a" * 64)

    def test_concurrent_install_does_not_replace_inflight_payload(self):
        entered = self.root / "copy_entered"
        release = self.root / "copy_release"
        self.command("cp", f'''case "$1" in
  */adclean.sh) touch "{entered}"; while [ ! -f "{release}" ]; do sleep 0.02; done ;;
esac
exec /bin/cp "$@"''')
        first = self.spawn(self.source / "install.sh", str(self.source), "a" * 64, "4.0.0")
        try:
            wait_file(entered, first)
            result = self.run_script(self.source / "install.sh", str(self.source), "b" * 64, "4.0.1")
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("正在初始化", result.stdout)
            self.assertFalse((self.runtime / "payload.sha256").exists())
        finally:
            release.touch()
            out, err = first.communicate(timeout=5)
        self.assertEqual(first.returncode, 0, out + err)
        self.assertEqual((self.runtime / "payload.sha256").read_text().strip(), "a" * 64)

    def test_early_boot_never_queries_package_manager(self):
        self.install()
        self.command("pm", 'echo "must not query pm" >&2; exit 1')
        result = self.run_script(self.runtime / "boot.sh", "early")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("must not query pm", result.stderr)


if __name__ == "__main__":
    unittest.main()
