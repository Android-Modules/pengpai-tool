#!/system/bin/sh
. "${0%/*}/../env.sh"
# KSU 的 su 可能在应用私有 mount namespace 中执行；局部 bind 成功不代表系统能看见。
# 所有命令（包括查询/恢复）都进入 init 的 mount namespace，并验证实际身份避免递归。
cycle_self_ns=$(readlink /proc/self/ns/mnt 2>/dev/null)
cycle_init_ns=$(readlink /proc/1/ns/mnt 2>/dev/null)
if [ -z "$cycle_self_ns" ] || [ -z "$cycle_init_ns" ]; then
  echo 'FAIL: 无法确认系统挂载命名空间，未修改电量计节点'; exit 1
fi
if [ "$cycle_self_ns" != "$cycle_init_ns" ]; then
  [ "${HYPERCRAFT_CYCLE_NS_ATTEMPT:-0}" = 0 ] || {
    echo 'FAIL: 无法进入系统挂载命名空间，未修改电量计节点'; exit 1;
  }
  export HYPERCRAFT_CYCLE_NS_ATTEMPT=1
  if command -v nsenter >/dev/null 2>&1; then exec nsenter -t 1 -m -- sh "$0" "$@"; fi
  for cycle_bb in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox /data/adb/ap/bin/busybox; do
    [ -x "$cycle_bb" ] && exec "$cycle_bb" nsenter -t 1 -m -- sh "$0" "$@"
  done
  echo 'FAIL: 缺少 nsenter，未修改电量计节点'; exit 1
fi
# ============================================================
# 电池循环次数伪装 Pro - 核心逻辑
# 方案: tmpfs(context=) 生成正确 SELinux 标签的伪装文件
#       + bind-mount 覆盖 sysfs 循环次数节点
#       不写电量计芯片, 卸载模块即恢复真实值
# 用法: apply.sh {apply|restore|watch|refresh|status|setmode|setfixed|clearpk}
# ============================================================

# 集成进 HyperAdClean：状态放到 /data/adb/hypercraft/state/battery，临时挂载点与独立版区分
STATE=/data/adb/hypercraft/state/battery
mkdir -p $STATE
# 状态刷新、挂载恢复与配置变更串行，避免守护与界面同时操作节点。
exec 8>>"$STATE/operation.lock" || exit 1
flock -x 8 8>&8 || exit 1
TMP_BASE="/data/adb/.hypercraft_cycle"
CONF="$STATE/config.txt"
REAL_F="$STATE/real_cycle.txt"
REAL_T="$STATE/real_cycle_time.txt"
STATUS_F="$STATE/status.json"
[ -f "$CONF" ] || printf 'MODE=off\nFIXED=20\n' > "$CONF"

FG_CLASS="/sys/class/xm_power/fg_master/cyclecount"
FG_DEV="/sys/devices/virtual/xm_power/fg_master/cyclecount"
BAT="/sys/class/power_supply/battery/cycle_count"
LABEL_BAT="u:object_r:vendor_sysfs_battery_supply:s0"
LABEL_FG="u:object_r:vendor_sysfs_xm_power:s0"

qmi() { which qmi 2>/dev/null; }

log() { echo "$(date '+%m-%d %H:%M:%S') 电池循环: $*" >> /data/adb/hypercraft/state/log.txt 2>/dev/null; }

has_cmd() { command -v "$1" >/dev/null 2>&1; }

# ---------- 配置读取 ----------
get_conf() { # $1=key $2=default
  v=$(grep -E "^$1=" "$CONF" 2>/dev/null | tail -1 | cut -d= -f2)
  [ -n "$v" ] || v="$2"
  echo "$v"
}

# ---------- 挂载检测 ----------
# /proc/mounts 记录的是解析后的设备路径(class 是 symlink), 必须先 readlink -f
is_bound() {
  p=$(readlink -f "$1" 2>/dev/null)
  [ -n "$p" ] || p="$1"
  grep -qF " $p " /proc/mounts 2>/dev/null
}

is_own_bound() {
  is_bound "$1" || return 1
  case "$1" in
    "$BAT") own_source=$TMP_BASE/bat/cycle_count ;;
    "$FG_CLASS"|"$FG_DEV") own_source=$TMP_BASE/fg/cyclecount ;;
    *) return 1 ;;
  esac
  own_inode=$(stat -c %d:%i "$own_source" 2>/dev/null) || return 1
  [ -n "$own_inode" ] && [ "$own_inode" = "$(stat -c %d:%i "$1" 2>/dev/null)" ]
}

# 真实值捕获(仅在未被覆盖时可读)
capture_real() {
  if [ -f "$BAT" ] && ! is_bound "$BAT"; then
    r=$(cat "$BAT" 2>/dev/null | tr -dc '0-9')
    if [ -n "$r" ]; then
      echo "$r" > "$REAL_F"
      date '+%Y-%m-%d %H:%M:%S' > "$REAL_T"
    fi
  elif [ -f "$FG_CLASS" ] && ! is_bound "$FG_CLASS" && [ ! -s "$REAL_F" ]; then
    r=$(cat "$FG_CLASS" 2>/dev/null | tr -dc '0-9')
    [ -n "$r" ] && echo "$r" > "$REAL_F" && date '+%Y-%m-%d %H:%M:%S' > "$REAL_T"
  fi
}

