#!/system/bin/sh
M=/data/adb/hypercraft/runtime/adclean.sh
echo 'ROOT|'; id
echo 'CONFIG|'; cat /data/adb/hypercraft/state/config
for cmd in fps-status color-status cpu-status ota-status af-status sync-status guard-status wlog-status rot-status adb-diag-status; do
  echo "INVENTORY|$cmd"; sh "$M" "$cmd"
done
echo 'INVENTORY|charge'; sh "$M" charge status
echo 'INVENTORY|cycle'; sh "$M" cycle status
echo 'INVENTORY|model'; sh "$M" dm status
echo 'INVENTORY|persistent-processes'
ps -A -o PID,PPID,RSS,ARGS | grep -E 'hypercraft|hyperhome|pengpaitool|cycle.sh|charge.sh' | grep -v grep
echo 'INVENTORY|mounts'; cat /proc/mounts | grep -E 'hypercraft|/system/etc/hosts'
