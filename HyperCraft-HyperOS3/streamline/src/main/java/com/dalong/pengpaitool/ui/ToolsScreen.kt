package com.dalong.pengpaitool.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dalong.pengpaitool.Mod
import com.dalong.pengpaitool.Root
import com.dalong.pengpaitool.Sys
import com.dalong.pengpaitool.shq
import com.dalong.pengpaitool.startPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/** 已在 HyperOS 3 上确认存在的隐藏/原生设置页 */
private val SHORTCUTS = listOf(
    Triple("通知使用权", "哪些应用可以读取通知", "-n com.android.settings/.Settings\$NotificationAccessSettingsActivity"),
    Triple("通知日志", "查看所有应用发过的通知", "-n com.android.settings/.Settings\$NotificationStationActivity"),
    Triple("通知历史", "近期通知和已延后的通知", "-n com.android.settings/.notification.history.NotificationHistoryActivity"),
    Triple("闪光灯通知", "来通知时闪光灯或屏幕闪烁", "-n com.android.settings/.Settings\$FlashNotificationsActivity"),
    Triple("原生护眼", "Android 原生夜间模式", "-n com.android.settings/.Settings\$NightDisplaySettingsActivity"),
    Triple("显示大小", "Android 原生显示大小调整", "-n com.android.settings/.display.ScreenZoomActivity"),
    Triple("电池优化", "Android 原生电池优化列表", "-a android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"),
    Triple("省电模式", "Android 原生省电模式", "-n com.android.settings/.Settings\$BatterySaverSettingsActivity"),
    Triple("电池用量", "Android 原生耗电统计", "-n com.android.settings/.Settings\$PowerUsageSummaryActivity"),
    Triple("内存使用", "各应用平均内存占用", "-n com.android.settings/.Settings\$MemorySettingsActivity"),
    Triple("使用情况统计", "应用使用时长记录", "-n com.android.settings/.UsageStatsActivity"),
    Triple("蜂窝网络调试", "信号、网络类型等调试信息", "-n com.android.phone/.settings.RadioInfo"),
    Triple("应用通话录音", "第三方应用语音通话录音", "-n com.miui.audiomonitor/.VoipRecordSettingsActivity"),
)

private data class Wifi(val ssid: String, val psk: String?)

