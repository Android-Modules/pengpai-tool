#!/system/bin/sh
# HyperOS 3 内置 Root 后端，由 APK 部署和启动。
# 输出约定：每行一条记录，字段用 | 分隔，便于 App 解析。

MODDIR=${0%/*}
. "$MODDIR/env.sh"
DATA=/data/adb/hypercraft/state
CONF=$DATA/config
APPLIED=$DATA/applied.list   # pkg|mode|原状态(e/d)|时间戳
TWEAKBAK=$DATA/tweaks.bak    # id|原值
HOSTS=$DATA/hosts
LOG=$DATA/log.txt
DEF_BACKUP=/storage/emulated/0/HyperCraft

# 你在用的功能 + 系统核心，任何情况下都拒绝处理
PROTECTED=" android com.android.systemui com.android.settings com.android.phone com.android.shell
 com.android.vending com.google.android.gms com.google.android.gsf com.android.bluetooth
 com.android.providers.settings com.android.providers.media com.android.providers.downloads
 com.android.providers.contacts com.android.providers.telephony com.android.permissioncontroller
 com.miui.home com.miui.personalassistant com.miui.core com.miui.rom com.miui.system com.miui.securitycenter com.miui.securitycore
 com.miui.guardprovider com.lbe.security.miui com.miui.powerkeeper com.miui.packageinstaller
 com.miui.cloudservice com.xiaomi.account com.xiaomi.registration com.xiaomi.simactivate.service
 com.xiaomi.trustservice com.xiaomi.finddevice com.xiaomi.bluetooth com.xiaomi.hypercomm miui.systemui.plugin
 com.xiaomi.xmsf com.xiaomi.xmsfkeeper
 com.xiaomi.mi_connect_service com.milink.service com.xiaomi.mirror com.miui.mishare.connectivity
 com.xiaomi.continuity.sdkapp com.miui.carlink
 com.miui.voiceassist com.miui.voicetrigger com.xiaomi.aiasst.service com.xiaomi.aiasst.vision
 com.xiaomi.aicr com.xiaomi.aiservice
 com.xiaomi.metoknlp com.xiaomi.location.fused com.xiaomi.ugd com.xiaomi.aon
 io.github.dalong.hypercraft io.github.dalong.hyperhome com.dalong.pengpaitool.hyperos4 me.weishu.kernelsu io.github.a13e300.ksuwebui com.sukisu.ultra com.rifsxd.ksunext me.bmax.apatch com.topjohnwu.magisk "

# 系统开关：id|命名空间|键|目标值|说明
TWEAKS="debuglog|secure|upload_debug_log_pref|0|关闭调试日志自动上传
predl|system|predownload_cloud_enable|0|关闭应用商店云端预下载
chooser|system|com.android.browser.enable_app_chooser_recommend|0|关闭「打开方式」里的应用推荐"

mkdir -p $DATA
touch $APPLIED $TWEAKBAK

log() {
  # post-fs-data 时系统时间还没同步（停在 1970 年），记成「开机早期」，App 会把它归到同一次开机里
  if [ "$(date +%Y)" -lt 2020 ]; then echo "-- 开机早期 $*" >> $LOG; else echo "$(date '+%m-%d %H:%M:%S') $*" >> $LOG; fi
  [ "$(stat -c %s $LOG 2>/dev/null || echo 0)" -gt 262144 ] && { tail -n 500 $LOG > $LOG.tmp; mv $LOG.tmp $LOG; }
  return 0
}

conf_get() {
  v=$(grep -m1 "^$1=" $CONF 2>/dev/null | cut -d= -f2-)
  [ -n "$v" ] && echo "$v" || echo "$2"
}

conf_set() {
  config_set "$CONF" "$@" || { echo 'ERR|config|配置保存失败'; exit 1; }
}

valid_pkg() { echo "$1" | grep -qE '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'; }

is_protected() { case " $(echo $PROTECTED) " in *" $1 "*) return 0 ;; esac; return 1; }

# ---------- 备份目录 ----------
# /storage/emulated/0 在 root 命名空间下不一定可见，统一换算成 /data/media/0 读写
bk_dir() {
  p=$(conf_get backup_dir $DEF_BACKUP)
  case "$p" in
    /storage/emulated/0*) p=/data/media/0${p#/storage/emulated/0} ;;
    /sdcard*) p=/data/media/0${p#/sdcard} ;;
  esac
  echo "$p"
}

# KernelSU 开机脚本用自带 busybox，它的 stat -c %C 不支持 SELinux（只输出字面 C），必须用系统 toybox
selabel() { /system/bin/stat -c %C "$1" 2>/dev/null; }

bk_ready() { [ -d /data/media/0/Android ]; }   # 首次解锁前内部存储是加密的

# 照抄 Download 目录的属主和 SELinux 标签（MediaProvider 的 uid + MLS 分类），文件管理器才能看到
bk_fix() {
  case "$1" in /data/media/0/*)
    ref=/data/media/0/Download
    chown -R $(stat -c %u:%g $ref) "$1" 2>/dev/null
    chcon -R $(selabel $ref) "$1" 2>/dev/null
    find "$1" -type d -exec chmod 2770 {} + 2>/dev/null
    find "$1" -type f -exec chmod 660 {} + 2>/dev/null ;;
  esac
}

bk_sync() (
  bk_ready || return 0
  exec 6>>"$DATA/backup.flock" || return 1
  flock -x 6 6>&6 || return 1
  d=$(bk_dir)
  mkdir -p "$d" || return 1
  pending=$d/.sync.$$
  trap 'rm -f "$pending"' EXIT
  # 记录写入与导出使用相同 inode 的锁；备份的每个文件也通过原子替换发布。
  exec 9>>"$APPLIED.flock" || return 1
  flock -x 9 9>&9 || return 1
  cp -f "$APPLIED" "$pending" && mv -f "$pending" "$d/applied.list" || return 1
  flock -u 9 9>&9
  exec 9>>"$DATA/tweaks.flock" || return 1
  flock -x 9 9>&9 || return 1
  cp -f "$TWEAKBAK" "$pending" && mv -f "$pending" "$d/tweaks.bak" || return 1
  flock -u 9 9>&9
  for source in "$CONF" "$DATA/hosts.custom"; do
    [ -f "$source" ] || continue
    cp -f "$source" "$pending" && mv -f "$pending" "$d/${source##*/}" || return 1
  done
  bk_fix "$d"
)

backup_apk() {
  [ "$(conf_get backup_apk 1)" = 1 ] && bk_ready || return 0
  d=$(bk_dir)/apk/$1
  [ -n "$(ls "$d" 2>/dev/null)" ] && return 0
  mkdir -p "$d"
  for a in $(pm path $1 2>/dev/null | sed 's/^package://'); do
    cp -f "$a" "$d/" 2>/dev/null
  done
}

restore_from_apk() {
  d=$(bk_dir)/apk/$1
  set -- "$d"/*.apk
  RESTORE_ERROR='备份目录里没有 APK'
  [ -f "$1" ] || return 1
  if [ $# -eq 1 ]; then
    size=$(stat -c %s "$1") || return 1
    # PackageInstaller 无权直接读取 /data/media 的备份，由 Root 打开后通过 stdin 流式传输。
    if RESTORE_ERROR=$(cat "$1" | pm install -r -d --user 0 -S "$size" -- - 2>&1); then
      RESTORE_ERROR=''; return 0
    else return 1; fi
  else
    if RESTORE_ERROR=$(restore_split_apks "$@"); then RESTORE_ERROR=''; return 0
    else return 1; fi
  fi
}

restore_split_apks() (
  # PackageInstaller sessions survive client death. Abandon on failure or cancellation,
  # in a subshell so these traps cannot replace the caller's transaction cleanup.
  sid=''
  trap '[ -z "$sid" ] || pm install-abandon "$sid" >/dev/null 2>&1' EXIT
  trap 'exit 1' HUP INT TERM
  output=$(pm install-create -r -d --user 0 2>&1) || { printf '%s\n' "$output"; exit 1; }
  sid=$(printf '%s\n' "$output" | sed -n 's/^Success: created install session \[\([0-9][0-9]*\)\]$/\1/p' | head -n 1)
  case "$sid" in ''|*[!0-9]*) sid=''; printf '%s\n' "$output"; exit 1 ;; esac
  for a in "$@"; do
    size=$(stat -c %s "$a") || exit 1
    output=$(cat "$a" | pm install-write -S "$size" "$sid" "${a##*/}" - 2>&1) || { printf '%s\n' "$output"; exit 1; }
  done
  output=$(pm install-commit "$sid" 2>&1) || { printf '%s\n' "$output"; exit 1; }
  sid=''
)

# ---------- 包状态 ----------
load_lists() {
  L_ALL=$(pm list packages -u --user 0 2>/dev/null)
  L_INS=$(pm list packages --user 0 2>/dev/null)
  L_DIS=$(pm list packages -d --user 0 2>/dev/null)
}

has() { echo "$1" | grep -Fqx "package:$2"; }

# x=不存在  u=已对当前用户卸载  d=已冻结  e=正常
pkg_state() {
  has "$L_ALL" $1 || { echo x; return; }
  has "$L_INS" $1 || { echo u; return; }
  has "$L_DIS" $1 && echo d || echo e
}

applied_get() { awk -F'|' -v package="$1" '$1==package { print; found=1; exit } END { if (!found) exit 1 }' "$APPLIED"; }

applied_add() (
  exec 9>>"$APPLIED.flock" || exit 1
  flock -x 9 9>&9 || exit 1
  applied_get "$1" >/dev/null && return 0
  printf '%s|%s|%s|%s\n' "$1" "$2" "$3" "$4" >> "$APPLIED"
)

applied_del() (
  exec 9>>"$APPLIED.flock" || exit 1
  flock -x 9 9>&9 || exit 1
  pending=$APPLIED.tmp.$$
  trap 'rm -f "$pending"' EXIT
  awk -F'|' -v package="$1" '$1!=package' "$APPLIED" > "$pending" && mv -f "$pending" "$APPLIED"
)

# ---------- 命令 ----------
# 一次 awk 算完全部包的状态（逐个 grep 太慢）
cmd_status() {
  load_lists
  {
    echo "$L_ALL" | sed 's/^package:/A /'
    echo "$L_INS" | sed 's/^package:/I /'
    echo "$L_DIS" | sed 's/^package:/D /'
    sed 's/^/P /' $APPLIED
    for p in "$@" $(cut -d'|' -f1 $APPLIED); do valid_pkg "$p" && echo "Q $p"; done
  } | awk '
    $1 == "A" { a[$2] = 1; next }
    $1 == "I" { i[$2] = 1; next }
    $1 == "D" { d[$2] = 1; next }
    $1 == "P" { split($2, f, "|"); m[f[1]] = f[2]; t[f[1]] = f[4]; next }
    $1 == "Q" {
      p = $2; if (seen[p]++) next
      st = !(p in a) ? "x" : !(p in i) ? "u" : (p in d) ? "d" : "e"
      print p "|" st "|" m[p] "|" t[p]
    }'
}

package_lock() {
  exec 8>>"$DATA/packages.transaction.flock" || return 1
  flock -x 8 8>&8
}

cmd_apply() (
  package_lock || { echo 'ERR|packages|无法锁定应用状态，未修改应用'; return 1; }
  mode=$1; shift
  [ "$mode" = uninstall ] || mode=disable
  load_lists
  for p in "$@"; do
    valid_pkg "$p" || { echo "ERR|$p|包名不合法"; continue; }
    is_protected $p && { echo "ERR|$p|受保护组件（核心或你在用的功能），已拒绝"; continue; }
    st=$(pkg_state $p)
    [ "$st" = x ] && { echo "ERR|$p|本机不存在"; continue; }
    applied_get $p >/dev/null && { echo "SKIP|$p|已处理过"; continue; }
    [ "$st" = u ] && { echo "SKIP|$p|原本就已卸载"; continue; }
    [ "$st" = d ] && [ "$mode" = disable ] && { echo "SKIP|$p|原本就是冻结状态"; continue; }
    backup_apk $p
    # 先落盘恢复记录，再发系统写入；即使页面取消/超时发生在 Binder 返回前也能恢复。
    applied_add "$p" "$mode" "$st" "$(date +%s)" || { echo "ERR|$p|恢复记录保存失败，未修改应用"; continue; }
    am force-stop $p >/dev/null 2>&1
    if [ "$mode" = uninstall ]; then
      out=$(pm uninstall -k --user 0 $p 2>&1)
      ok=$(echo "$out" | grep -c Success)
    else
      out=$(pm disable-user --user 0 $p 2>&1)
      ok=$(echo "$out" | grep -c 'disabled')
    fi
    if [ "$ok" -gt 0 ]; then
      log "apply $mode $p"
      echo "OK|$p|$([ $mode = uninstall ] && echo 已卸载 || echo 已冻结)"
    else
      # 系统可能已经生效但命令响应丢失；仅回读确认完全未变时才撤销预写记录。
      load_lists
      [ "$(pkg_state "$p")" = "$st" ] && applied_del "$p"
      log "apply $mode $p FAIL: $out"
      echo "ERR|$p|$(echo "$out" | tail -n 1 | tr '|' ' ')"
    fi
  done
  bk_sync
)

restore_one() {
  p=$1
  RESTORE_ERROR=''
  line=$(applied_get $p) || { echo "SKIP|$p|没有处理记录"; return; }
  mode=$(echo "$line" | cut -d'|' -f2)
  orig=$(echo "$line" | cut -d'|' -f3)
  protected=0
  # 旧备份可能记录了核心组件的冻结状态，恢复时也不能再次冻结它。
  if is_protected "$p"; then protected=1; orig=e; fi
  if [ "$mode" = uninstall ]; then
    pm install-existing --user 0 $p >/dev/null 2>&1
    pm list packages --user 0 | grep -Fqx "package:$p" || restore_from_apk $p
  fi
  action_ok=0
  if [ "$orig" = d ]; then
    action_out=$(pm disable-user --user 0 "$p" 2>&1) && action_ok=1
  else
    action_out=$(pm enable --user 0 "$p" 2>&1) && action_ok=1
  fi
  # 安装存在不代表恢复成功；enable 失败时必须保留记录，不能丢失之后的恢复机会。
  restored=0
  if [ "$action_ok" = 1 ] && installed=$(pm list packages --user 0 2>/dev/null) &&
      disabled=$(pm list packages -d --user 0 2>/dev/null) && has "$installed" "$p"; then
    if [ "$orig" = d ]; then has "$disabled" "$p" && restored=1
    else has "$disabled" "$p" || restored=1; fi
  fi
  if [ "$restored" = 1 ]; then
    applied_del $p
    log "restore $p"
    if [ "$protected" = 1 ]; then echo "OK|$p|受保护组件，已保持启用"
    else echo "OK|$p|已恢复"; fi
  else
    log "restore $p FAIL"
    detail=$(printf '%s' "${RESTORE_ERROR:-$action_out}" | tr '\n|' '  ' | head -c 600)
    echo "ERR|$p|恢复失败，已保留记录：${detail:-安装或启用状态未通过回读}"
  fi
}

cmd_restore() (
  package_lock || { echo 'ERR|packages|无法锁定应用状态，未恢复应用'; return 1; }
  for p in "$@"; do valid_pkg "$p" && restore_one $p; done
  bk_sync
)

cmd_restore_all() (
  package_lock || { echo 'ERR|packages|无法锁定应用状态，未恢复应用'; return 1; }
  for p in $(cut -d'|' -f1 $APPLIED); do restore_one $p; done
  bk_sync
)

# 模块数据丢失（重装/清数据）时，从备份目录把处理记录合并回来
cmd_import() (
  package_lock || { echo 'ERR|packages|无法锁定应用状态，未导入记录'; return 1; }
  d=$(bk_dir)
  [ -f "$d/applied.list" ] || { echo "ERR|import|备份目录里没有 applied.list"; return; }
  n=0
  while IFS= read -r line; do
    p=${line%%\|*}
    valid_pkg "$p" || continue
    is_protected "$p" && continue
    IFS='|' read -r p mode orig ts <<EOF
$line
EOF
    case "$mode:$orig" in disable:e|disable:d|uninstall:e|uninstall:d) ;; *) continue ;; esac
    case "$ts" in ''|*[!0-9]*) continue ;; esac
    applied_get "$p" >/dev/null || { applied_add "$p" "$mode" "$orig" "$ts" && n=$((n+1)); }
  done < "$d/applied.list"
  if [ -f "$d/tweaks.bak" ]; then (
    tweak_lock || exit 1
    pending=$TWEAKBAK.tmp.$$
    trap 'rm -f "$pending"' EXIT
    # 已有原值优先；未知开关、损坏行和重复行不能污染恢复表。
    awk -F'|' -v known="$(echo "$TWEAKS" | cut -d'|' -f1 | tr '\n' ' ')" '
      BEGIN { split(known, ids, " "); for (i in ids) allowed[ids[i]]=1 }
      NF>=2 && ($1 in allowed) && !seen[$1]++ { print }
    ' "$TWEAKBAK" "$d/tweaks.bak" > "$pending" && mv -f "$pending" "$TWEAKBAK"
  ) || { echo 'ERR|import|开关恢复记录导入失败'; return 1; }; fi
  echo "OK|import|合并了 $n 条处理记录"
)

