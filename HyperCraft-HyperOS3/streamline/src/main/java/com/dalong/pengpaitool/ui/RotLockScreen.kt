package com.dalong.pengpaitool.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dalong.pengpaitool.Mod
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RotLockScreen() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var s by remember { mutableStateOf(listOf<String>()) }
    var events by remember { mutableStateOf(listOf<List<String>>()) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun reload() {
        s = Mod.rows("rot-status").firstOrNull() ?: emptyList()
        events = Mod.rows("rot-log").filter { it.size >= 3 }.reversed()
    }
    LaunchedEffect(Unit) { reload(); loaded = true }
    fun run(cmd: String) = scope.launch { val r = Mod.result(cmd); reload(); scope.toast(snack, r.msg) }

    val on = s.getOrNull(0) == "1"
    val running = s.getOrNull(1) == "1"
    val locked = s.getOrNull(5) == "0" && s.getOrNull(6) == "0"

    Page(loaded = loaded) {
        item {
            Section("竖屏锁定", "关闭自动旋转并锁定为竖屏。控制中心的「自动旋转」或某些应用把它改回去时，按下面的间隔检查并强制改回。应用自己要求横屏（如视频全屏、游戏）不受影响。") {
                SwitchRow("开启竖屏锁定", if (on) (if (running) "运行中" else "未运行") else "已关闭", on) { run("rot-set enable ${if (it) 1 else 0}") }
                InfoRow("当前状态", if (locked) "已锁定竖屏" else "自动旋转=${s.getOrNull(5) ?: "-"}，方向=${s.getOrNull(6) ?: "-"}")
            }
        }
        item {
            Section("检查间隔") {
                FlowRow(Modifier.padding(vertical = 8.dp)) {
                    listOf("10" to "10 秒", "30" to "30 秒", "60" to "1 分钟", "300" to "5 分钟").forEach { (v, label) ->
                        FilterChip(selected = s.getOrNull(2) == v, onClick = { run("rot-set interval $v") }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        }
        item {
            Section("记录", trailing = "累计强制开启 ${s.getOrNull(3) ?: 0} 次") {
                if (events.isEmpty()) {
                    Text("暂无记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
                } else events.forEach { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row {
                            Text(e[1], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(e[0], fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(e[2], fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { run("rot-log-clear") }, enabled = events.isNotEmpty(), modifier = Modifier.padding(bottom = 4.dp)) { Text("清空记录") }
            }
        }
    }
}
