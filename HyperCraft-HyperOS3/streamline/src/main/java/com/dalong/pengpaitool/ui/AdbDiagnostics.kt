package com.dalong.pengpaitool.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dalong.pengpaitool.AdbAuthorization
import com.dalong.pengpaitool.Mod
import com.dalong.pengpaitool.shq
import kotlinx.coroutines.launch

private val GREEN = Color(0xFF34C759)
private val GRAY = Color(0xFFB0B5C0)

private data class PairedDevice(val name: String, val fingerprint: String, val connected: String)

/** 授权超时（毫秒）换成好读的时长 */
private fun fmtTimeout(v: String?): String {
    val ms = v?.toLongOrNull() ?: return "系统默认"
    if (ms == 0L) return "不自动过期"
    if (ms < 0) return "未知"
    val h = ms / 3_600_000.0
    return when {
        ms % 86_400_000L == 0L -> "${ms / 86_400_000L} 天"
        h >= 1 -> "%.1f 小时".format(h).replace(".0 ", " ")
        else -> "${ms / 60_000} 分钟"
    }
}

/** 诊断记录里的 key|value 换成中文，boot id 太长不显示 */
private val LOG_KEYS = mapOf(
    "wifi" to "无线", "usb" to "USB", "developer" to "开发者", "ip" to "IP", "port" to "端口",
    "tcp_connections" to "TCP", "adbd" to "adbd", "pid" to "PID", "timeout" to "超时",
)

private fun prettyLog(msg: String): String {
    if (!msg.startsWith("状态变化：")) return msg
    val parts = msg.removePrefix("状态变化：").trim().split(' ').mapNotNull { kv ->
        val k = kv.substringBefore('|'); val v = kv.substringAfter('|', "")
        val label = LOG_KEYS[k] ?: return@mapNotNull null
        val shown = when {
            v.isBlank() -> "-"
            k in setOf("wifi", "usb", "developer") -> if (v == "1") "开" else "关"
            k == "timeout" -> fmtTimeout(v)
            else -> v
        }
        "$label $shown"
    }
    return "状态变化：" + parts.joinToString(" · ")
}