# ---------- 系统开关 ----------
tweak_line() { echo "$TWEAKS" | grep -m1 "^$1|"; }

cmd_tweak_status() {
  echo "$TWEAKS" | while IFS='|' read -r id ns key val desc; do
    cur=$(settings get $ns $key </dev/null 2>/dev/null)
    on=0; grep -q "^$id|" $TWEAKBAK && on=1
    echo "$id|$on|$cur|$val|$desc"
  done
}

tweak_lock() {
  exec 8>>"$DATA/tweaks.flock" || return 1
  flock -x 8 8>&8
}

tweak_on() (
  tweak_lock || { echo "ERR|$1|开关状态正在变更，未修改系统"; return 1; }
  line=$(tweak_line $1) || { echo "ERR|$1|未知开关"; return; }
  IFS='|' read -r id ns key val desc <<EOF
$line
EOF
  if ! awk -F'|' -v wanted="$id" '$1==wanted { found=1 } END { exit !found }' "$TWEAKBAK"; then
    original=$(settings get "$ns" "$key" </dev/null 2>/dev/null) || {
      echo "ERR|$id|无法读取原始设置，未修改系统"; return 1;
    }
    pending=$TWEAKBAK.tmp.$$
    trap 'rm -f "$pending"' EXIT
    { cat "$TWEAKBAK" && printf '%s|%s\n' "$id" "$original"; } > "$pending" &&
      mv -f "$pending" "$TWEAKBAK" || { echo "ERR|$id|原始设置保存失败，未修改系统"; return 1; }
  fi
  if settings put "$ns" "$key" "$val" </dev/null &&
      actual=$(settings get "$ns" "$key" </dev/null 2>/dev/null) && [ "$actual" = "$val" ]; then
    echo "OK|$id|已开启"; log "tweak on $id"
  else
    echo "ERR|$id|设置未通过回读，已保留原始状态供恢复"; return 1
  fi
)

tweak_off() (
  tweak_lock || { echo "ERR|$1|开关状态正在变更，未恢复系统"; return 1; }
  line=$(tweak_line $1) || { echo "ERR|$1|未知开关"; return; }
  IFS='|' read -r id ns key val desc <<EOF
$line
EOF
  record=$(awk -F'|' -v wanted="$id" '$1==wanted { print; found=1; exit } END { if (!found) exit 1 }' "$TWEAKBAK") || {
    echo "OK|$id|已关闭，没有需要恢复的原始记录"; return 0;
  }
  orig=${record#*\|}
  if ! restore_setting "$ns" "$key" "$orig" ||
      ! actual=$(settings get "$ns" "$key" </dev/null 2>/dev/null) || [ "$actual" != "$orig" ]; then
    echo "ERR|$id|原始设置恢复未通过回读，已保留恢复记录"; return 1
  fi
  pending=$TWEAKBAK.tmp.$$
  trap 'rm -f "$pending"' EXIT
  awk -F'|' -v wanted="$id" '$1!=wanted' "$TWEAKBAK" > "$pending" &&
    mv -f "$pending" "$TWEAKBAK" || { echo "ERR|$id|系统已恢复，但恢复记录未能更新"; return 1; }
  log "tweak off $id"
  echo "OK|$id|已还原为 ${orig:-默认}"
)

# ---------- hosts ----------
# post-fs-data 把 $HOSTS 绑定到 /system/etc/hosts；之后原地改写内容即可立即生效
hosts_build() (
  exec 5>>"$DATA/hosts.build.flock" || return 1
  flock -x 5 5>&5 || return 1
  base=$DATA/hosts.orig
  [ -s "$base" ] || printf '127.0.0.1       localhost\n::1             ip6-localhost\n' > "$base" || return 1
  for g in xiaomi sdk antifraud reporting; do
    [ "$(conf_get hosts_$g 0)" != 1 ] || [ -r "$MODDIR/hosts/$g.txt" ] || return 1
  done
  pending=$(mktemp "$HOSTS.new.XXXXXXXX") || return 1
  trap 'rm -f "$pending"' EXIT
  {
    cat "$base" || exit 1
    for g in xiaomi sdk antifraud reporting; do
      [ "$(conf_get hosts_$g 0)" = 1 ] || continue
      echo "# HyperAdClean: $g"
      if [ "$g" = reporting ]; then
        grep -vE '^[[:space:]]*(#|$)' "$MODDIR/hosts/$g.txt" | awk '{print "0.0.0.0 " $0; print ":: " $0}'
      else
        grep -vE '^[[:space:]]*(#|$)' "$MODDIR/hosts/$g.txt" | sed 's/^/0.0.0.0 /'
      fi
    done
    if [ "$(conf_get hosts_custom 0)" = 1 ] && [ -f $DATA/hosts.custom ]; then
      echo "# HyperAdClean: custom"
      grep -vE '^[[:space:]]*(#|$)' $DATA/hosts.custom | while read -r a b; do
        if [ -n "$b" ]; then echo "$a $b"; else echo "0.0.0.0 $a"; fi
      done
    fi
  } > "$pending" || return 1
  if [ -f "$HOSTS" ]; then cat "$pending" > "$HOSTS"; else cp "$pending" "$HOSTS"; fi || return 1 # 保持 inode 不变
  chmod 644 "$HOSTS" && chcon u:object_r:system_file:s0 "$HOSTS"
)

# KSU 魔术挂载会把 /system/etc 变成 tmpfs 并单独绑定原版 hosts，所以用 设备号:inode 判断是不是我们的文件
in_init_ns() (
  current_ns=$(readlink /proc/self/ns/mnt 2>/dev/null) || return 1
  init_ns=$(readlink /proc/1/ns/mnt 2>/dev/null) || return 1
  [ -n "$current_ns" ] && [ -n "$init_ns" ] || return 1
  [ "$current_ns" = "$init_ns" ] && { "$@"; return; }
  # 不能把应用私有 namespace 的成功误报成系统全局生效。
  command -v nsenter >/dev/null 2>&1 || return 1
  nsenter -t 1 -m -- sh -c '
    current_ns=$(readlink /proc/self/ns/mnt 2>/dev/null) || exit 1
    init_ns=$(readlink /proc/1/ns/mnt 2>/dev/null) || exit 1
    [ -n "$current_ns" ] && [ "$current_ns" = "$init_ns" ] || exit 1
    exec "$@"
  ' hypercraft-init "$@"
)

hosts_mounted() {
  [ -f $HOSTS ] && [ "$(in_init_ns stat -c %d:%i /system/etc/hosts 2>/dev/null)" = "$(stat -c %d:%i $HOSTS)" ]
}

# 在 init 的挂载命名空间里绑定（netd 的 DNS 解析在这里读 hosts），开机后执行也能立即生效
hosts_mount() {
  [ -s $HOSTS ] || hosts_build
  hosts_mounted && return 0
  chcon u:object_r:system_file:s0 $HOSTS
  in_init_ns mount -o bind $HOSTS /system/etc/hosts
  hosts_mounted || return 1
  log "hosts mounted"
}

cmd_hosts_status() {
  m=0; hosts_mounted && m=1
  n=$(grep -c '^0\.0\.0\.0 ' $HOSTS 2>/dev/null)
  echo "$m|$(conf_get hosts_xiaomi 0)|$(conf_get hosts_sdk 0)|$(conf_get hosts_custom 0)|${n:-0}"
}

cmd_hosts_set() {
  case "$1" in xiaomi|sdk|custom|antifraud|reporting) ;; *) echo "ERR|$1|未知规则组"; return ;; esac
  case "$2" in 0|1) ;; *) echo "ERR|$1|取值只能是 0/1"; return 1 ;; esac
  conf_set hosts_$1 $2
  hosts_build || { echo "ERR|$1|已保存，但 hosts 规则生成失败，请检查后重试"; return 1; }
  bk_sync
  log "hosts $1=$2"
  hosts_mount && echo "OK|$1|已生效" || { echo "ERR|$1|已保存，但系统全局挂载失败，未确认生效"; return 1; }
}

cmd_reporting_hosts_status() {
  mounted=0; hosts_mounted && mounted=1
  # Verify exact names in the actual init namespace, not just the saved config.
  blocked=$(in_init_ns cat /system/etc/hosts 2>/dev/null | awk '
    { for(i=2;i<=NF;i++) {
      if ($i ~ /^#/) break
      if ($i=="fraud-detection.engine.miui.com" || $i=="flash.sec.miui.com" || $i=="dm.wo.com.cn") {
        if ($1=="0.0.0.0") ipv4[$i]=1
        else if ($1=="::") ipv6[$i]=1
        else conflict[$i]=1
      }
    }} END { for(k in ipv4) if(ipv6[k] && !conflict[k]) n++; print n+0 }')
  echo "$(conf_get hosts_reporting 0)|$mounted|$blocked"
}

cmd_hosts_custom_set() {
  echo "$1" | base64 -d > $DATA/hosts.custom 2>/dev/null || { echo "ERR|custom|解码失败"; return; }
  hosts_build
  bk_sync
  [ "$(conf_get hosts_custom 0)" = 1 ] && hosts_mount
  echo "OK|custom|已保存 $(grep -cvE '^[[:space:]]*(#|$)' $DATA/hosts.custom) 条"
}

# ---------- 系统更新屏蔽 ----------
# 新系统没有 get-component-enabled-setting shell 命令，按 PackageManager 自己输出的
# User 0 覆盖列表保存 0/1/2，恢复不能一律 default-state（会丢失用户原有的禁用）。
component_state_file() {
  awk -v target="$2" '
    BEGIN { state=0 }
    /^[[:space:]]*User 0:.*installed=/ { active=1; found=1; section=0; next }
    /^[[:space:]]*User [0-9]+:/ { active=0; section=0 }
    active && /^[[:space:]]*enabledComponents:/ { section=1; next }
    active && /^[[:space:]]*disabledComponents:/ { section=2; next }
    active && /^[[:space:]]*[A-Za-z _]+:/ { section=0 }
    active && section && NF==1 && $1==target { state=section }
    END { if (!found) exit 1; print state }' "$1"
}

component_disabled_count() {
  package=$1; shift
  dumpsys package "$package" </dev/null 2>/dev/null | awk -v targets="$*" '
    BEGIN { split(targets, list, " "); for (i in list) wanted[list[i]]=1 }
    /^[[:space:]]*User 0:.*installed=/ { active=1; section=0; next }
    /^[[:space:]]*User [0-9]+:/ { active=0; section=0 }
    active && /^[[:space:]]*disabledComponents:/ { section=1; next }
    active && /^[[:space:]]*[A-Za-z _]+:/ { section=0 }
    active && section && NF==1 && ($1 in wanted) && !seen[$1]++ { count++ }
    END { print count+0 }'
}

component_snapshot() (
  snapshot=$1; package=$2; shift 2
  [ -s "$snapshot" ] && return 0
  dump=$snapshot.dump.$$
  pending=$snapshot.new.$$
  trap 'rm -f "$dump" "$pending"' EXIT
  dumpsys package "$package" </dev/null > "$dump" 2>/dev/null || return 1
  : > "$pending" || return 1
  for component in "$@"; do
    state=$(component_state_file "$dump" "${component#*/}") || return 1
    printf '%s|%s\n' "$component" "$state" >> "$pending" || return 1
  done
  [ -s "$pending" ] && mv -f "$pending" "$snapshot"
)

component_restore() (
  snapshot=$1
  [ -s "$snapshot" ] || return 1
  failed=0
  IFS='|' read -r first_component first_state < "$snapshot" || return 1
  package=${first_component%%/*}
  dump=$snapshot.verify.$$
  trap 'rm -f "$dump"' EXIT
  dumpsys package "$package" </dev/null > "$dump" 2>/dev/null || return 1
  while IFS='|' read -r component state; do
    case "$state" in 0) action=default-state ;; 1) action=enable ;; 2) action=disable ;; *) return 1 ;; esac
    actual=$(component_state_file "$dump" "${component#*/}") || return 1
    [ "$actual" = "$state" ] && continue
    pm "$action" --user 0 "$component" </dev/null >/dev/null 2>&1 || failed=1
  done < "$snapshot"
  dumpsys package "$package" </dev/null > "$dump" 2>/dev/null || return 1
  while IFS='|' read -r component state; do
    actual=$(component_state_file "$dump" "${component#*/}") || return 1
    [ "$actual" = "$state" ] || failed=1
  done < "$snapshot"
  [ "$failed" = 0 ]
)

permission_state() {
  dumpsys package "$1" </dev/null 2>/dev/null | awk -v permission="$2:" '
    /^[[:space:]]*User 0:/ { active=1; next }
    /^[[:space:]]*User [0-9]+:/ { active=0 }
    active && $1==permission && $2 ~ /^granted=(true|false),?$/ {
      value=$2; sub(/^granted=/,"",value); sub(/,$/,"",value); print value; found=1; exit
    }
    END { if (!found) exit 1 }'
}

restore_setting() {
  if [ "$3" = null ]; then settings delete "$1" "$2" </dev/null >/dev/null
  else settings put "$1" "$2" "$3" </dev/null; fi
}

# 更新程序是「小米核心应用」，系统不允许整包冻结，所以分四层处理：
# 禁用它的检查组件、收回通知权限、按 uid 断网、清掉设置里的新版本提示和桌面角标。
OTA_PKG=com.android.updater
OTA_COMPS="DailyCheckJobService server.UploadInfoJobService receiver.BootCompletedReceiver receiver.DailyCheckReceiver
 receiver.SimChangeReceiver receiver.AccountChangedReceiver receiver.LauncherDialogReceiver receiver.UrlChangeReceiver receiver.LanguageReceiver"
OTA_HOOK=$DATA/ota_hook.sh
OTA_BAK=$DATA/ota.bak
OTA_COMPONENTS_BAK=$DATA/ota.components.bak
SETTINGS_DIR=/data/system/users/0

ota_uid() {
  u=$(pm list packages -U $OTA_PKG 2>/dev/null </dev/null | grep -m1 "^package:$OTA_PKG " | sed 's/.*uid://' | cut -d, -f1)
  case "$u" in ''|*[!0-9]*) return 1 ;; esac
  conf_set ota_uid "$u"
  echo "$u"
}

# 自建 hcf_ota 链挂到 OUTPUT 最前面，关闭时整链删除，不碰系统自己的规则
ota_fw() {
  uid=$2; [ -n "$uid" ] || uid=$(conf_get ota_uid "")
  for ipt in iptables ip6tables; do
    while $ipt -w -D OUTPUT -j hcf_ota 2>/dev/null; do :; done
    $ipt -w -F hcf_ota 2>/dev/null; $ipt -w -X hcf_ota 2>/dev/null
    [ "$1" = on ] && [ -n "$uid" ] || continue
    $ipt -w -N hcf_ota && $ipt -w -A hcf_ota -m owner --uid-owner $uid -j REJECT && $ipt -w -I OUTPUT 1 -j hcf_ota
  done
}

ota_fw_active() { iptables -w -S hcf_ota 2>/dev/null | grep -q REJECT; }

# 「我的设备 · 新版本」读 miui_new_version；桌面「设置」角标 = superscript_map 里置 1 的位数，bit1 是系统更新
ota_clear() {
  v=$(settings get global miui_new_version </dev/null)
  if [ -n "$v" ] && [ "$v" != null ]; then
    settings delete global miui_new_version </dev/null >/dev/null
    log "ota 清除新版本提示 $v"
  fi
  m=$(settings get global com.android.settings.superscript_map </dev/null)
  case "$m" in ''|null|*[!0-9]*) m=0 ;; esac
  c=0; t=$m; while [ $t -gt 0 ]; do c=$((c+1)); t=$((t & (t-1))); done
  if [ $((m & 1)) -ne 0 ]; then
    m=$((m & ~1)); c=$((c-1))
    settings put global com.android.settings.superscript_map $m </dev/null
    log "ota 清除设置角标"
  fi
  [ "$(settings get global com.android.settings.superscript_count </dev/null)" = "$c" ] || \
    settings put global com.android.settings.superscript_count $c </dev/null
}

