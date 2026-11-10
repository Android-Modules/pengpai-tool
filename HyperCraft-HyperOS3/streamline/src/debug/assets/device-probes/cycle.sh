#!/system/bin/sh
. "${0%/*}/device-write-common.sh"
C=$D/battery/config.txt
cp "$C" "$W/cycle.before"
original=$(cat /sys/class/power_supply/battery/cycle_count)
cleanup() {
  mod cycle setmode off
  eq cycle.real.restored "$(cat /sys/class/power_supply/battery/cycle_count)" "$original"
  mod cycle status | grep -q '"bound_bat":0,"bound_fg":0' && ok cycle.unmounted || bad cycle.unmounted
  cp "$W/cycle.before" "$C"
  restore_config
}
trap cleanup EXIT
mod cycle setmode fixed 33
eq cycle.fake.applied "$(cat /sys/class/power_supply/battery/cycle_count)" 33
eq cycle.init.visible "$(/data/adb/ksu/bin/busybox nsenter -t 1 -m -- cat /sys/class/power_supply/battery/cycle_count)" 33
mod cycle status | grep -q '"bound_bat":1' && ok cycle.bound || bad cycle.bound
cleanup
trap - EXIT
summary
