#!/system/bin/sh
. "${0%/*}/device-write-common.sh"
S=$D/devmodel
for f in stock.props runtime-stock.props; do
  echo "OBSERVE|model.snapshot.$f"
  grep -E '^ro.product.(manufacturer|brand|marketname|model|name)\|' "$S/$f" 2>/dev/null
done
getprop | grep -F '[ro.product.manufacturer]:'
eq model.clean.manufacturer "$(getprop ro.product.manufacturer)" Xiaomi
eq model.clean.brand "$(getprop ro.product.brand)" Redmi
eq model.clean.marketname "$(getprop ro.product.marketname)" 'REDMI K90 Ultra'
eq model.clean.model "$(getprop ro.product.model)" M332BF
eq model.clean.product "$(getprop ro.product.name)" warsaw
if [ "$fail" = 0 ]; then
  mod dm capture-runtime-stock
  eq model.capture.exit "$?" 0
fi
state=$(mod dm status)
echo "$state"
echo "$state" | grep -qE '^baseline_source=(firmware|clean_boot)$' && ok model.baseline.available || bad model.baseline.available
echo "$state" | grep -q '^restore_pending_reboot=0$' && ok model.baseline.no_stale_reboot || bad model.baseline.no_stale_reboot
echo 'OBSERVE|model.legacy'
ls -ld /data/adb/modules/changephone /data/adb/modules/changephone/disable /data/adb/modules/changephone/remove 2>/dev/null
summary