# 原版梯度映射
map_fake() {
  if [ "$1" -lt 500 ]; then echo 20
  elif [ "$1" -le 600 ]; then echo 50
  elif [ "$1" -le 900 ]; then echo 80
  else echo 200; fi
}

calc_fake() {
  real=$(cat "$REAL_F" 2>/dev/null | tr -dc '0-9')
  [ -n "$real" ] || real=0
  MODE=$(get_conf MODE map)
  case "$MODE" in
    fixed)
      fx=$(get_conf FIXED 20 | tr -dc '0-9')
      [ -n "$fx" ] || fx=20
      FAKE=$fx ;;
    off) FAKE="" ;;
    *)   FAKE=$(map_fake "$real") ;;
  esac
}

# 卸载目标节点上的 bind(可多层堆叠)
unbind_target() {
  n=0
  while is_bound "$1" && [ $n -lt 6 ]; do
    # 外部模块的绑定必须保持，不能为了“恢复”盲目 umount 所有覆盖层。
    is_own_bound "$1" || return 1
    umount "$1" 2>/dev/null || return 1
    n=$((n+1))
  done
  ! is_bound "$1"
}

# ---------- 状态/可视化数据 ----------
num_or() { v=$(cat "$1" 2>/dev/null | tr -dc '0-9.-'); [ -n "$v" ] && echo "$v" || echo "$2"; }

refresh_status() {
  capture_real
  calc_fake
  real=$(cat "$REAL_F" 2>/dev/null | tr -dc '0-9'); [ -n "$real" ] || real=0
  fakedisp=$(cat "$BAT" 2>/dev/null | tr -dc '0-9')
  [ -n "$fakedisp" ] || fakedisp=0
  bound_bat=0; is_bound "$BAT" && bound_bat=1
  bound_fg=0; [ -f "$FG_CLASS" ] && { is_bound "$FG_CLASS" && bound_fg=1; }

  soh=$(num_or /sys/class/xm_power/fg_master/soh 0)
  full=$(num_or /sys/class/power_supply/battery/charge_full 0)
  design=$(num_or /sys/class/power_supply/battery/charge_full_design 0)
  # 健康度百分比*10 (993 -> 99.3), 先除后乘避免 32 位溢出
  if [ "$design" -ge 1000 ] 2>/dev/null; then
    hpct10=$(( full / (design / 1000) ))
  else
    hpct10=0
  fi
  hpct="$((hpct10/10)).$((hpct10%10))"
  temp=$(num_or /sys/class/power_supply/battery/temp 0)
  temp="$((temp/10)).$((temp%10))"
  volt=$(num_or /sys/class/power_supply/battery/voltage_now 0)
  curr=$(cat /sys/class/power_supply/battery/current_now 2>/dev/null | tr -dc '0-9-')
  [ -n "$curr" ] || curr=0
  cap=$(num_or /sys/class/power_supply/battery/capacity 0)
  bstat=$(cat /sys/class/power_supply/battery/status 2>/dev/null)
  [ -n "$bstat" ] || bstat="Unknown"
  qmax=$(num_or /sys/devices/virtual/xm_power/fg_master/qmax_cyclecount 0)
  realcap=$(( full / 1000 )) 2>/dev/null || realcap=0
  designcap=$(( design / 1000 )) 2>/dev/null || designcap=0
  rt=$(cat "$REAL_T" 2>/dev/null); [ -n "$rt" ] || rt="安装时"
  now=$(date '+%Y-%m-%d %H:%M:%S')
  MODE=$(get_conf MODE map)

  cat > "$STATUS_F" <<EOF
{"updated":"$now","real_captured_at":"$rt","fake":$fakedisp,"real":$real,"mode":"$MODE","bound_bat":$bound_bat,"bound_fg":$bound_fg,"soh":$soh,"health_pct":$hpct,"charge_full":$realcap,"charge_design":$designcap,"temp":$temp,"volt":$volt,"curr":$curr,"level":$cap,"charge_status":"$bstat","qmax_ref":"$qmax"}
EOF
}

