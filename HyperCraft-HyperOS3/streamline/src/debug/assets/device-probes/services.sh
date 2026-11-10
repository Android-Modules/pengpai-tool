#!/system/bin/sh
# Debug acceptance only. Runtime services/configuration are restored even after failure.
[ "$(id -u)" = 0 ] || { echo 'FAIL|services.root'; exit 1; }
. "${0%/*}/device-write-common.sh"
. "${M%/*}/env.sh"
mod() { sh "$M" "$@" </dev/null; }
# Binder tools must never consume the records from an enclosing read loop.
remember() { printf '%s|%s|%s\n' "$1" "$2" "$(settings get "$1" "$2" </dev/null)" >> "$W/settings.before"; }
restore_settings() {
  while IFS='|' read -r ns key value; do
    actual=$(settings get "$ns" "$key" </dev/null)
    [ "$actual" = "$value" ] || restore_setting "$ns" "$key" "$value"
    eq "restore.$ns.$key" "$(settings get "$ns" "$key" </dev/null)" "$value"
  done < "$W/settings.before"
}
original() {
  saved=$(grep -m1 "^$1=" "$W/config.before" | cut -d= -f2-)
  printf '%s\n' "${saved:-$2}"
}
current() { grep -m1 "^$1=" "$D/config" | cut -d= -f2-; }
request() {
  response=$(mod "$@")
  printf 'OBSERVE|%s|%s\n' "$*" "$response"
  if printf '%s\n' "$response" | grep -q '^ERR|' || ! printf '%s\n' "$response" | grep -q '^OK|'; then
    bad "command.$*"; return 1
  fi
}
running() {
  if pgrep -f "/data/adb/hypercraft/runtime/adclean[.]sh $1$" >/dev/null; then echo 1; else echo 0; fi
}
wait_running() {
  attempts=0
  while [ "$(running "$1")" != "$2" ] && [ "$attempts" -lt 25 ]; do
    sleep 0.2; attempts=$((attempts + 1))
  done
  eq "$3" "$(running "$1")" "$2"
  [ "$(running "$1")" = "$2" ]
}
stop_daemon() {
  for probe_pid in $(pgrep -f "/data/adb/hypercraft/runtime/adclean[.]sh $1$"); do
    kill "$probe_pid" 2>/dev/null || true
  done
  wait_running "$1" 0 "$2"
}
save_file() {
  if [ -f "$1" ]; then cp -p "$1" "$W/file.$2" || return 1; present=1; else present=0; fi
  printf '%s|%s|%s\n' "$1" "$2" "$present" >> "$W/files.before"
}
restore_files() {
  while IFS='|' read -r file label present; do
    if [ "$present" = 1 ]; then
      cp -p "$W/file.$label" "$file"
      cmp -s "$W/file.$label" "$file" && ok "restore.file.$label" || bad "restore.file.$label"
    else
      # Only files known to be absent before this probe; never WLAN/vendor logs.
      rm -f "$file"
      [ ! -e "$file" ] && ok "restore.file.$label.absent" || bad "restore.file.$label.absent"
    fi
  done < "$W/files.before"
}
sync_value() { mod sync-status | awk -F'|' -v id="$1" '$1=="user" && $2==id {print $4}'; }
restore_sync_users() {
  while IFS='|' read -r uid value; do
    reply=$(mod sync-set "$uid" "$value")
    printf '%s\n' "$reply" | grep -q '^ERR|' && bad "restore.sync.user.$uid.command"
    eq "restore.sync.user.$uid" "$(sync_value "$uid")" "$value"
  done < "$W/sync.users.before"
}

[ -s "$W/config.before" ] && [ -f "$M" ] || { bad services.prerequisite; exit 1; }
: > "$W/files.before"
: > "$W/sync.users.before"
: > "$W/daemons.before.tmp"
for daemon in guard-daemon wlog-daemon rot-daemon adb-diag-daemon; do
  printf '%s|%s\n' "$daemon" "$(running "$daemon")" >> "$W/daemons.before.tmp"
done
mv "$W/daemons.before.tmp" "$W/daemons.before"
for spec in 'system accelerometer_rotation' 'system user_rotation' 'secure sync_on_wifi_only' 'global adb_enabled' 'global adb_wifi_enabled' 'global development_settings_enabled'; do
  set -- $spec; remember "$1" "$2"
