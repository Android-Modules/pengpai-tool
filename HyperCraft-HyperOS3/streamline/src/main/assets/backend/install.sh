#!/system/bin/sh
set -eu
BASE=/data/adb/hypercraft
RUNTIME=$BASE/runtime
DATA=$BASE/state
SOURCE=$1
HASH=$2
VERSION=$3
. "$SOURCE/env.sh"
[ "$(id -u)" = 0 ] || { echo 'ERR|root|需要授予应用 Root 权限'; exit 1; }
case "$HASH" in ''|*[!0-9a-f]*) echo 'ERR|runtime|运行环境校验值无效'; exit 1 ;; esac
[ ${#HASH} = 64 ] || exit 1
if ! sh "$SOURCE/legacy.sh" check; then
  echo 'ERR|legacy|检测到旧澎湃工具的 Root 后台、开机入口或早期配置。请先明确停用旧后台；提示重启时，重启后再检查'
  exit 1
fi
# 两个版本的守护不能同时控制系统；由用户先停用旧模块，不自动改动旧工程。
OLD=/data/adb/modules/hyperadclean
if [ -d "$OLD" ] && [ ! -f "$OLD/disable" ] && [ ! -f "$OLD/remove" ]; then
  echo 'ERR|legacy|请先停用 HyperOS 3 的澎湃小工具模块并重启，再初始化澎湃工坊'
  exit 1
fi
umask 077
mkdir -p "$DATA" "$RUNTIME"
exec 9>>"$BASE/install.flock"
flock -xn 9 9>&9 || { echo 'ERR|runtime|运行环境正在初始化，请稍后重新检查'; exit 1; }
# 与启动重放互斥，避免在旧脚本执行过程中替换后端。
exec 8>>"$BASE/boot.flock"
flock -xn 8 8>&8 || { echo 'ERR|runtime|运行环境正在启动，请稍后重新检查'; exit 1; }
current=$(cat "$RUNTIME/payload.sha256" 2>/dev/null || true)
for required in adclean.sh boot.sh env.sh legacy.sh reporting.sh hosts/reporting.txt battery/charge.sh battery/cycle.sh devmodel/control.sh devmodel/restart-framework.sh; do
  [ -f "$RUNTIME/$required" ] || current=''
done
if [ "$current" != "$HASH" ]; then
  # 写入前先验证所有脚本；逐文件原子替换，最后写入版本标记，失败时可重试。
  for f in "$SOURCE/"*.sh "$SOURCE/battery/"*.sh "$SOURCE/devmodel/"*.sh; do sh -n "$f"; done
  if [ -f "$RUNTIME/boot.sh" ]; then sh "$RUNTIME/boot.sh" stop 8>&- 9>&-; fi
  # 中途复制失败时，开机入口不能运行一套更新到一半的脚本。
  rm -f "$RUNTIME/payload.sha256"
  for folder in . battery devmodel hosts; do
    mkdir -p "$RUNTIME/$folder"
    for f in "$SOURCE/$folder/"*; do
      [ -f "$f" ] || continue
      name=${f##*/}
      cp "$f" "$RUNTIME/$folder/$name.new"
      chmod 600 "$RUNTIME/$folder/$name.new"
      mv -f "$RUNTIME/$folder/$name.new" "$RUNTIME/$folder/$name"
    done
  done
  mkdir -p "$RUNTIME/bin"
  for bb in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox /data/adb/ap/bin/busybox; do
    [ -x "$bb" ] || continue
    for tool in inotifyd nsenter; do ln -sf "$bb" "$RUNTIME/bin/$tool"; done
    break
  done
  rm -f "$DATA/boot.id"
fi
printf 'version=%s\n' "$VERSION" > "$RUNTIME/version.prop"
if [ ! -f "$DATA/config" ]; then
  cat > "$DATA/config" <<'EOF'
backup_dir=/storage/emulated/0/HyperCraft
backup_apk=1
keep_on_boot=1
restore_on_uninstall=1
default_mode=disable
hosts_xiaomi=0
hosts_sdk=0
hosts_custom=0
adb_diag_enable=0
EOF
fi
if [ ! -s "$DATA/hosts.orig" ]; then cp /system/etc/hosts "$DATA/hosts.orig"; fi
sh "$RUNTIME/adclean.sh" hosts-build 8>&- 9>&-
[ -s "$DATA/devmodel/stock.props" ] || sh "$RUNTIME/devmodel/control.sh" capture-stock 8>&- 9>&- >/dev/null 2>&1 || true
# 通用开机脚本由独立 APK 管理，不创建 /data/adb/modules 下的模块。
for entry in post-fs-data service; do
  mkdir -p "/data/adb/$entry.d"
  phase=early; [ "$entry" = service ] && phase=boot
  target=/data/adb/$entry.d/hypercraft.sh
  cat > "$target.new" <<EOF
#!/system/bin/sh
[ -f "$RUNTIME/boot.sh" ] || exit 0
exec /system/bin/sh "$RUNTIME/boot.sh" $phase
EOF
  chmod 700 "$target.new"
  mv -f "$target.new" "$target"
done
printf '%s\n' "$HASH" > "$RUNTIME/payload.sha256"
chmod 700 "$BASE" "$RUNTIME" "$DATA"
# 先释放启动锁；detached 子进程也不能继承安装锁。
flock -u 8 8>&8
exec 8>&-
start_detached sh "$RUNTIME/boot.sh" start
echo 'OK|runtime|内置 Root 运行环境已就绪'
