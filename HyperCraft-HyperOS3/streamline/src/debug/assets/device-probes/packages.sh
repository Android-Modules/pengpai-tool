#!/system/bin/sh
. "${0%/*}/device-write-common.sh"
P=io.github.dalong.hypercraft.probe
URI=content://com.miui.powerkeeper.configure/userTable
cleanup() {
  mod wl-set "$P" 0 >/dev/null
  dumpsys deviceidle whitelist -"$P" </dev/null >/dev/null
  cmd appops reset "$P" </dev/null >/dev/null 2>&1
  content delete --uri "$URI" --where "pkgName='$P' AND userId=0" </dev/null >/dev/null 2>&1
  mod restore "$P"
  pm uninstall "$P" </dev/null >/dev/null 2>&1
  restore_config
}
trap cleanup EXIT
pm install -r /data/local/tmp/hypercraft-device-probe.apk </dev/null
pm path "$P" </dev/null | grep -q '^package:' || { bad fixture.install; exit 1; }
ok fixture.install
mod restore "$P"
mod apply disable "$P"
pm list packages -d --user 0 </dev/null | grep -qx "package:$P" && ok package.disable || bad package.disable
mod restore "$P"
pm list packages -e --user 0 </dev/null | grep -qx "package:$P" && ok package.restore.enabled || bad package.restore.enabled
mod apply uninstall "$P"
pm list packages --user 0 </dev/null | grep -qx "package:$P" && bad package.uninstall || ok package.uninstall
mod restore "$P"
pm path --user 0 "$P" </dev/null | grep -q '^package:' && ok package.restore.from_apk || bad package.restore.from_apk
# 继续独立项目，安装恢复失败不得掩盖白名单和编译功能的结果。
pm path --user 0 "$P" </dev/null | grep -q '^package:' || pm install -r /data/local/tmp/hypercraft-device-probe.apk </dev/null
mod wl-set "$P" 1
dumpsys deviceidle whitelist </dev/null | grep -F "$P"
dumpsys deviceidle whitelist </dev/null | grep -q "user,$P," && ok whitelist.on || bad whitelist.on
content query --uri "$URI" --projection bgControl --where "pkgName='$P' AND userId=0" </dev/null | grep -q 'noRestrict' && ok whitelist.policy || bad whitelist.policy
mod wl-set "$P" 0
dumpsys deviceidle whitelist </dev/null | grep -q "user,$P," && bad whitelist.off || ok whitelist.off
content query --uri "$URI" --projection bgControl --where "pkgName='$P' AND userId=0" </dev/null | grep -q 'miuiAuto' && ok whitelist.policy.restore || bad whitelist.policy.restore
reply=$(mod apply disable com.android.systemui)
echo "OBSERVE|protected.reply|$reply"
echo "$reply" | grep -qE '^(SKIP|ERR)\|' && ok protected.systemui || bad protected.systemui
pm list packages -e --user 0 </dev/null | grep -qx 'package:com.android.systemui' && ok protected.systemui.unchanged || bad protected.systemui.unchanged
mod dexopt speed "$P"
for i in $(seq 1 30); do state=$(mod dexopt-status); echo "$state" | grep -q '^0|' && break; sleep 1; done
echo "OBSERVE|dexopt|$state"
mod dexopt-recent 2 | grep -q "$P|Success" && ok dexopt.fixture || bad dexopt.fixture
cleanup
trap - EXIT
summary