done
mod sync-status > "$W/sync.before"
awk -F'|' '$1=="user" {print $2 "|" $4}' "$W/sync.before" > "$W/sync.users.before"
save_file "$D/wlanlog/last_ts" wlan_last_ts || { bad snapshot.wlan_last_ts; exit 1; }
# setconf also synchronizes these four backup files; preserve their initial contents.
backup_dir=$(original backup_dir /storage/emulated/0/HyperCraft)
case "$backup_dir" in
  /storage/emulated/0*) backup_dir=/data/media/0${backup_dir#/storage/emulated/0} ;;
  /sdcard*) backup_dir=/data/media/0${backup_dir#/sdcard} ;;
esac
for leaf in config applied.list tweaks.bak hosts.custom; do
  save_file "$backup_dir/$leaf" "backup_$leaf" || { bad "snapshot.backup.$leaf"; exit 1; }
done

cleanup() {
  trap - EXIT HUP INT TERM
  # Stop test services before restoring configuration so no test callback races it.
  for daemon in guard-daemon wlog-daemon rot-daemon adb-diag-daemon; do
    stop_daemon "$daemon" "cleanup.$daemon.stopped"
  done
  # No sync daemon is started by sync-boot; disable its policy before restoring users.
  mod sync-boot 0 >/dev/null
  restore_sync_users
  restore_settings
  restore_files
  restore_config
  # Restore actual initial process state, including enabled-but-not-running services.
  # Calling adb-diag-status here would start its daemon and invalidate this check.
  while IFS='|' read -r daemon was_running; do
    if [ "$was_running" = 1 ]; then
      setsid nohup sh "$M" "$daemon" </dev/null >/dev/null 2>&1 &
    fi
    wait_running "$daemon" "$was_running" "restore.$daemon.running"
  done < "$W/daemons.before"
  cmp -s "$W/config.before" "$D/config" && ok config.final_restored || bad config.final_restored
  summary
}
trap 'probe_exit=$?; cleanup; cleanup_exit=$?; [ "$probe_exit" = 0 ] && exit "$cleanup_exit"; exit "$probe_exit"' EXIT
trap 'bad services.interrupted; exit 130' HUP INT TERM
for daemon in guard-daemon wlog-daemon rot-daemon adb-diag-daemon; do
  stop_daemon "$daemon" "setup.$daemon.stopped" || exit 1
done

# Each user's master sync, Wi-Fi policy, and immediate behavior of the boot policy.
eq sync.api "$(awk -F'|' '$1=="api" {print $2}' "$W/sync.before")" 1
[ "$(awk -F'|' '$1=="api" {print $2}' "$W/sync.before")" = 1 ] || exit 1
request sync-boot 0 || exit 1
while IFS='|' read -r uid value; do
  flipped=$((1 - value))
  request sync-set "$uid" "$flipped" || exit 1
  eq "sync.user.$uid.flipped" "$(sync_value "$uid")" "$flipped"
  request sync-set "$uid" "$value" || exit 1
  eq "sync.user.$uid.restored" "$(sync_value "$uid")" "$value"
done < "$W/sync.users.before"
wifi_before=$(awk -F'|' '$1=="wifi" {print $2}' "$W/sync.before")
request sync-set wifi "$((1 - wifi_before))" || exit 1
eq sync.wifi.flipped "$(settings get secure sync_on_wifi_only </dev/null)" "$((1 - wifi_before))"
request sync-set wifi "$wifi_before" || exit 1
eq sync.wifi.restored "$(settings get secure sync_on_wifi_only </dev/null)" "$wifi_before"
request sync-boot 1 || exit 1
eq sync.boot.enabled "$(current sync_boot_off)" 1
while IFS='|' read -r uid value; do eq "sync.boot.user.$uid.off" "$(sync_value "$uid")" 0; done < "$W/sync.users.before"
eq sync.boot.wifi.off "$(settings get secure sync_on_wifi_only </dev/null)" 0
request sync-boot 0 || exit 1
eq sync.boot.disabled "$(current sync_boot_off)" 0
restore_sync_users
restore_setting secure sync_on_wifi_only "$(awk -F'|' '$1=="secure" && $2=="sync_on_wifi_only" {print $3}' "$W/settings.before")"

# Turning on corrects immediately; the 10-second daemon repairs a later change.
request rot-set enable 0 || exit 1
wait_running rot-daemon 0 rot.initial_stopped || exit 1
request rot-set interval 10 || exit 1
settings put system accelerometer_rotation 1 </dev/null
settings put system user_rotation 1 </dev/null
request rot-set enable 1 || exit 1
eq rot.immediate.auto "$(settings get system accelerometer_rotation </dev/null)" 0
eq rot.immediate.direction "$(settings get system user_rotation </dev/null)" 0
wait_running rot-daemon 1 rot.started || exit 1
sleep 1
settings put system accelerometer_rotation 1 </dev/null
settings put system user_rotation 1 </dev/null
attempts=0
while [ "$attempts" -lt 14 ]; do
  state="$(settings get system accelerometer_rotation </dev/null)|$(settings get system user_rotation </dev/null)"
  [ "$state" = '0|0' ] && break
  sleep 1; attempts=$((attempts + 1))
done
eq rot.periodic.corrected "$state" '0|0'
eq rot.interval "$(mod rot-status | cut -d'|' -f3)" 10
request rot-set enable 0 || exit 1
wait_running rot-daemon 0 rot.stopped || exit 1
settings put system accelerometer_rotation 1 </dev/null
settings put system user_rotation 0 </dev/null
sleep 1
eq rot.disabled.no_correction "$(settings get system accelerometer_rotation </dev/null)" 1

# Never exercise termination of a real target: threshold exceeds all CPU capacity.
request guard-set enable 0 || exit 1
wait_running guard-daemon 0 guard.initial_stopped || exit 1
request guard-set threshold 1000000 || exit 1
request guard-set interval 2 || exit 1
guard_count=$(mod guard-status | cut -d'|' -f5)
request guard-set enable 1 || exit 1
wait_running guard-daemon 1 guard.started || exit 1
eq guard.threshold "$(mod guard-status | cut -d'|' -f3)" 1000000
sleep 3
eq guard.still_running "$(running guard-daemon)" 1
eq guard.no_process_killed "$(mod guard-status | cut -d'|' -f5)" "$guard_count"
request guard-set enable 0 || exit 1
wait_running guard-daemon 0 guard.stopped || exit 1

# Prepare both flags atomically under config locks while stopped. Using wlog-set
# before both flags are off could restart the original cleaning/blocking policy.
stop_daemon wlog-daemon wlog.initial_stopped || exit 1
config_set "$D/config" wlog_enable 0 || exit 1
config_set "$D/config" wlog_block 0 || exit 1
request wlog-set interval 24 || exit 1
request wlog-set threshold 0 || exit 1
mkdir -p "$D/wlanlog"
date +%s > "$D/wlanlog/last_ts"
wlog_count=$(mod wlog-status | cut -d'|' -f7)
request wlog-set enable 1 || exit 1
wait_running wlog-daemon 1 wlog.started || exit 1
sleep 1
wlog_state=$(mod wlog-status)
eq wlog.safe.interval "$(printf '%s\n' "$wlog_state" | cut -d'|' -f3)" 24
eq wlog.safe.threshold "$(printf '%s\n' "$wlog_state" | cut -d'|' -f4)" 0
eq wlog.no_capture_block "$(printf '%s\n' "$wlog_state" | cut -d'|' -f5)" 0
eq wlog.no_real_log_cleanup "$(printf '%s\n' "$wlog_state" | cut -d'|' -f7)" "$wlog_count"
request wlog-set enable 0 || exit 1
wait_running wlog-daemon 0 wlog.stopped || exit 1

# Diagnostics is observational: do not toggle ADB transports or generate pairing.
request adb-diag-set 0 || exit 1
wait_running adb-diag-daemon 0 adb_diag.initial_stopped || exit 1
diag_started=$(date +%s)
request adb-diag-set 1 || exit 1
wait_running adb-diag-daemon 1 adb_diag.started || exit 1
attempts=0
while [ "$attempts" -lt 25 ]; do
  checked=$(stat -c %Y "$D/adb-diagnostics/checked" 2>/dev/null || echo 0)
  [ "$checked" -ge "$diag_started" ] && break
  sleep 0.2; attempts=$((attempts + 1))
done
[ "$checked" -ge "$diag_started" ] && ok adb_diag.fresh_sample || bad adb_diag.fresh_sample
adb_state=$(mod adb-diag-status)
eq adb_diag.enabled "$(printf '%s\n' "$adb_state" | awk -F'|' '$1=="enabled" {print $2}')" 1
eq adb_diag.running "$(printf '%s\n' "$adb_state" | awk -F'|' '$1=="running" {print $2}')" 1
[ -s "$D/adb-diagnostics/state" ] && ok adb_diag.snapshot || bad adb_diag.snapshot
request adb-diag-set 0 || exit 1
wait_running adb-diag-daemon 0 adb_diag.stopped || exit 1
while IFS='|' read -r ns key value; do
  [ "$ns" = global ] && eq "adb_diag.unchanged.$key" "$(settings get "$ns" "$key" </dev/null)" "$value"
done < "$W/settings.before"

for key in backup_apk keep_on_boot restore_on_uninstall; do
  before=$(original "$key" 1)
  flipped=$((1 - before))
  request setconf "$key" "$flipped" || exit 1
  eq "setconf.$key.flipped" "$(mod info | awk -F'|' -v k="$key" '$1==k {print $2}')" "$flipped"
  request setconf "$key" "$before" || exit 1
  eq "setconf.$key.restored" "$(current "$key")" "$before"
done
exit 0
