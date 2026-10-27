package com.dalong.pengpaitool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dalong.pengpaitool.Mod
import kotlinx.coroutines.launch

/** KB → 可读大小 */
fun fmtKb(kb: Long): String = when {
    kb >= 1024 * 1024 -> "%.2f GB".format(kb / 1048576.0)
    kb >= 1024 -> "%.1f MB".format(kb / 1024.0)
    else -> "$kb KB"
}

private data class WlogFile(val path: String, val kb: Long, val time: String, val open: Boolean)
private data class WlogRecord(val time: String, val type: String, val kb: Long, val files: Int, val reason: String, val detail: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WlanLogScreen() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var s by remember { mutableStateOf(listOf<String>()) }
    var files by remember { mutableStateOf(listOf<WlogFile>()) }
    var records by remember { mutableStateOf(listOf<WlogRecord>()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var askClear by remember { mutableStateOf(false) }

    suspend fun reload() {
        s = Mod.rows("wlog-status").firstOrNull() ?: emptyList()
        files = Mod.rows("wlog-files").mapNotNull { r ->
            if (r.size < 4) null else WlogFile(r[0], r[1].toLongOrNull() ?: 0, r[2], r[3] == "1")
        }.sortedByDescending { it.kb }
        records = Mod.rows("wlog-records").mapNotNull { r ->
            if (r.size < 6) null else WlogRecord(r[0], r[1], r[2].toLongOrNull() ?: 0, r[3].toIntOrNull() ?: 0, r[4], r[5])
        }.reversed()
    }
    LaunchedEffect(Unit) { reload(); loaded = true }
    fun run(cmd: String) = scope.launch {
        busy = true
        val r = Mod.result(cmd)
        reload(); busy = false
        scope.toast(snack, r.msg)
    }

    val on = s.getOrNull(0) == "1"
    val running = s.getOrNull(1) == "1"
    val block = s.getOrNull(4) == "1"
    val capturing = s.getOrNull(10).orEmpty().split(',').filter { it.isNotBlank() }
    val sizeKb = s.getOrNull(9)?.toLongOrNull() ?: 0

    Page(loaded = loaded) {
        if (capturing.isNotEmpty()) item {
            Banner("检测到正在运行的调试抓包：${capturing.joinToString("、")}。" +
                if (block) "守护会在一分钟内停止它。" else "打开下方「拦截调试抓包」可以立即停止。", warn = true)
        }
        item {
            Section("当前状态", "/data/vendor/wlan_logs 是 WLAN 芯片的调试日志目录，由 cnss_diag（固件日志）、tcpdump / sniffer（抓包）写入。正常零售系统不开启，但云控可能远程打开全量抓包，循环写入几百 MB，持续耗电、占存储。") {
                InfoRow("目录占用", fmtKb(sizeKb))
                InfoRow("调试抓包", if (capturing.isEmpty()) "未运行" else capturing.joinToString("、"))
                InfoRow("上次清理", s.getOrNull(8)?.takeIf { it.isNotBlank() } ?: "暂无")
                if (files.isNotEmpty()) {
                    Divider()
                    Column(Modifier.padding(vertical = 6.dp)) {
                        files.forEach { f ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(f.path.substringAfterLast('/'), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (f.open) {
                                    Tag("使用中", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(fmtKb(f.kb), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                            }
                        }
                    }
                    Text("「使用中」的 *_current.bin 是 WLAN 驱动常驻进程 cnss-daemon 的内存日志（最多约 60MB），删除不释放空间，清理时会跳过；正在写的抓包文件会被截断。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { run("wlog-clean") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("立即清理") }
                    OutlinedButton(onClick = { scope.launch { reload() } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("刷新") }
                }
            }
        }
        item {
            Section("定时清理") {
                SwitchRow("开启定时清理", if (on) (if (running) "运行中" else "未运行") else "已关闭", on) { run("wlog-set enable ${if (it) 1 else 0}") }
                Text("清理间隔", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                FlowRow(Modifier.padding(vertical = 6.dp)) {
                    listOf("1", "3", "6", "12", "24").forEach { v ->
                        FilterChip(selected = s.getOrNull(2) == v, onClick = { run("wlog-set interval $v") }, label = { Text("$v 小时") }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                Text("超过多大立即清理（每 5 分钟检查一次）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(Modifier.padding(vertical = 6.dp)) {
                    listOf("0", "50", "100", "200", "500").forEach { v ->
                        FilterChip(selected = s.getOrNull(3) == v, onClick = { run("wlog-set threshold $v") },
                            label = { Text(if (v == "0") "不检查" else "$v MB") }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        }
        item {
            Section("拦截调试抓包") {
                SwitchRow("拦截调试抓包", "每分钟检查一次，发现 cnss_diag / tcpdump / sniffer 被启动（包括云控下发）就停掉，并把开关属性改回 false。不影响 WLAN 正常使用", block) {
                    run("wlog-set block ${if (it) 1 else 0}")
                }
            }
        }
        item {
            val totalKb = s.getOrNull(7)?.toLongOrNull() ?: 0
            Section("清理记录", trailing = "累计 ${s.getOrNull(6) ?: 0} 次 · 释放 ${fmtKb(totalKb)}") {
                Text("保留最近", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                FlowRow(Modifier.padding(vertical = 6.dp)) {
                    listOf("50", "100", "200", "500").forEach { v ->
                        FilterChip(selected = s.getOrNull(5) == v, onClick = { run("wlog-set keep $v") }, label = { Text("$v 条") }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                if (records.isEmpty()) {
                    Text("暂无记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
                } else {
                    Divider()
                    records.forEach { r ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (r.type == "block") Tag("拦截", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                                else Tag(r.reason, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
                                Spacer(Modifier.width(8.dp))
                                Text(if (r.type == "block") "停止调试抓包" else "释放 ${fmtKb(r.kb)} · ${r.files} 个文件",
                                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(r.time, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (r.detail.isNotBlank()) Text(r.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
                TextButton(onClick = { askClear = true }, enabled = records.isNotEmpty() || totalKb > 0, modifier = Modifier.padding(bottom = 4.dp)) { Text("清空记录") }
            }
        }
    }
    if (askClear) AlertDialog(
        onDismissRequest = { askClear = false },
        title = { Text("清空清理记录") },
        text = { Text("清空全部清理与拦截记录，累计次数和释放大小归零。运行日志中的记录不受影响。") },
        confirmButton = { TextButton(onClick = { askClear = false; run("wlog-records-clear") }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { askClear = false }) { Text("取消") } },
    )
    if (busy) BusyOverlay()
}
