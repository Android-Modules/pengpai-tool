#!/system/bin/sh
. "${0%/*}/device-write-common.sh"
H=/data/adb/hypercraft/device-acceptance/hold
mod fps-set 0 smart
cp "$H/config.before" "$D/config"
cp "$H/settings.before" "$W/settings.before"
restore_settings
cmp -s "$H/config.before" "$D/config" && ok hold.config.restored || bad hold.config.restored
summary
