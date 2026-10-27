#!/system/bin/sh
MODDIR=${0%/*}
DATA=/data/adb/hyperadclean
# 机型修改：在 Zygote 启动前重放，让新进程直接读到修改后的 Build 属性（旧的独立模块启用时让给它）
if grep -q '^enabled=1$' $DATA/devmodel/config 2>/dev/null && \
   { [ ! -d /data/adb/modules/changephone ] || [ -f /data/adb/modules/changephone/disable ] || [ -f /data/adb/modules/changephone/remove ]; }; then
  EARLY_BOOT=1 sh $MODDIR/devmodel/control.sh boot >/dev/null 2>&1
fi
# 尽早挂载 hosts（如果被 KSU 魔术挂载盖住，service.sh 会再挂一次）
grep -qE '^hosts_(xiaomi|sdk|custom|antifraud)=1' $DATA/config 2>/dev/null && sh $MODDIR/adclean.sh hosts-mount
# 完整色彩风格：FeatureParser 在 Zygote 里初始化，必须赶在 Zygote 启动前挂载
if grep -q '^color_full=1$' $DATA/config 2>/dev/null; then sh $MODDIR/adclean.sh color-boot; else echo 0 > $DATA/color.boot; fi
# 强制（省电）的降帧等待：SurfaceFlinger 和 DisplayManager 都只在启动时读，必须在这里改
if grep -q '^fps_method=hold$' $DATA/config 2>/dev/null; then sh $MODDIR/adclean.sh fps-idle-boot; else echo 0 > $DATA/idle.boot; fi
exit 0