@Composable
fun AdbDiagScreen() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val ctx = LocalContext.current
    var status by remember { mutableStateOf(emptyMap<String, String>()) }
    var auth by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var showLogs by remember { mutableStateOf(false) }

    suspend fun reload() {
        status = Mod.rows("adb-diag-status").filter { it.size >= 2 }
            .associate { it[0] to it.drop(1).joinToString("|") }
        auth = AdbAuthorization.run("list")
        logs = Mod.raw("adb-diag-log")
    }
    fun refresh() = scope.launch { busy = true; try { reload() } finally { busy = false } }
    LaunchedEffect(Unit) { try { reload() } finally { loaded = true } }

    val devices = auth.lines().map { it.split('|') }.filter { it.size >= 4 && it[0] == "DEVICE" }
        .map { PairedDevice(it[1], it[2], it[3]) }
    val authOk = auth.lines().any { it.startsWith("OK|adb|") }
    val wifiOn = status["wifi"] == "1"
    val tcp = status["tcp_connections"]?.toIntOrNull()
    val ip = status["ip"].orEmpty()
    val port = status["port"].orEmpty().takeIf { it.isNotBlank() && it != "0" }
    val addr = if (ip.isNotBlank() && port != null) "$ip:$port" else ""
    val logLines = logs.lines().filter { it.isNotBlank() }

    Page(loaded = loaded) {
        item {
            Section("连接状态") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (dot, title, sub) = when {
                        !wifiOn -> Triple(GRAY, "无线调试已关闭", "已配对的设备仍保留授权，重新打开即可连接")
                        tcp != null && tcp > 0 -> Triple(GREEN, "有设备连接中", "$tcp 个 TCP 连接（不代表都已通过授权）")
                        else -> Triple(MaterialTheme.colorScheme.primary, "已开启，等待连接", if (addr.isBlank()) "未连接 WLAN 或端口未就绪" else "电脑执行 adb connect 下方地址")
                    }
                    Box(Modifier.size(10.dp).background(dot, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { refresh() }, enabled = !busy) { Icon(Icons.Filled.Refresh, "刷新") }
                }
                if (wifiOn && addr.isNotBlank()) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                            .padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text("连接地址", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(addr, fontFamily = FontFamily.Monospace, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        }
                        IconButton(onClick = {
                            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("adb", addr))
                            scope.toast(snack, "已复制 $addr")
                        }) { Icon(Icons.Filled.ContentCopy, "复制") }
                    }
                }
                Divider()
                InfoRow("WLAN 地址", ip.ifBlank { "无" })
                InfoRow("TLS 端口", port ?: "未就绪")
                InfoRow("TCP 连接数", tcp?.toString() ?: "未知")
                InfoRow("adbd", when (status["adbd"]) {
                    "running" -> "运行中" + (status["pid"]?.takeIf { it.isNotBlank() }?.let { " · PID $it" } ?: "")
                    null, "" -> "未知"
                    else -> status["adbd"]!!
                })
                InfoRow("USB 调试", if (status["usb"] == "1") "开启" else "关闭")
                InfoRow("授权超时", fmtTimeout(status["timeout"]))
                Text("TCP 连接只说明有传输连接，不代表已完成 ADB 授权。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            }
        }
        item {
            Section("已配对设备", trailing = if (authOk) "${devices.size} 台" else null) {
                when {
                    !authOk -> Note("无法读取授权列表，请在开发者选项里用「系统配对页」查看。", warn = true)
                    devices.isEmpty() -> Text("还没有配对过的电脑", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
                    else -> devices.forEachIndexed { i, d ->
                        if (i > 0) Divider()
                        DeviceRow(d, enabled = !busy && Regex("[0-9A-Fa-f:]{16,128}").matches(d.fingerprint)) { confirmation = d.fingerprint }
                    }
                }
                if (authOk && devices.isNotEmpty()) {
                    Divider()
                    TextButton(onClick = { confirmation = "all" }, enabled = !busy, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("撤销全部调试授权", color = MaterialTheme.colorScheme.error)
                    }
                }
                Text("关闭无线调试不会删除授权；忘记设备后，那台电脑下次要重新配对。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        item {
            Section("诊断记录", "只记录状态变化，不会自动重开或保活；每 30 秒采样一次，可能漏掉很短的变化。") {
                SwitchRow("后台诊断记录", if (status["running"] == "1") "采集中 · 最近采样 ${status["checked"].orEmpty().ifBlank { "-" }}"
                    else "未运行（重启后恢复采集）", status["enabled"] == "1", enabled = !busy) { on ->
                    scope.launch {
                        busy = true
                        try { val r = Mod.result("adb-diag-set ${if (on) 1 else 0}"); reload(); scope.toast(snack, r.msg) } finally { busy = false }
                    }
                }
                Row(Modifier.padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { showLogs = true }) { Text("查看记录（${logLines.size}）") }
                    OutlinedButton(onClick = { confirmation = "logs" }, enabled = !busy && logLines.isNotEmpty()) { Text("清空") }
                }
            }
        }
    }

    confirmation?.let { target ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(when (target) { "logs" -> "清空诊断记录？"; "all" -> "撤销全部调试授权？"; else -> "忘记此设备？" }) },
            text = { Text(when (target) {
                "logs" -> "仅清空无线调试诊断记录，无法恢复。"
                "all" -> "可能同时影响 USB 调试；现有连接可能断开，下次需要重新授权或配对。"
                else -> "此设备的现有连接可能断开，下次需要重新配对。"
            }) },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                scope.launch {
                    busy = true
                    try {
                        if (target == "logs") {
                            scope.toast(snack, Mod.result("adb-diag-clear").msg)
                        } else {
                            val action = if (target == "all") "clear" else "unpair"
                            Mod.result("adb-diag-action $action-request")
                            val output = AdbAuthorization.run(if (target == "all") "clear" else "unpair ${shq(target)}")
                            val ok = output.lines().any { it.startsWith("OK|") }
                            Mod.result("adb-diag-action $action-${if (ok) "ok" else "error"}")
                            scope.toast(snack, output.lines().lastOrNull { it.startsWith("OK|") || it.startsWith("ERR|") }
                                ?.substringAfterLast('|') ?: "操作失败")
                        }
                        reload()
                    } finally { busy = false }
                }
            }) { Text("确认", color = if (target == "logs") Color.Unspecified else MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } })
    }

    if (showLogs) AlertDialog(onDismissRequest = { showLogs = false }, title = { Text("诊断记录") },
        text = {
            if (logLines.isEmpty()) Text("暂无记录")
            else SelectionContainer {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    logLines.asReversed().forEach { line ->
                        val time = line.take(19).takeIf { it.length == 19 && it[4] == '-' }
                        Column {
                            if (time != null) Text(time, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(prettyLog(if (time != null) line.drop(20) else line), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("无线调试诊断", logs))
            scope.toast(snack, "已复制，含本机网络地址，分享前请检查")
        }, enabled = logLines.isNotEmpty()) { Text("复制") } },
        dismissButton = { TextButton(onClick = { showLogs = false }) { Text("关闭") } })
}

@Composable
private fun DeviceRow(d: PairedDevice, enabled: Boolean, onForget: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Computer, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name.ifBlank { "未命名设备" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                when (d.connected) {
                    "true" -> Tag("已连接", Color(0xFF1E8E3E), Color(0xFFE3F5E8))
                    "false" -> Tag("未连接", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
                    else -> Tag("未知", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
                }
            }
            SelectionContainer {
                Text(d.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = onForget, enabled = enabled) { Text("忘记", color = if (enabled) MaterialTheme.colorScheme.error else Color.Unspecified) }
    }
}