ota_watch_pid() { pgrep -f "inotifyd $OTA_HOOK" | head -n 1; }

# 监听全局设置文件的写入，更新程序哪怕从缓存里重新写回提示，也会在 1 秒内被清掉
ota_watch_start() {
  [ -n "$(ota_watch_pid)" ] && return 0
  cat > $OTA_HOOK <<EOF
#!/system/bin/sh
[ "\$3" = settings_global.xml ] || exit 0
S=$MODDIR/adclean.sh
[ -f \$S ] && exec sh \$S ota-clear
EOF
  chmod 755 $OTA_HOOK
  start_detached inotifyd $OTA_HOOK $SETTINGS_DIR:wny
  sleep 1
  [ -n "$(ota_watch_pid)" ]
}

ota_watch_stop() { for p in $(pgrep -f "inotifyd $OTA_HOOK"); do kill $p; done; }

ota_comps_disabled() {
  set --
  for c in $OTA_COMPS; do set -- "$@" "$OTA_PKG.$c"; done
  component_disabled_count "$OTA_PKG" "$@"
}

cmd_ota_on() {
  uid=$(ota_uid)
  [ -n "$uid" ] || { echo "ERR|ota|本机没有系统更新应用"; return; }
  if [ ! -s "$OTA_BAK" ]; then
    [ "$(conf_get ota_block 0)" = 0 ] || { echo 'ERR|ota|旧配置缺少原始状态，请先关闭并手动检查系统更新设置'; return; }
    permission=$(permission_state "$OTA_PKG" android.permission.POST_NOTIFICATIONS) || {
      echo 'ERR|ota|无法读取通知权限基线，未修改系统'; return;
    }
    rm -f "$OTA_COMPONENTS_BAK"
    set --
    for c in $OTA_COMPS; do set -- "$@" "$OTA_PKG/$OTA_PKG.$c"; done
    component_snapshot "$OTA_COMPONENTS_BAK" "$OTA_PKG" "$@" || {
      echo 'ERR|ota|无法读取组件基线，未修改系统'; return;
    }
    {
      printf 'p|android.permission.POST_NOTIFICATIONS|%s\n' "$permission"
      for k in miui_new_version com.android.settings.superscript_map com.android.settings.superscript_count; do
        printf 'g|%s|%s\n' "$k" "$(settings get global "$k" </dev/null)"
      done
    } > "$OTA_BAK.new" && mv -f "$OTA_BAK.new" "$OTA_BAK" || {
      echo 'ERR|ota|无法保存恢复基线，未修改系统'; return;
    }
  fi
  [ -s "$OTA_COMPONENTS_BAK" ] || { echo 'ERR|ota|组件恢复基线丢失，未修改系统'; return; }
  conf_set ota_block 1
  for c in $OTA_COMPS; do pm disable --user 0 $OTA_PKG/$OTA_PKG.$c </dev/null >/dev/null 2>&1; done
  pm revoke $OTA_PKG android.permission.POST_NOTIFICATIONS </dev/null >/dev/null 2>&1
  am force-stop $OTA_PKG </dev/null
  ota_fw on $uid
  ota_clear
  ota_watch_start
  log "ota 屏蔽开启 uid=$uid"
  bk_sync
  echo "OK|ota|已屏蔽系统更新"
}

cmd_ota_off() {
  was_enabled=$(conf_get ota_block 0)
  conf_set ota_block 0
  ota_watch_stop
  ota_fw off
  if [ ! -s "$OTA_BAK" ] && [ ! -s "$OTA_COMPONENTS_BAK" ] && [ "$was_enabled" = 0 ]; then
    echo 'OK|ota|已关闭，系统设置未修改'; return
  fi
  restored=1
  component_restore "$OTA_COMPONENTS_BAK" || restored=0
  [ -s "$OTA_BAK" ] || restored=0
  if [ -s "$OTA_BAK" ]; then
    while IFS='|' read -r type key value; do
      case "$type" in
        g) restore_setting global "$key" "$value" || restored=0 ;;
        p)
          case "$value" in true) action=grant ;; false) action=revoke ;; *) restored=0; continue ;; esac
          current_permission=$(permission_state "$OTA_PKG" "$key") || { restored=0; continue; }
          if [ "$current_permission" != "$value" ]; then
            pm "$action" --user 0 "$OTA_PKG" "$key" </dev/null >/dev/null 2>&1 || restored=0
          fi
          [ "$(permission_state "$OTA_PKG" "$key")" = "$value" ] || restored=0 ;;
      esac
    done < "$OTA_BAK"
  fi
  [ "$restored" = 1 ] || { echo 'ERR|ota|开机屏蔽已停止，部分原始状态无法恢复；已保留基线，请检查后重试'; return; }
  rm -f "$OTA_BAK" "$OTA_COMPONENTS_BAK"
  log "ota 屏蔽关闭"
  bk_sync
  echo "OK|ota|已恢复，系统更新下次检查时会重新提示"
}

# 开关|断网|已禁用组件数|监听|当前提示的新版本|设置角标位
cmd_ota_status() {
  fw=0; ota_fw_active && fw=1
  w=0; [ -n "$(ota_watch_pid)" ] && w=1
  m=$(settings get global com.android.settings.superscript_map </dev/null)
  case "$m" in ''|null|*[!0-9]*) m=0 ;; esac
  echo "$(conf_get ota_block 0)|$fw|$(ota_comps_disabled)|$w|$(settings get global miui_new_version </dev/null)|$((m & 1))"
}

# 开机早期（pm 还没起来）先用缓存的 uid 断网，开机完成后再补齐其余部分
cmd_ota_early() { [ "$(conf_get ota_block 0)" = 1 ] && ota_fw on; }

# ---------- 全局高刷 ----------
# 两种方式（conf fps_method）：
# smart：把 HyperOS 自己的刷新率设置（secure miui_refresh_rate / user_refresh_rate）改成目标值，
#        系统的 PRIORITY_MIUI_REFRESH_RATE 投票随之变成 0~目标值，滑动时到顶，静止/视频时照常降帧。
#        设置界面只列出 120/90/60，但系统接受 144；开机后由 cmd_boot 重新写入，防止被系统改回。
# force：让 SurfaceFlinger 强制显示模式（1035），始终满帧不降，更耗电。
#        强制模式在灭屏后会被系统重置，由 fps-daemon 在每次亮屏（排除息屏 AOD）后重新应用。
# hold：强制（省电）。在 smart 基础上延长 SurfaceFlinger 的静止降帧等待（系统默认 1.1 秒），
#        操作时和停手后 N 秒内保持目标值，之后照常降帧；视频、温控、省电模式也照常降。
#        HyperOS 不处理 min_refresh_rate，小米刷新率投票的下限写死为 0，所以只能从降帧等待入手。
#        等待时间有两处：属性 debug.sf.set_idle_timer_ms（SurfaceFlinger 启动时读）和 displayconfig 里的
#        idleScreenRefreshRateTimeout（DisplayManager 启动时读、随策略下发），都在 post-fs-data 改，重启生效。
FPS_SMART_MAX=144
FI_PROP=debug.sf.set_idle_timer_ms
FI_DIR=/dev/hypercraft/displayconfig
FI_BOOT=$DATA/idle.boot

fps_native_apply() {
  for k in miui_refresh_rate user_refresh_rate; do
    [ "$(conf_get fps_orig_$k "")" = "" ] && conf_set fps_orig_$k "$(settings get secure $k </dev/null)"
    settings put secure $k $1 </dev/null
  done
}

fps_native_restore() {
  for k in miui_refresh_rate user_refresh_rate; do
    o=$(conf_get fps_orig_$k "")
    [ -n "$o" ] || continue
    if [ "$o" = null ]; then settings delete secure $k </dev/null >/dev/null; else settings put secure $k $o </dev/null; fi
    conf_set fps_orig_$k ""
  done
}

# 本次开机要用的降帧等待（毫秒），空表示不改
fi_want() {
  [ "$(conf_get fps_method smart)" = hold ] && [ "$(conf_get fps_lock 0)" != 0 ] && conf_get fps_idle 5000
}

# post-fs-data 调用：改属性 + 挂载改过等待时间的 displayconfig 副本
fps_idle_boot() {
  echo 0 > $FI_BOOT
  ms=$(fi_want); [ -n "$ms" ] || return 0
  resetprop -n "$FI_PROP" "$ms" || { log "降帧等待：resetprop 不可用，跳过"; return 1; }
  echo $ms > $FI_BOOT
  mkdir -p $FI_DIR; chmod 755 $FI_DIR
  for f in $(grep -l '<idleScreenRefreshRateTimeout>' /product/etc/displayconfig/*.xml /vendor/etc/displayconfig/*.xml /odm/etc/displayconfig/*.xml 2>/dev/null); do
    p=$FI_DIR/$(basename $f)
    sed "s#<timeout>[0-9]*</timeout>#<timeout>$ms</timeout>#" $f > $p
    chmod 644 $p; chcon $CM_LABEL $p
    [ "$(selabel $p)" = "$CM_LABEL" ] && grep -q "<timeout>$ms</timeout>" $p && grep -q '</displayConfiguration>' $p \
      || { log "降帧等待：副本异常，跳过 $f"; rm -f $p; continue; }
    in_init_ns mount -o bind $p $f
  done
  log "降帧等待：${ms}ms"
}

# 本次开机实际改成的降帧等待（毫秒），0 表示没改
fi_boot() { b=0; read -r b 2>/dev/null < $FI_BOOT; echo ${b:-0}; }

# fps-idle <毫秒>：只记录，重启生效
cmd_fps_idle() {
  case "$1" in 3000|5000|10000) ;; *) echo "ERR|fps|等待时间只能是 3000/5000/10000"; return ;; esac
  conf_set fps_idle $1
  log "降帧等待设为 ${1}ms"
  if [ "$(fi_boot)" = "$(fi_want)" ]; then echo "OK|fps|已设为停手 $(($1 / 1000)) 秒后降帧"; else echo "OK|fps|已设为停手 $(($1 / 1000)) 秒后降帧，重启手机后生效"; fi
}
BL_NODE=$(ls /sys/class/backlight/*/brightness 2>/dev/null | head -n 1)
DOZE_NODE=$(ls /sys/devices/virtual/mi_display/disp_feature/disp-DSI-0/doze_brightness 2>/dev/null)

fps_modes() {
  dumpsys SurfaceFlinger 2>/dev/null </dev/null | grep -oE 'id=[0-9]+, hwcId=[0-9]+, resolution=[0-9x]+, vsyncRate=[0-9]+' \
    | sed -E 's/^id=([0-9]+),.*vsyncRate=([0-9]+)$/\1:\2/' | sort -u -t: -k2,2n
}

fps_current() { dumpsys SurfaceFlinger 2>/dev/null </dev/null | grep -m1 -oE 'activeMode=\{id=[0-9]+[^}]*vsyncRate=[0-9]+' | grep -oE '[0-9]+$'; }

fps_apply() { service call SurfaceFlinger 1035 i32 $(conf_get fps_mode 0) >/dev/null 2>&1 </dev/null; }

fps_daemon_pid() { pgrep -f "$MODDIR/adclean[.]sh fps-daemon$" | head -n 1; }

# 系统在切换应用、打开相机等时机会重新下发刷新率策略，覆盖掉强制模式。
# 每秒读一次面板实际刷新率（sysfs，开销极小），不一致就立即重新锁定。
DFPS_NODE=$(ls /sys/devices/virtual/mi_display/disp_feature/disp-DSI-0/dynamic_fps 2>/dev/null)

# 循环里不启动任何外部进程：Android 上每启动一次 cat/sleep 约耗 20ms CPU，
# 改用 shell 内建 read 读 sysfs（约 0.05ms），用读空管道超时代替 sleep。
# 亮屏时每秒检查一次；灭屏/息屏时每 2 秒看一眼屏幕状态（亮屏后最多 2 秒锁回）。
cmd_fps_daemon() {
  daemon_lock "$DATA/fps.daemon.flock" || return 0
  fifo=$DATA/fps.fifo
  rm -f $fifo; mkfifo $fifo; exec 3<>$fifo; rm -f $fifo
  hz=$(conf_get fps_lock 0); n=0
  while [ "$hz" != 0 ]; do
    on=1; b=1; z=0
    [ -n "$BL_NODE" ] && read -r b < $BL_NODE
    [ "${b:-1}" -gt 0 ] || on=0
    [ $on = 1 ] && [ -n "$DOZE_NODE" ] && read -r z < $DOZE_NODE
    [ "${z:-0}" = 0 ] || on=0
    if [ $on = 1 ]; then
      if [ -n "$DFPS_NODE" ]; then
        read -r cur < $DFPS_NODE
        [ "$cur" != "$hz" ] && fps_apply
      elif [ $((n % 5)) = 0 ]; then
        fps_apply
      fi
      wait_s=1
    else
      wait_s=2
    fi
    n=$((n+1))
    if [ $((n % 30)) = 0 ]; then
      hz=0; while IFS='=' read -r k v; do [ "$k" = fps_lock ] && hz=$v; done < $CONF
    fi
    read -t $wait_s -u3 _ 2>/dev/null
  done
}

# 结束旧的守护进程并等它真正退出，否则新进程会以为已在运行而不启动
fps_daemon_stop() {
  for p in $(pgrep -f "$MODDIR/adclean[.]sh fps-daemon$"); do kill $p; done
  i=0; while [ -n "$(fps_daemon_pid)" ] && [ $i -lt 20 ]; do sleep 0.1; i=$((i+1)); done
}

fps_daemon_start() {
  [ -n "$(fps_daemon_pid)" ] && return 0
  start_detached sh $MODDIR/adclean.sh fps-daemon
}

# 锁定值|当前|守护进程|可选模式(id:hz,...)|方式|系统刷新率设置|智能方式上限|上次选的刷新率|降帧等待设置|本次开机已生效的降帧等待（0=系统默认）
cmd_fps_status() {
  d=0; [ -n "$(fps_daemon_pid)" ] && d=1
  modes=$(fps_modes)
  # 没选过刷新率时，默认取本机支持的最高档（不超过智能方式上限）
  top=0; for e in $modes; do hz=${e#*:}; [ $hz -le $FPS_SMART_MAX ] && top=$hz; done
  [ $top = 0 ] && top=60
  echo "$(conf_get fps_lock 0)|$(fps_current)|$d|$(echo $modes | tr ' ' ',')|$(conf_get fps_method smart)|$(settings get secure miui_refresh_rate </dev/null)|$FPS_SMART_MAX|$(conf_get fps_last $top)|$(conf_get fps_idle 5000)|$(fi_boot)"
}

# 降帧等待和本次开机不一致时提示重启
fi_hint() { w=$(fi_want); [ "${w:-0}" = "$(fi_boot)" ] || echo "，重启手机后$1"; }

# fps-set <Hz|0> [smart|force|hold]
cmd_fps_set() {
  m=${2:-$(conf_get fps_method smart)}
  case "$m" in smart|force|hold) ;; *) echo "ERR|fps|方式只能是 smart/force/hold"; return ;; esac
  # 强制锁定会让 SurfaceFlinger 忽略系统下发的刷新率策略，直到下次灭屏
  forced=0; [ -n "$(fps_daemon_pid)" ] && forced=1
  if [ "$1" = 0 ]; then
    conf_set fps_lock 0
    fps_daemon_stop
    fps_native_restore
    log "fps 恢复系统默认"
    [ $forced = 1 ] && echo "OK|fps|已恢复系统默认，锁一次屏后完全生效$(fi_hint 恢复系统降帧等待)" || echo "OK|fps|已恢复系统默认$(fi_hint 恢复系统降帧等待)"
    return
  fi
  id=$(fps_modes | grep -m1 ":$1$" | cut -d: -f1)
  [ -n "$id" ] || { echo "ERR|fps|屏幕不支持 ${1}Hz"; return; }
  [ $m = smart ] && [ $1 -gt $FPS_SMART_MAX ] && { echo "ERR|fps|智能方式最高 ${FPS_SMART_MAX}Hz，更高请用强制锁定"; return; }
  [ $m = hold ] && [ $1 -gt $FPS_SMART_MAX ] && { echo "ERR|fps|强制（省电）最高 ${FPS_SMART_MAX}Hz，更高请用强制锁定"; return; }
  conf_set fps_mode $id; conf_set fps_lock $1; conf_set fps_last $1; conf_set fps_method $m
  fps_daemon_stop
  # 两种方式都写系统设置：强制方式下系统策略与强制值一致，守护进程就很少需要重新锁定
  [ $1 -le $FPS_SMART_MAX ] && fps_native_apply $1
  if [ $m = force ]; then
    fps_apply; fps_daemon_start
    log "fps 强制锁定 ${1}Hz (mode $id)"
    echo "OK|fps|已强制锁定 ${1}Hz$(fi_hint 恢复系统降帧等待)"
  elif [ $m = hold ]; then
    s=$(($(conf_get fps_idle 5000) / 1000))
    log "fps 强制（省电）${1}Hz，停手 ${s} 秒后降帧"
    [ $forced = 1 ] && echo "OK|fps|已设为 ${1}Hz、停手 ${s} 秒后降帧，锁一次屏后解除之前的强制锁定$(fi_hint 延长降帧等待)" || echo "OK|fps|已设为 ${1}Hz、停手 ${s} 秒后降帧$(fi_hint 延长降帧等待)"
  else
    log "fps 智能 最高 ${1}Hz"
    [ $forced = 1 ] && echo "OK|fps|已设为最高 ${1}Hz，锁一次屏后解除之前的强制锁定$(fi_hint 恢复系统降帧等待)" || echo "OK|fps|已设为最高 ${1}Hz，静止时系统自动降帧$(fi_hint 恢复系统降帧等待)"
  fi
}

