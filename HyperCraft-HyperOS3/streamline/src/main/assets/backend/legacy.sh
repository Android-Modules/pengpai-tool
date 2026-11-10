#!/system/bin/sh
# 只处理旧 APK 的独立 Root 后台；保留应用、配置和备份，绝不恢复/冻结其应用处理名单。
SELF_DIR=${0%/*}
. "$SELF_DIR/env.sh"
OLD=/data/adb/hyperos4-streamline
CURRENT=/data/adb/hypercraft
OLD_CYCLE=/data/adb/.hyperos4_cycle
OLD_DEV=/dev/hyperos4-streamline

legacy_in_init() {
  if command -v nsenter >/dev/null 2>&1; then nsenter -t 1 -m -- "$@"; return; fi
  for bb in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox /data/adb/ap/bin/busybox; do
    [ -x "$bb" ] || continue
    "$bb" nsenter -t 1 -m -- "$@"; return
  done
  "$@"
}

legacy_same_file() {
  legacy_a=$(legacy_in_init stat -c %d:%i "$1" 2>/dev/null) || return 1
  legacy_b=$(legacy_in_init stat -c %d:%i "$2" 2>/dev/null) || return 1
  [ -n "$legacy_a" ] && [ "$legacy_a" = "$legacy_b" ]
}

legacy_entries() {
  for entry in /data/adb/post-fs-data.d/hyperos4-streamline.sh /data/adb/service.d/hyperos4-streamline.sh; do
    [ -f "$entry" ] && grep -qF "$OLD/runtime/boot.sh" "$entry" && printf '%s\n' "$entry"
  done
  return 0
}

legacy_pids() {
  pgrep -f "$OLD/runtime/(boot[.]sh (boot|start|early)|adclean[.]sh (boot|guard-daemon|wlog-daemon|rot-daemon|cycle-watch|fps-daemon|adb-diag-daemon)|battery/charge[.]sh daemon)$"
  pgrep -f "inotifyd $OLD/state/ota_hook[.]sh"
  return 0
}

# 对比实际 inode，不能仅凭目标有 bind 就卸载其他模块的挂载。
legacy_mounts() {
  for pair in "/system/etc/hosts|$OLD/state/hosts" \
      "/sys/class/power_supply/battery/cycle_count|$OLD_CYCLE/bat/cycle_count" \
      "/sys/class/xm_power/fg_master/cyclecount|$OLD_CYCLE/fg/cyclecount"; do
    target=${pair%%\|*}; source=${pair#*\|}
    legacy_same_file "$target" "$source" && printf '%s\n' "$pair"
  done
  for target in /product/etc/device_features/*.xml; do
    legacy_same_file "$target" "$OLD_DEV/device_features.xml" && printf '%s|%s\n' "$target" "$OLD_DEV/device_features.xml"
  done
  for target in /product/etc/displayconfig/*.xml /vendor/etc/displayconfig/*.xml /odm/etc/displayconfig/*.xml; do
    source=$OLD_DEV/displayconfig/${target##*/}
    legacy_same_file "$target" "$source" && printf '%s|%s\n' "$target" "$source"
  done
  return 0
}

legacy_find() {
  legacy_entries | while IFS= read -r entry; do printf 'entry|%s\n' "$entry"; done
  legacy_pids | while IFS= read -r pid; do printf 'process|%s\n' "$pid"; done
  legacy_mounts | while IFS='|' read -r target source; do printf 'mount|%s\n' "$target"; done
  previous=$(cat "$CURRENT/state/legacy-reboot-required" 2>/dev/null)
  boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
  [ -n "$previous" ] && [ "$previous" = "$boot" ] && echo 'reboot|1'
  return 0
}

legacy_check() { [ -z "$(legacy_find)" ]; }

