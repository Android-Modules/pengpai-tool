#!/system/bin/sh
. "${0%/*}/device-write-common.sh"
reply=$(mod sync)
echo "$reply"
echo "$reply" | grep -q '^OK|sync|' && ok backup.synced || bad backup.synced
eq fps.disabled "$(mod fps-status | cut -d'|' -f1)" 0
eq cpu.disabled "$(mod cpu-status | head -n 1)" 'enable|0'
eq ota.disabled "$(mod ota-status | cut -d'|' -f1-2)" '0|0'
eq cycle.disabled "$(mod cycle status | sed -n 's/.*"mode":"\([^"]*\)".*/\1/p')" off
eq model.disabled "$(mod dm status | sed -n 's/^enabled=//p')" 0
pm path io.github.dalong.hypercraft.probe </dev/null | grep -q '^package:' && bad fixture.remains || ok fixture.absent
for stage in post-fs-data.d service.d; do
  [ -f "/data/adb/$stage/hypercraft.sh" ] && ok "boot.$stage.present" || bad "boot.$stage.present"
done
echo 'OBSERVE|charge'
mod charge status
for probe in af-status reporting-status reporting-hosts-status; do
  started=$(date +%s)
  echo "OBSERVE|$probe"
  mod "$probe"
  echo "TIMING|$probe|$(($(date +%s) - started))s"
done
summary
