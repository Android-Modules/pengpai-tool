SKIPUNZIP=0
DATA=/data/adb/hyperadclean

ui_print "- 澎湃小工具"
ui_print "- 机型: $(getprop ro.product.marketname) / $(getprop ro.mi.os.version.incremental)"

mkdir -p $DATA
if [ ! -f $DATA/config ]; then
  cat > $DATA/config <<EOF
backup_dir=/storage/emulated/0/HyperAdClean
backup_apk=1
keep_on_boot=1
restore_on_uninstall=1
default_mode=disable
hosts_xiaomi=0
hosts_sdk=0
hosts_custom=0
EOF
fi

# 记录原始 hosts（仅在尚未被本模块覆盖时）
if [ ! -f $DATA/hosts ] || [ "$(stat -c %d:%i /system/etc/hosts)" != "$(stat -c %d:%i $DATA/hosts)" ]; then
  cp -f /system/etc/hosts $DATA/hosts.orig
fi
sh $MODPATH/adclean.sh hosts-build

set_perm_recursive $MODPATH 0 0 0755 0644
set_perm $MODPATH/adclean.sh 0 0 0755
set_perm $MODPATH/devmodel/control.sh 0 0 0755
set_perm $MODPATH/devmodel/restart-framework.sh 0 0 0755
set_perm $MODPATH/battery/cycle.sh 0 0 0755
set_perm $MODPATH/battery/charge.sh 0 0 0755
[ -d /data/adb/modules/LPKC ] && [ ! -f /data/adb/modules/LPKC/disable ] && \
  ui_print "- 检测到「米系亮屏快充」模块：它针对旧款骁龙机型，会用空文件覆盖温控配置，建议停用，改用本模块的充电控制"

# 接管已安装的独立模块：搬运它们的配置与记录（旧模块继续生效，直到你在 App 里停用它）
sh $MODPATH/adclean.sh migrate
for id in changephone fake_battery_cycle baize_remote_cpu_guard; do
  [ -d /data/adb/modules/$id ] && [ ! -f /data/adb/modules/$id/disable ] && \
    ui_print "- 检测到独立模块 $id，配置已迁移；在 App 中停用它后由本模块接管"
done
# 机型修改：首次安装时记录固件原始机型（已迁移旧基线则跳过）
[ -f /data/adb/hyperadclean/devmodel/stock.props ] || sh $MODPATH/devmodel/control.sh capture-stock >/dev/null 2>&1

if [ -f $MODPATH/app/HyperAdClean.apk ]; then
  ui_print "- 安装管理 App「澎湃小工具」"
  if sh $MODPATH/adclean.sh app-ensure | grep -q OK; then
    ui_print "  已安装，请在 KernelSU「超级用户」里给它授权 Root"
  else
    ui_print "  安装失败，开机后会自动重试"
  fi
fi

ui_print "- 安装完成，本模块不会自动精简任何东西"
ui_print "- 在桌面打开「澎湃小工具」管理"