legacy_deactivate() {
  [ "$(id -u)" = 0 ] || { echo 'ERR|legacy|需要 Root 权限'; return 1; }
  umask 077
  mkdir -p "$CURRENT/state"
  exec 9>>"$CURRENT/legacy.flock" || return 1
  flock -xn 9 9>&9 || { echo 'ERR|legacy|正在停用旧后台，请稍后重试'; return 1; }
  # 入口原位置与完整脚本均留在本模块私有 Root 目录，方便人工恢复。
  backup=$CURRENT/state/legacy-disabled/$(date +%s)-$$
  mkdir -p "$backup" || return 1
  for entry in $(legacy_entries); do
    destination=$backup/${entry%/*}; destination=$destination/${entry##*/}
    mkdir -p "${destination%/*}" || return 1
    mv "$entry" "$destination" || { echo 'ERR|legacy|旧开机入口停用失败'; return 1; }
    printf '%s\t%s\n' "$entry" "$destination" >> "$backup/entries.tsv"
  done
  need_reboot=0
  # 早期属性和 Zygote 缓存无法用一次运行时卸载可靠恢复，必须重新开机获取干净基线。
  if grep -q '^enabled=1$' "$OLD/state/devmodel/config" 2>/dev/null ||
      [ -f "$OLD/state/devmodel/restore-reboot-required" ]; then need_reboot=1; fi
  mounts=$(legacy_mounts)
  # 任意旧绑定都可能已传播至其他 mount namespace；当前会话卸载不能证明全系统干净。
  [ -n "$mounts" ] && need_reboot=1
  pids=$(legacy_pids)
  for pid in $pids; do kill "$pid" 2>/dev/null || true; done
  # charge 的 TERM trap 先交还其自己持有的充电控制，避免直接 KILL 遗留停充票。
  n=0
  while [ -n "$(legacy_pids)" ] && [ "$n" -lt 30 ]; do sleep 0.1; n=$((n + 1)); done
  remaining=$(legacy_pids)
  [ -n "$remaining" ] && need_reboot=1
  for pid in $remaining; do kill -KILL "$pid" 2>/dev/null || true; done
  # 旧版被强杀/重启过也可能遗留中性的 MCA 票；仅写旧 voter，绝不碰新 hypercraft 票。
  for node in /sys/class/xm_power/charger/charge_interface/charge_enable /sys/class/xm_power/charger/charge_interface/input_suspend; do
    [ -f "$node" ] || continue
    grep -q 'hyperadclean' "$node" 2>/dev/null || continue
    case "$node" in */charge_enable) printf 'hyperadclean all 1\n' > "$node" ;; *) printf 'hyperadclean all 0\n' > "$node" ;; esac
  done
  failed=0
  while IFS='|' read -r target source; do
    [ -n "$target" ] || continue
    n=0
    while legacy_same_file "$target" "$source" && [ "$n" -lt 6 ]; do
      legacy_in_init umount "$target" 2>/dev/null || break
      n=$((n + 1))
    done
    if legacy_same_file "$target" "$source"; then
      printf 'ERR|legacy|旧绑定未能解除：%s，请重启后重新检查\n' "$target"
      failed=1
    fi
  done <<EOF
$mounts
EOF
  if [ "$need_reboot" = 1 ]; then
    cat /proc/sys/kernel/random/boot_id > "$CURRENT/state/legacy-reboot-required"
    echo 'WARN|legacy|旧早期机型、色彩或降帧配置需要重启才能清除；重启前不会启用新后台'
  fi
  [ "$failed" = 0 ] || return 1
  [ -z "$(legacy_pids)" ] || { echo 'ERR|legacy|旧后台仍在运行，请重启后重新检查'; return 1; }
  printf 'OK|legacy|旧后台与开机入口已停用，保留旧应用和配置；入口备份：%s\n' "$backup"
}

case "${1:-status}" in
  status) found=$(legacy_find); [ -z "$found" ] || printf '%s\n' "$found"; [ -n "$found" ] && echo 'active|1' || echo 'active|0' ;;
  check) legacy_check ;;
  deactivate) legacy_deactivate ;;
  *) exit 1 ;;
esac