# 开机：智能方式重新写入系统设置（系统可能把界面里没有的 144 改回去），强制方式启动守护进程
fps_boot() {
  hz=$(conf_get fps_lock 0); [ "$hz" != 0 ] || return 0
  [ $hz -le $FPS_SMART_MAX ] && fps_native_apply $hz
  [ "$(conf_get fps_method smart)" = force ] && fps_daemon_start
}

cmd_fps_overlay() {
  case "$1" in
    0|1) service call SurfaceFlinger 1034 i32 $1 >/dev/null </dev/null; echo "OK|overlay|$([ $1 = 1 ] && echo 已显示刷新率 || echo 已关闭刷新率显示)" ;;
    *) service call SurfaceFlinger 1034 i32 2 </dev/null | grep -q '00000001' && echo 1 || echo 0 ;;
  esac
}

# ---------- 字体大小（系统重启会还原，开机由模块重新应用） ----------
cmd_font_set() {
  case "$1" in 0) conf_set font_scale 0; echo "OK|font|已取消开机保持"; return ;; esac
  echo "$1" | grep -qE '^[0-9]+(\.[0-9]+)?$' || { echo "ERR|font|数值不合法"; return; }
  settings put system font_scale $1 </dev/null
  conf_set font_scale $1
  log "font_scale=$1"
  echo "OK|font|字体缩放 $1，开机自动保持"
}

# ---------- 完整色彩风格 ----------
# 官方 device_features 里 support_simplified_colormode=true，设置的「色彩风格」只剩专业原色/生动两项，
# 改成 false 就是完整版（生动/鲜艳/高级模式）。设置通过 FeatureParser 读这个值，而 FeatureParser
# 在 Zygote 里就已初始化，开机后再挂载对新进程也无效，所以只在 post-fs-data 挂载，切换后重启生效。
CM_KEY=support_simplified_colormode
# 副本必须是 system_file 标签，否则 Zygote 读不了，整份 device_features 失效（所有功能回到默认值）。
# /data/adb 下的文件开机时会被重新打成 adb_data_file，所以放在 tmpfs 的 /dev 下
CM_DIR=/dev/hypercraft
CM_PATCH=$CM_DIR/device_features.xml
CM_LABEL=u:object_r:system_file:s0
CM_BOOT=$DATA/color.boot   # 本次开机实际是否挂载

cm_file() {
  for d in $(getprop ro.product.device) $(getprop ro.product.odm.device) $(getprop ro.product.mod_device); do
    [ -f /product/etc/device_features/$d.xml ] && { echo /product/etc/device_features/$d.xml; return; }
  done
}

cm_mounted() {
  f=$(cm_file)
  [ -n "$f" ] && [ -f $CM_PATCH ] && [ "$(in_init_ns stat -c %d:%i $f 2>/dev/null)" = "$(stat -c %d:%i $CM_PATCH)" ]
}

# 原版里这个开关的值：true / false / 空（没有这个开关）。挂载后读到的是副本，而只有原版为 true 才会挂载
cm_orig() {
  cm_mounted && { echo true; return; }
  f=$(cm_file); [ -n "$f" ] || return
  sed -n "s#.*<bool name=\"$CM_KEY\">\([a-z]*\)</bool>.*#\1#p" $f | head -n 1
}

# 每次都从当前的原版生成副本，系统更新改了原文件也不会错位
cm_mount() {
  cm_mounted && return 0
  f=$(cm_file); [ -n "$f" ] || return 1
  grep -q "<bool name=\"$CM_KEY\">true</bool>" $f || return 1
  mkdir -p $CM_DIR; chmod 755 $CM_DIR
  sed "s#<bool name=\"$CM_KEY\">true</bool>#<bool name=\"$CM_KEY\">false</bool>#" $f > $CM_PATCH
  chmod 644 $CM_PATCH
  chcon $CM_LABEL $CM_PATCH
  # 标签或内容不对宁可不挂，避免整份 device_features 失效
  [ "$(selabel $CM_PATCH)" = "$CM_LABEL" ] || { log "色彩风格：副本标签异常，放弃挂载"; rm -f $CM_PATCH; return 1; }
  grep -q "<bool name=\"$CM_KEY\">false</bool>" $CM_PATCH && grep -q '</features>' $CM_PATCH \
    || { log "色彩风格：副本内容异常，放弃挂载"; rm -f $CM_PATCH; return 1; }
  in_init_ns mount -o bind $CM_PATCH $f
  cm_mounted || return 1
  log "色彩风格：已挂载完整版 device_features"
}

cmd_color_boot() {
  s=0
  [ "$(conf_get color_full 0)" = 1 ] && cm_mount && s=1
  echo $s > $CM_BOOT
}

# 开关|本次开机已生效|原版取值
cmd_color_status() {
  b=0; read -r b 2>/dev/null < $CM_BOOT
  [ "$b" = 1 ] && ! cm_mounted && b=0
  echo "$(conf_get color_full 0)|${b:-0}|$(cm_orig)"
}

cmd_color_set() {
  case "$1" in 0|1) ;; *) echo "ERR|color|取值只能是 0/1"; return ;; esac
  if [ $1 = 1 ]; then
    o=$(cm_orig)
    [ -n "$o" ] || { echo "ERR|color|本机的设备配置里没有简化色彩开关"; return; }
    [ "$o" = true ] || { echo "ERR|color|系统本来就是完整版色彩风格"; return; }
  fi
  conf_set color_full $1
  bk_sync
  log "色彩风格完整版=$1"
  b=0; read -r b 2>/dev/null < $CM_BOOT
  if [ "$1" = "${b:-0}" ]; then echo "OK|color|已恢复为当前生效的状态"
  elif [ $1 = 1 ]; then echo "OK|color|已开启，重启手机后生效"
  else echo "OK|color|已关闭，重启手机后恢复官方精简版"; fi
}

# ---------- CPU 限频 ----------
# cpu_<策略>=选定频率（关闭总开关后仍保留，再打开时直接套用）
# cpu_<策略>_orig=限频前的上限（可能本来就被其他模块/系统限制着），恢复默认时写回它
CPUFREQ=/sys/devices/system/cpu/cpufreq

cpu_write() (
  case "$1" in policy[0-9]*) ;; *) return 1 ;; esac
  case "${1#policy}" in ''|*[!0-9]*) return 1 ;; esac
  case "$2" in ''|*[!0-9]*) return 1 ;; esac
  [ -n "$2" ] && [ "$2" -gt 0 ] || return 1
  cpu_node=$CPUFREQ/$1/scaling_max_freq
  cpu_mode=$(stat -c %a "$cpu_node") || return 1
  # 恢复写入前的权限，不能把所有设备节点永久变为 0444。
  trap 'chmod "$cpu_mode" "$cpu_node" 2>/dev/null' EXIT
  trap 'exit 1' HUP INT TERM
  chmod u+w "$cpu_node" || return 1
  printf '%s\n' "$2" > "$cpu_node" || return 1
  chmod "$cpu_mode" "$cpu_node" || return 1
  [ "$(cat "$cpu_node")" = "$2" ]
)

cpu_limit() {
  if [ "$(conf_get cpu_${1}_orig 0)" = 0 ]; then
    cpu_original=$(cat "$CPUFREQ/$1/scaling_max_freq") || return 1
    case "$cpu_original" in ''|*[!0-9]*) return 1 ;; esac
    [ "$cpu_original" -gt 0 ] || return 1
    conf_set cpu_${1}_orig "$cpu_original"
  fi
  cpu_write $1 $2
}

cpu_unlimit() {
  orig=$(conf_get cpu_${1}_orig 0)
  [ "$orig" = 0 ] && return 0
  cpu_write $1 $orig || return 1
  conf_set cpu_${1}_orig 0
}

# 第一行 enable|总开关，其后每行：策略|当前上限|硬件最大|可选频率(逗号)|选定频率
cmd_cpu_status() {
  echo "enable|$(conf_get cpu_enable 0)"
  for p in $CPUFREQ/policy*; do
    n=${p##*/}
    echo "$n|$(cat $p/scaling_max_freq)|$(cat $p/cpuinfo_max_freq)|$(tr ' ' ',' < $p/scaling_available_frequencies | sed 's/,*$//')|$(conf_get cpu_$n 0)"
  done
}

cmd_cpu_set() {
  case "$1" in policy[0-9]*) ;; *) echo "ERR|cpu|CPU 策略不合法"; return ;; esac
  case "${1#policy}" in ''|*[!0-9]*) echo "ERR|cpu|CPU 策略不合法"; return ;; esac
  case "$2" in ''|*[!0-9]*) echo "ERR|cpu|CPU 参数不合法"; return ;; esac
  [ -d $CPUFREQ/$1 ] || { echo "ERR|$1|没有这个 CPU 策略"; return; }
  if [ "$2" = 0 ]; then
    cpu_unlimit $1 || { echo "ERR|$1|恢复未通过回读，已保留原始上限供重试"; return; }
    conf_set cpu_$1 0
    log "cpu $1 不限制"
    echo "OK|$1|已还原为默认上限"; return
  fi
  grep -qw "$2" $CPUFREQ/$1/scaling_available_frequencies || { echo "ERR|$1|频率不合法"; return; }
  cpu_limit $1 $2 || { echo "ERR|$1|写入未生效或被系统覆盖，已保留原始上限供恢复"; return; }
  conf_set cpu_$1 $2; conf_set cpu_enable 1
  log "cpu $1 限频 $2"
  [ "$(cat $CPUFREQ/$1/scaling_max_freq)" = "$2" ] && echo "OK|$1|已限制到 $(($2/1000))MHz" || echo "OK|$1|已写入，但被系统调度覆盖为 $(($(cat $CPUFREQ/$1/scaling_max_freq)/1000))MHz"
}

# 总开关：关 = 全部恢复默认上限（保留选定值），开 = 重新套用选定值
cmd_cpu_enable() {
  case "$1" in 0|1) ;; *) echo "ERR|cpu|取值只能是 0/1"; return ;; esac
  conf_set cpu_enable $1
  cpu_failed=0
  for p in $CPUFREQ/policy*; do
    [ -d "$p" ] || continue
    n=${p##*/}; v=$(conf_get cpu_$n 0)
    if [ "$1" = 1 ]; then
      if [ "$v" != 0 ]; then cpu_limit $n $v || cpu_failed=1; fi
    else cpu_unlimit $n || cpu_failed=1; fi
  done
  [ "$cpu_failed" = 0 ] || { echo "ERR|cpu|部分策略未通过回读，已保留原始上限供恢复"; return; }
  log "cpu 总开关 $1"
  [ "$1" = 1 ] && echo "OK|cpu|已启用限频" || echo "OK|cpu|已恢复默认，所有核心还原为原来的上限"
}

# ---------- 电池优化白名单（开机重新加回，防止被系统清掉） ----------
# 白名单按包名生效，所有用户空间（工作资料、分身）共用；系统设置「电池优化」里的「无限制」就是这份名单，
# 「受限」则是各空间单独的 RUN_ANY_IN_BACKGROUND=ignore。
# 白名单只管 Doze 和待机分组，HyperOS 退后台冻结由 PowerKeeper「省电策略」决定，要同时设成无限制才不被冻结。
wl_users() { pm list users </dev/null | sed -n 's/.*UserInfo{\([0-9]*\):\([^:]*\):\([0-9a-fA-F]*\)}.*/\1|\2|\3/p'; }

# 省电策略按用户记录：noRestrict 无限制 / miuiAuto 智能限制（默认）/ restrictBg 限制后台
PK_URI=content://com.miui.powerkeeper.configure/userTable
pk_get() { content query --uri $PK_URI --projection bgControl --where "pkgName='$1' AND userId=$2" </dev/null 2>/dev/null | grep -o 'bgControl=[A-Za-z]*' | cut -d= -f2; }
pk_set() {
  cur=$(pk_get $1 $2)
  [ "$cur" = "$3" ] && return
  if [ -n "$cur" ]; then
    content update --uri $PK_URI --bind bgControl:s:$3 --where "pkgName='$1' AND userId=$2" </dev/null >/dev/null 2>&1
  else
    content insert --uri $PK_URI --bind userId:i:$2 --bind pkgName:s:$1 --bind bgControl:s:$3 --bind lastConfigured:l:$(date +%s)000 </dev/null >/dev/null 2>&1
  fi
}

# 应用装在哪些空间
wl_pkg_users() { for u in $(wl_users | cut -d'|' -f1); do pm path --user $u $1 </dev/null >/dev/null 2>&1 && echo $u; done; }

# 加入白名单时把某个空间里也设成「无限制」：省电策略 noRestrict，解除「受限」
wl_unrestrict_in() {
  pk_set $1 $2 noRestrict
  cmd appops get --user $2 $1 RUN_ANY_IN_BACKGROUND </dev/null 2>/dev/null | grep -q ': ignore' &&
    cmd appops set --user $2 $1 RUN_ANY_IN_BACKGROUND allow </dev/null
}
wl_unrestrict() { for u in $(wl_pkg_users $1); do wl_unrestrict_in $1 $u; done; }

# 输出：w|包名|user/sys  r|包名|用户id（受限）  u|用户id|名称|flags  p|用户id|包名（非主空间的第三方应用）
#       k|包名|用户id|省电策略（只列非默认的）
cmd_wl_list() {
  wl=$(dumpsys deviceidle whitelist </dev/null)
  echo "$wl" | grep '^system,' | cut -d, -f2 | sort -u | sed 's/^/w|/; s/$/|sys/'
  cur=$(echo "$wl" | grep '^user,' | cut -d, -f2)
  echo "$cur" | grep . | sed 's/^/w|/; s/$/|user/'
  # 主空间只输出白名单里的第三方应用（系统应用不会被冻结，不需要同步省电策略）
  pm list packages --user 0 -3 </dev/null | sed 's/^package://' | grep -xF "$cur" | sed 's/^/p|0|/'
  # 在系统设置里改过的也同步进开机重加列表，避免关掉的又被加回来、加上的开机丢失
  cur=$(echo "$cur" | grep . | tr '\n' ',' | sed 's/,$//')
  [ "$cur" != "$(conf_get wl_pkgs "")" ] && conf_set wl_pkgs "$cur"
  # query-op 不区分用户，只当候选，再逐个空间 get 确认
  rs=$(cmd appops query-op RUN_ANY_IN_BACKGROUND ignore </dev/null 2>/dev/null | grep -E '^[A-Za-z][A-Za-z0-9_.]*$')
  wl_users | while IFS='|' read -r id name flags; do
    echo "u|$id|$name|$flags"
    for p in $rs; do
      cmd appops get --user $id $p RUN_ANY_IN_BACKGROUND </dev/null 2>/dev/null | grep -q ': ignore' && echo "r|$p|$id"
    done
    [ "$id" = 0 ] || pm list packages --user $id -3 </dev/null | sed "s/^package:/p|$id|/"
  done
  content query --uri $PK_URI --projection pkgName:userId:bgControl </dev/null 2>/dev/null |
    sed -n 's/.*pkgName=\([^,]*\), userId=\([0-9]*\), bgControl=\([A-Za-z]*\).*/k|\1|\2|\3/p' | grep -v '|miuiAuto$'
}

# 把白名单里的第三方应用在所有空间补设成「无限制」（在系统设置里加的、或旧版只加了白名单的）
cmd_wl_sync() {
  pkgs=$(dumpsys deviceidle whitelist </dev/null | grep '^user,' | cut -d, -f2)
  for u in $(wl_users | cut -d'|' -f1); do
    inst=$(pm list packages --user $u -3 </dev/null)
    for p in $pkgs; do echo "$inst" | grep -qx "package:$p" && wl_unrestrict_in $p $u; done
  done
  echo "OK|wl|已把 $(echo "$pkgs" | grep -c .) 个应用的省电策略设为无限制"
}

