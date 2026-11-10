#!/system/bin/sh
M=/data/adb/hypercraft/runtime/adclean.sh
D=/data/adb/hypercraft/state
PLAN=${0##*/}; PLAN=${PLAN#device-write-}; PLAN=${PLAN%.sh}
W=/data/adb/hypercraft/device-acceptance/$PLAN
umask 077
mkdir -p "$W"
cp "$D/config" "$W/config.before"
: > "$W/settings.before"
pass=0; fail=0
ok() { echo "PASS|$*"; pass=$((pass + 1)); }
bad() { echo "FAIL|$*"; fail=$((fail + 1)); }
eq() { if [ "$2" = "$3" ]; then ok "$1|$2"; else bad "$1|expected=$3 actual=$2"; fi; }
mod() { sh "$M" "$@"; }
remember() { printf '%s|%s|%s\n' "$1" "$2" "$(settings get "$1" "$2")" >> "$W/settings.before"; }
restore_setting() { if [ "$3" = null ]; then settings delete "$1" "$2" </dev/null >/dev/null; else settings put "$1" "$2" "$3" </dev/null; fi; }
restore_settings() {
  while IFS='|' read -r ns key value; do
    restore_setting "$ns" "$key" "$value"
    eq "restore.$ns.$key" "$(settings get "$ns" "$key" </dev/null)" "$value"
  done < "$W/settings.before"
}
restore_config() { cp "$W/config.before" "$D/config"; cmp -s "$W/config.before" "$D/config" && ok config.restored || bad config.restored; }
summary() { echo "SUMMARY|$PLAN|pass=$pass|fail=$fail"; [ "$fail" = 0 ]; }
