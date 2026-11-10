#!/system/bin/sh
. "${0%/*}/device-write-common.sh"
H=/data/adb/hypercraft/device-acceptance/hold
mkdir -p "$H"
cp "$D/config" "$H/config.before"
remember secure miui_refresh_rate
remember secure user_refresh_rate
cp "$W/settings.before" "$H/settings.before"
getprop debug.sf.set_idle_timer_ms > "$H/property.before"
mod fps-idle 3000
mod fps-set 60 hold
eq hold.staged.method "$(sed -n 's/^fps_method=//p' "$D/config")" hold
eq hold.staged.lock "$(sed -n 's/^fps_lock=//p' "$D/config")" 60
summary