cmd_wl_set() {
  valid_pkg "$1" || { echo "ERR|$1|包名不合法"; return; }
  list=$(conf_get wl_pkgs "")
  list=$(echo "$list" | tr ',' '\n' | grep -vx "$1" | grep . | tr '\n' ',' | sed 's/,$//')
  if [ "$2" = 1 ]; then
    dumpsys deviceidle whitelist +$1 >/dev/null </dev/null
    list=${list:+$list,}$1
    wl_unrestrict $1
  else
    dumpsys deviceidle whitelist -$1 >/dev/null </dev/null
    # 只还原本来是「无限制」的，用户手动设的「限制后台」不动
    for u in $(wl_pkg_users $1); do [ "$(pk_get $1 $u)" = noRestrict ] && pk_set $1 $u miuiAuto; done
  fi
  conf_set wl_pkgs "$list"
  echo "OK|$1|$([ "$2" = 1 ] && echo 已加入白名单，省电策略设为无限制 || echo 已移出白名单，省电策略恢复智能限制)"
}

# ---------- 应用编译优化 ----------
# 日志格式：第一行「开始|任务|总数」，之后每个应用一行「序号/总数|包名|结果」，最后一行「完成|时间」
DEXOPT_LOG=$DATA/dexopt.log
DEXOPT_QUEUE=$DATA/dexopt.queue

dexopt_running() { pgrep -f "$MODDIR/adclean[.]sh dexopt-run [a-z-]*$" >/dev/null; }

# dexopt <speed-profile|speed> 包名...   或   dexopt bg
cmd_dexopt() (
  # fd 6 stays open in the detached runner, so there is no check/start race.
  # Only this caller opts in to forwarding the lock through the detached launcher.
  exec 6>>"$DATA/dexopt.flock" || { echo "ERR|dexopt|无法创建编译锁"; return 1; }
  flock -xn 6 6>&6 || { echo "ERR|dexopt|已有编译任务在运行"; return 1; }
  mode=$1; shift
  case "$mode" in
    bg) : > $DEXOPT_QUEUE ;;
    speed-profile|speed)
      [ $# -gt 0 ] || { echo "ERR|dexopt|没有选择应用"; return; }
      for p in "$@"; do valid_pkg "$p" || { echo "ERR|dexopt|包名不合法 $p"; return; }; done
      echo "$*" | tr ' ' '\n' > $DEXOPT_QUEUE ;;
    *) echo "ERR|dexopt|模式不合法"; return ;;
  esac
  echo "开始|$mode|$(grep -c . $DEXOPT_QUEUE)" > $DEXOPT_LOG
  if ! HYPERCRAFT_DETACHED_LOCK_FD6=1 start_detached sh "$MODDIR/adclean.sh" dexopt-run "$mode" 6>&6; then
    echo "ERR|dexopt|后台编译启动失败，请重试"; return 1
  fi
  echo "OK|dexopt|已在后台开始"
)

# cmd 会把输出 fd 交给 system_server，SELinux 不允许它写 /data/adb 下的文件，所以用 $( ) 管道接收
cmd_dexopt_run() {
  # Inherit and verify the lock acquired before publishing the shared queue/log.
  flock -xn 6 6>&6 || return 1
  if [ "$1" = bg ]; then
    r=$(cmd package bg-dexopt-job 2>&1 </dev/null | tail -n 1)
    echo "1/1|后台优化任务|${r:-完成}" >> $DEXOPT_LOG
  else
    total=$(grep -c . $DEXOPT_QUEUE); i=0
    while read -r p; do
      [ -n "$p" ] || continue
      i=$((i+1))
      echo "$p" > $DATA/dexopt.cur   # 正在编译的应用，App 圆环中间显示它
      r=$(cmd package compile -m $1 -f $p 2>&1 </dev/null | tail -n 1)
      echo "$i/$total|$p|$r" >> $DEXOPT_LOG
    done < $DEXOPT_QUEUE
  fi
  rm -f $DATA/dexopt.cur
  echo "完成|$(date '+%m-%d %H:%M')" >> $DEXOPT_LOG
  log "dexopt $1 完成"
}

# 运行中|已完成数|总数|当前/最后的包名|成功数|失败数|完成时间|任务
cmd_dexopt_status() {
  [ -f $DEXOPT_LOG ] || { echo "0|0|0||0|0||"; return; }
  r=0; dexopt_running && r=1
  head=$(head -n 1 $DEXOPT_LOG)
  last=$(grep '^[0-9]*/' $DEXOPT_LOG | tail -n 1)
  ok=$(grep '^[0-9]*/' $DEXOPT_LOG | grep -c '|Success$')
  fail=$(grep '^[0-9]*/' $DEXOPT_LOG | grep -vc '|Success$')
  [ "$(echo "$head" | cut -d'|' -f2)" = bg ] && { ok=$(grep -c '^1/1' $DEXOPT_LOG); fail=0; }
  cur=$(echo "$last" | cut -d'|' -f2)
  [ $r = 1 ] && [ -s $DATA/dexopt.cur ] && read -r cur < $DATA/dexopt.cur
  echo "$r|$(echo "$last" | cut -d/ -f1)|$(echo "$head" | cut -d'|' -f3)|$cur|$ok|$fail|$(grep '^完成' $DEXOPT_LOG | cut -d'|' -f2)|$(echo "$head" | cut -d'|' -f2)"
}

# 最近完成的几个：包名|结果
cmd_dexopt_recent() { grep '^[0-9]*/' $DEXOPT_LOG 2>/dev/null | tail -n ${1:-6} | cut -d'|' -f2,3; }

# ---------- 集成的独立模块：机型修改 / 电池循环伪装 / 远程进程守卫 ----------
# 旧的独立模块还启用时，对应功能让给它，避免两份同时改同一处；App 里提供一键停用旧模块。
LEGACY_IDS="changephone fake_battery_cycle baize_remote_cpu_guard"
DM=$MODDIR/devmodel/control.sh
CYCLE=$MODDIR/battery/cycle.sh
CHG=$MODDIR/battery/charge.sh

legacy_name() {
  case "$1" in
    changephone) echo 动态机型修改 ;;
    fake_battery_cycle) echo 电池循环次数伪装 ;;
    baize_remote_cpu_guard) echo 远程进程守卫 ;;
  esac
}

legacy_active() { [ -d /data/adb/modules/$1 ] && [ ! -f /data/adb/modules/$1/disable ] && [ ! -f /data/adb/modules/$1/remove ]; }

# 旧模块ID|名称|状态(active/disabled/none)
cmd_legacy_status() {
  for id in $LEGACY_IDS; do
    st=none; [ -d /data/adb/modules/$id ] && st=disabled; legacy_active $id && st=active
    echo "$id|$(legacy_name $id)|$st"
  done
}

cmd_legacy_disable() {
  case " $LEGACY_IDS " in *" $1 "*) ;; *) echo "ERR|$1|不是可接管的模块"; return ;; esac
  [ -d /data/adb/modules/$1 ] || { echo "ERR|$1|没有安装这个模块"; return; }
  touch /data/adb/modules/$1/disable
  log "停用旧模块 $1"
  echo "OK|$1|已停用「$(legacy_name $1)」，重启后由本模块接管"
}

# 把旧模块的配置和记录搬过来（只在本模块还没有对应数据时做，可重复执行）
cmd_migrate() {
  L=/data/adb/modules/changephone/state; D=$DATA/devmodel
  if [ -f $L/config ] && [ ! -f $D/config ]; then
    mkdir -p $D
    for f in config original.props stock.props runtime-stock.props restore-reboot-required rollback.props; do
      [ -f $L/$f ] && cp -af $L/$f $D/
    done
    log "迁移：动态机型修改的配置与原机基线"
  fi
  L=/data/adb/modules/fake_battery_cycle; D=$DATA/battery
  if [ -f $L/config.txt ] && [ ! -f $D/config.txt ]; then
    mkdir -p $D
    for f in config.txt real_cycle.txt real_cycle_time.txt; do [ -f $L/$f ] && cp -af $L/$f $D/; done
    log "迁移：电池循环伪装的配置与真实循环次数"
  fi
  L=/data/adb/modules/baize_remote_cpu_guard; D=$DATA/guard
  if [ -d $L ] && [ ! -d $D ]; then
    mkdir -p $D
    for f in kill_count last_cpu last_time; do [ -f $L/state/$f ] && cp -af $L/state/$f $D/; done
    t=$(grep -m1 '^CPU_THRESHOLD=' $L/config.conf 2>/dev/null | cut -d= -f2); [ -n "$t" ] && conf_set guard_threshold $t
    i=$(grep -m1 '^CHECK_INTERVAL=' $L/config.conf 2>/dev/null | cut -d= -f2); [ -n "$i" ] && conf_set guard_interval $i
    conf_set guard_enable 1
    log "迁移：远程进程守卫的设置与查杀记录"
  fi
  return 0
}

# 机型修改：转给内置的 control.sh；旧模块启用时只允许查询
cmd_dm() {
  case "$1" in
    status)
      sh $DM status
      # 固件原始机型（control.sh 的基线校验不通过时也能显示原机是什么）
      for k in marketname model; do
        v=$(grep -m1 "^ro.product.$k|1|" $DATA/devmodel/stock.props 2>/dev/null | cut -d'|' -f3)
        echo "stock_$k=$( [ -n "$v" ] && echo "$v" | base64 -d 2>/dev/null )"
      done ;;
    list|summary|restart-status) sh $DM "$@" ;;
    *)
      legacy_active changephone && { echo "ERR|devmodel|旧模块「动态机型修改」仍在启用，请先停用"; return; }
      sh $DM "$@"; r=$?
      [ $r = 0 ] && log "机型修改：$1" || log "机型修改：$1 失败 code=$r"
      return $r ;;
  esac
}

# 电池循环伪装：转给内置的 cycle.sh
cmd_cycle() {
  case "$1" in
    status|refresh) sh $CYCLE "$@" ;;
    *)
      legacy_active fake_battery_cycle && { echo "FAIL: 旧模块「电池循环次数伪装」仍在启用，请先停用"; return; }
      if sh $CYCLE "$@"; then :
      else
        cycle_failure=$?
        grep -qE '^MODE=(map|fixed)$' $DATA/battery/config.txt 2>/dev/null || cycle_watch_stop
        return "$cycle_failure"
      fi
      # 开启伪装后才保留挂载守护；关闭时立即停止，不留下空转进程。
      if grep -qE '^MODE=(map|fixed)$' $DATA/battery/config.txt 2>/dev/null; then
        if [ -z "$(cycle_watch_pid)" ]; then
          start_detached sh $MODDIR/adclean.sh cycle-watch
        fi
      else
        cycle_watch_stop
      fi ;;
  esac
}

cycle_watch_pid() { pgrep -f "$MODDIR/adclean[.]sh cycle-watch$" | head -n 1; }
cycle_watch_stop() { for p in $(cycle_watch_pid); do kill "$p" 2>/dev/null; done; return 0; }

# 每 5 分钟确认一次伪装挂载还在（读空管道等待，不占 CPU）
cmd_cycle_watch() {
  daemon_lock "$DATA/cycle.daemon.flock" || return 0
  fifo=$DATA/cycle.fifo; rm -f $fifo; mkfifo $fifo; exec 3<>$fifo; rm -f $fifo
  while grep -qE '^MODE=(map|fixed)$' $DATA/battery/config.txt 2>/dev/null; do
    read -t 300 -u3 _ 2>/dev/null
    grep -qE '^MODE=(map|fixed)$' $DATA/battery/config.txt 2>/dev/null || break
    legacy_active fake_battery_cycle && continue
    sh $CYCLE watch >/dev/null 2>&1
  done
}

# ---------- 远程进程守卫（com.miui.securitycenter.remote 失控时结束它） ----------
# 只在进程变化时用 pidof 找一次 PID；之后用内建 read 读 /proc/<pid>/stat，几乎不耗电。
GUARD_DIR=$DATA/guard
GUARD_TARGET=${GUARD_TARGET:-com.miui.securitycenter.remote}

guard_pid() { pgrep -f "$MODDIR/adclean[.]sh guard-daemon$" | head -n 1; }

# 读 /proc/<pid>/stat，结果放进 g_t（CPU 总时间）和 g_s（启动时间）；全程内建命令，不起新进程
guard_sample() {
  read -r line 2>/dev/null < /proc/$1/stat || return 1
  set -- ${line##*) }
  [ $# -ge 20 ] || return 1
  g_t=$(( ${12} + ${13} )); g_s=${20}
}

# 把守卫相关配置读进 c_guard_* 变量（内建 read，不起新进程）
guard_conf() {
  c_guard_enable=0; c_guard_threshold=100; c_guard_interval=5
  [ -f "$CONF" ] || return 0
  while IFS='=' read -r k v; do
    case "$k" in guard_enable|guard_threshold|guard_interval) eval "c_$k=\$v" ;; esac
  done < $CONF
}

guard_record() {
  mkdir -p $GUARD_DIR
  c=0; read -r c 2>/dev/null < $GUARD_DIR/kill_count; c=$(( ${c:-0} + 1 ))
  echo $c > $GUARD_DIR/kill_count
  echo $1 > $GUARD_DIR/last_cpu
  date '+%m-%d %H:%M:%S' > $GUARD_DIR/last_time
  echo "$(date '+%m-%d %H:%M:%S') 结束 PID=$2 CPU=$1%" >> $GUARD_DIR/events.log
  if [ "$(stat -c %s "$GUARD_DIR/events.log" 2>/dev/null || echo 0)" -gt 262144 ]; then
    tail -n 500 "$GUARD_DIR/events.log" > "$GUARD_DIR/events.tmp"
    mv -f "$GUARD_DIR/events.tmp" "$GUARD_DIR/events.log"
  fi
  log "进程守卫：结束 $GUARD_TARGET PID=$2 CPU=$1%"
}

cmd_guard_daemon() {
  mkdir -p $GUARD_DIR
  daemon_lock "$GUARD_DIR/daemon.flock" || return 0
  fifo=$GUARD_DIR/wait.fifo; rm -f $fifo; mkfifo $fifo; exec 3<>$fifo; rm -f $fifo
  hz=$(getconf CLK_TCK 2>/dev/null); [ "${hz:-0}" -gt 0 ] 2>/dev/null || hz=100
  pid=""
  guard_conf
  while [ "$c_guard_enable" = 1 ]; do
    intv=$c_guard_interval
    # 只在进程变化（或刚启动）时用 pidof 找 PID
    [ -n "$pid" ] && [ -r /proc/$pid/stat ] || { set -- $(pidof $GUARD_TARGET 2>/dev/null); pid=$1; }
    if [ -z "$pid" ]; then read -t $intv -u3 _ 2>/dev/null; guard_conf; continue; fi
    guard_sample $pid || { pid=""; continue; }
    t0=$g_t; s0=$g_s
    read -t $intv -u3 _ 2>/dev/null
    guard_sample $pid || { pid=""; guard_conf; continue; }
    if [ "$g_s" = "$s0" ]; then
      cpu=$(( (g_t - t0) * 100 / (hz * intv) ))
      if [ $cpu -gt $c_guard_threshold ]; then
        kill -TERM $pid 2>/dev/null
        read -t 1 -u3 _ 2>/dev/null
        guard_sample $pid && [ "$g_s" = "$s0" ] && kill -KILL $pid 2>/dev/null
        guard_record $cpu $pid
        pid=""
      fi
    else
      pid=""
    fi
    guard_conf
  done
}

guard_start() {
  [ "$(conf_get guard_enable 0)" = 1 ] || return 0
  legacy_active baize_remote_cpu_guard && return 0
  [ -n "$(guard_pid)" ] && return 0
  start_detached sh $MODDIR/adclean.sh guard-daemon
}

guard_stop() {
  for p in $(pgrep -f "$MODDIR/adclean[.]sh guard-daemon$"); do kill $p; done
  return 0
}

# 开关|运行中|阈值|间隔|累计次数|上次CPU|上次时间|旧模块启用
cmd_guard_status() {
  r=0; [ -n "$(guard_pid)" ] && r=1
  l=0; legacy_active baize_remote_cpu_guard && l=1
  c=0; read -r c 2>/dev/null < $GUARD_DIR/kill_count
  lc=""; read -r lc 2>/dev/null < $GUARD_DIR/last_cpu
  lt=""; read -r lt 2>/dev/null < $GUARD_DIR/last_time
  echo "$(conf_get guard_enable 0)|$r|$(conf_get guard_threshold 100)|$(conf_get guard_interval 5)|${c:-0}|$lc|$lt|$l"
}

cmd_guard_set() {
  case "$1" in
    enable)
      case "$2" in 0|1) ;; *) echo "ERR|guard|取值只能是 0/1"; return ;; esac
      conf_set guard_enable $2
      if [ $2 = 1 ]; then
        legacy_active baize_remote_cpu_guard && { echo "ERR|guard|旧模块「远程进程守卫」仍在启用，请先停用"; return; }
        guard_stop; guard_start; echo "OK|guard|已开启守卫"
      else
        guard_stop; echo "OK|guard|已关闭守卫"
      fi ;;
    threshold|interval)
      echo "$2" | grep -qE '^[0-9]+$' || { echo "ERR|guard|数值不合法"; return; }
      [ $1 = interval ] && [ $2 -lt 2 ] && { echo "ERR|guard|间隔不能小于 2 秒"; return; }
      conf_set guard_$1 $2
      guard_stop; guard_start
      echo "OK|guard|已保存" ;;
    *) echo "ERR|guard|未知设置" ;;
  esac
  log "进程守卫：$1=$2"
}

