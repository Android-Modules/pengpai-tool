#!/system/bin/sh
# Explicit, reversible package control. Never block a shared system UID or touch
# Qualcomm's similarly named SystemHelper, SIM activation, telephony or user data.
umask 077
RUNTIME=${0%/*}
. "$RUNTIME/env.sh"
STATE=/data/adb/hypercraft/state/reporting
PACKAGES='com.mobiletools.systemhelper com.miui.guardprovider'
mkdir -p "$STATE" || exit 1
exec 8>>"$STATE/operation.flock" || exit 1
flock -x 8 8>&8 || exit 1

error() { printf 'ERR|reporting|%s\n' "$1"; return 1; }
mode() { cat "$STATE/mode" 2>/dev/null || echo off; }
set_mode() { printf '%s\n' "$1" > "$STATE/mode.new" && mv -f "$STATE/mode.new" "$STATE/mode"; }

# Read the full installed inventory first, so a failed PackageManager query is
# never mistaken for an absent package. Enabled states 0..4 must restore exactly.
refresh_packages() {
  inventory=$(pm list packages --user 0 2>/dev/null) || return 1
  printf '%s\n' "$inventory" | grep -qx 'package:android'
}
package_state() {
  printf '%s\n' "$inventory" | grep -qx "package:$1" || { echo x; return; }
  dumpsys package "$1" 2>/dev/null | awk '
    /^[[:space:]]*User 0:.*installed=true/ {
      for (i=1; i<=NF; i++) if ($i ~ /^enabled=[0-4]$/) {
        sub(/^enabled=/,"",$i); print $i; found=1; exit
      }
    }
    END { if (!found) exit 1 }'
}
valid_baseline() {
  [ -s "$STATE/original" ] || return 1
  awk -F '|' '
    $1!="com.mobiletools.systemhelper" && $1!="com.miui.guardprovider" { bad=1 }
    NF!=2 || $2!~/^([0-4]|x)$/ || seen[$1]++ { bad=1 }
    END { exit bad || NR!=2 }' "$STATE/original"
}
snapshot() {
  if [ "$(mode)" != off ]; then valid_baseline; return; fi
  # An interrupted completed restore (or snapshot before publishing "on") can
  # leave a stale baseline while off. No package writes are pending in this mode.
  rm -f "$STATE/original" || return 1
  refresh_packages || return 1
  : > "$STATE/original.new" || return 1
  available=0
  for package in $PACKAGES; do
    state=$(package_state "$package") || return 1
    [ "$state" = x ] || available=$((available + 1))
    printf '%s|%s\n' "$package" "$state" >> "$STATE/original.new" || return 1
  done
  [ "$available" -gt 0 ] || return 1
  mv -f "$STATE/original.new" "$STATE/original"
}
apply_disabled() {
  valid_baseline && refresh_packages || return 1
  failed=0
  while IFS='|' read -r package original; do
    [ "$original" != x ] || continue
    actual=$(package_state "$package") || { failed=1; continue; }
    [ "$actual" != x ] || { failed=1; continue; }
    if [ "$actual" != 3 ]; then
      # PackageManager kills the disabled package's processes. force-stop would
      # also change its stopped flag, preventing an exact enable-state restore.
      pm disable-user --user 0 "$package" </dev/null >/dev/null 2>&1 || failed=1
    fi
    [ "$(package_state "$package")" = 3 ] || failed=1
  done < "$STATE/original"
  [ "$failed" = 0 ]
}
restore() {
  valid_baseline && refresh_packages || return 1
  failed=0
  while IFS='|' read -r package original; do
    [ "$original" != x ] || continue
    actual=$(package_state "$package") || { failed=1; continue; }
    [ "$actual" != x ] || { failed=1; continue; }
    [ "$actual" != "$original" ] || continue
    case "$original" in
      0) action=default-state ;; 1) action=enable ;; 2) action=disable ;;
      3) action=disable-user ;; 4) action=disable-until-used ;;
    esac
    pm "$action" --user 0 "$package" </dev/null >/dev/null 2>&1 || failed=1
    [ "$(package_state "$package")" = "$original" ] || failed=1
  done < "$STATE/original"
  [ "$failed" = 0 ]
}
turn_off() {
  if [ ! -e "$STATE/original" ]; then
    [ "$(mode)" = off ] || { error '恢复基线缺失，未猜测原始设置'; return; }
    return 0
  fi
  set_mode restoring || return 1
  restore || { error '部分组件未恢复，已保留原始状态，请重试'; return; }
  # Removing the mode first keeps an interrupted cleanup recognizable as off.
  set_mode off || return 1
  rm -f "$STATE/original" "$STATE/original.new"
}
turn_on() {
  case "$(mode)" in
    on|off) ;;
    *) error '上次恢复尚未完成或状态异常，请先关闭开关重试恢复'; return ;;
  esac
  if [ ! -e "$STATE/original" ] && [ "$(mode)" != off ]; then
    error '原始状态缺失，未覆盖恢复记录'; return
  fi
  snapshot || { error '无法读取原始状态，或本机没有目标组件，未停用'; return; }
  # Durable intent before any package mutation: interruption/boot replay never
  # takes a new baseline from already-disabled values.
  set_mode on || return 1
  if ! apply_disabled; then
    turn_off >/dev/null 2>&1
    error '停用未完成，已尝试恢复；请刷新查看，恢复失败时保留基线'; return
  fi
  echo 'OK|reporting|已停用本机目标组件，关闭开关可恢复原始状态'
}
status() {
  current_mode=$(mode)
  refresh_packages || { echo "unknown|unknown|unknown"; return 1; }
  helper=$(package_state com.mobiletools.systemhelper) || helper=unknown
  guard=$(package_state com.miui.guardprovider) || guard=unknown
  printf '%s|%s|%s\n' "$current_mode" "$helper" "$guard"
}

case "$1" in
  status) status ;;
  on) turn_on ;;
  off) turn_off && echo 'OK|reporting|已按启用前状态恢复，原本停用的组件保持停用' ;;
  boot)
    case "$(mode)" in
      on) apply_disabled || error '开机停用检查未完成，保留基线' ;;
      restoring) turn_off ;;
    esac ;;
  *) error '未知操作' ;;
esac
