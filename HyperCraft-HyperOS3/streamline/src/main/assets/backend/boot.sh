#!/system/bin/sh
RUNTIME=${0%/*}
BASE=${RUNTIME%/*}
DATA=$BASE/state
. "$RUNTIME/env.sh"
APP=io.github.dalong.hypercraft

stop_runtime() {
  sh "$RUNTIME/battery/charge.sh" stop >/dev/null 2>&1
  for pid in $(pgrep -f "$RUNTIME/adclean[.]sh (boot|guard-daemon|wlog-daemon|rot-daemon|cycle-watch|fps-daemon|adb-diag-daemon|sync-off-delayed)$"); do kill "$pid" 2>/dev/null; done
  for pid in $(pgrep -f "inotifyd $DATA/ota_hook.sh"); do kill "$pid" 2>/dev/null; done
  return 0
}

case "${1:-start}" in
  early)
    [ -s "$RUNTIME/payload.sha256" ] || exit 0
    cat /proc/sys/kernel/random/boot_id > "$DATA/early.boot.id"
    # early 阶段尚不能查询 PackageManager；卸载判定留给 boot 阶段。
    if grep -q '^enabled=1$' "$DATA/devmodel/config" 2>/dev/null; then
      EARLY_BOOT=1 sh "$RUNTIME/adclean.sh" dm boot >/dev/null 2>&1
    fi
    grep -qE '^hosts_(xiaomi|sdk|custom|antifraud|reporting)=1' "$DATA/config" 2>/dev/null && sh "$RUNTIME/adclean.sh" hosts-mount
    if grep -q '^color_full=1$' "$DATA/config" 2>/dev/null; then sh "$RUNTIME/adclean.sh" color-boot; else echo 0 > "$DATA/color.boot"; fi
    if grep -q '^fps_method=hold$' "$DATA/config" 2>/dev/null; then sh "$RUNTIME/adclean.sh" fps-idle-boot; else echo 0 > "$DATA/idle.boot"; fi
    exit 0 ;;
  stop) stop_runtime; exit 0 ;;
  boot|start) ;;
  *) exit 1 ;;
esac

[ -s "$RUNTIME/payload.sha256" ] || exit 0
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 3; done
# flock 的获取是原子的，进程退出即可释放，不存在 mkdir 后尚未写入 owner 的空窗。
exec 9>>"$BASE/boot.flock" || exit 1
flock -xn 9 9>&9 || exit 0
# PackageManager 异常时保留数据，不能把一次查询失败当成卸载。
packages=$(pm list packages --user 0 2>/dev/null) || exit 1
echo "$packages" | grep -q '^package:android$' || exit 1
if ! echo "$packages" | grep -qx "package:$APP"; then
  stop_runtime
  # Keep the recovery entry on failure. The next boot can retry without
  # restarting normal runtime tasks or discarding the original package states.
  sh "$RUNTIME/adclean.sh" uninstall-restore 9>&- >> "$DATA/log.txt" 2>&1 || exit 1
  rm -f /data/adb/post-fs-data.d/hypercraft.sh /data/adb/service.d/hypercraft.sh
  # 保留记录和备份以便人工检查；不再启动守护或重放已保存配置。
  rm -f "$RUNTIME/payload.sha256"
  exit 0
fi

boot_id=$(cat /proc/sys/kernel/random/boot_id) || exit 1
completed=$(cat "$DATA/boot.id" 2>/dev/null)
[ "$completed" != "$boot_id" ] || exit 0
# 后端会创建常驻进程，不能让它们继承启动锁。
sh "$RUNTIME/adclean.sh" ota-early 9>&-
grep -qE '^hosts_(xiaomi|sdk|custom|antifraud|reporting)=1' "$DATA/config" 2>/dev/null && sh "$RUNTIME/adclean.sh" hosts-mount 9>&-
sh "$RUNTIME/adclean.sh" boot 9>&- >> "$DATA/log.txt" 2>&1 || exit 1
echo "$boot_id" > "$DATA/boot.id"
