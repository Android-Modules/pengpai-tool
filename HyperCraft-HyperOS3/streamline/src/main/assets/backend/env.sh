#!/system/bin/sh
# su 会话与开机环境使用同一组工具；不依赖安装可挂载 system 的模块。
PATH=/data/adb/hypercraft/runtime/bin:/data/adb/ksu/bin:/data/adb/magisk:/data/adb/ap/bin:/system/bin:/system/xbin:$PATH
export PATH

# 只有新 session 内写出 ready 后才返回，前台 Root 包装器随后清理本组时不会误杀尚未 setsid 的启动者。
# 独立临时目录与 EXIT trap 只属于本次启动；传参保持 argv，不拼接成可执行字符串。
start_detached() (
  [ "$#" -gt 0 ] && command -v "$1" >/dev/null 2>&1 || return 1
  launch_base=${HYPERCRAFT_LAUNCH_DIR:-/data/adb/hypercraft/state/.launch}
  mkdir -p "$launch_base" || return 1
  launch_dir=$(mktemp -d "$launch_base/ready.XXXXXXXX") || return 1
  trap 'rm -f "$launch_dir/ready"; rmdir "$launch_dir" 2>/dev/null' EXIT
  trap 'exit 1' HUP INT TERM
  set -- nohup sh -c '
    ready=$1; shift
    printf "%s\n" "$$" > "$ready" || exit 1
    exec "$@"
  ' hypercraft-detached "$launch_dir/ready" "$@"
  # mksh re-marks high descriptors close-on-exec in a function subshell.
  # Forward the compiler's ownership lock at the actual exec boundary only.
  if [ "${HYPERCRAFT_DETACHED_LOCK_FD6:-0}" = 1 ]; then
    setsid -w "$@" 6>&6 7>&- 8>&- 9>&- </dev/null >/dev/null 2>&1 &
  else
    setsid -w "$@" 6>&- 7>&- 8>&- 9>&- </dev/null >/dev/null 2>&1 &
  fi
  launch_pid=$!
  launch_start=$(awk '{print $22}' "/proc/$launch_pid/stat" 2>/dev/null) || launch_start=''
  launch_attempt=0
  while [ "$launch_attempt" -lt 250 ]; do
    if [ -s "$launch_dir/ready" ]; then return 0; fi
    kill -0 "$launch_pid" 2>/dev/null || { [ -s "$launch_dir/ready" ] && return 0; return 1; }
    sleep 0.02
    launch_attempt=$((launch_attempt + 1))
  done
  # ready 之前不允许执行目标。超时只终止本次 launcher，不按进程名清理其它任务。
  if [ -n "$launch_start" ] && [ "$(awk '{print $22}' "/proc/$launch_pid/stat" 2>/dev/null)" = "$launch_start" ]; then
    kill -TERM "$launch_pid" 2>/dev/null
  fi
  return 1
)

# pgrep 只能优化常见路径，不能阻止两个并发启动者。守护在碰 FIFO/硬件之前持有内核锁。
daemon_lock() {
  exec 7>>"$1" || return 1
  flock -xn 7 7>&7
}

# 锁文件保持同一 inode，不能在解锁后删除。子 shell 退出时自动释放内核锁。
# 所有配置写入共用此入口，锁覆盖读取、修改和原子替换的全过程。
config_set() (
  config_file=$1; config_key=$2; config_value=$3
  case "$config_key" in ''|*[!A-Za-z0-9_]*) return 1 ;; esac
  exec 9>>"$config_file.lock" || exit 1
  # mksh 的 exec 高位 fd 默认带 close-on-exec，显式自复制后再交给 flock。
  flock -x 9 9>&9 || exit 1
  config_tmp="$config_file.tmp.$$"
  trap 'rm -f "$config_tmp"' EXIT
  trap 'exit 1' HUP INT TERM
  if [ -f "$config_file" ]; then
    grep -v "^$config_key=" "$config_file" > "$config_tmp" || [ "$?" = 1 ] || exit 1
  else
    : > "$config_tmp" || exit 1
  fi
  printf '%s=%s\n' "$config_key" "$config_value" >> "$config_tmp" || exit 1
  mv -f "$config_tmp" "$config_file"
)
