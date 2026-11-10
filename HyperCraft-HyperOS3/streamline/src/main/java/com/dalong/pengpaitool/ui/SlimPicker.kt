package com.dalong.pengpaitool.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dalong.pengpaitool.Mod

/**
 * 精简页的「选择应用」弹窗：用户应用 / 系统应用分开（默认用户应用），
 * 已冻结和受保护的核心组件不可选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlimPickerDialog(exclude: Set<String>, onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var protected by remember { mutableStateOf(setOf<String>()) }
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        protected = Mod.raw("protected").lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        apps = loadAllApps(ctx)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("选择要精简的应用") }) },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.weight(1f))
                        Button(enabled = picked.isNotEmpty(), onClick = { onConfirm(picked.toList()) }) { Text("加入精简列表 (${picked.size})") }
                    }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                val all = apps ?: emptyList()
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    listOf("用户应用", "系统应用").forEachIndexed { i, t ->
                        SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                            Text("$t ${if (apps == null) "" else all.count { it.system == (i == 1) }}")
                        }
                    }
                }
                Text(
                    if (tab == 0) "第三方应用建议用冻结，卸载后需从备份 APK 恢复。" else "包含没有桌面图标的系统组件。不认识的组件不要动，核心组件已锁定不可选。",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tab == 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("搜索应用名或包名") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                val list = apps?.filter { it.system == (tab == 1) && (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)) }
                if (list == null) LoadingHint("正在读取应用列表…")
                else LazyColumn(Modifier.padding(horizontal = 8.dp)) {
                    items(list, key = { it.pkg }) { a ->
                        val lock = when {
                            a.pkg in protected -> "受保护"
                            !a.enabled -> "已冻结"
                            a.pkg in exclude -> "已在列表"
                            else -> null
                        }
                        val on = a.pkg in picked
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = lock == null) { if (on) picked.remove(a.pkg) else picked.add(a.pkg) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(a.pkg)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(a.label, style = MaterialTheme.typography.bodyLarge,
                                        color = if (lock == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (lock != null) {
                                        Spacer(Modifier.width(6.dp))
                                        val cs = MaterialTheme.colorScheme
                                        Tag(lock, cs.onSurfaceVariant, cs.surfaceContainerHighest)
                                    }
                                }
                                Mono(a.pkg)
                            }
                            Checkbox(checked = on, enabled = lock == null, onCheckedChange = { if (it) picked.add(a.pkg) else picked.remove(a.pkg) })
                        }
                    }
                }
            }
        }
    }
}
