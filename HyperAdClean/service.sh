#!/system/bin/sh
MODDIR=${0%/*}
sh $MODDIR/adclean.sh ota-early
grep -qE '^hosts_(xiaomi|sdk|custom|antifraud)=1' /data/adb/hyperadclean/config 2>/dev/null && sh $MODDIR/adclean.sh hosts-mount
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 3; done
sleep 20
# OTA 或小米云控可能把冻结的组件/开关恢复，这里按记录重新应用
sh $MODDIR/adclean.sh boot
