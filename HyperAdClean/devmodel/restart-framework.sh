#!/system/bin/sh

request_id="$1"
status_file=/data/adb/hyperadclean/devmodel/restart.status

sleep 2
if ! setprop ctl.restart zygote 2>/dev/null; then
  printf 'id=%s\nstate=failed\ncode=zygote_request_rejected\ntime=%s\n' \
    "$request_id" "$(date +%s)" > "$status_file.tmp.$$" &&
    mv -f "$status_file.tmp.$$" "$status_file"
  exit 1
fi

if [ "$(getprop init.svc.zygote_secondary 2>/dev/null)" = 'running' ]; then
  if ! setprop ctl.restart zygote_secondary 2>/dev/null; then
    printf 'id=%s\nstate=failed\ncode=secondary_request_rejected\ntime=%s\n' \
      "$request_id" "$(date +%s)" > "$status_file.tmp.$$" &&
      mv -f "$status_file.tmp.$$" "$status_file"
    exit 1
  fi
fi

printf 'id=%s\nstate=accepted\ncode=0\ntime=%s\n' \
  "$request_id" "$(date +%s)" > "$status_file.tmp.$$" &&
  mv -f "$status_file.tmp.$$" "$status_file"
