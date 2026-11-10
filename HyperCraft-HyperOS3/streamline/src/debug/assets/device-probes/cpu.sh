#!/system/bin/sh
. "${0%/*}/device-write-common.sh"
F=/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq
original=$(cat "$F"); mode=$(stat -c %a "$F")
cleanup() {
  mod cpu-enable 0
  eq cpu.permission.restored "$(stat -c %a "$F")" "$mode"
  eq cpu.frequency.restored "$(cat "$F")" "$original"
  restore_config
}
trap cleanup EXIT
min=$(cat "${F%/*}/scaling_min_freq")
chosen=$(tr ' ' '\n' < "${F%/*}/scaling_available_frequencies" | awk -v o="$original" -v m="$min" '$1<o && $1>=m { v=$1 } END { print v }')
[ -n "$chosen" ] || chosen=$original
mod cpu-set policy0 "$chosen"
eq cpu.frequency.applied "$(cat "$F")" "$chosen"
eq cpu.permission.preserved "$(stat -c %a "$F")" "$mode"
mod cpu-status | grep -qx 'enable|1' && ok cpu.enabled || bad cpu.enabled
cleanup
trap - EXIT
summary
