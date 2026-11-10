#!/system/bin/sh
. "${0%/*}/device-write-common.sh"
cp "$D/tweaks.bak" "$W/tweaks.before"
overlay=$(mod fps-overlay)
cleanup() {
  mod fps-set 0 smart >/dev/null
  mod fps-overlay "$overlay" >/dev/null
  restore_settings
  cp "$W/tweaks.before" "$D/tweaks.bak"
  restore_config
}
trap cleanup EXIT
for spec in 'system font_scale' 'secure miui_refresh_rate' 'secure user_refresh_rate' 'system status_bar_show_notification_icon' 'system status_bar_hide_icon_slot_name' 'global development_settings_enabled' 'global adb_wifi_enabled' 'secure upload_debug_log_pref' 'system predownload_cloud_enable' 'system com.android.browser.enable_app_chooser_recommend'; do
  set -- $spec; remember "$1" "$2"
done
mod font-set 0.98
eq font.live "$(settings get system font_scale)" 0.98
eq font.config "$(grep '^font_scale=' "$D/config" | cut -d= -f2)" 0.98
mod font-set 0
eq font.cancel_boot "$(grep '^font_scale=' "$D/config" | cut -d= -f2)" 0
for spec in 'debuglog secure upload_debug_log_pref' 'predl system predownload_cloud_enable' 'chooser system com.android.browser.enable_app_chooser_recommend'; do
  set -- $spec; id=$1; ns=$2; key=$3; orig=$(settings get "$ns" "$key")
  mod tweak-on "$id"
  eq "tweak.$id.enabled" "$(settings get "$ns" "$key")" 0
  mod tweak-off "$id"
  eq "tweak.$id.restore" "$(settings get "$ns" "$key")" "$orig"
done
for spec in 'system status_bar_show_notification_icon 0' 'system status_bar_hide_icon_slot_name alarm_clock' 'global development_settings_enabled 1' 'global adb_wifi_enabled 1'; do
  set -- $spec; settings put "$1" "$2" "$3"
  eq "direct.$2" "$(settings get "$1" "$2")" "$3"
done
for hz in 60 120 144; do
  mod fps-set "$hz" smart
  eq "fps.smart.$hz" "$(settings get secure miui_refresh_rate)" "$hz"
  eq "fps.smart.user.$hz" "$(settings get secure user_refresh_rate)" "$hz"
done
mod fps-set 60 force
sleep 2
state=$(mod fps-status); echo "OBSERVE|force|$state"
echo "$state" | grep -q '^60|60|1|' && ok fps.force60 || bad fps.force60
mod fps-set 0 smart
sleep 1
eq fps.daemon.stopped "$(mod fps-status | cut -d'|' -f3)" 0
mod fps-overlay 1
eq fps.overlay.on "$(mod fps-overlay)" 1
mod fps-overlay 0
eq fps.overlay.off "$(mod fps-overlay)" 0
for prop in persist.security.adbinstall persist.security.adbinput; do
  orig=$(getprop "$prop")
  setprop "$prop" 1
  eq "property.$prop" "$(getprop "$prop")" 1
  setprop "$prop" "$orig"
  eq "restore.property.$prop" "$(getprop "$prop")" "$orig"
done
cleanup
trap - EXIT
summary
