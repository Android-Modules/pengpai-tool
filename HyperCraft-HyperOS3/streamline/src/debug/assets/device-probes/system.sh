#!/system/bin/sh
. "${0%/*}/device-write-common.sh"
I=/data/adb/ksu/bin/busybox
afkeys='settings_sms_risk_warn_enable settings_sms_code_clipboard_warn_enable screen_share_protection ai_guard_function_switch com.android.browser.web_page_warning_states'
for key in $afkeys; do remember secure "$key"; done
remember system cloud_antispam
remember system key_yellowpage_main_switch
cp /system/etc/hosts "$W/hosts.content.before"
original_inode=$($I nsenter -t 1 -m -- stat -c %d:%i /system/etc/hosts)
[ -f "$D/hosts.custom" ] && cp "$D/hosts.custom" "$W/hosts.custom.before" || touch "$W/hosts.custom.absent"
component_state() {
  dumpsys package com.miui.securitycenter </dev/null | awk '
    /^    User 0: ceDataInode=/ { user=1; next }
    user && /^    User [0-9]+: ceDataInode=/ { exit }
    user && /^      (enabled|disabled)Components:/ { kind=$1; next }
    user && kind && /^        [A-Za-z]/ { print kind "|" $1; next }
    { kind="" }
  ' | sort
}
component_state > "$W/components.before"
before_af=$(mod af-status | cut -d'|' -f5-8)
cleanup() {
  reply=$(mod ota-off); echo "$reply"
  echo "$reply" | grep -q '^OK|' && ok ota.restore.result || bad ota.restore.result
  reply=$(mod af-off); echo "$reply"
  echo "$reply" | grep -q '^OK|' && ok antifraud.restore.result || bad antifraud.restore.result
  component_state > "$W/components.after"
  cmp -s "$W/components.before" "$W/components.after" && ok antifraud.components.restored || bad antifraud.components.restored
  eq antifraud.values.restored "$(mod af-status | cut -d'|' -f5-8)" "$before_af"
  restore_settings
  if [ -f "$W/hosts.custom.absent" ]; then rm -f "$D/hosts.custom"; else cp "$W/hosts.custom.before" "$D/hosts.custom"; fi
  restore_config
  mod hosts-build
  own_inode=$(stat -c %d:%i "$D/hosts")
  current_inode=$($I nsenter -t 1 -m -- stat -c %d:%i /system/etc/hosts)
  if [ "$original_inode" != "$own_inode" ] && [ "$current_inode" = "$own_inode" ]; then
    $I nsenter -t 1 -m -- umount /system/etc/hosts
  fi
  eq hosts.inode.restored "$($I nsenter -t 1 -m -- stat -c %d:%i /system/etc/hosts)" "$original_inode"
  $I nsenter -t 1 -m -- cat /system/etc/hosts > "$W/hosts.content.after"
  cmp -s "$W/hosts.content.before" "$W/hosts.content.after" && ok hosts.content.restored || bad hosts.content.restored
}
trap cleanup EXIT
# 根据当前手机能力检查，不能沿用 HyperOS 4 测试机没有更新程序的假设。
reply=$(mod ota-on); echo "$reply"
if pm path com.android.updater </dev/null | grep -q '^package:'; then
  echo "$reply" | grep -q '^OK|ota|' && ok ota.enabled || bad ota.enabled
  state=$(mod ota-status)
  echo "OBSERVE|ota|$state"
  echo "$state" | grep -q '^1|1|' && ok ota.firewall.active || bad ota.firewall.active
else
  echo "$reply" | grep -q '^ERR|ota|' && ok ota.unsupported.refused || bad ota.unsupported.refused
fi
reply=$(mod af-on); echo "$reply"
echo "$reply" | grep -q '^OK|' && ok antifraud.on || bad antifraud.on
for key in $afkeys; do eq "antifraud.disabled.$key" "$(settings get secure "$key" </dev/null)" 0; done
mod af-call 0
eq antifraud.call.off "$(settings get system cloud_antispam </dev/null)" 0
mod af-call 1
eq antifraud.call.on "$(settings get system cloud_antispam </dev/null)" 1
data=$(printf '%s\n' 'hypercraft-probe.invalid' | base64 | tr -d '\n')
mod hosts-custom-set "$data"
mod hosts-set custom 1
$I nsenter -t 1 -m -- grep -qx '0.0.0.0 hypercraft-probe.invalid' /system/etc/hosts && ok hosts.init.applied || bad hosts.init.applied
cleanup
trap - EXIT
summary
