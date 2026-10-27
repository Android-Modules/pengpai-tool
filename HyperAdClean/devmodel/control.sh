#!/system/bin/sh

# 集成进 HyperAdClean：脚本放在模块的 devmodel/ 下，状态放到 /data/adb（模块升级不会丢原机基线）
DM_DIR=${0%/*}
MODDIR=${DM_DIR%/*}
STATE_DIR=/data/adb/hyperadclean/devmodel
CONFIG_FILE="$STATE_DIR/config"
ORIGINAL_FILE="$STATE_DIR/original.props"
STOCK_FILE="$STATE_DIR/stock.props"
RUNTIME_STOCK_FILE="$STATE_DIR/runtime-stock.props"
REBOOT_REQUIRED_FILE="$STATE_DIR/restore-reboot-required"
PRESETS_FILES="$DM_DIR/presets.tsv $DM_DIR/presets-v2.0.3.tsv $DM_DIR/presets-v2.0.4.tsv"
MODULE_PROP="$MODDIR/module.prop"
LOCK_FILE="$STATE_DIR/operation.lock"
ROLLBACK_FILE="$STATE_DIR/rollback.props"

PROP_PREFIXES='ro.product ro.product.system ro.product.vendor ro.product.product ro.product.odm ro.product.system_ext'
FIELDS='manufacturer brand marketname model name device'

mkdir -p "$STATE_DIR"
LOCK_RETRIES="${LOCK_RETRIES:-50}"
LOCK_SLEEP="${LOCK_SLEEP:-1}"

process_start_ticks() {
  process_pid="$1"
  [ -z "${LOCK_START_OVERRIDE:-}" ] || { printf '%s\n' "$LOCK_START_OVERRIDE"; return 0; }
  process_stat="$(sed -n '1p' "${PROC_ROOT:-/proc}/$process_pid/stat" 2>/dev/null)"
  [ -n "$process_stat" ] || return 1
  process_fields="${process_stat##*) }"
  set -- $process_fields
  [ "$#" -ge 20 ] || return 1
  printf '%s\n' "${20}"
}

lock_owner_active() {
  owner_value="$1"
  owner_pid="${owner_value%%:*}"
  owner_start="${owner_value#*:}"
  case "$owner_pid:$owner_start" in
    *[!0-9:]*) return 1 ;;
  esac
  kill -0 "$owner_pid" 2>/dev/null || return 1
  [ "$(process_start_ticks "$owner_pid")" = "$owner_start" ]
}

acquire_lock() {
  own_start="$(process_start_ticks "$$")" || return 1
  LOCK_OWNER="$$:$own_start"
  attempts=0
  while ! ln -s "$LOCK_OWNER" "$LOCK_FILE" 2>/dev/null; do
    existing_owner="$(readlink "$LOCK_FILE" 2>/dev/null)"
    if [ -n "$existing_owner" ] && ! lock_owner_active "$existing_owner"; then
      [ "$(readlink "$LOCK_FILE" 2>/dev/null)" = "$existing_owner" ] && rm -f "$LOCK_FILE"
      continue
    fi
    attempts=$((attempts + 1))
    [ "$attempts" -lt "$LOCK_RETRIES" ] || return 1
    sleep "$LOCK_SLEEP"
  done
}

release_lock() {
  [ -n "${LOCK_OWNER:-}" ] || return 0
  [ "$(readlink "$LOCK_FILE" 2>/dev/null)" = "$LOCK_OWNER" ] && rm -f "$LOCK_FILE"
  LOCK_OWNER=
}

valid_id() {
  case "$1" in
    ''|*[!A-Za-z0-9._-]*) return 1 ;;
    *) return 0 ;;
  esac
}

valid_b64() {
  case "$1" in
    *[!A-Za-z0-9+/=]*) return 1 ;;
    *) return 0 ;;
  esac
}

decode_b64() {
  encoded="$1"
  [ "$encoded" = '-' ] && return 0
  valid_b64 "$encoded" || return 1
  [ "${#encoded}" -le 1024 ] || return 1
  [ -n "$encoded" ] || return 0
  decoded="$(printf '%s' "$encoded" | base64 -d 2>/dev/null)" ||
    decoded="$(printf '%s' "$encoded" | base64 --decode 2>/dev/null)" || return 1
  [ "$(printf '%s' "$decoded" | wc -l)" -eq 0 ] || return 1
  [ "${#decoded}" -le 80 ] || return 1
  printf '%s' "$decoded"
}

config_value() {
  key="$1"
  sed -n "s/^${key}=//p" "$CONFIG_FILE" 2>/dev/null | sed -n '1p'
}

write_config() {
  enabled="$1"
  mode="$2"
  preset_id="$3"
  manufacturer_b64="$4"
  brand_b64="$5"
  marketname_b64="$6"
  model_b64="$7"
  product_b64="$8"
  temp_config="$CONFIG_FILE.tmp.$$"
  {
    printf 'enabled=%s\n' "$enabled"
    printf 'mode=%s\n' "$mode"
    printf 'preset_id=%s\n' "$preset_id"
    printf 'manufacturer_b64=%s\n' "$manufacturer_b64"
    printf 'brand_b64=%s\n' "$brand_b64"
    printf 'marketname_b64=%s\n' "$marketname_b64"
    printf 'model_b64=%s\n' "$model_b64"
    printf 'product_b64=%s\n' "$product_b64"
  } > "$temp_config" && mv -f "$temp_config" "$CONFIG_FILE"
}

file_value() {
  key="$1"
  file="$2"
  sed -n "s/^${key}=//p" "$file" 2>/dev/null | sed -n '1p'
}

encode_b64() {
  value="$1"
  [ -n "$value" ] || { printf '%s' '-'; return 0; }
  printf '%s' "$value" | base64 | tr -d '\n'
}

stock_prop_value() {
  stock_key="$1"
  stock_root="${PROP_FS_ROOT:-}"
  for stock_relative in \
    /product/etc/build.prop /product/build.prop \
    /system/product/etc/build.prop /system/product/build.prop \
    /odm/etc/build.prop /odm/default.prop /odm/build.prop \
    /vendor/odm/etc/build.prop /vendor/odm/default.prop /vendor/odm/build.prop \
    /system/odm/etc/build.prop /system/odm/default.prop /system/odm/build.prop \
    /vendor/etc/build.prop /vendor/default.prop /vendor/build.prop \
    /system/vendor/etc/build.prop /system/vendor/default.prop /system/vendor/build.prop \
    /system_ext/etc/build.prop /system_ext/default.prop /system_ext/build.prop \
    /system/system_ext/etc/build.prop /system/system_ext/default.prop /system/system_ext/build.prop \
    /system/build.prop /system/etc/prop.default /system/etc/build.prop \
    /system/system/build.prop /system/system/etc/prop.default /system/system/etc/build.prop \
    /prop.default /default.prop; do
    stock_path="${stock_root}${stock_relative}"
    [ -f "$stock_path" ] || continue
    stock_value="$(awk -v wanted="$stock_key" '
      index($0, wanted "=") == 1 {
        value=substr($0, length(wanted) + 2)
        found=1
      }
      END {
        if (found) {
          printf "%s", value
          exit 0
        }
        exit 1
      }
    ' "$stock_path")" || continue
    printf '%s' "$stock_value"
    return 0
  done
  return 1
}

stock_source_order() {
  source_order="$(stock_prop_value ro.product.property_source_order 2>/dev/null)" ||
    source_order="$(getprop ro.product.property_source_order 2>/dev/null)"
  [ -n "$source_order" ] || source_order='product,odm,vendor,system_ext,system'
  printf '%s' "$source_order" | tr ',' ' '
}

stock_resolve_prop() {
  stock_key="$1"
  stock_prefix="$2"
  stock_field="$3"
  stock_prop_value "$stock_key" && return 0
  [ "$stock_prefix" = ro.product ] || return 1
  for stock_source in $(stock_source_order); do
    case "$stock_source" in
      product|odm|vendor|system_ext|system) ;;
      *) continue ;;
    esac
    stock_prop_value "ro.product.${stock_source}.${stock_field}" && return 0
  done
  return 1
}

capture_stock_props() {
  stock_temp="$STOCK_FILE.tmp.$$"
  stock_identity_found=0
  : > "$stock_temp" || return 1
  for stock_prefix in $PROP_PREFIXES; do
    for stock_field in $FIELDS; do
      stock_key="$stock_prefix.$stock_field"
      if stock_value="$(stock_resolve_prop "$stock_key" "$stock_prefix" "$stock_field")"; then
        stock_present=1
        if [ "$stock_prefix" = ro.product ]; then
          case "$stock_field" in
            manufacturer|brand|marketname|model|name)
              [ -n "$stock_value" ] && stock_identity_found=$((stock_identity_found + 1))
              ;;
          esac
        fi
      else
        stock_present=0
        stock_value=''
      fi
      stock_encoded="$(encode_b64 "$stock_value")"
      printf '%s|%s|%s\n' "$stock_key" "$stock_present" "$stock_encoded" >> "$stock_temp" ||
        { rm -f "$stock_temp"; return 1; }
    done
  done
  # 文件存在不代表值有效；恢复所需的五个身份字段必须全部为非空。
  if [ "$stock_identity_found" -ne 5 ]; then
    rm -f "$stock_temp"
    baseline_file_valid "$STOCK_FILE" || rm -f "$STOCK_FILE"
    return 1
  fi
  mv -f "$stock_temp" "$STOCK_FILE"
}

snapshot_props() {
  snapshot_destination="$1"
  snapshot_temp="$snapshot_destination.tmp.$$"
  snapshot_all="$STATE_DIR/all-props.$$"
  : > "$snapshot_temp" || return 1
  getprop > "$snapshot_all" 2>/dev/null || { rm -f "$snapshot_temp" "$snapshot_all"; return 1; }
  for snapshot_prefix in $PROP_PREFIXES; do
    for snapshot_field in $FIELDS; do
      snapshot_key="$snapshot_prefix.$snapshot_field"
      snapshot_value="$(getprop "$snapshot_key" 2>/dev/null)"
      if grep -Fq "[$snapshot_key]:" "$snapshot_all"; then
        snapshot_present=1
      else
        snapshot_present=0
      fi
      snapshot_encoded="$(encode_b64 "$snapshot_value")"
      printf '%s|%s|%s\n' "$snapshot_key" "$snapshot_present" "$snapshot_encoded" >> "$snapshot_temp"
    done
  done
  rm -f "$snapshot_all"
  mv -f "$snapshot_temp" "$snapshot_destination"
}

save_originals() {
  [ -f "$ORIGINAL_FILE" ] && return 0
  snapshot_props "$ORIGINAL_FILE"
}

set_prop_value() {
  key="$1"
  value="$2"
  [ -n "$value" ] || return 0
  resetprop -n "$key" "$value" >/dev/null 2>&1 ||
    resetprop "$key" "$value" >/dev/null 2>&1
}

set_prop_exact() {
  resetprop -n "$1" "$2" >/dev/null 2>&1 ||
    resetprop "$1" "$2" >/dev/null 2>&1
}

delete_prop_value() {
  resetprop --delete "$1" >/dev/null 2>&1 ||
    resetprop -n --delete "$1" >/dev/null 2>&1
}

apply_values() {
  manufacturer="$1"
  brand="$2"
  marketname="$3"
  model="$4"
  product="$5"

  for prefix in $PROP_PREFIXES; do
    set_prop_value "$prefix.manufacturer" "$manufacturer" || return 1
    set_prop_value "$prefix.brand" "$brand" || return 1
    set_prop_value "$prefix.marketname" "$marketname" || return 1
    set_prop_value "$prefix.model" "$model" || return 1
    set_prop_value "$prefix.name" "$product" || return 1
    set_prop_value "$prefix.device" "$product" || return 1
  done
  return 0
}

verify_values_applied() {
  verify_manufacturer="$1"
  verify_brand="$2"
  verify_marketname="$3"
  verify_model="$4"
  verify_product="$5"

  [ -z "$verify_manufacturer" ] ||
    [ "$(getprop ro.product.manufacturer 2>/dev/null)" = "$verify_manufacturer" ] || return 1
  [ -z "$verify_brand" ] ||
    [ "$(getprop ro.product.brand 2>/dev/null)" = "$verify_brand" ] || return 1
  [ -z "$verify_marketname" ] ||
    [ "$(getprop ro.product.marketname 2>/dev/null)" = "$verify_marketname" ] || return 1
  [ -z "$verify_model" ] ||
    [ "$(getprop ro.product.model 2>/dev/null)" = "$verify_model" ] || return 1
  if [ -n "$verify_product" ]; then
    [ "$(getprop ro.product.name 2>/dev/null)" = "$verify_product" ] || return 1
    [ "$(getprop ro.product.device 2>/dev/null)" = "$verify_product" ] || return 1
  fi
}

restore_snapshot() {
  restore_source="$1"
  [ -f "$restore_source" ] || return 0
  restore_error=0
  while IFS= read -r restore_line; do
    case "$restore_line" in
      *'|'*)
        restore_key="${restore_line%%|*}"
        restore_tail="${restore_line#*|}"
        restore_present="${restore_tail%%|*}"
        restore_encoded="${restore_tail#*|}"
        ;;
      *=*)
        # 兼容 v2.0.1：旧格式无法区分空值和不存在，维持旧版删除语义。
        restore_key="${restore_line%%=*}"
        restore_encoded="${restore_line#*=}"
        restore_present=legacy
        ;;
      *) restore_error=1; continue ;;
    esac
    [ -n "$restore_key" ] || continue
    restore_value="$(decode_b64 "$restore_encoded")" || { restore_error=1; continue; }
    if [ "$restore_present" = '0' ] || { [ "$restore_present" = legacy ] && [ -z "$restore_value" ]; }; then
      delete_prop_value "$restore_key" || restore_error=1
    else
      set_prop_exact "$restore_key" "$restore_value" || restore_error=1
    fi
  done < "$restore_source"
  [ "$restore_error" -eq 0 ] || return 1
}

snapshot_prop_value() {
  snapshot_source="$1"
  snapshot_wanted="$2"
  [ -s "$snapshot_source" ] || return 1
  snapshot_line="$(awk -F '|' -v wanted="$snapshot_wanted" '$1 == wanted { print; exit }' "$snapshot_source")"
  [ -n "$snapshot_line" ] || return 1
  snapshot_tail="${snapshot_line#*|}"
  snapshot_present="${snapshot_tail%%|*}"
  snapshot_encoded="${snapshot_tail#*|}"
  [ "$snapshot_present" = '1' ] || return 1
  decode_b64 "$snapshot_encoded"
}

baseline_file_valid() {
  baseline_candidate="$1"
  [ -s "$baseline_candidate" ] || return 1
  for baseline_required_key in \
    ro.product.manufacturer ro.product.brand ro.product.marketname \
    ro.product.model ro.product.name; do
    baseline_required_value="$(snapshot_prop_value "$baseline_candidate" "$baseline_required_key")" || return 1
    [ -n "$baseline_required_value" ] || return 1
  done
}

trusted_baseline_file() {
  if baseline_file_valid "$STOCK_FILE"; then
    printf '%s' "$STOCK_FILE"
  elif baseline_file_valid "$RUNTIME_STOCK_FILE"; then
    printf '%s' "$RUNTIME_STOCK_FILE"
  else
    return 1
  fi
}

capture_runtime_stock() {
  [ "$(config_value enabled)" != '1' ] || return 1
  runtime_temp="$RUNTIME_STOCK_FILE.capture.$$"
  snapshot_props "$runtime_temp" || return 1
  if ! baseline_file_valid "$runtime_temp"; then
    rm -f "$runtime_temp"
    return 1
  fi
  mv -f "$runtime_temp" "$RUNTIME_STOCK_FILE" || return 1
  rm -f "$REBOOT_REQUIRED_FILE"
}

verify_snapshot_applied() {
  verify_source="$1"
  for verify_key in \
    ro.product.manufacturer ro.product.brand ro.product.marketname \
    ro.product.model ro.product.name; do
    verify_expected="$(snapshot_prop_value "$verify_source" "$verify_key")" || return 1
    verify_actual="$(getprop "$verify_key" 2>/dev/null)"
    [ "$verify_actual" = "$verify_expected" ] || return 1
  done
}

restore_pending_reboot() {
  [ "$(config_value enabled)" != '1' ] || return 1
  [ -f "$REBOOT_REQUIRED_FILE" ] && return 0
  pending_baseline="$(trusted_baseline_file 2>/dev/null)" || return 1
  verify_snapshot_applied "$pending_baseline" || return 0
  return 1
}

baseline_source() {
  if baseline_file_valid "$STOCK_FILE"; then
    printf '%s' firmware
  elif baseline_file_valid "$RUNTIME_STOCK_FILE"; then
    printf '%s' clean_boot
  else
    printf '%s' unavailable
  fi
}

baseline_prop_value() {
  baseline_key="$1"
  baseline_path="$(trusted_baseline_file)" || return 1
  snapshot_prop_value "$baseline_path" "$baseline_key"
}

restore_originals() {
  restore_baseline || return 1
  rm -f "$ORIGINAL_FILE"
}

restore_baseline() {
  restore_source="$(trusted_baseline_file)" || return 1
  restore_snapshot "$restore_source" || return 1
  verify_snapshot_applied "$restore_source"
}

recover_pending_transaction() {
  [ -f "$ROLLBACK_FILE" ] || return 0
  restore_snapshot "$ROLLBACK_FILE" || return 1
  rm -f "$ROLLBACK_FILE"
  [ "$(config_value enabled)" = '1' ] || rm -f "$ORIGINAL_FILE"
}

preset_line() {
  preset_id="$1"
  valid_id "$preset_id" || return 1
  awk -F '|' -v id="$preset_id" '$1 == id { print; exit }' $PRESETS_FILES
}

set_description() {
  # 集成版不改写模块描述（module.prop 由 HyperAdClean 共用），状态由 App 读取 status 展示
  return 0
}

apply_config() {
  [ -f "$CONFIG_FILE" ] || return 0
  enabled="$(config_value enabled)"
  mode="$(config_value mode)"
  [ "$enabled" = '1' ] || { set_description '未启用' '原机属性'; return 0; }

  case "$mode" in
    preset)
      preset_id="$(config_value preset_id)"
      line="$(preset_line "$preset_id")" || return 1
      [ -n "$line" ] || return 1
      old_ifs="$IFS"
      IFS='|'
      set -- $line
      IFS="$old_ifs"
      manufacturer="$2"
      brand="$3"
      marketname="$4"
      model="$5"
      product="$6"
      ;;
    custom)
      manufacturer="$(decode_b64 "$(config_value manufacturer_b64)")" || return 1
      brand="$(decode_b64 "$(config_value brand_b64)")" || return 1
      marketname="$(decode_b64 "$(config_value marketname_b64)")" || return 1
      model="$(decode_b64 "$(config_value model_b64)")" || return 1
      product="$(decode_b64 "$(config_value product_b64)")" || return 1
      [ -n "$marketname" ] && [ -n "$model" ] || return 1
      ;;
    *) return 1 ;;
  esac

  apply_values "$manufacturer" "$brand" "$marketname" "$model" "$product" || return 1
  set_description '已生效' "$marketname · $model"
}

apply_transaction() {
  tx_manufacturer="$1"
  tx_brand="$2"
  tx_marketname="$3"
  tx_model="$4"
  tx_product="$5"
  tx_enabled="$6"
  tx_mode="$7"
  tx_preset_id="$8"
  tx_manufacturer_b64="$9"
  shift 9
  tx_brand_b64="$1"
  tx_marketname_b64="$2"
  tx_model_b64="$3"
  tx_product_b64="$4"

  acquire_lock || return 5
  trap 'release_lock' EXIT
  trap 'release_lock; exit 130' HUP INT TERM
  recover_pending_transaction || return 6
  had_original=0
  [ -f "$ORIGINAL_FILE" ] && had_original=1
  rm -f "$ROLLBACK_FILE"
  snapshot_props "$ROLLBACK_FILE" || return 3
  save_originals || { rm -f "$ROLLBACK_FILE"; return 3; }

  # 恢复基线只用于清理上一个配置的可选字段；没有基线也必须允许设置机型。
  apply_baseline="$(trusted_baseline_file 2>/dev/null)" || apply_baseline=''
  if [ -n "$apply_baseline" ] && ! restore_snapshot "$apply_baseline"; then
    if restore_snapshot "$ROLLBACK_FILE"; then
      rm -f "$ROLLBACK_FILE"
      [ "$had_original" -eq 1 ] || rm -f "$ORIGINAL_FILE"
    fi
    return 4
  fi

  if ! apply_values "$tx_manufacturer" "$tx_brand" "$tx_marketname" "$tx_model" "$tx_product" ||
    ! verify_values_applied "$tx_manufacturer" "$tx_brand" "$tx_marketname" "$tx_model" "$tx_product"; then
    if restore_snapshot "$ROLLBACK_FILE"; then
      rm -f "$ROLLBACK_FILE"
      [ "$had_original" -eq 1 ] || rm -f "$ORIGINAL_FILE"
    fi
    return 4
  fi

  if ! write_config "$tx_enabled" "$tx_mode" "$tx_preset_id" \
    "$tx_manufacturer_b64" "$tx_brand_b64" "$tx_marketname_b64" "$tx_model_b64" "$tx_product_b64"; then
    if restore_snapshot "$ROLLBACK_FILE"; then
      rm -f "$ROLLBACK_FILE"
      [ "$had_original" -eq 1 ] || rm -f "$ORIGINAL_FILE"
    fi
    return 3
  fi

  rm -f "$ROLLBACK_FILE"
  rm -f "$REBOOT_REQUIRED_FILE"
  set_description '已生效' "$tx_marketname · $tx_model"
  trap - EXIT HUP INT TERM
  release_lock
  return 0
}

case "${1:-status}" in
  capture-stock)
    capture_stock_props || exit 3
    printf '%s\n' 'OK'
    ;;
  capture-runtime-stock)
    capture_runtime_stock || exit 3
    printf '%s\n' 'OK'
    ;;
  list)
    cat $PRESETS_FILES
    ;;
  status)
    enabled="$(config_value enabled)"
    mode="$(config_value mode)"
    preset_id="$(config_value preset_id)"
    printf 'enabled=%s\n' "${enabled:-0}"
    printf 'mode=%s\n' "${mode:-none}"
    printf 'preset_id=%s\n' "$preset_id"
    printf 'manufacturer_b64=%s\n' "$(config_value manufacturer_b64)"
    printf 'brand_b64=%s\n' "$(config_value brand_b64)"
    printf 'marketname_b64=%s\n' "$(config_value marketname_b64)"
    printf 'model_b64=%s\n' "$(config_value model_b64)"
    printf 'product_b64=%s\n' "$(config_value product_b64)"
    printf 'current_manufacturer=%s\n' "$(getprop ro.product.manufacturer 2>/dev/null)"
    printf 'current_brand=%s\n' "$(getprop ro.product.brand 2>/dev/null)"
    printf 'current_marketname=%s\n' "$(getprop ro.product.marketname 2>/dev/null)"
    printf 'current_model=%s\n' "$(getprop ro.product.model 2>/dev/null)"
    printf 'current_product=%s\n' "$(getprop ro.product.name 2>/dev/null)"
    printf 'module_version=%s\n' "$(file_value version "$MODULE_PROP")"
    printf 'baseline_source=%s\n' "$(baseline_source)"
    printf 'baseline_marketname=%s\n' "$(baseline_prop_value ro.product.marketname 2>/dev/null)"
    printf 'baseline_model=%s\n' "$(baseline_prop_value ro.product.model 2>/dev/null)"
    if restore_pending_reboot; then
      printf 'restore_pending_reboot=1\n'
    else
      printf 'restore_pending_reboot=0\n'
    fi
    ;;
  apply-preset)
    preset_id="${2:-}"
    line="$(preset_line "$preset_id")" || exit 2
    [ -n "$line" ] || exit 2
    old_ifs="$IFS"
    IFS='|'
    set -- $line
    IFS="$old_ifs"
    apply_transaction "$2" "$3" "$4" "$5" "$6" \
      1 preset "$1" '' '' '' '' '' || exit $?
    printf '%s\n' 'OK'
    ;;
  apply-custom)
    [ "$#" -eq 6 ] || exit 2
    manufacturer_b64="$2"
    brand_b64="$3"
    marketname_b64="$4"
    model_b64="$5"
    product_b64="$6"
    manufacturer="$(decode_b64 "$manufacturer_b64")" || exit 2
    brand="$(decode_b64 "$brand_b64")" || exit 2
    marketname="$(decode_b64 "$marketname_b64")" || exit 2
    model="$(decode_b64 "$model_b64")" || exit 2
    product="$(decode_b64 "$product_b64")" || exit 2
    [ -n "$marketname" ] && [ -n "$model" ] || exit 2
    apply_transaction "$manufacturer" "$brand" "$marketname" "$model" "$product" \
      1 custom '' "$manufacturer_b64" "$brand_b64" "$marketname_b64" \
      "$model_b64" "$product_b64" || exit $?
    printf '%s\n' 'OK'
    ;;
  disable)
    acquire_lock || exit 5
    trap 'release_lock' EXIT
    trap 'release_lock; exit 130' HUP INT TERM
    recover_pending_transaction || exit 6
    # 安装器所在命名空间可能看不到动态分区；恢复时在完整系统环境再现场读取一次。
    capture_stock_props >/dev/null 2>&1 || true
    pending_config="$STATE_DIR/disabled.config.$$"
    current_config="$CONFIG_FILE"
    CONFIG_FILE="$pending_config"
    write_config 0 none '' '' '' '' '' '' || exit 3
    CONFIG_FILE="$current_config"
    restore_result=reboot-required
    restore_source="$(trusted_baseline_file 2>/dev/null)" || restore_source=''
    if [ -n "$restore_source" ] && \
      restore_snapshot "$restore_source" && \
      verify_snapshot_applied "$restore_source"; then
      restore_result=immediate
    fi
    if ! mv -f "$pending_config" "$CONFIG_FILE"; then
      apply_config || true
      exit 3
    fi
    rm -f "$ORIGINAL_FILE" "$ROLLBACK_FILE"
    if [ "$restore_result" = immediate ]; then
      rm -f "$REBOOT_REQUIRED_FILE"
      capture_runtime_stock >/dev/null 2>&1 || true
      set_description '未启用' '原机属性'
    else
      : > "$REBOOT_REQUIRED_FILE"
      set_description '未启用' '等待重启恢复原机'
    fi
    trap - EXIT HUP INT TERM
    release_lock
    if [ "$restore_result" = immediate ]; then
      printf '%s\n' 'OK'
    else
      printf '%s\n' 'OK:reboot-required'
    fi
    ;;
  restart-framework)
    request_id="$(date +%s).$$"
    printf 'id=%s\nstate=pending\ncode=0\ntime=%s\n' \
      "$request_id" "$(date +%s)" > "$STATE_DIR/restart.status.tmp.$$" || exit 3
    mv -f "$STATE_DIR/restart.status.tmp.$$" "$STATE_DIR/restart.status" || exit 3
    nohup sh "$DM_DIR/restart-framework.sh" "$request_id" >/dev/null 2>&1 &
    printf 'OK:%s\n' "$request_id"
    ;;
  restart-status)
    request_id="${2:-}"
    valid_id "$request_id" || exit 2
    [ -f "$STATE_DIR/restart.status" ] || exit 2
    [ "$(file_value id "$STATE_DIR/restart.status")" = "$request_id" ] || exit 2
    cat "$STATE_DIR/restart.status"
    ;;
  boot)
    if [ "${EARLY_BOOT:-0}" = '1' ] && [ "$(config_value enabled)" != '1' ] && [ ! -f "$ROLLBACK_FILE" ]; then
      exit 0
    fi
    acquire_lock || exit 5
    trap 'release_lock' EXIT
    trap 'release_lock; exit 130' HUP INT TERM
    recover_pending_transaction || exit 6
    rm -f "$ROLLBACK_FILE"
    snapshot_props "$ROLLBACK_FILE" || exit 3
    if ! apply_config; then
      restore_snapshot "$ROLLBACK_FILE" || exit 6
      rm -f "$ROLLBACK_FILE"
      exit 4
    fi
    rm -f "$ROLLBACK_FILE"
    trap - EXIT HUP INT TERM
    release_lock
    ;;
  summary)
    enabled="$(config_value enabled)"
    if [ "$enabled" = '1' ]; then
      printf '状态：已生效\n'
    else
      printf '状态：未启用（默认不修改）\n'
    fi
    printf '市场名称：%s\n' "$(getprop ro.product.marketname 2>/dev/null)"
    printf '认证型号：%s\n' "$(getprop ro.product.model 2>/dev/null)"
    printf '产品名称：%s\n' "$(getprop ro.product.name 2>/dev/null)"
    ;;
  *)
    printf '用法：%s {list|status|capture-stock|capture-runtime-stock|apply-preset ID|apply-custom B64...|disable|restart-framework|restart-status ID|boot|summary}\n' "$0" >&2
    exit 2
    ;;
esac