# ---------- WLAN 调试日志清理 ----------
# /data/vendor/wlan_logs 由 vendor 的 init 服务写入，开关都是属性：
#   vendor.cnss_diag    固件/驱动日志 cnss_fw_logs_*、host_driver_logs_*   persist.vendor.cnss_diag.enable
#   vendor.tcpdump      tcpdump.pcap*（3×5MB 循环）                        persist.vendor.tcpdump.enable
#   vendor.sniffer(.circular)  sniffer.pcap*（3×300MB 循环，只能 ctl.start）
#   vendor.diag.tcpdump /data/vendor/modem/diag_logs/tcpdump.pcap*（3×500MB）persist.logd.diag.tcpdump
# 零售机默认都不开，云控可能在运行时打开。正在写的文件删掉不释放空间，所以先停服务再删。
# cnss-daemon 常驻打开的 *_current.bin 是驱动内存日志（最多 3×20MB），只删它轮转出来的旧文件。
WLOG_DIR=$DATA/wlanlog
WLOG_EVENTS=$WLOG_DIR/events.log   # 时间|类型|释放KB|文件数|原因|说明
WLOG_SVCS="vendor.cnss_diag vendor.tcpdump vendor.sniffer vendor.sniffer.circular vendor.diag.tcpdump"
WLOG_PROPS="persist.vendor.cnss_diag.enable persist.vendor.tcpdump.enable persist.logd.diag.tcpdump"
WLOG_OPEN_RE='/data/vendor/(wlan_logs|modem/diag_logs)/[^ ]+'

wlog_val() { v=$(cat $WLOG_DIR/$1 2>/dev/null); echo ${v:-$2}; }

wlog_pid() { pgrep -f "$MODDIR/adclean[.]sh wlog-daemon$" | head -n 1; }

wlog_conf() {
  c_wlog_enable=0; c_wlog_interval=6; c_wlog_threshold=100; c_wlog_block=0; c_wlog_keep=100
  while IFS='=' read -r k v; do
    case "$k" in wlog_enable|wlog_interval|wlog_threshold|wlog_block|wlog_keep) eval "c_$k=\$v" ;; esac
  done < $CONF
}

# 可清理的文件：wlan_logs 下全部文件 + modem 目录里的 tcpdump 抓包
wlog_files() {
  for f in /data/vendor/wlan_logs/* /data/vendor/modem/diag_logs/tcpdump.pcap*; do [ -f "$f" ] && echo "$f"; done
}

# 当前被进程打开的文件（一次扫描约 0.2 秒）
wlog_open() { ls -l /proc/[0-9]*/fd 2>/dev/null | grep -oE "$WLOG_OPEN_RE" | sort -u; }

wlog_is_capture() { case "${1##*/}" in cnss_fw_logs*|host_driver_logs*|*.pcap*|*.txt|*.log) return 0 ;; esac; return 1; }

# 合计大小（KB）
wlog_size() {
  set -- $(wlog_files)
  [ $# -gt 0 ] || { echo 0; return; }
  stat -c %s "$@" 2>/dev/null | awk '{ s += $1 } END { printf "%d\n", s / 1024 }'
}

wlog_event() {
  mkdir -p $WLOG_DIR
  echo "$(date '+%m-%d %H:%M:%S')|$1|$2|$3|$4|$5" >> $WLOG_EVENTS
  keep=$(conf_get wlog_keep 100)
  [ "$(wc -l < $WLOG_EVENTS)" -gt "$keep" ] && { tail -n "$keep" $WLOG_EVENTS > $WLOG_EVENTS.tmp; mv $WLOG_EVENTS.tmp $WLOG_EVENTS; }
  return 0
}

# 正在运行的抓包：init 服务 + 直接拉起的进程
wlog_capturing() {
  r=""
  for s in $WLOG_SVCS; do [ "$(getprop init.svc.$s)" = running ] && r="$r $s"; done
  for b in cnss_diag tcpdump; do [ -n "$(pidof $b)" ] && case "$r" in *"$b"*) ;; *) r="$r $b" ;; esac; done
  echo $r
}

# 停掉调试抓包：属性改回 false（触发 init 的 stop），再逐个 stop，最后结束漏网的进程
wlog_block_check() {
  hit=$(wlog_capturing)
  props=""
  for p in $WLOG_PROPS; do [ "$(getprop $p)" = true ] && { setprop $p false; props="$props $p"; }; done
  [ -n "$hit$props" ] || return 0
  for s in $WLOG_SVCS; do [ "$(getprop init.svc.$s)" = running ] && stop $s; done
  sleep 1
  for b in cnss_diag tcpdump; do for p in $(pidof $b); do kill $p 2>/dev/null; done; done
  msg="${hit:+停止 $hit}${hit:+${props:+；}}${props:+属性改回 false:$props}"
  wlog_event block 0 0 拦截 "$msg"
  log "WLAN 日志：拦截调试抓包，$msg"
}

# 清理：未被打开的文件直接删；正在写的抓包文件截断；cnss-daemon 常驻的文件跳过
wlog_clean() {
  reason=$1
  open=$(wlog_open)
  n=0; kb=0; skip=0
  for f in $(wlog_files); do
    s=$(stat -c %s "$f" 2>/dev/null || echo 0)
    [ "$s" -gt 0 ] || continue
    if echo "$open" | grep -qxF "$f"; then
      if wlog_is_capture "$f"; then : > "$f"; else skip=$((skip + 1)); continue; fi
    else
      rm -f "$f" || continue
    fi
    n=$((n + 1)); kb=$((kb + s / 1024))
  done
  mkdir -p $WLOG_DIR
  date +%s > $WLOG_DIR/last_ts
  echo $(( $(wlog_val total_kb 0) + kb )) > $WLOG_DIR/total_kb
  echo $(( $(wlog_val count 0) + 1 )) > $WLOG_DIR/count
  date '+%m-%d %H:%M:%S' > $WLOG_DIR/last_time
  wlog_event clean $kb $n "$reason" "跳过正在使用的 $skip 个"
  log "WLAN 日志：$reason清理，删除 $n 个文件，释放 $((kb / 1024)) MB，跳过正在使用的 $skip 个"
  r_n=$n; r_kb=$kb
}

cmd_wlog_daemon() {
  mkdir -p $WLOG_DIR
  daemon_lock "$WLOG_DIR/daemon.flock" || return 0
  fifo=$WLOG_DIR/wait.fifo; rm -f $fifo; mkfifo $fifo; exec 3<>$fifo; rm -f $fifo
  wlog_conf
  last=$(wlog_val last_ts 0)
  [ "$last" -gt 0 ] 2>/dev/null || { last=$(date +%s); echo $last > $WLOG_DIR/last_ts; }
  tick=0
  log "WLAN 日志：守护启动（定时清理=$c_wlog_enable 每 ${c_wlog_interval} 小时，超过 ${c_wlog_threshold}MB 清理，拦截抓包=$c_wlog_block）"
  while [ "$c_wlog_enable" = 1 ] || [ "$c_wlog_block" = 1 ]; do
    [ "$c_wlog_block" = 1 ] && wlog_block_check
    if [ "$c_wlog_enable" = 1 ]; then
      now=$(date +%s)
      last=$(wlog_val last_ts $now)
      if [ $((now - last)) -ge $((c_wlog_interval * 3600)) ]; then
        wlog_clean 定时
      elif [ "$c_wlog_threshold" -gt 0 ] && [ $((tick % 5)) = 0 ] && [ "$(wlog_size)" -gt $((c_wlog_threshold * 1024)) ]; then
        wlog_clean 超过阈值
      fi
    fi
    tick=$((tick + 1))
    read -t 60 -u3 _ 2>/dev/null
    wlog_conf
  done
  log "WLAN 日志：守护退出"
}

wlog_start() {
  [ "$(conf_get wlog_enable 0)" = 1 ] || [ "$(conf_get wlog_block 0)" = 1 ] || return 0
  [ -n "$(wlog_pid)" ] && return 0
  start_detached sh $MODDIR/adclean.sh wlog-daemon
}

wlog_stop() {
  for p in $(pgrep -f "$MODDIR/adclean[.]sh wlog-daemon$"); do kill $p; done
  return 0
}

# 定时开关|运行中|间隔小时|阈值MB|拦截开关|保留条数|累计次数|累计释放KB|上次时间|当前占用KB|正在抓包
cmd_wlog_status() {
  r=0; [ -n "$(wlog_pid)" ] && r=1
  cap=$(wlog_capturing | tr ' ' ',')
  echo "$(conf_get wlog_enable 0)|$r|$(conf_get wlog_interval 6)|$(conf_get wlog_threshold 100)|$(conf_get wlog_block 0)|$(conf_get wlog_keep 100)|$(wlog_val count 0)|$(wlog_val total_kb 0)|$(wlog_val last_time)|$(wlog_size)|$cap"
}

# 文件名|大小KB|修改时间|正在使用(0/1)
cmd_wlog_files() {
  open=$(wlog_open)
  for f in $(wlog_files); do
    o=0; echo "$open" | grep -qxF "$f" && o=1
    echo "$f|$(( $(stat -c %s "$f" 2>/dev/null || echo 0) / 1024 ))|$(date -r "$f" '+%m-%d %H:%M' 2>/dev/null)|$o"
  done
}

cmd_wlog_set() {
  case "$1" in
    enable|block)
      case "$2" in 0|1) ;; *) echo "ERR|wlog|取值只能是 0/1"; return ;; esac
      conf_set wlog_$1 $2
      wlog_stop
      [ $1 = block ] && [ $2 = 1 ] && wlog_block_check
      wlog_start
      if [ $1 = enable ]; then [ $2 = 1 ] && echo "OK|wlog|已开启定时清理" || echo "OK|wlog|已关闭定时清理"
      else [ $2 = 1 ] && echo "OK|wlog|已开启拦截调试抓包" || echo "OK|wlog|已关闭拦截"; fi ;;
    interval)
      case "$2" in 1|3|6|12|24) ;; *) echo "ERR|wlog|间隔只能是 1/3/6/12/24 小时"; return ;; esac
      conf_set wlog_interval $2; wlog_stop; wlog_start; echo "OK|wlog|已保存" ;;
    threshold)
      case "$2" in 0|50|100|200|500) ;; *) echo "ERR|wlog|阈值不合法"; return ;; esac
      conf_set wlog_threshold $2; wlog_stop; wlog_start; echo "OK|wlog|已保存" ;;
    keep)
      case "$2" in 50|100|200|500) ;; *) echo "ERR|wlog|保留条数不合法"; return ;; esac
      conf_set wlog_keep $2
      [ -f $WLOG_EVENTS ] && { tail -n $2 $WLOG_EVENTS > $WLOG_EVENTS.tmp; mv $WLOG_EVENTS.tmp $WLOG_EVENTS; }
      echo "OK|wlog|已保存" ;;
    *) echo "ERR|wlog|未知设置"; return ;;
  esac
  log "WLAN 日志：设置 $1=$2"
}

cmd_wlog_clean_now() {
  [ "$(conf_get wlog_block 0)" = 1 ] && wlog_block_check
  wlog_clean 手动
  echo "OK|wlog|删除 $r_n 个文件，释放 $(awk "BEGIN { printf \"%.1f\", $r_kb / 1024 }") MB"
}

cmd_wlog_records_clear() {
  rm -f $WLOG_EVENTS $WLOG_DIR/count $WLOG_DIR/total_kb $WLOG_DIR/last_time
  log "WLAN 日志：清空清理记录"
  echo "OK|wlog|已清空清理记录"
}

# ---------- 竖屏方向锁定 ----------
# 关闭自动旋转（accelerometer_rotation=0）并锁定竖屏（user_rotation=0）。
# 控制中心的「自动旋转」、部分应用会把它改回去，守护按间隔检查，被改就强制改回。
ROT_DIR=$DATA/rot

rot_pid() { pgrep -f "$MODDIR/adclean[.]sh rot-daemon$" | head -n 1; }
rot_val() { v=$(cat $ROT_DIR/$1 2>/dev/null); echo ${v:-$2}; }
# 输出「自动旋转 方向」两个值，用空格分隔；mksh 裁剪模式中的字面量竖线必须转义。
rot_state() { echo "$(settings get system accelerometer_rotation </dev/null) $(settings get system user_rotation </dev/null)"; }

rot_event() {
  mkdir -p $ROT_DIR
  echo "$(date '+%m-%d %H:%M:%S')|$1|$2" >> $ROT_DIR/events.log
  [ "$(wc -l < $ROT_DIR/events.log)" -gt 100 ] && { tail -n 100 $ROT_DIR/events.log > $ROT_DIR/events.tmp; mv $ROT_DIR/events.tmp $ROT_DIR/events.log; }
  return 0
}

# 没锁定竖屏就改回，返回 0 表示做了修正
rot_fix() {
  why=$1
  set -- $(rot_state)
  [ "$1 $2" = "0 0" ] && return 1
  a=$1; u=$2
  settings put system accelerometer_rotation 0 </dev/null
  settings put system user_rotation 0 </dev/null
  mkdir -p $ROT_DIR
  echo $(( $(rot_val count 0) + 1 )) > $ROT_DIR/count
  date '+%m-%d %H:%M:%S' > $ROT_DIR/last_time
  rot_event "$why" "自动旋转=$a，方向=$u，已改回竖屏锁定"
  log "竖屏锁定：${why}检测到未锁定（自动旋转=$a，方向=$u），已强制开启"
  return 0
}

cmd_rot_daemon() {
  mkdir -p $ROT_DIR
  daemon_lock "$ROT_DIR/daemon.flock" || return 0
  fifo=$ROT_DIR/wait.fifo; rm -f $fifo; mkfifo $fifo; exec 3<>$fifo; rm -f $fifo
  log "竖屏锁定：守护启动（每 $(conf_get rot_interval 30) 秒检查）"
  while [ "$(conf_get rot_enable 0)" = 1 ]; do
    rot_fix 定时
    read -t "$(conf_get rot_interval 30)" -u3 _ 2>/dev/null
  done
  log "竖屏锁定：守护退出"
}

rot_start() {
  [ "$(conf_get rot_enable 0)" = 1 ] || return 0
  [ -n "$(rot_pid)" ] && return 0
  start_detached sh $MODDIR/adclean.sh rot-daemon
}

rot_stop() {
  for p in $(pgrep -f "$MODDIR/adclean[.]sh rot-daemon$"); do kill $p; done
  return 0
}

# 开关|运行中|间隔秒|累计强制次数|上次时间|自动旋转|方向
cmd_rot_status() {
  r=0; [ -n "$(rot_pid)" ] && r=1
  set -- $(rot_state)
  echo "$(conf_get rot_enable 0)|$r|$(conf_get rot_interval 30)|$(rot_val count 0)|$(rot_val last_time)|$1|$2"
}

cmd_rot_set() {
  case "$1" in
    enable)
      case "$2" in 0|1) ;; *) echo "ERR|rot|取值只能是 0/1"; return ;; esac
      conf_set rot_enable $2
      rot_stop
      if [ $2 = 1 ]; then
        rot_fix 开启时
        rot_start
        echo "OK|rot|已开启竖屏锁定"
      else
        echo "OK|rot|已关闭，自动旋转保持当前状态"
      fi ;;
    interval)
      case "$2" in 10|30|60|300) ;; *) echo "ERR|rot|间隔只能是 10/30/60/300 秒"; return ;; esac
      conf_set rot_interval $2; rot_stop; rot_start; echo "OK|rot|已保存" ;;
    *) echo "ERR|rot|未知设置"; return ;;
  esac
  log "竖屏锁定：设置 $1=$2"
}

