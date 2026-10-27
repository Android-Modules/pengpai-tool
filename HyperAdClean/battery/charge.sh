#!/system/bin/sh
# ============================================================
# 充电控制：自定义温控墙、亮屏/息屏快充、快充电量区间、充电上限，以及充电曲线记录
# 两套充电驱动（HW）：
#   mca  小米 MCA 充电框架（REDMI K90 等）
#   qcom 高通 pmic_glink + qcom-battery（小米 13 Ultra 等），节点对应关系：
#        解除温控 qcom-battery/thermal_remove、温控电流上限 battery/constant_charge_current（回读约有 1 秒延迟，最大 6A）、
#        限速等级 battery/charge_control_limit；停充用恒流写 0（继续供电）或 qcom-battery/input_suspend（断开输入，usb/online 会变 0）
# MCA 框架：
#   mi_thermald 按虚拟板温给出有线充电电流上限 → charger_thermal/wired_chg_curr
#   亮屏等场景的限速等级                       → charger_thermal/wired_ctrl_limit
#   两者都受驱动开关 charger_thermal/wired_thermal_remove 控制
# 停充通过 charge_interface 的投票实现（charge_enable 是“与”、input_suspend 是“或”），
# 本脚本只投自己的票，系统自身的高温停充、JEITA 等保护仍然有效。
# 阶梯限流（米系亮屏快充的思路）：不解除温控，而是自己写 wired_chg_curr（按电池温度分档）
# 并把 wired_ctrl_limit 写 0，mi_thermald 改回时下一轮重写。
# 用法: charge.sh {status|set <键> <值>|start|stop|daemon|restore|thermal|thermal-scan|sessions|session <id> [起始秒]|session-del <id>}
# ============================================================