# ---------- 应用伪装 ----------
do_apply() {
  capture_real
  calc_fake

  if [ "$MODE" = "off" ]; then
    do_restore || return 1
    echo "OK: 伪装已关闭(恢复真实)"
    return 0
  fi

  ok=1
  # 清理旧挂载, 保证幂等
  unbind_target "$BAT" || { echo 'FAIL: 循环节点被其他模块覆盖或旧绑定无法解除，未改写电量计节点'; return 1; }
  if [ -f "$FG_CLASS" ]; then
    unbind_target "$FG_CLASS" || { echo 'FAIL: 电量计节点被其他模块覆盖或旧绑定无法解除，未改写电量计节点'; return 1; }
  fi
  grep -F "$TMP_BASE" /proc/mounts 2>/dev/null | while read dev mnt rest; do
    umount "$mnt" 2>/dev/null
  done

  mkdir -p "$TMP_BASE/bat" "$TMP_BASE/fg" 2>/dev/null

  # tmpfs + 正确 SELinux 标签
  m1=0; m2=0
  grep -qF " $TMP_BASE/bat " /proc/mounts || mount -t tmpfs -o "context=$LABEL_BAT,mode=755,uid=0,gid=0" tmpfs "$TMP_BASE/bat" 2>/dev/null
  grep -qF " $TMP_BASE/bat " /proc/mounts && m1=1
  if [ -f "$FG_CLASS" ]; then
    grep -qF " $TMP_BASE/fg " /proc/mounts || mount -t tmpfs -o "context=$LABEL_FG,mode=755,uid=0,gid=0" tmpfs "$TMP_BASE/fg" 2>/dev/null
    grep -qF " $TMP_BASE/fg " /proc/mounts && m2=1
  fi

  if [ "$m1" = "1" ]; then
    echo "$FAKE" > "$TMP_BASE/bat/cycle_count" 2>/dev/null
    chmod 644 "$TMP_BASE/bat/cycle_count" 2>/dev/null
    mount --bind "$TMP_BASE/bat/cycle_count" "$BAT" 2>/dev/null && is_own_bound "$BAT" && ok=0
  fi
  fg_ok=1; [ -f "$FG_CLASS" ] && fg_ok=0
  if [ "$m2" = "1" ] && [ -f "$FG_CLASS" ]; then
    echo "$FAKE" > "$TMP_BASE/fg/cyclecount" 2>/dev/null
    chmod 644 "$TMP_BASE/fg/cyclecount" 2>/dev/null
    mount --bind "$TMP_BASE/fg/cyclecount" "$FG_CLASS" 2>/dev/null && is_own_bound "$FG_CLASS" && fg_ok=1
  fi

  refresh_status

  cur=$(cat "$BAT" 2>/dev/null | tr -dc '0-9')
  if [ "$ok" = 0 ] && [ "$fg_ok" = 1 ] && [ "$cur" = "$FAKE" ]; then
    log "apply 成功: real=$real fake=$FAKE mode=$MODE (bind)"
    echo "OK: 伪装已应用 (真实 $real 次 → 显示 $FAKE 次)"
    return 0
  fi
  # 挂载失败时仅撤销本次挂载，绝不改写电量计节点或权限。
  do_restore >/dev/null
  log "apply 失败: cur=$cur fake=$FAKE"
  echo "FAIL: 循环伪装挂载失败，未改写电量计节点"
  return 1
}

# ---------- 恢复真实 ----------
do_restore() {
  unbind_target "$BAT"
  [ -f "$FG_CLASS" ] && unbind_target "$FG_CLASS"
  grep -F "$TMP_BASE" /proc/mounts 2>/dev/null | while read dev mnt rest; do
    umount "$mnt" 2>/dev/null
  done
  if is_bound "$BAT" || { [ -f "$FG_CLASS" ] && is_bound "$FG_CLASS"; }; then
    log "restore 失败: 节点仍被挂载覆盖"
    echo "FAIL: 恢复失败，节点仍被挂载覆盖"
    return 1
  fi
  capture_real
  refresh_status
  log "restore 完成"
  echo "OK: 已恢复真实循环次数"
}

# ---------- 守护检查 ----------
do_watch() {
  [ "$(get_conf MODE map)" = "off" ] && exit 0
  if ! is_own_bound "$BAT" || { [ -f "$FG_CLASS" ] && ! is_own_bound "$FG_CLASS"; }; then
    log "watch: 挂载丢失, 重新应用"
    do_apply
  fi
}

# ---------- 配置修改 ----------
do_setmode() { # $1=mode $2=fixed(可选)
  m="$1"
  case "$m" in map|fixed|off) ;; *) echo "FAIL: 未知模式 $m"; exit 1;; esac
  upd_conf "MODE" "$m"
  if [ -n "$2" ]; then
    fx=$(echo "$2" | tr -dc '0-9'); [ -n "$fx" ] || fx=20
    upd_conf "FIXED" "$fx"
  fi
  do_apply
}
upd_conf() {
  config_set "$CONF" "$@" || { echo 'FAIL: 循环配置保存失败'; exit 1; }
}

case "$1" in
  apply)    do_apply ;;
  restore)  do_restore ;;
  watch)    do_watch ;;
  refresh)  refresh_status; echo "OK: 状态已刷新" ;;
  status)   refresh_status >/dev/null 2>&1; cat "$STATUS_F" 2>/dev/null ;;
  setmode)  shift; do_setmode "$@" ;;
  clearpk)
    rm -rf /data/user/0/com.miui.powerkeeper/* 2>/dev/null
    rm -rf /data/user/999/com.miui.powerkeeper/* 2>/dev/null
    echo "OK: powerkeeper 数据已清理(重启安全服务后生效)"
    ;;
  *) echo "usage: cycle.sh {apply|restore|watch|refresh|status|setmode|clearpk}"; exit 1 ;;
esac
