"""使用真实临时属性存储模拟 resetprop；不访问设备或系统属性。"""
import base64
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from shell_support import wait_file

BACKEND = Path(__file__).resolve().parents[1] / "src/main/assets/backend"


class DeviceModelTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.properties = self.root / "properties.json"
        self.original = {
            "ro.product.manufacturer": "Xiaomi", "ro.product.brand": "Redmi",
            "ro.product.marketname": "Synthetic Phone", "ro.product.model": "TESTMODEL",
            "ro.product.name": "fixture", "ro.product.device": "fixture",
            "ro.product.product.brand": "",
        }
        self.properties.write_text(json.dumps(self.original))
        fixture = self.bin / "property_fixture.py"
        fixture.write_text('''import json
import os
from pathlib import Path
import sys
path=Path(os.environ["MODEL_FIXTURE_PROPERTIES"])
values=json.loads(path.read_text())
tool=sys.argv[1]
args=sys.argv[2:]
if tool == "getprop":
    if not args and os.environ.get("MODEL_FIXTURE_FAIL_READ"): sys.exit(1)
    if not args and os.environ.get("MODEL_FIXTURE_HOLD_READ"):
        import time
        Path(os.environ["MODEL_FIXTURE_HOLD_READ"]).touch()
        time.sleep(30)
    if args:
        print(values.get(args[0], ""))
    else:
        for key, value in values.items(): print(f"[{key}]: [{value}]")
else:
    args=[arg for arg in args if arg != "-n"]
    if args[0] == "--delete":
        if args[1] not in values: sys.exit(1)
        del values[args[1]]
    else:
        if os.environ.get("MODEL_FIXTURE_REJECT") == args[0]: sys.exit(1)
        values[args[0]]=args[1]
    path.write_text(json.dumps(values))
''')
        for tool in ("getprop", "resetprop"):
            file = self.bin / tool
            file.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{fixture}" {tool} "$@"\n')
            file.chmod(0o700)
        self.env = {**os.environ, "PATH": str(self.bin) + ":" + os.environ["PATH"],
                    "MODEL_FIXTURE_PROPERTIES": str(self.properties)}
        self.code = (BACKEND / "devmodel/control.sh").read_text().split('\ncase "${1:-status}" in\n', 1)[0]
        self.code = self.code.replace('. "${0%/*}/../env.sh"', '').replace('/data/adb', str(self.root / 'adb'))
        self.code += '\nLOCK_START_OVERRIDE=12345\n'

    def shell(self, code, **env):
        return subprocess.run(["sh", "-c", self.code + code], env={**self.env, **env},
                              text=True, capture_output=True, timeout=30)

    def test_valid_baseline_with_already_absent_optional_fields_allows_apply_and_restore(self):
        market = base64.b64encode(b"HyperCraft Probe").decode()
        model = base64.b64encode(b"HC-PROBE").decode()
        result = self.shell(f'''
capture_runtime_stock || exit 10
apply_transaction '' '' 'HyperCraft Probe' HC-PROBE '' 1 custom '' '' '' '{market}' '{model}' '' || exit $?
''')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        changed = json.loads(self.properties.read_text())
        self.assertEqual(changed["ro.product.marketname"], "HyperCraft Probe")
        self.assertEqual(changed["ro.product.model"], "HC-PROBE")
        self.assertNotIn("ro.product.vendor.manufacturer", changed)
        # 同时覆盖 present=1 的空值与 present=0 的后来新增字段，不能混为一谈。
        del changed["ro.product.product.brand"]
        changed["ro.product.vendor.manufacturer"] = "Temporary"
        self.properties.write_text(json.dumps(changed))
        result = self.shell('\nrestore_baseline\n')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads(self.properties.read_text()), self.original)

    def test_rejected_model_write_rolls_back_all_properties_and_keeps_config_disabled(self):
        result = self.shell('''
capture_runtime_stock || exit 10
apply_transaction '' '' 'HyperCraft Probe' HC-PROBE '' 1 custom '' '' '' '' '' ''
''', MODEL_FIXTURE_REJECT="ro.product.model")
        self.assertEqual(result.returncode, 4)
        self.assertEqual(json.loads(self.properties.read_text()), self.original)
        config = self.root / "adb/hypercraft/state/devmodel/config"
        self.assertFalse(config.exists(), "失败事务不能提交 enabled=1 配置")

    def test_restore_temporary_property_table_is_removed_on_read_failure(self):
        result = self.shell('''
capture_runtime_stock || exit 10
export MODEL_FIXTURE_FAIL_READ=1
restore_baseline
''')
        self.assertNotEqual(result.returncode, 0)
        state = self.root / "adb/hypercraft/state/devmodel"
        self.assertEqual(list(state.glob("restore-props.*")), [])

    def test_cancelled_restore_removes_temporary_property_table(self):
        result = self.shell('\ncapture_runtime_stock\n')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        ready = self.root / "read-blocked"
        process = subprocess.Popen(["sh", "-c", self.code + '\nrestore_baseline\n'],
                                   env={**self.env, "MODEL_FIXTURE_HOLD_READ": str(ready)},
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        self.addCleanup(lambda: process.poll() is None and os.killpg(process.pid, signal.SIGKILL))
        wait_file(ready, process)
        os.killpg(process.pid, signal.SIGTERM)
        process.communicate(timeout=5)
        state = self.root / "adb/hypercraft/state/devmodel"
        self.assertEqual(list(state.glob("restore-props.*")), [])


if __name__ == "__main__":
    unittest.main()