DATA=/data/adb/hyperadclean
DIR=$DATA/charge
CONF=$DIR/config
WAKE=$DIR/wake.fifo
ACT=$DIR/action
LOG=$DIR/log
TH=$DIR/thermal.tsv
SELF=$(readlink -f "$0" 2>/dev/null || echo "$0")
BASE=${SELF%/*}

XC=/sys/class/xm_power/charger
CT=$XC/charger_thermal
CI=$XC/charge_interface
SC=$XC/smart_charge
B=/sys/class/power_supply/battery
U=/sys/class/power_supply/usb
TM=/sys/class/thermal/thermal_message
QB=/sys/class/qcom-battery
DEC=/data/vendor/thermal/decrypt.txt
DT=/proc/device-tree/soc
VOTER=hyperadclean
# 充电记录最多保留的次数
KEEP=60
# 米系亮屏快充 1.3 的默认阶梯：低于 33℃ 10A、33~55℃ 6A、55~65℃ 4A、更高 2A（999 表示其余温度）
TIERS_DEF=33:10000,55:6000,65:4000,999:2000
# wired_chg_curr 的“不限”值（与 wired_chg_curr2 的默认值一致）
NOLIMIT=22000000

# qcom 的 constant_charge_current 默认值，也是驱动允许的最大值
QMAX_DEF=6000000

mkdir -p $DIR

log() { echo "$(date '+%m-%d %H:%M:%S') 充电控制：$*" >> $DATA/log.txt 2>/dev/null; }

# N_REMOVE 解除温控开关、N_CURR 温控电流上限、N_LEVEL 限速等级
if [ -f $CT/wired_thermal_remove ] && [ -f $CI/charge_enable ] && [ -f $CI/input_suspend ]; then
  HW=mca; N_REMOVE=$CT/wired_thermal_remove; N_CURR=$CT/wired_chg_curr; N_LEVEL=$CT/wired_ctrl_limit
elif [ -f $QB/thermal_remove ] && [ -f $QB/input_suspend ] && [ -f $B/constant_charge_current ] && [ -f $B/charge_control_limit ]; then
  HW=qcom; N_REMOVE=$QB/thermal_remove; N_CURR=$B/constant_charge_current; N_LEVEL=$B/charge_control_limit
else
  HW=none; N_REMOVE=/dev/null; N_CURR=/dev/null; N_LEVEL=/dev/null
fi

supported() { [ $HW != none ]; }

# qcom：断开充电输入时 usb/online 也会变 0，这时看 Type-C 是否还接着充电器
plugged() {
  o=0; read -r o 2>/dev/null < $U/online
  [ "$o" = 1 ] && return 0
  [ $HW = qcom ] || return 1
  s=0; read -r s 2>/dev/null < $QB/input_suspend
  [ "$s" = 1 ] || return 1
  m=""; read -r m 2>/dev/null < $QB/typec_mode
  case "$m" in "Source attached"*) return 0 ;; esac
  return 1
}

# qcom：没在接管时记下见过的最大电流上限（默认 6A），阶梯超过它的档位按它写
qmax() { m=""; read -r m 2>/dev/null < $DIR/qmax; echo ${m:-$QMAX_DEF}; }
q_seen() {
  [ $HW = qcom ] && [ ! -f $DIR/tier_on ] && [ ! -f $DIR/stop_curr ] || return 0
  v=""; read -r v 2>/dev/null < $N_CURR
  [ -n "$v" ] && [ "$v" -gt "$(qmax)" ] && echo $v > $DIR/qmax
}

# qcom：限速等级压到 0，系统改回时记下系统的值再重写；交还时写回系统最近的值
lvl_hold() {
  v=""; read -r v 2>/dev/null < $N_LEVEL
  [ "$v" = 0 ] && return 1
  [ $HW = qcom ] && [ -n "$v" ] && echo $v > $DIR/sys_level
  echo 0 > $N_LEVEL 2>/dev/null
}
lvl_release() {
  [ -f $DIR/sys_level ] || return 0
  l=""; read -r l 2>/dev/null < $DIR/sys_level
  [ -n "$l" ] && echo $l > $N_LEVEL 2>/dev/null
  rm -f $DIR/sys_level
}

# 配置读进 c_* 变量（内建 read，不起新进程）
load_conf() {
  c_enable=0; c_thermal=custom; c_wall=42; c_stop_temp=46; c_screen_on=0; c_screen_off=1
  c_fast_soc=100; c_limit=100; c_recharge=5; c_stop_mode=enable; c_record=1; c_tiers=$TIERS_DEF
  [ -f $CONF ] || return 0
  # 用 ck/cv 而不是 k/v：cmd_set 读完配置后还要用自己的 k/v
  while IFS='=' read -r ck cv; do
    case "$ck" in
      enable|thermal|wall|stop_temp|screen_on|screen_off|fast_soc|limit|recharge|stop_mode|record|tiers) eval "c_$ck=\$cv" ;;
    esac
  done < $CONF
}

conf_set() {
  touch $CONF
  grep -v "^$1=" $CONF > $CONF.tmp
  echo "$1=$2" >> $CONF.tmp
  mv $CONF.tmp $CONF
}

daemon_pid() { pgrep -f 'charge[.]sh daemon$' | head -n 1; }

# ---------- 节点写入（只在变化时写） ----------
set_remove() {
  [ "$cur_remove" = "$1" ] && return
  echo $1 > $N_REMOVE 2>/dev/null
  cur_remove=$1
}

# $1: 1 允许充电 / 0 停止充电
set_charge() {
  if [ $HW = qcom ]; then q_charge $1; return; fi
  [ "$cur_charge" = "$1" ] && return
  if [ "$1" = 1 ]; then
    echo "$VOTER all 1" > $CI/charge_enable 2>/dev/null
    echo "$VOTER all 0" > $CI/input_suspend 2>/dev/null
  elif [ "$c_stop_mode" = suspend ]; then
    echo "$VOTER all 1" > $CI/input_suspend 2>/dev/null
  else
    echo "$VOTER all 0" > $CI/charge_enable 2>/dev/null
  fi
  cur_charge=$1
}

# qcom 停充：继续供电方式把恒流写 0（每轮核对，被改回就重写），断开输入方式写 input_suspend
q_charge() {
  if [ "$1" = 1 ]; then
    [ "$cur_charge" = 1 ] && return
    if [ -f $DIR/stop_curr ]; then
      c=""; read -r c 2>/dev/null < $DIR/stop_curr
      echo ${c:-$(qmax)} > $N_CURR 2>/dev/null; rm -f $DIR/stop_curr
    fi
    echo 0 > $QB/input_suspend 2>/dev/null
  elif [ "$c_stop_mode" = suspend ]; then
    [ "$cur_charge" = 0 ] && return
    echo 1 > $QB/input_suspend 2>/dev/null
  else
    v=""; read -r v 2>/dev/null < $N_CURR
    if [ "$v" != 0 ]; then
      [ -f $DIR/stop_curr ] || echo ${v:-$(qmax)} > $DIR/stop_curr
      echo 0 > $N_CURR 2>/dev/null
    fi
  fi
  cur_charge=$1
}

# 阶梯限流：$1 为电流上限 mA。每轮都核对一次，被 mi_thermald 改回就重写并计数
apply_tier() {
  first=0; [ -f $DIR/tier_on ] || { : > $DIR/tier_on; first=1; }
  want=$(($1 * 1000)); fixed=0
  lvl_hold && fixed=1
  v=""; read -r v 2>/dev/null < $N_CURR
  if [ $HW = qcom ]; then
    # 超过驱动上限的按上限写；回读有延迟，读到的还是上一轮写的值时不算被改回
    m=$(qmax); [ $want -gt $m ] && want=$m
    [ $first = 1 ] && echo ${v:-$m} > $DIR/sys_curr
    if [ "$v" != "$want" ]; then
      if [ "$v" != "$tier_want" ] && [ $first = 0 ]; then echo $v > $DIR/sys_curr; [ "$cur_tier" = "$1" ] && fixed=1; fi
      echo $want > $N_CURR 2>/dev/null
    fi
    tier_want=$want
  else
    [ "$v" = "$want" ] || { echo $want > $N_CURR 2>/dev/null; [ "$cur_tier" = "$1" ] && fixed=1; }
  fi
  if [ $fixed = 1 ] && [ -n "$cur_tier" ]; then
    tier_fix=$((tier_fix + 1)); echo $tier_fix > $DIR/tier_fix
  fi
  cur_tier=$1
}

# 交还系统：电流写回“不限”，再切到别的温控场景并切回，让 mi_thermald 立即重新下发电流和限速等级
# qcom 把电流上限和限速等级写回系统最近的值
tier_restore() {
  if [ $HW = qcom ]; then
    c=""; read -r c 2>/dev/null < $DIR/sys_curr
    echo ${c:-$(qmax)} > $N_CURR 2>/dev/null
    lvl_release
    rm -f $DIR/tier_on $DIR/sys_curr
    tier_want=""
    return
  fi
  echo $NOLIMIT > $N_CURR 2>/dev/null
  s=""; read -r s 2>/dev/null < $TM/sconfig
  if [ -n "$s" ]; then
    if [ "$s" = 0 ]; then o=7; else o=0; fi
    echo $o > $TM/sconfig 2>/dev/null; sleep 2.5; echo $s > $TM/sconfig 2>/dev/null
  fi
  rm -f $DIR/tier_on
}

release_tier() {
  [ -n "$cur_tier" ] || [ -f $DIR/tier_on ] || return 0
  tier_restore
  cur_tier=""; tier_fix=0; rm -f $DIR/tier_fix
}

# 当前电池温度 t（0.1℃）落在哪一档，结果放在 tcap（mA）
tier_cap() {
  tcap=0; o=$IFS; IFS=,
  for e in $c_tiers; do tcap=${e#*:}; [ "$t" -lt $((${e%%:*} * 10)) ] && break; done
  IFS=$o
}

release() { release_tier; set_remove 0; lvl_release; set_charge 1; }

# 只在本守护动过节点时才恢复，没开充电控制时不去投票
release_if_touched() { [ -n "$cur_remove$cur_charge$cur_tier" ] && release; }

set_act() {
  [ "$act" = "$1|$2" ] && return
  act="$1|$2"
  echo "$act" > $ACT
  log "$2"
}

# ---------- 充电曲线记录 ----------
# 每行: 时间(秒),电量%,电流mA(充入为正),电压mV,电池温度0.1℃,机身温度0.1℃,系统温控电流上限mA,限速等级,温控已解除,亮屏,温控场景编号,策略
begin_session() {
  mkdir -p $LOG
  now=${EPOCHREALTIME%.*}
  sess=""
  read -r sess 2>/dev/null < $LOG/current
  # 守护重启（切换开关、模块更新）后 2 分钟内接着记同一次充电
  if [ -n "$sess" ] && [ -f $LOG/$sess.csv ]; then
    l=$(tail -n 1 $LOG/$sess.csv 2>/dev/null); l=${l%%,*}
    [ -n "$l" ] && [ $((now - l)) -lt 120 ] && return
  fi
  sess=$now
  echo $sess > $LOG/current
  : > $LOG/$sess.csv
  rec_n=0
}

end_session() {
  [ -n "$sess" ] || return
  rm -f $LOG/current
  # 插拔抖动产生的几秒记录不保留
  n=$(wc -l < $LOG/$sess.csv 2>/dev/null)
  [ "${n:-0}" -lt 5 ] && rm -f $LOG/$sess.csv
  sess=""
  ls $LOG/*.csv 2>/dev/null | sort -r | tail -n +$((KEEP + 1)) | while read -r f; do rm -f "$f"; done
}

record() {
  [ -n "$sess" ] || begin_session
  st=""; read -r st 2>/dev/null < $B/status
  # 充满或停充后电流基本不变，每 30 秒记一次
  rec_n=$((rec_n + 1))
  if [ "$st" = Full ] || [ "${act%%|*}" = full ]; then
    [ $((rec_n % 15)) = 1 ] || return
  fi
  c=0; v=0; bd=0; sx=0; cl=0; rm_=0; sf=0
  read -r c 2>/dev/null < $B/current_now
  read -r v 2>/dev/null < $B/voltage_now
  read -r bd 2>/dev/null < $TM/board_sensor_temp
  read -r sx 2>/dev/null < $N_CURR
  read -r cl 2>/dev/null < $N_LEVEL
  read -r rm_ 2>/dev/null < $N_REMOVE
  read -r sf 2>/dev/null < $TM/sconfig
  echo "${EPOCHREALTIME%.*},$soc,$((-c / 1000)),$((v / 1000)),$t,$((bd / 100)),$((sx / 1000)),$cl,$rm_,$scr,$sf,${act%%|*}" >> $LOG/$sess.csv
}

# ---------- 守护 ----------
# 充电控制策略，需要先读好 soc / t / scr
control() {
  # 充电上限（带回充差值，避免在上限附近反复启停）
  if [ "$c_limit" -lt 100 ]; then
    if [ "$soc" -ge "$c_limit" ]; then full=1
    elif [ "$soc" -le $((c_limit - c_recharge)) ]; then full=0; fi
  else
    full=0
  fi
  # 过热停充（回落 3℃ 后恢复）
  if [ "$t" -ge $((c_stop_temp * 10)) ]; then hot=1
  elif [ "$t" -le $((c_stop_temp * 10 - 30)) ]; then hot=0; fi

  # qcom 的停充也写电流上限，必须在交还温控（写回电流）之后
  if [ $hot = 1 ]; then
    set_remove 0; release_tier; lvl_release; set_charge 0
    set_act hot "电池 $((t / 10))℃ ≥ ${c_stop_temp}℃，暂停充电"
  elif [ $full = 1 ]; then
    set_remove 0; release_tier; lvl_release; set_charge 0
    set_act full "已充到上限 ${c_limit}%，暂停充电"
  elif [ "$c_thermal" = tier ]; then
    set_charge 1; set_remove 0
    if [ "$soc" -ge "$c_fast_soc" ]; then
      release_tier
      set_act system "电量 ${soc}% ≥ 快充区间 ${c_fast_soc}%，交还系统温控"
    else
      tier_cap; apply_tier $tcap
      set_act tier "阶梯限流：电池 $((t / 10))℃ → $((tcap / 1000)).$((tcap % 1000 / 100)) A"
    fi
  else
    set_charge 1; release_tier
    fast=0; why=""
    if [ "$c_thermal" != custom ]; then
      why="使用系统温控"
    else
      # 温控墙（回落 2℃ 后再次解除）
      if [ "$t" -ge $((c_wall * 10)) ]; then over=1
      elif [ "$t" -le $((c_wall * 10 - 20)) ]; then over=0; fi
      if [ "$scr" = 1 ]; then sc=$c_screen_on; sn=亮屏; else sc=$c_screen_off; sn=息屏; fi
      if [ $over = 1 ]; then why="电池 $((t / 10))℃ 到达温控墙 ${c_wall}℃，交还系统温控"
      elif [ "$soc" -ge "$c_fast_soc" ]; then why="电量 ${soc}% ≥ 快充区间 ${c_fast_soc}%，交还系统温控"
      elif [ "$sc" != 1 ]; then why="${sn}快充未开启，交还系统温控"
      else fast=1; fi
    fi
    set_remove $fast
    # qcom 的 thermal_remove 不确定是否包含亮屏限速等级，快充时一起压到 0
    if [ $HW = qcom ]; then if [ $fast = 1 ]; then lvl_hold; else lvl_release; fi; fi
    if [ $fast = 1 ]; then set_act fast "${sn}快充：已解除系统温控墙"
    else set_act system "$why"; fi
  fi
}

cmd_daemon() {
  supported || { log "本机没有可用的充电控制节点，守护退出"; exit 1; }
  rm -f $WAKE; mkfifo $WAKE; exec 3<>$WAKE
  cur_remove=""; cur_charge=""; cur_tier=""; tier_want=""; tier_fix=0; act=""; full=0; hot=0; over=0; sess=""; rec_n=0
  trap 'release_if_touched; set_act off "已关闭，恢复系统默认"; rm -f $WAKE; exit 0' TERM INT
  load_conf
  while [ "$c_enable" = 1 ] || [ "$c_record" = 1 ]; do
    q_seen
    if ! plugged; then
      end_session
      release_if_touched; full=0; hot=0; over=0
      if [ "$c_enable" = 1 ]; then set_act idle "未连接充电器"; else set_act off "充电控制已关闭，使用系统默认"; fi
      read -t 10 -u3 _ 2>/dev/null
      load_conf
      continue
    fi
    soc=0; t=0; scr=1
    read -r soc 2>/dev/null < $B/capacity
    read -r t 2>/dev/null < $B/temp
    read -r scr 2>/dev/null < $TM/screen_state
    if [ "$c_enable" = 1 ]; then
      control
    else
      release_if_touched
      set_act off "充电控制已关闭，使用系统默认"
    fi
    if [ "$c_record" = 1 ]; then record; else end_session; fi
    # 阶梯限流时每秒核对一次，缩短被 mi_thermald 改回后的时间
    if [ -n "$cur_tier" ]; then read -t 1 -u3 _ 2>/dev/null; else read -t 2 -u3 _ 2>/dev/null; fi
    load_conf
  done
  end_session
  release_if_touched
  set_act off "已关闭，恢复系统默认"
  rm -f $WAKE
}

cmd_start() {
  load_conf
  [ "$c_enable" = 1 ] || [ "$c_record" = 1 ] || return 0
  supported || return 0
  [ -n "$(daemon_pid)" ] && return 0
  setsid nohup sh $SELF daemon </dev/null >/dev/null 2>&1 &
}

cmd_stop() {
  for p in $(pgrep -f 'charge[.]sh daemon$'); do kill $p; done
  sleep 1
  cmd_restore
}

# 不依赖守护的状态，直接把节点恢复为系统默认
cmd_restore() {
  supported || return 0
  [ -f $DIR/tier_on ] && tier_restore
  rm -f $DIR/tier_fix
  echo 0 > $N_REMOVE 2>/dev/null
  if [ $HW = qcom ]; then
    lvl_release
    if [ -f $DIR/stop_curr ]; then
      c=""; read -r c 2>/dev/null < $DIR/stop_curr
      echo ${c:-$(qmax)} > $N_CURR 2>/dev/null; rm -f $DIR/stop_curr
    fi
    echo 0 > $QB/input_suspend 2>/dev/null
    return
  fi
  echo "$VOTER all 1" > $CI/charge_enable 2>/dev/null
  echo "$VOTER all 0" > $CI/input_suspend 2>/dev/null
}

# 唤醒守护，让新设置立即生效
wake() { [ -n "$(daemon_pid)" ] && [ -p $WAKE ] && echo > $WAKE; }

# 开关变化后：还有任一功能开着就让守护继续跑，否则停掉
apply_switch() {
  load_conf
  if [ "$c_enable" = 1 ] || [ "$c_record" = 1 ]; then
    if [ -n "$(daemon_pid)" ]; then wake; else cmd_start; fi
  else
    cmd_stop
  fi
}

cmd_set() {
  k=$1; v=$2
  case "$k" in
    enable|screen_on|screen_off|record) case "$v" in 0|1) ;; *) echo "ERR|charge|取值只能是 0/1"; return ;; esac ;;
    thermal) case "$v" in system|custom|tier) ;; *) echo "ERR|charge|取值不合法"; return ;; esac ;;
    tiers)
      # 温度:电流mA，温度递增，最后一档温度写 999（其余温度）
      echo "$v" | grep -qE '^[0-9]{2,3}:[0-9]{3,5}(,[0-9]{2,3}:[0-9]{3,5}){0,7}$' || { echo "ERR|charge|阶梯格式不合法"; return; }
      echo "$v" | awk -F, '{ p = 0; for (i = 1; i <= NF; i++) { split($i, a, ":"); t = a[1] + 0; c = a[2] + 0
          if (c < 500 || c > 22000) exit 1
          if (i < NF && (t < 20 || t > 99 || t <= p)) exit 1
          if (i == NF && t != 999) exit 1
          p = t } }' || { echo "ERR|charge|温度需递增（20~99℃），电流 0.5~22A，最后一档为其余温度"; return; } ;;
    stop_mode) case "$v" in enable|suspend) ;; *) echo "ERR|charge|取值不合法"; return ;; esac ;;
    wall|stop_temp|fast_soc|limit|recharge)
      echo "$v" | grep -qE '^[0-9]+$' || { echo "ERR|charge|数值不合法"; return; }
      case "$k" in
        wall) [ $v -ge 30 ] && [ $v -le 48 ] || { echo "ERR|charge|温控墙范围 30~48℃"; return; } ;;
        stop_temp) [ $v -ge 38 ] && [ $v -le 50 ] || { echo "ERR|charge|停充温度范围 38~50℃"; return; } ;;
        fast_soc|limit) [ $v -ge 20 ] && [ $v -le 100 ] || { echo "ERR|charge|电量范围 20~100%"; return; } ;;
        recharge) [ $v -ge 1 ] && [ $v -le 20 ] || { echo "ERR|charge|回充差值范围 1~20%"; return; } ;;
      esac ;;
    *) echo "ERR|charge|未知设置"; return ;;
  esac
  if [ "$v" = 1 ] && { [ "$k" = enable ] || [ "$k" = record ]; } && ! supported; then
    echo "ERR|charge|本机没有可用的充电控制节点，不支持"; return
  fi
  conf_set $k $v
  log "设置 $k=$v"
  case "$k" in
    enable)
      [ "$v" = 0 ] && cmd_restore
      apply_switch
      if [ "$v" = 1 ]; then echo "OK|charge|已开启充电控制"; else echo "OK|charge|已关闭，恢复系统默认"; fi ;;
    record)
      apply_switch
      if [ "$v" = 1 ]; then echo "OK|charge|插上充电器后开始记录"; else echo "OK|charge|已停止记录充电曲线"; fi ;;
    *) wake; echo "OK|charge|已保存" ;;
  esac
}

# key=value 每行一项，供 App 解析
cmd_status() {
  load_conf
  rv() { v=""; read -r v 2>/dev/null < "$2"; echo "$1=$v"; }
  echo "supported=$(supported && echo 1 || echo 0)"
  echo "hw=$HW"
  for k in enable thermal wall stop_temp screen_on screen_off fast_soc limit recharge stop_mode record tiers; do
    eval "echo $k=\$c_$k"
  done
  rv tier_fix $DIR/tier_fix
  echo "running=$([ -n "$(daemon_pid)" ] && echo 1 || echo 0)"
  echo "online=$(plugged && echo 1 || echo 0)"
  rv status $B/status
  rv soc $B/capacity
  rv temp $B/temp
  rv curr $B/current_now
  rv volt $B/voltage_now
  rv board $TM/board_sensor_temp
  rv screen $TM/screen_state
  rv sconfig $TM/sconfig
  rv real_type $QB/usb_real_type
  rv qc_type $QB/quick_charge_type
  rv power_max $QB/power_max
  rv sys_curr $N_CURR
  rv ctrl_limit $N_LEVEL
  rv remove $N_REMOVE
  if [ $HW = qcom ]; then
    rv night $QB/night_charging
    echo "curr_max=$(qmax)"
    rv suspend $QB/input_suspend
  else
    rv night $SC/smart_night
  fi
  rv session $LOG/current
  a=""; m=""; IFS='|' read -r a m 2>/dev/null < $ACT
  echo "action=$a"
  echo "action_msg=$m"
  e=""; read -r e 2>/dev/null < $CI/charge_enable
  echo "vote_enable=$e"
}

# ---------- 系统温度墙 ----------
# mi_thermald 只把当前温控场景解密到 decrypt.txt；取出其中有线充电相关的两段：
#   *SIC-BAT     按机身温度分档的电流墙（PID 在 min~max 之间调节，单位 mA）
#   *MONITOR-BAT 按机身温度分档的限速等级，编码为 亮屏等级*100+息屏等级
# 输出: T|场景|sic|trig|clr|target|max|min  或  T|场景|lvl|trig|clr|target（数值以空格分隔）
parse_decrypt() {
  awk 'BEGIN { RS = ""; FS = "\n" }
  {
    h = $1; gsub(/[\[\]]/, "", h)
    if (h ~ /SS-CPU0$/) { sc = h; sub(/-?SS-CPU0$/, "", sc); if (sc == "") sc = "NORMAL" }
    typ = ""
    if (h ~ /SIC-BAT$/) typ = "sic"; else if (h ~ /MONITOR-BAT$/) typ = "lvl"
    if (typ == "" || sc == "") next
    split("", v)
    for (i = 2; i <= NF; i++) {
      n = split($i, a, "\t"); s = ""
      for (j = 2; j <= n; j++) s = s (j > 2 ? " " : "") a[j]
      v[a[1]] = s
    }
    if (typ == "lvl" && v["device"] != "battery") next
    if (typ == "sic") print "T|" sc "|sic|" v["trig"] "|" v["clr"] "|" v["target"] "|" v["max"] "|" v["min"]
    else print "T|" sc "|lvl|" v["trig"] "|" v["clr"] "|" v["target"]
  }' $DEC 2>/dev/null
}

# 把当前场景合并进缓存（以场景名为键），输出当前场景名
merge_current() {
  new=$(parse_decrypt)
  [ -n "$new" ] || return 1
  sc=$(echo "$new" | head -n 1 | cut -d'|' -f2)
  if [ "$(grep "^T|$sc|" $TH 2>/dev/null)" != "$new" ]; then
    { grep -v "^T|$sc|" $TH 2>/dev/null; echo "$new"; } > $TH.tmp && mv $TH.tmp $TH
  fi
  echo $sc
}

dt_u32() { od -An -tu1 -v "$1" 2>/dev/null | awk '{ for (i = 1; i <= NF; i++) { v = v * 256 + $i; if (++k % 4 == 0) { printf "%s%d", (k > 4 ? " " : ""), v; v = 0 } } } END { print "" }'; }
dt_str() { [ -f "$1" ] && tr '\0' ',' < "$1" 2>/dev/null; }

cmd_thermal() {
  # 首次使用时装入同机型的预置表，再用当前场景覆盖
  if [ ! -s $TH ]; then
    pre=$BASE/thermal-$(getprop ro.product.device).tsv
    [ -f "$pre" ] && cp "$pre" $TH
  fi
  scene=$(merge_current)
  rv() { v=""; read -r v 2>/dev/null < "$2"; echo "$1=$v"; }
  echo "scene=$scene"
  rv sconfig $TM/sconfig
  rv board $TM/board_sensor_temp
  rv temp $B/temp
  rv volt $B/voltage_now
  rv screen $TM/screen_state
  echo "online=$(plugged && echo 1 || echo 0)"
  echo "hw=$HW"
  rv sys_curr $N_CURR
  rv ctrl_limit $N_LEVEL
  rv remove $N_REMOVE
  rv limit_max $B/charge_control_limit_max
  cat $TH 2>/dev/null
  # 驱动里的限速等级 → 电流表（每级 10 列，对应不同充电协议）
  echo "W|$(dt_u32 $DT/mca_charger_thermal/wired_thermal)"
  # 驱动 JEITA：按电池温度的普通充电电流和截止电压
  echo "J|$(dt_str $DT/mca_buckchg_jeita/jeita_para)"
  # 快充：按电池温度的最大电流，以及按电池电压逐级降流的阶梯
  QS=$DT/mca_strategy_quick_charge
  bp=$(dt_str $QS/batt_para | cut -d, -f3)
  if [ -n "$bp" ] && [ -f $QS/$bp ]; then
    echo "Q|$(dt_str $QS/$bp)"
    for f in $QS/normal_para_${bp#batt_para_}_*; do
      [ -f "$f" ] && echo "V|${f##*_para_${bp#batt_para_}_}|$(dt_str $f)"
    done
  fi
}

# 依次切换温控场景，让 mi_thermald 把每个场景解密出来，读完恢复原场景
cmd_thermal_scan() {
  [ -f $DEC ] || { echo "ERR|charge|没有找到 mi_thermald 的温控配置"; return; }
  orig=""; read -r orig 2>/dev/null < $TM/sconfig
  for n in 0 1 5 6 7 9 10 11 14 15 16 18 19 20 25 26 27 28; do
    echo $n > $TM/sconfig 2>/dev/null || continue
    sleep 2.5
    merge_current >/dev/null
  done
  echo "$orig" > $TM/sconfig
  echo "OK|charge|已读取 $(cut -d'|' -f2 $TH | sort -u | wc -l) 个温控场景"
}

# ---------- 充电记录查询 ----------
# 每次一行: 编号|开始|结束|开始电量|结束电量|峰值功率W|平均功率W|最高电池温度0.1℃|最高机身温度0.1℃|采样数|充入mAh|进行中
cmd_sessions() {
  cur=""; read -r cur 2>/dev/null < $LOG/current
  set -- $(ls $LOG/*.csv 2>/dev/null | sort -r)
  [ $# -gt 0 ] || return 0
  awk -F, -v cur="$cur" '
  function flush() {
    if (n) printf "%s|%s|%s|%s|%s|%.1f|%.1f|%s|%s|%d|%d|%d\n", id, t0, t1, s0, s1, pm, (tt > 0 ? e / tt : 0), tm, bm, n, q, (id == cur)
    n = 0
  }
  FNR == 1 { flush(); id = FILENAME; sub(/.*\//, "", id); sub(/\.csv$/, "", id); e = 0; tt = 0; q = 0; pm = 0; tm = -999; bm = -999; t0 = $1; s0 = $2; pt = $1 }
  {
    n++; p = $3 * $4 / 1e6; if (p > pm) pm = p
    d = $1 - pt
    if (d > 0 && d <= 60) { q += $3 * d / 3600; if ($3 > 100) { e += p * d; tt += d } }
    pt = $1; t1 = $1; s1 = $2
    if ($5 > tm) tm = $5; if ($6 > bm) bm = $6
  }
  END { flush() }' "$@"
}

valid_id() { echo "$1" | grep -qE '^[0-9]+$' && [ -f $LOG/$1.csv ]; }

cmd_session() {
  valid_id "$1" || return 0
  from=${2:-0}
  echo "$from" | grep -qE '^[0-9]+$' || from=0
  awk -F, -v f=$from '$1 > f' $LOG/$1.csv
}

cmd_session_del() {
  valid_id "$1" || { echo "ERR|charge|记录不存在"; return; }
  cur=""; read -r cur 2>/dev/null < $LOG/current
  [ "$1" = "$cur" ] && { echo "ERR|charge|正在记录中，拔掉充电器后再删除"; return; }
  rm -f $LOG/$1.csv
  echo "OK|charge|已删除"
}

case "$1" in
  status) cmd_status ;;
  set) cmd_set "$2" "$3" ;;
  start) cmd_start ;;
  stop) cmd_stop ;;
  daemon) cmd_daemon ;;
  restore) cmd_restore ;;
  thermal) cmd_thermal ;;
  thermal-scan) cmd_thermal_scan ;;
  sessions) cmd_sessions ;;
  session) cmd_session "$2" "$3" ;;
  session-del) cmd_session_del "$2" ;;
  *) echo "usage: charge.sh {status|set <键> <值>|start|stop|daemon|restore|thermal|thermal-scan|sessions|session <id> [起始秒]|session-del <id>}"; exit 1 ;;
esac
