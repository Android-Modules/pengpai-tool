#!/system/bin/sh
# KernelSU 在开机早期执行本脚本，此时 pm 还不可用，
# 所以把核心脚本拷出模块目录，放到后台等开机完成后再卸载 App、恢复改动。
DATA=/data/adb/hyperadclean
[ -d $DATA ] || exit 0
cp -f ${0%/*}/adclean.sh $DATA/adclean.sh
setsid nohup sh $DATA/adclean.sh uninstall-restore >/dev/null 2>&1 &
