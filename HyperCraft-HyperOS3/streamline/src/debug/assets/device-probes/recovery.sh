#!/system/bin/sh
W=/data/adb/hypercraft/device-acceptance/settings
while IFS='|' read -r ns key value; do
  if [ "$value" = null ]; then settings delete "$ns" "$key" </dev/null >/dev/null; else settings put "$ns" "$key" "$value" </dev/null; fi
  actual=$(settings get "$ns" "$key" </dev/null)
  if [ "$actual" = "$value" ]; then echo "PASS|recovery.$ns.$key|$actual"; else echo "FAIL|recovery.$ns.$key|expected=$value actual=$actual"; fi
done < "$W/settings.before"