@Composable
fun ToolsScreen(part: String, open: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val ctx = LocalContext.current
    var dev by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var info by remember { mutableStateOf(mapOf<String, String>()) }
    var dir by remember { mutableStateOf("") }
    var wifi by remember { mutableStateOf<List<Wifi>?>(null) }
    var adbAddr by remember { mutableStateOf("") }
    var pair by remember { mutableStateOf<AdbPairingInfo?>(null) }
    var pairingRequested by remember { mutableStateOf(false) }
    var refreshingConnection by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(part == "shortcuts") }
    var wifiLoading by remember { mutableStateOf(false) }
    var wifiError by remember { mutableStateOf<String?>(null) }
    var writing by remember { mutableStateOf(false) }

    suspend fun reload() {
        if (part == "dev") {
            dev = mapOf(
                "dev" to (Sys.get("global", "development_settings_enabled") == "1"),
                "adb" to (Sys.get("global", "adb_enabled") == "1"),
                "adbwifi" to (Sys.get("global", "adb_wifi_enabled") == "1"),
                "install" to (Sys.prop("persist.security.adbinstall") == "1"),
                "input" to (Sys.prop("persist.security.adbinput") == "1"),
            )
            // 无线调试端口每次开启都会变化，关闭时无需查询网络。
            adbAddr = if (dev["adbwifi"] == true)
                AdbEndpoint.parse(Mod.raw("adb-connect-address"))?.address.orEmpty() else ""
        }
        if (part == "backup") {
            info = Mod.info()
            dir = info["backup_dir"] ?: ""
        }
    }
    suspend fun reloadWifi() {
        if (wifiLoading) return
        wifiLoading = true
        wifiError = null
        try { wifi = loadWifi() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            wifi = null
            wifiError = "无法读取已保存的网络，请检查 Root 权限后重试。"
        } finally { wifiLoading = false }
    }
    LaunchedEffect(part, com.dalong.pengpaitool.Access.root) {
        if (com.dalong.pengpaitool.Access.root && part != "shortcuts") {
            if (part == "wifi") {
                loaded = true
                reloadWifi()
            } else reload()
        }
        loaded = true
    }
    fun updateSetting(key: String, value: String, property: Boolean = false) = scope.launch {
        if (writing) return@launch
        writing = true
        try {
            val applied = if (property) writeProperty(key, value) else writeSetting("global", key, value)
            reload()
            if (!applied) scope.toast(snack, "系统未接受此修改，请检查 Root 权限和系统支持情况。")
        } finally { writing = false }
    }

    Page(loaded = loaded) {
        if (part == "dev") {
            item {
                Section("开发者选项", "无需插入 SIM 卡或登录账号。") {
                    SwitchRow("开发者选项", null, dev["dev"] == true, enabled = !writing) { v -> updateSetting("development_settings_enabled", if (v) "1" else "0") }
                    Divider()
                    SwitchRow("USB 调试", "关闭会断开当前 USB 调试连接", dev["adb"] == true, enabled = !writing) { v -> updateSetting("adb_enabled", if (v) "1" else "0") }
                    Divider()
                    SwitchRow("USB 安装", "允许通过 USB 安装应用", dev["install"] == true, enabled = !writing) { v -> updateSetting("persist.security.adbinstall", if (v) "1" else "0", property = true) }
                    Divider()
                    SwitchRow("USB 调试（安全设置）", "允许通过 USB 调试修改权限和模拟点击", dev["input"] == true, enabled = !writing) { v -> updateSetting("persist.security.adbinput", if (v) "1" else "0", property = true) }
                }
            }
            item {
                Section("无线调试", "首次连接先配对，再连接；手机与电脑需在同一 WLAN。") {
                    SwitchRow("无线调试", "关闭会断开无线 ADB 连接", dev["adbwifi"] == true, enabled = !writing && !pairingRequested) { v -> scope.launch {
                        if (writing) return@launch
                        writing = true
                        try {
                            Mod.result("adb-diag-action ${if (v) "wifi-on" else "wifi-off"}")
                            val applied = writeSetting("global", "adb_wifi_enabled", if (v) "1" else "0")
                            kotlinx.coroutines.delay(1500); reload()
                            if (!applied) scope.toast(snack, "系统未接受无线调试开关，请检查 Root 权限和 WLAN 连接。")
                        } finally { writing = false }
                    } }
                    if (dev["adbwifi"] == true) {
                        Divider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("连接地址", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer {
                                    Text(adbAddr.ifBlank { "WLAN 地址或连接端口未就绪" }, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                }
                            }
                            IconButton(enabled = adbAddr.isNotBlank(), onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("adb connect", "adb connect $adbAddr"))
                                scope.toast(snack, "已复制连接命令")
                            }) { Icon(Icons.Filled.ContentCopy, "复制连接命令") }
                        }
                        FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalButton(enabled = !writing && !pairingRequested, onClick = { pairingRequested = true }) {
                                Text(if (pairingRequested) "正在生成…" else "生成配对码", maxLines = 1, softWrap = false)
                            }
                            OutlinedButton(enabled = !writing && !pairingRequested && !refreshingConnection, onClick = { scope.launch {
                                if (refreshingConnection) return@launch
                                refreshingConnection = true
                                try { reload() } finally { refreshingConnection = false }
                            } }) {
                                Text(if (refreshingConnection) "正在刷新…" else "刷新地址", maxLines = 1, softWrap = false)
                            }
                        }
                        Text("配对码与配对端口只在弹窗打开期间有效。已配对电脑可直接复制连接命令。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    NavRow("系统无线调试", "系统配对页与设备授权") {
                        scope.launch { startPage(ctx, "-n com.android.settings/.SubSettings --es :settings:show_fragment com.android.settings.development.WirelessDebuggingFragment")?.let { scope.toast(snack, it) } }
                    }
                    Divider()
                    NavRow("无线调试诊断", "连接状态、已配对设备与诊断记录") { open("adbdiag") }
                }
            }
        }
        if (part == "wifi") item {
            Section("WLAN 密码", "查看本机保存过的 WLAN 密码。") {
                if (wifiLoading) {
                    LoadingHint("正在读取已保存的网络…", Modifier.fillMaxWidth().padding(vertical = 24.dp))
                } else if (wifi == null) {
                    wifiError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                    FilledTonalButton(onClick = { scope.launch { reloadWifi() } }, modifier = Modifier.padding(top = 6.dp)) { Text("重新读取") }
                } else {
                    if (wifi!!.isEmpty()) Text("没有读到保存的网络", style = MaterialTheme.typography.bodyMedium)
                    wifi!!.forEachIndexed { i, w ->
                        if (i > 0) Divider()
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            SelectionContainer(Modifier.weight(1f).padding(top = 8.dp)) {
                                androidx.compose.foundation.layout.Column {
                                    Text(w.ssid, style = MaterialTheme.typography.bodyLarge)
                                    Text(w.psk ?: "开放网络 / 企业认证", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (w.psk != null) IconButton(onClick = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("wifi", w.psk))
                                scope.toast(snack, "已复制 ${w.ssid} 的密码")
                            }) { Icon(Icons.Filled.ContentCopy, "复制") }
                        }
                    }
                }
            }
        }
        if (part == "shortcuts") item {
            Section("隐藏设置入口", "直接打开系统里藏起来的原生设置页。") {
                SHORTCUTS.forEachIndexed { i, (t, d, args) ->
                    if (i > 0) Divider()
                    ClickRow(t, d, onClick = { scope.launch { startPage(ctx, args)?.let { scope.toast(snack, it) } } }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                }
            }
        }
        if (part == "backup") item {
            Section("备份与模块设置", "处理记录、原始状态和 APK 备份存放在这里，模块数据丢了也能从这里导入。", info["backup_size"]?.takeIf { it.isNotBlank() }) {
                Row(Modifier.padding(top = 6.dp)) {
                    OutlinedTextField(dir, { dir = it }, singleLine = true, label = { Text("备份目录") }, modifier = Modifier.weight(1f))
                }
                FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = { scope.launch {
                        val r = Mod.result("setconf backup_dir ${shq(dir.trim())}"); reload(); scope.toast(snack, r.msg)
                    } }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) { Text("保存目录", maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { scope.launch { val r = Mod.result("sync"); scope.toast(snack, r.msg) } },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) { Text("立即同步", maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { scope.launch { val r = Mod.result("import"); scope.toast(snack, r.msg) } },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) { Text("导入记录", maxLines = 1, softWrap = false) }
                }
                Divider()
                listOf(
                    Triple("backup_apk", "处理前备份 APK", "恢复失败时用来重装"),
                    Triple("keep_on_boot", "开机自动保持", "系统更新或云控把组件、开关改回来时，开机后按记录重新应用"),
                    Triple("restore_on_uninstall", "卸载模块时全部恢复", "卸载本应用后，下次开机恢复有记录的改动并停用后台；备份与记录保留"),
                ).forEach { (k, t, d) ->
                    SwitchRow(t, d, info[k] == "1") { v -> scope.launch { val r = Mod.result("setconf $k ${if (v) 1 else 0}"); reload(); scope.toast(snack, r.msg) } }
                }
            }
        }
    }

    // 清理从启动前就建立：启动过程中切页也不能遗留系统配对服务。
    LaunchedEffect(pairingRequested) {
        if (pairingRequested) try {
            PairingSession.run(stop = { Mod.result("adb-pair-stop") }) {
                val r = Mod.rows("adb-pair-start").firstOrNull() ?: emptyList()
                val parsed = AdbPairingInfo.parse(r)
                if (parsed != null) {
                    pair = parsed
                    reload()
                    awaitCancellation()
                } else scope.toast(snack, if (r.firstOrNull() == "ERR") r.getOrNull(2) ?: "启动配对失败" else "配对信息不完整，请重试或使用系统配对页")
            }
        } finally {
            pair = null
            pairingRequested = false
        }
    }
    pair?.let { p ->
        AdbPairingDialog(info = p, refreshing = refreshingConnection,
            onRefresh = {
                scope.launch {
                    if (refreshingConnection) return@launch
                    refreshingConnection = true
                    try {
                        val address = Mod.raw("adb-connect-address").trim()
                        if (pair === p) pair = p.withConnection(address)
                        adbAddr = AdbEndpoint.parse(address)?.address.orEmpty()
                    } finally { refreshingConnection = false }
                }
            },
            onCopy = { label, value ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(label, value))
                scope.toast(snack, "已复制$label")
            },
            onDismiss = { pairingRequested = false },
        )
    }
    if (writing) BusyOverlay()
}

private suspend fun loadWifi(): List<Wifi> {
    val result = Root.exec("cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml")
    check(result.ok && !result.truncated) { "Saved WLAN networks could not be read" }
    val xml = result.out
    fun unesc(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    fun field(block: String, name: String) = Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.let { unesc(it).trim('"') }
    return xml.split("<WifiConfiguration>").drop(1).mapNotNull { b ->
        val ssid = field(b, "SSID") ?: return@mapNotNull null
        Wifi(ssid, field(b, "PreSharedKey"))
    }.distinctBy { it.ssid }
}
