#!/system/bin/sh
. "${0%/*}/device-write-common.sh"
C=$D/devmodel/config
[ -f "$C" ] && cp "$C" "$W/model.before"
keys='ro.product.manufacturer ro.product.brand ro.product.marketname ro.product.model ro.product.name'
: > "$W/properties.before"
for key in $keys; do printf '%s|%s\n' "$key" "$(getprop "$key")" >> "$W/properties.before"; done
cleanup() {
  mod dm disable
  eq model.disable.exit "$?" 0
  state=$(mod dm status)
  echo "$state" | grep -q '^restore_pending_reboot=0$' && ok model.restore.immediate || bad model.restore.immediate
  while IFS='|' read -r key value; do eq "model.restore.$key" "$(getprop "$key")" "$value"; done < "$W/properties.before"
  [ -f "$W/model.before" ] && cp "$W/model.before" "$C"
  restore_config
}
trap cleanup EXIT
market=$(printf '%s' 'HyperCraft Probe' | base64 | tr -d '\n')
model=$(printf '%s' 'HC-PROBE' | base64 | tr -d '\n')
mod dm apply-custom '' '' "$market" "$model" ''
eq model.apply.exit "$?" 0
eq model.market.applied "$(getprop ro.product.marketname)" 'HyperCraft Probe'
eq model.model.applied "$(getprop ro.product.model)" HC-PROBE
cleanup
trap - EXIT
summary