cmd_rot_log_clear() {
  rm -f $ROT_DIR/events.log $ROT_DIR/count $ROT_DIR/last_time
  log "竖屏锁定：清空记录"
  echo "OK|rot|已清空记录"
}

# ---------- 无线调试诊断（只观察，不保活或重新开启） ----------
ADB_DIAG=$DATA/adb-diagnostics

adb_diag_event() (
  umask 077
  mkdir -p "$ADB_DIAG"
  printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >> "$ADB_DIAG/events.log"
  if [ "$(stat -c %s "$ADB_DIAG/events.log" 2>/dev/null || echo 0)" -gt 262144 ]; then
    tail -n 500 "$ADB_DIAG/events.log" > "$ADB_DIAG/events.$$"
    mv "$ADB_DIAG/events.$$" "$ADB_DIAG/events.log"
  fi
)

adb_diag_connections() (
  port=$(getprop service.adb.tls.port)
  case "$port" in ''|*[!0-9]*) echo unknown; return ;; esac
  [ "$port" -gt 0 ] && [ "$port" -le 65535 ] || { echo unknown; return; }
  hex=$(printf '%04X' "$port")
  sockets=$(cat /proc/net/tcp /proc/net/tcp6 2>/dev/null) || { echo unknown; return; }
  # TCP established is transport evidence, not proof of ADB authorization.
  printf '%s\n' "$sockets" | awk -v port="$hex" '$4=="01" {split($2,a,":"); if (toupper(a[2])==port) n++} END {print n+0}'
)

# 守护每 30 秒采样一次：每个 settings get 要起 sh + cmd 两个进程，所以一次列出全局设置再挑出需要的项
adb_diag_snapshot() {
  cmd settings list global </dev/null 2>/dev/null | awk '
    { k = $0; sub(/=.*/, "", k); v[k] = substr($0, length(k) + 2) }
    function g(k) { return (k in v) ? v[k] : "null" }
    END {
      print "wifi|" g("adb_wifi_enabled"); print "usb|" g("adb_enabled")
      print "developer|" g("development_settings_enabled"); print "timeout|" g("adb_allowed_connection_time")
    }'
  echo "ip|$(ip -4 -o addr show wlan0 2>/dev/null | awk '{ split($4, a, "/"); print a[1]; exit }')"
  echo "port|$(getprop service.adb.tls.port)"
  echo "tcp_connections|$(adb_diag_connections)"
  echo "adbd|$(getprop init.svc.adbd)"
  echo "pid|$(pidof adbd)"
  b=""; read -r b 2>/dev/null < /proc/sys/kernel/random/boot_id
  echo "boot|$b"
}

adb_diag_pid() { pgrep -f "$MODDIR/adclean[.]sh adb-diag-daemon$"; }

adb_diag_start() {
  [ "$(conf_get adb_diag_enable 0)" = 1 ] || return 0
  [ -n "$(adb_diag_pid)" ] && return 0
  start_detached sh "$MODDIR/adclean.sh" adb-diag-daemon
}

cmd_adb_diag_daemon() {
  umask 077
  mkdir -p "$ADB_DIAG"
  daemon_lock "$ADB_DIAG/daemon.flock" || return 0
  trap 'exit 0' TERM INT
  fifo=$ADB_DIAG/wait.fifo; rm -f $fifo; mkfifo $fifo; exec 3<>$fifo; rm -f $fifo
  adb_diag_event '诊断启动：每 30 秒采样；不保证捕获采样间的短暂变化'
  previous=''
  while [ "$(conf_get adb_diag_enable 0)" = 1 ] && [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; do
    current=$(adb_diag_snapshot)
    if [ "$current" != "$previous" ]; then
      adb_diag_event "状态变化：$(printf '%s' "$current" | tr '\n' ' ')"
      printf '%s\n' "$current" > "$ADB_DIAG/state.tmp"
      mv "$ADB_DIAG/state.tmp" "$ADB_DIAG/state"
      previous=$current
    fi
    date '+%Y-%m-%d %H:%M:%S' > "$ADB_DIAG/checked"
    read -t 30 -u3 _ 2>/dev/null
  done
}

cmd_adb_diag_status() {
  adb_diag_start
  adb_diag_snapshot
  echo "enabled|$(conf_get adb_diag_enable 0)"
  echo "running|$([ -n "$(adb_diag_pid)" ] && echo 1 || echo 0)"
  echo "checked|$(cat "$ADB_DIAG/checked" 2>/dev/null)"
}

cmd_adb_diag_set() {
  case "$1" in 0|1) ;; *) echo 'ERR|adb|取值只能是 0/1'; return ;; esac
  conf_set adb_diag_enable "$1"
  if [ "$1" = 1 ]; then adb_diag_start
  else for p in $(adb_diag_pid); do kill "$p"; done; fi
  echo 'OK|adb|已保存诊断开关'
}

# ---------- 无线调试配对码 ----------
# 系统设置的配对码只发给设置自己，App 收不到。这里改用「二维码配对」接口：
# 通过具名 IAdbManager 方法生成/停止配对，避免 HyperOS 4 新增接口后事务号变化。密码由我们生成，
# 电脑上执行 adb pair IP:端口 配对码 即可。配对端口通过对比开启前后的监听端口得到。
listen_ports() {
  cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | awk '$4=="0A" { n=split($2,a,":"); print a[n] }' | sort -u
}

adb_pair_control() {
  pairing_apk=$(pm path io.github.dalong.hypercraft 2>/dev/null | sed -n 's/^package://p' | head -n 1)
  [ -n "$pairing_apk" ] && [ -r "$pairing_apk" ] || return 1
  CLASSPATH="$pairing_apk" /system/bin/app_process /system/bin com.dalong.pengpaitool.AdbPairingCommand "$@" </dev/null
}

adb_valid_port() {
  case "$1" in ''|*[!0-9]*) return 1 ;; esac
  [ "${#1}" -le 5 ] && [ "$1" -gt 0 ] && [ "$1" -le 65535 ]
}

adb_connection_port() (
  # Some HyperOS builds leave service.adb.tls.port empty. Query the named
  # framework API first; never substitute the short-lived pairing port.
  value=$(adb_pair_control port 2>/dev/null) || value=''
  if ! adb_valid_port "$value"; then value=$(getprop service.adb.tls.port); fi
  adb_valid_port "$value" || return 1
  printf '%s\n' "$value"
)

cmd_adb_connect_address() (
  [ "$(settings get global adb_wifi_enabled </dev/null)" = 1 ] || return 1
  address=$(ip -4 -o addr show wlan0 2>/dev/null | awk '{split($4,a,"/"); print a[1]; exit}')
  [ -n "$address" ] || return 1
  connection_port=$(adb_connection_port) || return 1
  printf '%s:%s\n' "$address" "$connection_port"
)

cmd_adb_pair_start() {
  adb_diag_start
  adb_diag_event 'App 请求启动配对（不记录配对码）'
  if [ "$(settings get global adb_wifi_enabled </dev/null)" != 1 ]; then
    settings put global adb_wifi_enabled 1 </dev/null
    sleep 2
  fi
  ip=$(ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | cut -d' ' -f2)
  [ -n "$ip" ] || { adb_diag_event '配对失败：没有 WLAN 地址'; echo "ERR|pair|没有连接 WLAN"; return; }
  adb_pair_control stop || { echo "ERR|pair|无法停止上一次配对"; return 1; }
  before=$DATA/ports.before; listen_ports > $before
  code=$(( (RANDOM * 32768 + RANDOM) % 900000 + 100000 ))
  adb_pair_control start "$code" || { rm -f "$before"; echo "ERR|pair|配对接口调用失败"; return 1; }
  port=""; i=0
  while [ -z "$port" ] && [ $i -lt 15 ]; do
    sleep 0.2; i=$((i+1))
    for h in $(listen_ports | comm -13 $before -); do port=$(( 16#$h )); done
  done
  rm -f $before
  [ -n "$port" ] || { adb_pair_control stop; adb_diag_event '配对失败：端口未就绪'; echo "ERR|pair|配对服务没有启动"; return; }
  cport=$(adb_connection_port) || cport=''
  log "无线调试：生成配对码，配对端口 $port"
  adb_diag_event "配对服务启动：端口 $port（不代表配对成功）"
  echo "OK|$code|$ip|$port|$cport"
}

cmd_adb_pair_stop() {
  adb_pair_control stop || { echo "ERR|pair|停止配对失败"; return 1; }
  adb_diag_event 'App 请求停止配对'
  echo "OK|pair|已停止配对"
}

# ---------- 反诈 ----------
# 此处仅控制手机管家的开关与后台组件；独立组件由 reporting.sh 管理。
AF_PKG=com.miui.securitycenter
AF_BAK=$DATA/antifraud.bak
AF_COMPONENTS_BAK=$DATA/antifraud.components.bak
# 支付页防诈监控（通话/短信后盯支付页面）、XSOF 欺诈态势采集与对外查询、AI 反诈场景监控
AF_COMPS="com.miui.antifraud.AntiFraudReceiver com.miui.antifraud.PayActivityMonitorService com.xiaomi.security.xsof.frauddetection.service.MiFraudDetectionService com.xiaomi.security.xsof.MiSafetyDetectService com.miui.electricrisk.AiGuardSceneService"
# secure：短信风险提醒、验证码剪贴板提醒、屏幕共享防护、AI 通话预警、浏览器网页风险提醒
AF_KEYS="settings_sms_risk_warn_enable settings_sms_code_clipboard_warn_enable screen_share_protection ai_guard_function_switch com.android.browser.web_page_warning_states"
# 手机管家自己的偏好：通话/短信后的支付警告、风险应用识别
AF_PREFS="key_risk_sms_and_call_pay_warn_enable key_risk_application_warning_enable"
AF_URI=content://com.miui.securitycenter.remoteprovider

af_pref_get() {
  content call --uri $AF_URI --method callPreference --arg GET --extra type:i:1 --extra key:s:$1 --extra default:b:true </dev/null 2>/dev/null |
    grep -o "$1=[a-z]*" | cut -d= -f2
}
af_pref_set() { content call --uri $AF_URI --method callPreference --arg SET --extra type:i:1 --extra key:s:$1 --extra value:b:$2 </dev/null >/dev/null 2>&1; }

af_comps_disabled() {
  component_disabled_count "$AF_PKG" $AF_COMPS
}

af_apply() {
  for k in $AF_KEYS; do settings put secure $k 0 </dev/null; done
  for k in $AF_PREFS; do af_pref_set $k false; done
  for c in $AF_COMPS; do pm disable --user 0 $AF_PKG/$c </dev/null >/dev/null 2>&1; done
  # 禁用组件不会停掉已经在跑的服务
  am stopservice -n $AF_PKG/com.xiaomi.security.xsof.frauddetection.service.MiFraudDetectionService </dev/null >/dev/null 2>&1
}

cmd_af_on() {
  if [ ! -s "$AF_BAK" ]; then
    [ "$(conf_get af_off 0)" = 0 ] || { echo 'ERR|af|旧配置缺少原始状态，请先关闭并检查系统反诈设置'; return; }
    # 偏好接口不可读取时不能关闭：否则无法在停用时恢复真实原值。
    af_valid=1
    for k in $AF_PREFS; do
      value=$(af_pref_get "$k")
      case "$value" in true|false) ;; *) af_valid=0 ;; esac
    done
    [ "$af_valid" = 1 ] || { echo 'ERR|af|本机反诈偏好接口无法读取，未修改系统'; return; }
    rm -f "$AF_COMPONENTS_BAK"
    set --
    for c in $AF_COMPS; do set -- "$@" "$AF_PKG/$c"; done
    component_snapshot "$AF_COMPONENTS_BAK" "$AF_PKG" "$@" || {
      echo 'ERR|af|无法读取组件基线，未修改系统'; return;
    }
  fi
  [ -s "$AF_COMPONENTS_BAK" ] || { echo 'ERR|af|组件恢复基线丢失，未修改系统'; return; }
  # 只在第一次开启时备份，重复开启不会把已关掉的值当成原值
  [ -s $AF_BAK ] || {
    for k in $AF_KEYS; do echo "s|$k|$(settings get secure $k </dev/null)"; done
    for k in $AF_PREFS; do echo "p|$k|$(af_pref_get $k)"; done
  } > $AF_BAK
  conf_set af_off 1
  af_apply
  log "反诈 已禁用"
  bk_sync
  echo "OK|af|已禁用反诈，$(af_comps_disabled) 个后台组件已停用"
}

cmd_af_off() {
  was_enabled=$(conf_get af_off 0)
  conf_set af_off 0
  if [ ! -s "$AF_BAK" ] && [ ! -s "$AF_COMPONENTS_BAK" ] && [ "$was_enabled" = 0 ]; then
    echo 'OK|af|已关闭，系统设置未修改'; return
  fi
  restored=1
  component_restore "$AF_COMPONENTS_BAK" || restored=0
  [ -s "$AF_BAK" ] || restored=0
  if [ -s $AF_BAK ]; then
    while IFS='|' read -r t k v; do
      case $t in
        s) restore_setting secure "$k" "$v" || restored=0
           [ "$(settings get secure "$k" </dev/null)" = "$v" ] || restored=0 ;;
        p) case "$v" in true|false) af_pref_set "$k" "$v" || restored=0 ;; *) restored=0 ;; esac
           [ "$(af_pref_get "$k")" = "$v" ] || restored=0 ;;
      esac
    done < $AF_BAK
  fi
  [ "$restored" = 1 ] || { echo 'ERR|af|开机禁用已停止，部分原始状态无法恢复；已保留基线，请检查后重试'; return; }
  rm -f "$AF_BAK" "$AF_COMPONENTS_BAK"
  log "反诈 已恢复"
  bk_sync
  echo "OK|af|已恢复反诈和后台组件"
}

# 陌生号码智能识别：来电号码发到黄页云端查询标记（骚扰/诈骗/快递等），和电诈防护页「通话防护」是同一个开关
cmd_af_call() {
  case "$1" in 0|1) ;; *) echo "ERR|call|取值只能是 0/1"; return ;; esac
  settings put system cloud_antispam $1 </dev/null
  settings put system key_yellowpage_main_switch $1 </dev/null
  log "陌生号码识别=$1"
  [ $1 = 1 ] && echo "OK|call|已开启陌生号码识别" || echo "OK|call|已关闭陌生号码识别，来电不再查询云端标记"
}

# 禁用|已停用组件数|组件总数|采集服务在跑|短信提醒|屏幕共享防护|支付警告|风险应用识别|上传屏蔽|hosts 已挂载|陌生号码识别
cmd_af_status() {
  run=0; dumpsys activity services $AF_PKG </dev/null 2>/dev/null | grep -q 'MiFraudDetectionService' && run=1
  m=0; hosts_mounted && m=1
  echo "$(conf_get af_off 0)|$(af_comps_disabled)|$(echo $AF_COMPS | wc -w)|$run|$(settings get secure settings_sms_risk_warn_enable </dev/null)|$(settings get secure screen_share_protection </dev/null)|$(af_pref_get key_risk_sms_and_call_pay_warn_enable)|$(af_pref_get key_risk_application_warning_enable)|$(conf_get hosts_antifraud 0)|$m|$(settings get system cloud_antispam </dev/null)"
}

