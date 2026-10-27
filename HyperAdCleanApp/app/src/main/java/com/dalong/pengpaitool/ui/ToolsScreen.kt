package com.dalong.pengpaitool.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.dalong.pengpaitool.BuildInfo
import com.dalong.pengpaitool.Mod
import com.dalong.pengpaitool.Root
import com.dalong.pengpaitool.Sys
import com.dalong.pengpaitool.shq
import com.dalong.pengpaitool.startPage
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
    var modVer by remember { mutableStateOf("") }
    var adbAddr by remember { mutableStateOf("") }
    var pair by remember { mutableStateOf<List<String>?>(null) }
    var loaded by remember { mutableStateOf(part == "shortcuts") }
    var wifiLoading by remember { mutableStateOf(false) }

    suspend fun reload() {
        dev = mapOf(
            "dev" to (Sys.get("global", "development_settings_enabled") == "1"),
            "adb" to (Sys.get("global", "adb_enabled") == "1"),
            "adbwifi" to (Sys.get("global", "adb_wifi_enabled") == "1"),
            "install" to (Sys.prop("persist.security.adbinstall") == "1"),
            "input" to (Sys.prop("persist.security.adbinput") == "1"),
        )
        info = Mod.info()
        dir = info["backup_dir"] ?: ""
        modVer = Root.exec("grep ^version= ${Mod.DIR}/module.prop | cut -d= -f2").out.trim()
        // 无线调试的连接地址：WLAN IP + adbd 的 TLS 端口（每次开启都会变）
        adbAddr = Root.exec("ip=\$(ip -4 addr show wlan0 | grep -o 'inet [0-9.]*' | cut -d' ' -f2); port=\$(getprop service.adb.tls.port); [ -n \"\$ip\" ] && [ -n \"\$port\" ] && [ \"\$port\" != 0 ] && echo \$ip:\$port").out.trim()
    }
    LaunchedEffect(Unit) { reload(); loaded = true }

    Page(loaded = loaded) {
        if (part == "dev") item {
            Section("开发者选项", "不插卡、不登录也能直接打开。") {
                SwitchRow("开发者选项", null, dev["dev"] == true) { v -> scope.launch { Sys.put("global", "development_settings_enabled", if (v) "1" else "0"); reload() } }
                Divider()
                SwitchRow("USB 调试", "关闭会断开当前的 adb 连接", dev["adb"] == true) { v -> scope.launch { Sys.put("global", "adb_enabled", if (v) "1" else "0"); reload() } }
                Divider()
                SwitchRow("无线调试", null, dev["adbwifi"] == true) { v -> scope.launch {
                    Mod.result("adb-diag-action ${if (v) "wifi-on" else "wifi-off"}")
                    Sys.put("global", "adb_wifi_enabled", if (v) "1" else "0")
                    kotlinx.coroutines.delay(1500); reload()
                } }
                if (dev["adbwifi"] == true) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("连接地址", style = MaterialTheme.typography.bodyMedium)
                            Text(adbAddr.ifBlank { "未连接 WLAN 或端口未就绪" }, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (adbAddr.isNotBlank()) IconButton(onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("adb", adbAddr))
                            scope.toast(snack, "已复制 $adbAddr")
                        }) { Icon(Icons.Filled.ContentCopy, "复制") }
                    }
                    Row(Modifier.padding(vertical = 4.dp)) {
                        FilledTonalButton(onClick = { scope.launch {
                            val r = Mod.rows("adb-pair-start").firstOrNull() ?: emptyList()
                            if (r.getOrNull(0) == "OK") pair = r else scope.toast(snack, r.getOrNull(2) ?: "启动配对失败")
                            reload()
                        } }) { Text("生成配对码") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { scope.launch {
                            Root.exec("am start -n com.android.settings/.SubSettings --es :settings:show_fragment com.android.settings.development.WirelessDebuggingFragment")
                        } }) { Text("系统配对页") }
                    }
                    Text("电脑首次连接需要配对：点「生成配对码」，在电脑上执行弹窗里的 adb pair 命令即可。已配对过的电脑直接 adb connect 上面的连接地址。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                }
                NavRow("无线调试诊断", "连接状态、已配对设备、诊断记录") { open("adbdiag") }
                Divider()
                SwitchRow("USB 安装", "允许通过 USB 安装应用（小米默认需要登录账号才能开）", dev["install"] == true) { v -> scope.launch { Sys.setProp("persist.security.adbinstall", if (v) "1" else "0"); reload() } }
                Divider()
                SwitchRow("USB 调试（安全设置）", "允许通过 USB 调试修改权限和模拟点击", dev["input"] == true) { v -> scope.launch { Sys.setProp("persist.security.adbinput", if (v) "1" else "0"); reload() } }
            }
        }
        if (part == "wifi") item {
            Section("WLAN 密码", "查看本机保存过的 WLAN 密码。") {
                if (wifiLoading) {
                    LoadingHint("正在读取已保存的网络…", Modifier.fillMaxWidth().padding(vertical = 24.dp))
                } else if (wifi == null) {
                    FilledTonalButton(onClick = { scope.launch { wifiLoading = true; wifi = loadWifi(); wifiLoading = false } }, modifier = Modifier.padding(top = 6.dp)) { Text("读取已保存的网络") }
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
                Row(Modifier.padding(top = 8.dp)) {
                    FilledTonalButton(onClick = { scope.launch {
                        val r = Mod.result("setconf backup_dir ${shq(dir.trim())}"); reload(); scope.toast(snack, r.msg)
                    } }) { Text("保存目录") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { scope.launch { val r = Mod.result("sync"); scope.toast(snack, r.msg) } }) { Text("立即同步") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { scope.launch { val r = Mod.result("import"); scope.toast(snack, r.msg) } }) { Text("导入记录") }
                }
                Divider()
                listOf(
                    Triple("backup_apk", "处理前备份 APK", "恢复失败时用来重装"),
                    Triple("keep_on_boot", "开机自动保持", "系统更新或云控把组件、开关改回来时，开机后按记录重新应用"),
                    Triple("restore_on_uninstall", "卸载模块时全部恢复", "删除模块后下次开机自动还原所有改动，并卸载本应用"),
                ).forEach { (k, t, d) ->
                    SwitchRow(t, d, info[k] == "1") { v -> scope.launch { val r = Mod.result("setconf $k ${if (v) 1 else 0}"); reload(); scope.toast(snack, r.msg) } }
                }
            }
        }
        if (part == "about") item {
            Section("关于") {
                InfoRow("机型", info["model"] ?: android.os.Build.MODEL)
                InfoRow("系统", "${info["os"] ?: android.os.Build.VERSION.INCREMENTAL} · Android ${info["android"] ?: android.os.Build.VERSION.RELEASE}")
                InfoRow("模块版本", modVer.ifBlank { "-" })
                InfoRow("应用版本", BuildInfo.versionName(ctx))
            }
        }
    }

    // 配对码弹窗：OK|配对码|IP|配对端口|连接端口；关闭弹窗即停止配对
    pair?.let { p ->
        val cmd = "adb pair ${p[2]}:${p[3]} ${p[1]}"
        AlertDialog(onDismissRequest = {}, title = { Text("无线调试配对") }, text = {
            Column {
                Text("配对码", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(p[1].chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                InfoRow("配对地址", "${p[2]}:${p[3]}")
                InfoRow("连接地址", "${p[2]}:${p[4]}")
                Text("在电脑上执行：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                SelectionContainer { Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                Text("配对成功后执行 adb connect ${p[2]}:${p[4]}。配对码只在这个弹窗打开时有效。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }, confirmButton = { TextButton(onClick = {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adb pair", cmd)); scope.toast(snack, "已复制配对命令")
        }) { Text("复制命令") } }, dismissButton = { TextButton(onClick = {
            pair = null; scope.launch { Mod.result("adb-pair-stop") }
        }) { Text("完成") } })
    }
}

private suspend fun loadWifi(): List<Wifi> {
    val xml = Root.exec("cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml").out
    fun unesc(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    fun field(block: String, name: String) = Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.let { unesc(it).trim('"') }
    return xml.split("<WifiConfiguration>").drop(1).mapNotNull { b ->
        val ssid = field(b, "SSID") ?: return@mapNotNull null
        Wifi(ssid, field(b, "PreSharedKey"))
    }.distinctBy { it.ssid }
}
