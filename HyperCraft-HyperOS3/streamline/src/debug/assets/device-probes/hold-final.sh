#!/system/bin/sh
. "${0%/*}/device-write-common.sh"
H=/data/adb/hypercraft/device-acceptance/hold
eq hold.restored.property "$(getprop debug.sf.set_idle_timer_ms)" "$(cat "$H/property.before")"
eq hold.restored.marker "$(cat "$D/idle.boot")" 0
cp "$H/settings.before" "$W/settings.before"
while IFS='|' read -r ns key value; do eq "hold.restored.$key" "$(settings get "$ns" "$key" </dev/null)" "$value"; done < "$W/settings.before"
summary
