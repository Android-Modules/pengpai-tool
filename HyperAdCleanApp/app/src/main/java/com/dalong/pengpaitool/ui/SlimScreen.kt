package com.dalong.pengpaitool.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dalong.pengpaitool.CATALOG
import com.dalong.pengpaitool.Level
import com.dalong.pengpaitool.Mod
import com.dalong.pengpaitool.labelOf
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val STATE_TXT = mapOf("e" to "运行中", "d" to "已冻结", "u" to "已卸载", "x" to "本机没有")
private val MODE_TXT = mapOf("disable" to "冻结", "uninstall" to "卸载")

/** 自定义列表里每个应用的展示信息；system 为 null 表示本机已没有这个包 */
private data class CustomInfo(val label: String, val system: Boolean?)

private fun customInfo(pm: PackageManager, pkg: String): CustomInfo = runCatching {
    val ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES)
    CustomInfo(pm.getApplicationLabel(ai).toString(), ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
}.getOrDefault(CustomInfo(pkg, null))

@Composable
fun SlimScreen() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val prefs = remember { ctx.getSharedPreferences("slim", Context.MODE_PRIVATE) }

    var status by remember { mutableStateOf<Map<String, Mod.PkgState>>(emptyMap()) }
    var ota by remember { mutableStateOf(listOf<String>()) }
    val selected = remember { mutableStateListOf<String>() }
    val custom = remember {
        mutableStateListOf<String>().apply { addAll(prefs.getString("custom", "")!!.split(',').filter { Mod.PKG_RE.matches(it) }) }
    }
    // 默认只展开前两类（广告、统计），其余折叠，页面不至于太长
    val expanded = remember { mutableStateMapOf<String, Boolean>().apply { CATALOG.take(2).forEach { put(it.title, true) } } }
    var view by remember { mutableStateOf(0) }
    var confirmMode by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<Pair<String, List<Mod.Result>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    fun saveCustom() = prefs.edit().putString("custom", custom.joinToString(",")).apply()
    val customInfos = remember(custom.toList()) { custom.associateWith { customInfo(pm, it) } }
    val catalogItems = CATALOG.flatMap { it.items }
    val levelOf = catalogItems.associate { it.pkg to it.level }

    suspend fun reload() {
        // 直接读 CATALOG + custom 状态列表，不能用组合时捕获的局部值（刚加入的应用还不在里面）
        status = Mod.status(CATALOG.flatMap { c -> c.items.map { it.pkg } } + custom)
        ota = Mod.rows("ota-status").firstOrNull() ?: emptyList()
        selected.removeAll { !canPick(status[it]) }
    }
    LaunchedEffect(Unit) { reload(); loaded = true }

    val applied = status.filter { it.value.mode.isNotEmpty() }.toList().sortedByDescending { it.second.ts }

    Box(Modifier.fillMaxSize()) {
        Page(bottomSpace = if (selected.isNotEmpty()) 120 else 24, loaded = loaded) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("推荐清单", "自定义 ${custom.size}", "已处理 ${applied.size}").forEachIndexed { i, t ->
                        SegmentedButton(selected = view == i, onClick = { view = i }, shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(t) }
                    }
                }
            }
            when (view) {
                0 -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("核心组件（服务框架、互联、投屏、小爱等）已锁定，不会被处理。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                catalogItems.filter { it.level == Level.REC && canPick(status[it.pkg]) && it.pkg !in selected }.forEach { selected.add(it.pkg) }
                            }) { Text("勾选推荐") }
                        }
                    }
                    items(CATALOG, key = { it.title }) { cat ->
                        val present = cat.items.filter { (status[it.pkg]?.st ?: "x") != "x" }
                        val open = expanded[cat.title] == true
                        val picked = present.count { it.pkg in selected }
                        val done = present.count { status[it.pkg]?.mode?.isNotEmpty() == true }
                        GroupCard(
                            title = cat.title,
                            subtitle = cat.desc,
                            meta = buildString {
                                append("${present.size} 项")
                                if (done > 0) append(" · 已处理 $done")
                                if (picked > 0) append(" · 已选 $picked")
                            },
                            open = open,
                            onToggle = { expanded[cat.title] = !open },
                        ) {
                            present.forEachIndexed { i, item ->
                                if (i > 0) Divider()
                                PkgRow(item.name, item.pkg, item.desc, item.level, null, status[item.pkg], item.pkg in selected, canPick(status[item.pkg])) { on ->
                                    if (on) selected.add(item.pkg) else selected.remove(item.pkg)
                                }
                            }
                            if (present.isEmpty()) Text("本机没有这类组件", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
                1 -> item {
                    Section("自定义", "清单外的应用和系统组件。从列表移除不会恢复已处理的应用，恢复请到「已处理」。", "${custom.size} 项") {
                        Row(Modifier.padding(top = 6.dp, bottom = 4.dp)) {
                            FilledTonalButton(onClick = { showPicker = true }) {
                                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("添加应用")
                            }
                            Spacer(Modifier.weight(1f))
                            if (custom.isNotEmpty()) TextButton(onClick = { selected.removeAll(custom); custom.clear(); saveCustom() }) {
                                Text("全部移除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (custom.isEmpty()) Text("还没有添加。点「添加应用」从本机的用户应用或系统应用里选。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                        custom.forEach { pkg ->
                            Divider()
                            val info = customInfos[pkg] ?: CustomInfo(pkg, null)
                            PkgRow(info.label, pkg, "", null, info.system, status[pkg], pkg in selected, canPick(status[pkg]),
                                onRemove = { selected.remove(pkg); custom.remove(pkg); saveCustom() }) { on ->
                                if (on) selected.add(pkg) else selected.remove(pkg)
                            }
                        }
                    }
                }
                else -> item {
                    Section("已处理", "恢复后应用回到处理前的状态。卸载的应用恢复失败时，会用备份目录里的 APK 重新安装。", "${applied.size} 项") {
                        if (applied.isEmpty()) Text("还没有处理过任何应用", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
                        applied.forEachIndexed { i, (pkg, s) ->
                            if (i > 0) Divider()
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(pkg, 36)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(labelOf(pkg, pm), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Mono(pkg)
                                    Text("${MODE_TXT[s.mode]} · ${fmtTime(s.ts)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { scope.launch {
                                    busy = true; val r = Mod.results("restore $pkg"); reload(); busy = false
                                    results = "恢复结果" to r
                                } }) { Text("恢复") }
                            }
                        }
                        if (applied.size > 1) TextButton(onClick = { scope.launch {
                            busy = true; val r = Mod.results("restore-all"); reload(); busy = false
                            results = "恢复结果" to r
                        } }) { Text("全部恢复", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        if (selected.isNotEmpty() && view != 2) {
            ActionBar(selected.size, Modifier.align(Alignment.BottomCenter), onClear = { selected.clear() }) { mode -> confirmMode = mode }
        }
        if (busy) BusyOverlay()
    }

    confirmMode?.let { mode ->
        val warn = selected.count { levelOf[it] == Level.WARN }
        val sys = selected.count { customInfos[it]?.system == true }
        AlertDialog(
            onDismissRequest = { confirmMode = null },
            title = { Text("${MODE_TXT[mode]} ${selected.size} 个应用") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(if (mode == "uninstall") "对当前用户卸载并保留数据，系统分区的 APK 还在。处理前会备份，可在「已处理」里恢复。"
                         else "冻结后应用无法运行，数据保留。处理前会备份，可在「已处理」里恢复。")
                    if (warn > 0) Note("其中 $warn 项标记为「谨慎」，会影响对应功能。", warn = true)
                    if (sys > 0) Note("其中 $sys 个是你自选的系统应用，请确认认识它们。", warn = true)
                    Spacer(Modifier.height(8.dp))
                    selected.forEach { Text("• ${labelOf(it, pm)}", style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = {
                confirmMode = null
                val list = selected.toList()
                scope.launch {
                    busy = true
                    val r = Mod.results("apply $mode ${list.joinToString(" ")}")
                    selected.clear(); reload(); busy = false
                    results = "执行结果" to r
                }
            }) { Text("确认${MODE_TXT[mode]}") } },
            dismissButton = { TextButton(onClick = { confirmMode = null }) { Text("取消") } },
        )
    }
    results?.let { (title, list) -> ResultDialog(title, list) { results = null } }
    if (showPicker) SlimPickerDialog(exclude = (catalogItems.map { it.pkg } + custom).toSet(), onDismiss = { showPicker = false }) { pkgs ->
        showPicker = false
        custom.addAll(pkgs.filter { it !in custom })
        saveCustom()
        scope.launch {
            reload()
            pkgs.filter { canPick(status[it]) && it !in selected }.forEach { selected.add(it) }
            scope.toast(snack, "已加入 ${pkgs.size} 个应用并勾选")
        }
    }
}

/** 正常运行或已冻结（可再改为卸载）且没有处理记录的才能选 */
private fun canPick(s: Mod.PkgState?): Boolean =
    s != null && (s.st == "e" || s.st == "d") && s.mode.isEmpty()

private fun fmtTime(ts: Long) = if (ts <= 0) "" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ts * 1000))

@Composable
private fun ActionBar(count: Int, modifier: Modifier, onClear: () -> Unit, onAction: (String) -> Unit) {
    Surface(tonalElevation = 6.dp, shadowElevation = 6.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("已选 $count 项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("清空", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onClear))
            }
            OutlinedButton(onClick = { onAction("uninstall") }) { Text("卸载") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onAction("disable") }) { Text("冻结") }
        }
    }
}

/** 可折叠的分类卡片 */
@Composable
private fun GroupCard(title: String, subtitle: String, meta: String, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("$subtitle · $meta", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "收起" else "展开")
        }
        if (open) Column(Modifier.padding(start = 8.dp, end = 16.dp, bottom = 8.dp)) { content() }
    }
}

/** 一行应用。清单项传 level（推荐/可选/谨慎），自定义项传 system（系统应用/用户应用）并显示图标 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PkgRow(
    name: String, pkg: String, desc: String, level: Level?, system: Boolean?,
    s: Mod.PkgState?, checked: Boolean, enabled: Boolean,
    onRemove: (() -> Unit)? = null, onCheck: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onCheck(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheck, enabled = enabled)
        if (level == null) Box(Modifier.padding(top = 6.dp, end = 10.dp)) { AppIcon(pkg, 36) }
        Column(Modifier.weight(1f).padding(top = 8.dp)) {
            // 名称和标签一起换行，名字太长时标签不会被挤掉
            FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, fontWeight = FontWeight.Medium, color = if (enabled) cs.onSurface else cs.onSurfaceVariant)
                when (level) {
                    Level.REC -> Tag("推荐", cs.onTertiaryContainer, cs.tertiaryContainer)
                    Level.WARN -> Tag("谨慎", cs.onErrorContainer, cs.errorContainer)
                    Level.OPT -> Tag("可选", cs.onSurfaceVariant, cs.surfaceContainerHighest)
                    null -> when (system) {
                        true -> Tag("系统应用", cs.onErrorContainer, cs.errorContainer)
                        false -> Tag("用户应用", cs.onSecondaryContainer, cs.secondaryContainer)
                        null -> {}
                    }
                }
                val doneMode = s?.mode?.takeIf { it.isNotEmpty() }
                if (doneMode != null) Tag("已${MODE_TXT[doneMode]}", cs.onPrimaryContainer, cs.primaryContainer)
                else Tag(STATE_TXT[s?.st ?: "x"] ?: "", cs.onSurfaceVariant, cs.surfaceContainerHighest)
            }
            Mono(pkg)
            if (desc.isNotBlank()) Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (onRemove != null) IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, "从列表移除", tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun OtaCard(ota: List<String>, onToggle: (Boolean) -> Unit) {
    val on = ota.getOrNull(0) == "1"
    val fw = ota.getOrNull(1) == "1"
    val watch = ota.getOrNull(3) == "1"
    val ver = ota.getOrNull(4)?.takeIf { it.isNotBlank() && it != "null" }
    val badge = ota.getOrNull(5) == "1"
    val ok = fw && watch && ver == null && !badge
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("屏蔽系统更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        ota.isEmpty() -> "读取中…"
                        !on -> if (ver != null) "未屏蔽 · 当前提示新版本 $ver" else "未屏蔽 · 系统更新正常工作"
                        ok -> "已屏蔽 · 设置里无新版本提示，桌面无角标"
                        else -> "已屏蔽，但有异常：" + listOfNotNull(if (!fw) "网络未阻断" else null, if (!watch) "监听未运行" else null, if (ver != null) "仍提示 $ver" else null).joinToString("、")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (on && !ok) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = on, onCheckedChange = onToggle, enabled = ota.isNotEmpty())
        }
    }
}
