#!/system/bin/sh
. "${0%/*}/device-write-common.sh"
eq hold.boot.property "$(getprop debug.sf.set_idle_timer_ms)" 3000
eq hold.boot.marker "$(cat "$D/idle.boot")" 3000
lock=$(sed -n 's/^fps_lock=//p' "$D/config")
if [ "${lock:-0}" = 0 ]; then
  expected=$(sed -n 's/^secure|user_refresh_rate|//p' /data/adb/hypercraft/device-acceptance/hold/settings.before)
else
  expected=$lock
fi
eq hold.current.refresh "$(settings get secure user_refresh_rate </dev/null)" "$expected"
# Some devices have no DisplayManager idle XML at all. Check the real SF timer,
# rather than requiring an XML bind that this device does not use.
interval=$(dumpsys SurfaceFlinger </dev/null | awk '/idleTimer=/{getline; if ($1 == "interval=3000.000") {print $1; exit}}')
eq hold.surfaceflinger.interval "$interval" interval=3000.000
init=$(readlink /proc/1/ns/mnt)
self=$(readlink /proc/self/ns/mnt)
echo "OBSERVE|hold.namespaces|self=$self init=$init"
sources=$(/data/adb/ksu/bin/busybox nsenter -t 1 -m -- sh -c 'grep -l "<idleScreenRefreshRateTimeout>" /product/etc/displayconfig/*.xml /vendor/etc/displayconfig/*.xml /odm/etc/displayconfig/*.xml 2>/dev/null' | wc -l)
files=$(/data/adb/ksu/bin/busybox nsenter -t 1 -m -- find /dev/hypercraft/displayconfig -type f 2>/dev/null | wc -l)
echo "OBSERVE|hold.xml|sources=$sources copies=$files"
if [ "$sources" -gt 0 ]; then
  [ "$files" -gt 0 ] && ok hold.xml.present || bad hold.xml.present
else
  echo "NOT_APPLICABLE|hold.xml|device uses SurfaceFlinger timer without idle XML"
fi
summary