# ---------- 账号同步 ----------
# 「自动同步个人/工作账号数据」是 ContentService 的 master sync，按用户分开（手机分身 999 也有一份，系统页不显示）；
# 「仅在 WLAN 下同步」是 secure sync_on_wifi_only。master sync 没有命令行，用 service call 调 IContentService，
# 调用编号随系统版本可能变，每次先用 get 对照 dumpsys 校验，不一致就不动。
SYNC_GET=24; SYNC_SET=22
sync_dump() { dumpsys content </dev/null | sed -n 's/^Auto sync: //p'; }
sync_get() { service call content $SYNC_GET i32 $1 </dev/null 2>/dev/null | grep -oE '[0-9a-f]{8}' | sed -n 2p; }
sync_api_ok() {
  d=$(sync_dump); [ -n "$d" ] || return 1
  for kv in $d; do
    u=${kv%%=*}; u=${u#u}
    [ "${kv#*=}" = true ] && w=00000001 || w=00000000
    [ "$(sync_get $u)" = "$w" ] || return 1
  done
}
sync_set_user() { service call content $SYNC_SET i32 $2 i32 $1 </dev/null >/dev/null 2>&1; }

# 输出：api|1/0  user|id|personal/work/xspace/other|1/0  wifi|1/0  boot|1/0
cmd_sync_status() {
  ok=0; sync_api_ok && ok=1
  echo "api|$ok"
  flags=$(wl_users)
  for kv in $(sync_dump); do
    u=${kv%%=*}; u=${u#u}
    f=$(echo "$flags" | grep "^$u|" | cut -d'|' -f3)
    if [ "$u" = 0 ]; then k=personal
    elif [ -n "$f" ] && [ $((0x$f & 0x20)) != 0 ]; then k=work
    elif [ "$u" = 999 ]; then k=xspace
    else k=other; fi
    echo "user|$u|$k|$([ "${kv#*=}" = true ] && echo 1 || echo 0)"
  done
  echo "wifi|$([ "$(settings get secure sync_on_wifi_only </dev/null)" = 1 ] && echo 1 || echo 0)"
  echo "boot|$(conf_get sync_boot_off 0)"
}

# sync-set <用户id|wifi> 0|1
cmd_sync_set() {
  case "$2" in 0|1) ;; *) echo "ERR|sync|参数错误"; return ;; esac
  if [ "$1" = wifi ]; then
    settings put secure sync_on_wifi_only $2 </dev/null
    echo "OK|sync|$([ $2 = 1 ] && echo 已开启仅在 WLAN 下同步 || echo 已关闭仅在 WLAN 下同步)"; return
  fi
  echo "$1" | grep -qE '^[0-9]+$' || { echo "ERR|sync|用户 id 不合法"; return; }
  sync_api_ok || { echo "ERR|sync|本机系统接口不匹配，请到系统设置「账号与同步」里手动操作"; return; }
  sync_set_user $1 $2
  [ "$(sync_get $1)" = "0000000$2" ] || { echo "ERR|sync|设置没有生效"; return; }
  log "账号同步 u$1=$2"
  echo "OK|sync|$([ $2 = 1 ] && echo 已开启自动同步 || echo 已关闭自动同步)"
}

# 全部关闭：所有用户的自动同步 + 仅在 WLAN 下同步
sync_off_all() {
  settings put secure sync_on_wifi_only 0 </dev/null
  sync_api_ok || return 1
  for kv in $(sync_dump); do
    u=${kv%%=*}; u=${u#u}
    [ "${kv#*=}" = true ] && sync_set_user $u 0
  done
  return 0
}

cmd_sync_off() {
  if sync_off_all; then log "账号同步 全部关闭"; echo "OK|sync|已关闭全部同步"
  else echo "ERR|sync|已关闭仅在 WLAN 下同步；自动同步的系统接口不匹配，请到系统设置里手动关闭"; fi
}

cmd_sync_boot() {
  case "$1" in 0|1) ;; *) echo "ERR|sync|参数错误"; return ;; esac
  conf_set sync_boot_off $1
  if [ "$1" = 1 ]; then
    sync_off_all || { echo 'ERR|sync|本机同步接口不匹配，未全部关闭'; return; }
  else
    for pid in $(pgrep -f "$MODDIR/adclean[.]sh sync-off-delayed$"); do kill "$pid" 2>/dev/null; done
  fi
  echo "OK|sync|$([ $1 = 1 ] && echo 已开启开机自动关闭，并已立即关闭全部同步 || echo 已关闭开机自动关闭)"
}

cmd_sync_off_delayed() {
  daemon_lock "$DATA/sync.daemon.flock" || return 0
  sleep 120
  [ "$(conf_get sync_boot_off 0)" = 1 ] && sync_off_all
}

replay_packages() (
  package_lock || return 1
  load_lists
  while IFS='|' read -r p mode orig ts; do
    valid_pkg "$p" || continue
    is_protected "$p" && continue
    st=$(pkg_state "$p")
    if [ "$mode" = uninstall ] && [ "$st" = e -o "$st" = d ]; then
      pm uninstall -k --user 0 "$p" </dev/null >/dev/null 2>&1 && log "boot re-uninstall $p"
    elif [ "$mode" = disable ] && [ "$st" = e ]; then
      pm disable-user --user 0 "$p" </dev/null >/dev/null 2>&1 && log "boot re-disable $p"
    fi
  done < "$APPLIED"
)

replay_tweaks() (
  tweak_lock || return 1
  cut -d'|' -f1 "$TWEAKBAK" | while IFS= read -r id; do
    line=$(tweak_line "$id") || continue
    IFS='|' read -r id ns key val desc <<EOF
$line
EOF
    [ "$(settings get "$ns" "$key" </dev/null)" = "$val" ] || {
      settings put "$ns" "$key" "$val" </dev/null && log "boot re-tweak $id"
    }
  done
)

# ---------- 开机 ----------
cmd_boot() {
  # 账号登录、小米云服务启动后可能把同步打开，开机关一次，2 分钟后再关一次
  if [ "$(conf_get sync_boot_off 0)" = 1 ]; then
    sync_off_all
    start_detached sh "$MODDIR/adclean.sh" sync-off-delayed
  fi
  [ "$(conf_get ota_block 0)" = 1 ] && cmd_ota_on >/dev/null
  # 手机管家更新或云控可能把开关改回去，开机再关一遍
  [ "$(conf_get af_off 0)" = 1 ] && af_apply
  if [ -s "$DATA/reporting/mode" ]; then
    sh "$MODDIR/reporting.sh" boot || log '应用上报组件开机检查未完成'
  fi
  # 旧模块配置仅由用户执行 migrate 显式导入，首次启动不能接管旧设置。
  # 机型修改：启用时校验一次（早期失败时补偿）；未启用且旧模块没在改属性时，记录干净的原机基线
  if ! legacy_active changephone; then
    if grep -q '^enabled=1$' $DATA/devmodel/config 2>/dev/null; then sh $DM boot >/dev/null 2>&1
    else sh $DM capture-runtime-stock >/dev/null 2>&1; fi
  fi
  # 电池循环伪装
  if ! legacy_active fake_battery_cycle && ! grep -q '^MODE=off' $DATA/battery/config.txt 2>/dev/null && [ -f $DATA/battery/config.txt ]; then
    sh $CYCLE apply >/dev/null 2>&1
    [ -n "$(cycle_watch_pid)" ] || start_detached sh $MODDIR/adclean.sh cycle-watch
  fi
  guard_start
  wlog_start
  rot_start
  adb_diag_start
  sh $CHG start
  fps_boot
  fs=$(conf_get font_scale 0); [ "$fs" != 0 ] && settings put system font_scale $fs </dev/null
  [ "$(conf_get cpu_enable 0)" = 1 ] && for p in $CPUFREQ/policy*; do v=$(conf_get cpu_${p##*/} 0); [ "$v" != 0 ] && cpu_limit ${p##*/} $v; done
  for p in $(conf_get wl_pkgs "" | tr ',' ' '); do dumpsys deviceidle whitelist +$p >/dev/null </dev/null; done
  [ "$(conf_get keep_on_boot 1)" = 1 ] || return
  # 只序列化应用事务，不让包管理器阻塞其它硬件守护。
  replay_packages
  replay_tweaks
  log "开机检查完成"
  until bk_ready; do sleep 10; done
  bk_sync
}

# APK 已卸载时，由通用开机脚本恢复。绝不调用 pm uninstall 或重新安装 APK。
cmd_uninstall_restore() {
  restore_failed=0
  sh "$CHG" stop >/dev/null 2>&1
  for p in $(pgrep -f "$MODDIR/adclean[.]sh (guard-daemon|wlog-daemon|rot-daemon|cycle-watch|fps-daemon|adb-diag-daemon|sync-off-delayed)$"); do kill "$p"; done
  ota_watch_stop
  [ "$(conf_get restore_on_uninstall 1)" = 1 ] || return "$restore_failed"
  cmd_restore_all >/dev/null
  for id in $(cut -d'|' -f1 "$TWEAKBAK"); do tweak_off "$id" >/dev/null; done
  [ "$(conf_get ota_block 0)" = 1 ] && cmd_ota_off >/dev/null
  [ "$(conf_get af_off 0)" = 1 ] && cmd_af_off >/dev/null
  if [ -e "$DATA/reporting/original" ] || [ "$(cat "$DATA/reporting/mode" 2>/dev/null)" = on ] || [ "$(cat "$DATA/reporting/mode" 2>/dev/null)" = restoring ]; then
    if ! sh "$MODDIR/reporting.sh" off; then
      restore_failed=1
      log "应用上报组件恢复未完成，保留基线与开机恢复入口"
    fi
  fi
  cmd_cpu_enable 0 >/dev/null
  cmd_fps_set 0 smart >/dev/null
  sh "$CYCLE" restore >/dev/null 2>&1
  sh "$DM" disable >/dev/null 2>&1
  hosts_mounted && in_init_ns umount /system/etc/hosts
  return "$restore_failed"
}

cmd_info() {
  echo "model|$(getprop ro.product.marketname)"
  echo "os|$(getprop ro.mi.os.version.incremental)"
  echo "android|$(getprop ro.build.version.release)"
  echo "backup_dir|$(conf_get backup_dir $DEF_BACKUP)"
  echo "backup_apk|$(conf_get backup_apk 1)"
  echo "keep_on_boot|$(conf_get keep_on_boot 1)"
  echo "restore_on_uninstall|$(conf_get restore_on_uninstall 1)"
  echo "default_mode|$(conf_get default_mode disable)"
  boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
  early_id=$(cat "$DATA/early.boot.id" 2>/dev/null)
  if [ -n "$boot_id" ] && [ "$boot_id" = "$early_id" ]; then
    echo 'early_boot|本次开机已执行'
  else
    echo 'early_boot|已注册，重启后验证；机型、色彩和省电高刷依赖此阶段'
  fi
  echo "storage_ready|$(bk_ready && echo 1 || echo 0)"
  echo "backup_size|$(du -sh "$(bk_dir)" 2>/dev/null | cut -f1)"
}

cmd_setconf() {
  case "$1" in
    backup_dir)
      case "$2" in /*) ;; *) echo "ERR|$1|必须是绝对路径"; return ;; esac
      echo "$2" | grep -q "[\"'\`\$;&|<>]" && { echo "ERR|$1|路径含非法字符"; return; } ;;
    backup_apk|keep_on_boot|restore_on_uninstall) case "$2" in 0|1) ;; *) echo "ERR|$1|取值只能是 0/1"; return ;; esac ;;
    default_mode) case "$2" in disable|uninstall) ;; *) echo "ERR|$1|取值不合法"; return ;; esac ;;
    fps_method) case "$2" in smart|force|hold) ;; *) echo "ERR|$1|取值不合法"; return ;; esac ;;
    *) echo "ERR|$1|未知配置项"; return ;;
  esac
  conf_set $1 "$2"
  bk_sync
  echo "OK|$1|已保存"
}

case "$1" in
  info) cmd_info ;;
  protected) echo $PROTECTED | tr ' ' '\n' ;;
  status) shift; cmd_status "$@" ;;
  apply) shift; cmd_apply "$@" ;;
  restore) shift; cmd_restore "$@" ;;
  restore-all) cmd_restore_all ;;
  import) cmd_import; bk_sync ;;
  sync) bk_sync && echo "OK|sync|已同步到 $(conf_get backup_dir $DEF_BACKUP)" ;;
  tweak-status) cmd_tweak_status ;;
  tweak-on) tweak_on "$2"; bk_sync ;;
  tweak-off) tweak_off "$2"; bk_sync ;;
  hosts-status) cmd_hosts_status ;;
  hosts-set) cmd_hosts_set "$2" "$3" ;;
  hosts-custom-get) cat $DATA/hosts.custom 2>/dev/null ;;
  hosts-custom-set) cmd_hosts_custom_set "$2" ;;
  hosts-build) hosts_build ;;
  hosts-mount) hosts_mount ;;
  setconf) cmd_setconf "$2" "$3" ;;
  log) if [ "${2:-200}" = 0 ]; then cat $LOG 2>/dev/null; else tail -n "${2:-200}" $LOG 2>/dev/null; fi ;;
  # 截断而不是删除：充电控制、电池循环的后台进程也在往这个文件追加
  log-clear) : > $LOG; echo "OK|log|已清空运行日志" ;;
  ota-on) cmd_ota_on ;;
  ota-off) cmd_ota_off ;;
  ota-status) cmd_ota_status ;;
  ota-clear) ota_clear ;;
  ota-early) cmd_ota_early ;;
  af-on) cmd_af_on ;;
  af-off) cmd_af_off ;;
  reporting-status) sh "$MODDIR/reporting.sh" status ;;
  reporting-on) sh "$MODDIR/reporting.sh" on ;;
  reporting-off) sh "$MODDIR/reporting.sh" off ;;
  reporting-hosts-status) cmd_reporting_hosts_status ;;
  af-status) cmd_af_status ;;
  af-call) cmd_af_call "$2" ;;
  sync-status) cmd_sync_status ;;
  sync-set) cmd_sync_set "$2" "$3" ;;
  sync-off) cmd_sync_off ;;
  sync-boot) cmd_sync_boot "$2" ;;
  sync-off-delayed) cmd_sync_off_delayed ;;
  fps-status) cmd_fps_status ;;
  fps-idle) cmd_fps_idle "$2" ;;
  fps-idle-boot) fps_idle_boot ;;
  fps-set) cmd_fps_set "$2" "$3" ;;
  fps-daemon) cmd_fps_daemon ;;
  font-set) cmd_font_set "$2" ;;
  color-status) cmd_color_status ;;
  color-set) cmd_color_set "$2" ;;
  color-boot) cmd_color_boot ;;
  cpu-status) cmd_cpu_status ;;
  cpu-set) cmd_cpu_set "$2" "$3" ;;
  cpu-enable) cmd_cpu_enable "$2" ;;
  wl-list) cmd_wl_list ;;
  wl-set) cmd_wl_set "$2" "$3" ;;
  wl-sync) cmd_wl_sync ;;
  dexopt) shift; cmd_dexopt "$@" ;;
  dexopt-run) cmd_dexopt_run "$2" ;;
  dexopt-recent) cmd_dexopt_recent "$2" ;;
  fps-overlay) cmd_fps_overlay "$2" ;;
  dexopt-status) cmd_dexopt_status ;;
  legacy-status) cmd_legacy_status ;;
  legacy-disable) cmd_legacy_disable "$2" ;;
  migrate) cmd_migrate ;;
  dm) shift; cmd_dm "$@" ;;
  cycle) shift; cmd_cycle "$@" ;;
  cycle-watch) cmd_cycle_watch ;;
  charge) shift; sh $CHG "$@" ;;
  guard-status) cmd_guard_status ;;
  guard-set) cmd_guard_set "$2" "$3" ;;
  guard-daemon) cmd_guard_daemon ;;
  guard-log) tail -n 50 $GUARD_DIR/events.log 2>/dev/null ;;
  wlog-status) cmd_wlog_status ;;
  wlog-files) cmd_wlog_files ;;
  wlog-set) cmd_wlog_set "$2" "$3" ;;
  wlog-clean) cmd_wlog_clean_now ;;
  wlog-records) cat $WLOG_EVENTS 2>/dev/null ;;
  wlog-records-clear) cmd_wlog_records_clear ;;
  wlog-daemon) cmd_wlog_daemon ;;
  rot-status) cmd_rot_status ;;
  rot-set) cmd_rot_set "$2" "$3" ;;
  rot-log) cat $ROT_DIR/events.log 2>/dev/null ;;
  rot-log-clear) cmd_rot_log_clear ;;
  rot-daemon) cmd_rot_daemon ;;
  adb-pair-start) cmd_adb_pair_start ;;
  adb-pair-stop) cmd_adb_pair_stop ;;
  adb-connect-address) cmd_adb_connect_address ;;
  adb-diag-status) cmd_adb_diag_status ;;
  adb-diag-set) cmd_adb_diag_set "$2" ;;
  adb-diag-daemon) cmd_adb_diag_daemon ;;
  adb-diag-log) cat "$ADB_DIAG/events.log" 2>/dev/null ;;
  adb-diag-clear)
    (umask 077; mkdir -p "$ADB_DIAG" && : > "$ADB_DIAG/events.log") &&
      echo 'OK|adb|已清空诊断记录' || echo 'ERR|adb|清空失败' ;;
  adb-diag-action)
    case "$2" in
      wifi-on|wifi-off|unpair-request|unpair-ok|unpair-error|clear-request|clear-ok|clear-error)
        adb_diag_event "App 操作：$2"; echo 'OK|adb|已记录' ;;
      *) echo 'ERR|adb|未知操作' ;;
    esac ;;
  boot) cmd_boot ;;
  uninstall-restore) cmd_uninstall_restore ;;
  *) echo "用法: $0 {info|status|apply|restore|restore-all|import|sync|tweak-*|hosts-*|setconf|log}" ;;
esac
