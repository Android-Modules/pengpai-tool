package com.dalong.pengpaitool.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dalong.pengpaitool.Mod
import kotlinx.coroutines.launch

/** 一行日志：log() 写入的格式是「MM-dd HH:mm:ss 内容」，开机早期系统时间未同步时是「-- 开机早期 内容」 */
private class LogLine(val day: String, val time: String, val msg: String)

private val STAMP = Regex("""^(\d\d)-(\d\d) (\d\d:\d\d:\d\d) (.*)$""")
private const val EARLY = "-- 开机早期 "

private fun parseLog(text: String): List<LogLine> = text.lines().filter { it.isNotBlank() }.map { l ->
    when {
        l.startsWith(EARLY) -> LogLine("", "开机早期", l.removePrefix(EARLY))
        else -> STAMP.find(l)?.groupValues?.let { LogLine("${it[1].toInt()}月${it[2].toInt()}日", it[3], it[4]) } ?: LogLine("", "", l)
    }
}.reversed()

@Composable
fun LogScreen() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val ctx = LocalContext.current
    var raw by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }

    suspend fun reload() { raw = Mod.raw("log 0") }
    LaunchedEffect(Unit) { reload() }

    val lines = remember(raw) { parseLog(raw ?: "") }
    // 按日期分组，保持最新在上；没有时间戳的行归到它上面最近的那一组
    val groups = remember(lines) {
        val out = mutableListOf<Pair<String, MutableList<LogLine>>>()
        lines.forEach { l ->
            if (out.isEmpty() || (l.day.isNotEmpty() && out.last().first != l.day)) out += l.day to mutableListOf()
            out.last().second += l
        }
        out
    }

    Page(loaded = raw != null) {
        item {
            Section("运行日志", "模块的操作记录和开机检查，最新的在最上面。文件超过 256KB 时自动只保留最近 500 行。", "${lines.size} 条") {
                Row(Modifier.padding(vertical = 8.dp)) {
                    OutlinedButton(onClick = { scope.launch { reload(); scope.toast(snack, "已刷新") } }) { Text("刷新") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("log", raw ?: ""))
                        scope.toast(snack, "已复制 ${lines.size} 条日志")
                    }, enabled = lines.isNotEmpty()) { Text("复制") }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { confirm = true }, enabled = lines.isNotEmpty(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) { Text("清空") }
                }
            }
        }
        if (lines.isEmpty()) item {
            Section("记录") {
                Text("日志是空的。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
        groups.forEach { (day, list) ->
            item {
                Section(day.ifEmpty { "未知日期" }, trailing = "${list.size} 条") {
                    SelectionContainer {
                        androidx.compose.foundation.layout.Column {
                            list.forEachIndexed { i, l ->
                                if (i > 0) Divider()
                                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                    Text(l.time, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(78.dp))
                                    Text(l.msg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("清空运行日志") },
        text = { Text("将删除全部 ${lines.size} 条记录，无法恢复。") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            scope.launch { val r = Mod.result("log-clear"); reload(); scope.toast(snack, r.msg) }
        }) { Text("清空", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
    )
}
