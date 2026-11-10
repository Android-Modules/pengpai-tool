"""可选真机回归：仅写自己的 /data/local/tmp 目录，不申请 Root。"""
import os
from pathlib import Path
import shutil
import subprocess
import unittest

BACKEND = Path(__file__).resolve().parents[1] / "src/main/assets/backend"
SERIAL = os.environ.get("HYPEROS_TEST_ADB_SERIAL")


@unittest.skipUnless(SERIAL, "显式设置 HYPEROS_TEST_ADB_SERIAL 后才连接测试手机")
class AndroidShellTest(unittest.TestCase):
    def shell(self, body):
        adb = os.environ.get("HYPEROS_TEST_ADB") or shutil.which("adb")
        self.assertTrue(adb, "需要 adb 或 HYPEROS_TEST_ADB")
        setup = '''set -e
task_dir=$(mktemp -d /data/local/tmp/pengpai-review.XXXXXX)
export task_dir
trap 'rm -rf "$task_dir"' EXIT
'''
        result = subprocess.run([adb, "-s", SERIAL, "shell", "sh", "-s"], input=setup + body,
                                text=True, capture_output=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("Bad file descriptor", result.stderr)

    def test_android_mksh_preserves_concurrent_config_updates(self):
        self.shell((BACKEND / "env.sh").read_text() + '''
for i in $(seq 1 40); do
  (config_set "$task_dir/config" "key_$i" "$i" || echo fail >> "$task_dir/errors") &
done
wait
[ ! -s "$task_dir/errors" ]
[ "$(wc -l < "$task_dir/config")" -eq 40 ]
for i in $(seq 1 40); do grep -qx "key_$i=$i" "$task_dir/config"; done
''')

    def test_android_detached_compiler_keeps_inherited_lock(self):
        source = (BACKEND / "adclean.sh").read_text()
        def function(name):
            start = source.index(name + "() ")
            close = "\n)" if source[start + len(name + "() ")] == "(" else "\n}"
            return source[start:source.index(close, start) + len(close)] + "\n"
        runner = (BACKEND / "env.sh").read_text() + """
DATA="$task_dir"; MODDIR="$task_dir"; DEXOPT_QUEUE="$DATA/dexopt.queue"; DEXOPT_LOG="$DATA/dexopt.log"
HYPERCRAFT_LAUNCH_DIR="$task_dir/launch"
log() { :; }
cmd() {
  echo running >> "$task_dir/calls"
  touch "$task_dir/running"
  while [ -d "$task_dir" ] && [ ! -f "$task_dir/release" ]; do sleep 0.02; done
  echo Success
}
""" + function("cmd_dexopt") + function("cmd_dexopt_run") + """
case "$1" in dexopt-run) shift; cmd_dexopt_run "$@";; *) cmd_dexopt bg;; esac
"""
        self.shell("cat > \"$task_dir/adclean.sh\" <<'RUNNER'\n" + runner + "\nRUNNER\n" + """
sh "$task_dir/adclean.sh" start > "$task_dir/first"
grep -q '^OK|' "$task_dir/first"
i=0
until [ -f "$task_dir/running" ]; do
  [ "$i" -lt 100 ] || exit 1
  i=$((i+1)); sleep 0.02
done
if sh "$task_dir/adclean.sh" start > "$task_dir/second"; then exit 1; fi
grep -q '^ERR|' "$task_dir/second"
[ "$(wc -l < "$task_dir/calls")" -eq 1 ]
touch "$task_dir/release"
exec 6>>"$task_dir/dexopt.flock"
flock -x 6 6>&6
flock -u 6 6>&6
exec 6>&-
sh "$task_dir/adclean.sh" start > "$task_dir/retry"
grep -q '^OK|' "$task_dir/retry"
exec 6>>"$task_dir/dexopt.flock"
flock -x 6 6>&6
""")

    def test_android_mksh_parses_real_model_baseline_and_clears_reboot_marker(self):
        # 提取完整生产函数，仅替换状态路径和 getprop/resetprop，不访问真实属性或 Root。
        source = (BACKEND / "devmodel/control.sh").read_text().split('\ncase "${1:-status}" in\n', 1)[0]
        source = source.replace('. "${0%/*}/../env.sh"', '').replace('/data/adb', '$task_dir/adb')
        self.shell(source + '''
printf '[%s]: [%s]\\n' ro.product.manufacturer Xiaomi ro.product.brand Redmi \\
  ro.product.marketname 'Synthetic Phone' ro.product.model TESTMODEL ro.product.name fixture ro.product.device fixture > "$task_dir/model-props"
# 实际 Android printf 是外部程序，大于 128 KiB 的单个属性表 argv 会报 E2BIG。
awk 'BEGIN { for (i=0; i<3000; i++) printf "[fixture.bulk.%d]: [abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789]\\n", i }' >> "$task_dir/model-props"
[ "$(wc -c < "$task_dir/model-props")" -gt 131072 ]
printf 'fixture_property_bytes=%s\\n' "$(wc -c < "$task_dir/model-props")"
getprop() {
  if [ "$#" = 0 ]; then
    cat "$task_dir/model-props"
  else
    awk -v prefix="[$1]: [" 'index($0,prefix)==1 { value=substr($0,length(prefix)+1); sub(/\\]$/, "", value); print value; exit }' "$task_dir/model-props"
  fi
}
resetprop() {
  printf '%s\\n' "$*" >> "$task_dir/restored"
  [ "$1" = -n ] && shift
  if [ "$1" = --delete ]; then
    awk -v wanted="[$2]:" '$1!=wanted' "$task_dir/model-props" > "$task_dir/model-props.next"
  else
    awk -v wanted="[$1]:" -v replacement="[$1]: [$2]" '$1==wanted { print replacement; found=1; next } { print } END { if (!found) print replacement }' "$task_dir/model-props" > "$task_dir/model-props.next"
  fi
  mv "$task_dir/model-props.next" "$task_dir/model-props"
}
touch "$REBOOT_REQUIRED_FILE"
capture_runtime_stock
[ "$(baseline_source)" = clean_boot ]
[ "$(baseline_prop_value ro.product.marketname)" = 'Synthetic Phone' ]
[ "$(baseline_prop_value ro.product.model)" = TESTMODEL ]
[ ! -f "$REBOOT_REQUIRED_FILE" ]
verify_snapshot_applied "$RUNTIME_STOCK_FILE"
resetprop -n ro.product.marketname Changed
resetprop -n ro.product.model CHANGED
restore_snapshot "$RUNTIME_STOCK_FILE"
grep -Fqx -- '-n ro.product.marketname Synthetic Phone' "$task_dir/restored"
grep -Fqx -- '-n ro.product.model TESTMODEL' "$task_dir/restored"
! restore_pending_reboot
[ "$(find "$STATE_DIR" -name 'restore-props.*' | wc -l)" -eq 0 ]
''')

    def test_production_boot_script_is_exclusive_on_android(self):
        # 即使 stub 异常，生产脚本的卸载路径也已被限定在本测试临时目录。
        boot = (BACKEND / "boot.sh").read_text().replace("/data/adb", "$task_dir/adb")
        self.shell('''
mkdir -p "$task_dir/runtime" "$task_dir/state" "$task_dir/bin"
echo ready > "$task_dir/runtime/payload.sha256"
cat > "$task_dir/runtime/env.sh" <<'ENV'
export PATH="$task_dir/bin:/system/bin"
ENV
cat > "$task_dir/bin/getprop" <<'STUB'
#!/system/bin/sh
echo 1
STUB
cat > "$task_dir/bin/pm" <<'STUB'
#!/system/bin/sh
echo package:android
echo package:io.github.dalong.hypercraft
STUB
cat > "$task_dir/runtime/adclean.sh" <<'STUB'
#!/system/bin/sh
if [ "$1" = boot ]; then
  echo boot >> "$task_dir/calls"
  touch "$task_dir/entered"
  while [ -d "$task_dir" ] && [ ! -f "$task_dir/release" ]; do sleep 0.02; done
fi
STUB
chmod 700 "$task_dir/bin/getprop" "$task_dir/bin/pm"
cat > "$task_dir/runtime/boot.sh" <<'BOOT'
''' + boot + '''
BOOT
sh "$task_dir/runtime/boot.sh" start &
owner=$!
i=0
until [ -f "$task_dir/entered" ]; do
  [ "$i" -lt 100 ] || exit 1
  i=$((i + 1)); sleep 0.02
done
for i in 1 2 3 4; do sh "$task_dir/runtime/boot.sh" start; done
[ "$(wc -l < "$task_dir/calls")" -eq 1 ]
touch "$task_dir/release"
wait "$owner"
sh "$task_dir/runtime/boot.sh" start
[ "$(wc -l < "$task_dir/calls")" -eq 1 ]
''')


if __name__ == "__main__":
    unittest.main()
