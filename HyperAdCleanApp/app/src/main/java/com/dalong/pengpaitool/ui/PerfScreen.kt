package com.dalong.pengpaitool.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dalong.pengpaitool.Mod
import com.dalong.pengpaitool.Root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class Cpu(val policy: String, val cur: Long, val hwMax: Long, val freqs: List<Long>, val set: Long)

/** 用户空间：0 个人，managed profile 是「工作」，999 是小米手机分身 */
internal data class Space(val id: Int, val name: String, val flags: Int) {
    val title get() = when {
        id == 0 -> "个人"
        flags and 0x20 != 0 -> "工作"
        id == 999 || name == "XSpace" -> "手机分身"
        else -> name
    }
}

/** wl-list 的解析结果；白名单按包名生效所有空间共用，受限状态各空间单独记录 */
internal data class WlState(
    val user: Set<String> = emptySet(),
    val sys: Set<String> = emptySet(),
    val restricted: Set<Pair<String, Int>> = emptySet(),
    val spaces: List<Space> = emptyList(),
    val spacePkgs: Map<Int, Set<String>> = emptyMap(),
    val bg: Map<Pair<String, Int>, String> = emptyMap(),
) {
    fun on(pkg: String) = pkg in user || pkg in sys

    /** HyperOS 省电策略，没记录的就是默认的智能限制 */
    fun bgOf(pkg: String, space: Int) = bg[pkg to space] ?: "miuiAuto"

    /** 手动加入白名单的第三方应用、但某个空间里省电策略不是「无限制」的（退后台仍会被冻结） */
    val unsynced: List<Pair<String, Int>>
        get() = user.flatMap { p -> spacePkgs.filter { p in it.value }.keys.map { p to it } }
            .filter { (p, s) -> bgOf(p, s) != "noRestrict" }

    companion object {
        fun parse(raw: String): WlState {
            val rows = raw.lines().map { it.trim().split('|') }.filter { it.size >= 3 }
            return WlState(
                user = rows.filter { it[0] == "w" && it[2] == "user" }.map { it[1] }.toSet(),
                sys = rows.filter { it[0] == "w" && it[2] == "sys" }.map { it[1] }.toSet(),
                restricted = rows.filter { it[0] == "r" }.mapNotNull { r -> r[2].toIntOrNull()?.let { r[1] to it } }.toSet(),
                spaces = rows.filter { it[0] == "u" && it.size >= 4 }.mapNotNull { r ->
                    r[1].toIntOrNull()?.let { Space(it, r[2], r[3].toIntOrNull(16) ?: 0) }
                },
                spacePkgs = rows.filter { it[0] == "p" }.groupBy({ it[1].toIntOrNull() ?: -1 }, { it[2] }).mapValues { it.value.toSet() },
                bg = rows.filter { it[0] == "k" && it.size >= 4 }.mapNotNull { r -> r[2].toIntOrNull()?.let { (r[1] to it) to r[3] } }.toMap(),
            )
        }
    }
}

@Composable
fun PerfScreen(part: String, onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var bat by remember { mutableStateOf(mapOf<String, String>()) }
    var cycle by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var cpus by remember { mutableStateOf(listOf<Cpu>()) }
    var cpuOn by remember { mutableStateOf(false) }
    var wl by remember { mutableStateOf(WlState()) }
    var showWl by remember { mutableStateOf(part == "whitelist") }
    var syncing by remember { mutableStateOf(false) }
    var showPick by remember { mutableStateOf(false) }
    var dex by remember { mutableStateOf(listOf<String>()) }
    var recent by remember { mutableStateOf(listOf<Pair<String, Boolean>>()) }
    var loaded by remember { mutableStateOf(false) }
    suspend fun loadRecent() {
        recent = Mod.raw("dexopt-recent 6").lines().filter { '|' in it }.map { it.substringBefore('|') to (it.substringAfter('|').trim() == "Success") }
    }

    suspend fun reload() {
        bat = Root.exec(
            "cd /sys/class/power_supply/battery; for f in capacity charge_full charge_full_design cycle_count temp health status voltage_now current_now; do echo \"\$f=\$(cat \$f 2>/dev/null)\"; done; echo soh=\$(cat /sys/class/qcom-battery/soh 2>/dev/null)"
        ).lines.associate { it.substringBefore('=') to it.substringAfter('=') }
        if (part == "battery") cycle = runCatching { org.json.JSONObject(Mod.raw("cycle status").trim()) }.getOrNull()
        val cpuRows = Mod.rows("cpu-status")
        cpuOn = cpuRows.firstOrNull { it[0] == "enable" }?.getOrNull(1) == "1"
        cpus = cpuRows.filter { it[0].startsWith("policy") }.map {
            Cpu(it[0], it[1].toLongOrNull() ?: 0, it[2].toLongOrNull() ?: 0, it[3].split(',').mapNotNull { f -> f.toLongOrNull() }, it.getOrNull(4)?.toLongOrNull() ?: 0)
        }
        wl = WlState.parse(Mod.raw("wl-list"))
        dex = Mod.rows("dexopt-status").firstOrNull() ?: emptyList()
    }
    LaunchedEffect(Unit) { reload(); if (part == "dexopt") loadRecent(); loaded = true }
    // 编译进行中每秒刷新一次：圆环进度、当前应用、最近完成
    LaunchedEffect(dex.getOrNull(0)) {
        while (dex.getOrNull(0) == "1") {
            delay(1000)
            dex = Mod.rows("dexopt-status").firstOrNull() ?: emptyList()
            loadRecent()
        }
    }

    Page(loaded = loaded) {
        if (part == "battery") item { BatteryCard(bat, cycle) }
        if (part == "whitelist") item {
            Section("电池优化白名单", "加入时同时设置两处：系统设置「电池优化」里的「无限制」（不受 Doze 休眠限制），以及应用详情里的 HyperOS「省电策略 → 无限制」（退到后台不被冻结）。开机自动重新加入，防止被系统清掉。", "${wl.user.size} 个") {
                InfoRow("手动加入", "${wl.user.size} 个")
                InfoRow("系统内置", "${wl.sys.size} 个")
                if (wl.spaces.size > 1) InfoRow("用户空间", wl.spaces.joinToString("、") { it.title })
                if (wl.restricted.isNotEmpty()) InfoRow("后台受限", "${wl.restricted.size} 个")
                FilledTonalButton(onClick = { showWl = true }, modifier = Modifier.padding(top = 6.dp)) { Text("管理白名单") }
            }
        }
        if (part == "cpu") item {
            Section("处理器限频", "限制各 CPU 簇的最高频率来省电、降温。开机自动重新应用。") {
                SwitchRow("启用限频", if (cpuOn) "关闭即恢复默认，所有核心还原为限频前的上限" else "已恢复默认；打开会重新套用下面设定的频率", cpuOn) { v -> scope.launch {
                    val r = Mod.result("cpu-enable ${if (v) 1 else 0}"); reload(); scope.toast(snack, r.msg)
                } }
                Divider()
                cpus.forEachIndexed { i, c -> if (i > 0) Divider(); CpuRow(c, i, cpus.size, cpuOn) { f -> scope.launch {
                    val r = Mod.result("cpu-set ${c.policy} $f"); reload(); scope.toast(snack, r.msg)
                } } }
                Note("Scene、温控等模块或系统调度也会改频率上限，同时使用时以最后写入的为准。")
            }
        }
        if (part == "dexopt") item {
            DexoptPanel(dex, recent, onPick = { showPick = true }, onBg = { scope.launch {
                val r = Mod.result("dexopt bg"); scope.toast(snack, r.msg); delay(500); reload()
            } })
        }
    }

    if (showWl && loaded) WhitelistDialog(wl, onDismiss = { showWl = false; if (part == "whitelist") onBack() },
        syncing = syncing,
        onSync = { scope.launch { syncing = true; val r = Mod.result("wl-sync"); reload(); syncing = false; scope.toast(snack, r.msg) } }) { pkg, on ->
        scope.launch { val r = Mod.result("wl-set $pkg ${if (on) 1 else 0}"); reload(); scope.toast(snack, r.msg) }
    }
    if (showPick) CompilePickerDialog(onDismiss = { showPick = false }) { mode, pkgs ->
        showPick = false
        scope.launch { val r = Mod.result("dexopt $mode ${pkgs.joinToString(" ")}"); scope.toast(snack, r.msg); delay(400); reload(); loadRecent() }
    }
}

@Composable
private fun labelFor(pkg: String): String {
    val ctx = LocalContext.current
    return remember(pkg) { runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompilePickerDialog(onDismiss: () -> Unit, onStart: (String, List<String>) -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("speed-profile") }
    val picked = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) { apps = loadApps(ctx, userOnly = true) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("选择要编译的应用") }, actions = {
                    val all = apps ?: emptyList()
                    TextButton(onClick = {
                        if (picked.size == all.size) picked.clear() else { picked.clear(); picked.addAll(all.map { it.pkg }) }
                    }) { Text(if (apps != null && picked.size == all.size) "全不选" else "全选") }
                })
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.weight(1f))
                        Button(enabled = picked.isNotEmpty(), onClick = { onStart(mode, picked.toList()) }) { Text("开始编译 (${picked.size})") }
                    }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    listOf("speed-profile" to "推荐（按使用记录）", "speed" to "完全编译").forEachIndexed { i, (m, t) ->
                        SegmentedButton(selected = mode == m, onClick = { mode = m }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(t) }
                    }
                }
                Text(
                    if (mode == "speed") "整个应用全部编译：启动最快，但占用更多存储、编译更久。" else "只编译你实际用到的代码：速度和空间最均衡，日常推荐。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("搜索应用") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                val list = apps?.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                if (list == null) LoadingHint("正在读取应用列表…")
                else LazyColumn(Modifier.padding(horizontal = 8.dp)) {
                    items(list, key = { it.pkg }) { a ->
                        val on = a.pkg in picked
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) picked.remove(a.pkg) else picked.add(a.pkg) }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(a.pkg)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(a.label, style = MaterialTheme.typography.bodyLarge)
                                Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(checked = on, onCheckedChange = { if (it) picked.add(a.pkg) else picked.remove(a.pkg) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryCard(b: Map<String, String>, cycle: org.json.JSONObject?) {
    val full = b["charge_full"]?.toLongOrNull() ?: 0
    val design = b["charge_full_design"]?.toLongOrNull() ?: 0
    val health = if (full > 0 && design > 0) "%.1f%%".format(full * 100.0 / design) else "-"
    Section("电池健康度", "数据从系统读取，仅供参考。", health) {
        InfoRow("当前电量", "${b["capacity"] ?: "-"}%")
        InfoRow("设计容量", if (design > 0) "${design / 1000} mAh" else "-")
        InfoRow("实际容量", if (full > 0) "${full / 1000} mAh" else "-")
        b["soh"]?.takeIf { it.isNotBlank() }?.let { InfoRow("售后健康度 (SOH)", "$it%") }
        val spoofed = cycle != null && cycle.optInt("bound_bat") == 1 && cycle.optString("mode") != "off"
        InfoRow("循环次数", if (spoofed) "显示 ${b["cycle_count"] ?: "-"}（真实 ${cycle!!.optInt("real")}）" else b["cycle_count"] ?: "-")
        InfoRow("温度", b["temp"]?.toIntOrNull()?.let { "%.1f ℃".format(it / 10.0) } ?: "-")
        InfoRow("电池状态", when (b["health"]) { "Good" -> "良好"; "Overheat" -> "过热"; "Dead" -> "损坏"; "Cold" -> "过冷"; "Over voltage" -> "过压"; else -> b["health"] ?: "-" })
        InfoRow("充电状态", when (b["status"]) { "Charging" -> "充电中"; "Discharging" -> "放电中"; "Full" -> "已充满"; "Not charging" -> "未充电"; else -> b["status"] ?: "-" })
        if (!spoofed) Note("循环次数如果被其他模块改写，这里显示的是改写后的值。")
    }
}

@Composable
private fun CpuRow(c: Cpu, index: Int, total: Int, active: Boolean, onSet: (Long) -> Unit) {
    val name = when { total == 2 && index == 0 -> "小核"; total == 2 && index == 1 -> "大核"; else -> "CPU 簇 ${index + 1}" } + " (${c.policy})"
    val start = c.freqs.indexOf(if (c.set > 0) c.set else c.hwMax).takeIf { it >= 0 } ?: (c.freqs.size - 1)
    var pos by remember(c) { mutableFloatStateOf(start.toFloat()) }
    val f = c.freqs.getOrNull(pos.roundToInt()) ?: 0
    Column(Modifier.padding(vertical = 8.dp)) {
        Row {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                when { c.set <= 0 -> "不限制"; active -> "限制 ${c.set / 1000}MHz"; else -> "已设 ${c.set / 1000}MHz（未启用）" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (active && c.set > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("当前上限 ${c.cur / 1000}MHz · 硬件最高 ${c.hwMax / 1000}MHz", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (c.freqs.size > 1) {
            Slider(pos, { pos = it }, valueRange = 0f..(c.freqs.size - 1).toFloat(), steps = (c.freqs.size - 2).coerceAtLeast(0))
            Row {
                Text("${f / 1000}MHz", modifier = Modifier.weight(1f).padding(top = 10.dp))
                TextButton(onClick = { onSet(f) }) { Text("应用") }
                TextButton(onClick = { onSet(0) }, enabled = c.set > 0) { Text("清除") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhitelistDialog(wl: WlState, onDismiss: () -> Unit, syncing: Boolean, onSync: () -> Unit, onToggle: (String, Boolean) -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    var space by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        val launcher = loadApps(ctx, userOnly = false)
        // 白名单里没有桌面图标的系统组件（如小米账号、推送），以及只装在其他空间的应用，也补进来
        val known = launcher.map { it.pkg }.toSet()
        val want = wl.user + wl.sys + wl.spacePkgs.values.flatten()
        val extra = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            want.filter { it !in known }.mapNotNull { pkg ->
                runCatching {
                    val ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                    AppEntry(pkg, pm.getApplicationLabel(ai).toString(), ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
                }.getOrElse { AppEntry(pkg, pkg, false) }
            }
        }
        apps = (launcher + extra).sortedBy { it.label.lowercase() }
    }
    val others = wl.spaces.filter { it.id != 0 }
    // 已选中的置顶；只在打开、切换分组、搜索时重新排，拨动开关时不挪位置，避免列表在手指下跳动
    var pinned by remember { mutableStateOf(wl.user + wl.sys) }
    LaunchedEffect(tab, query, space) { pinned = wl.user + wl.sys }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("电池优化白名单") }, actions = { TextButton(onClick = onDismiss) { Text("完成") } })
        }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                val all = apps ?: emptyList()
                if (others.isNotEmpty()) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    wl.spaces.forEachIndexed { i, s ->
                        SegmentedButton(selected = space == s.id, onClick = { space = s.id }, shape = SegmentedButtonDefaults.itemShape(i, wl.spaces.size)) {
                            Text(s.title)
                        }
                    }
                }
                val inSpace = wl.spacePkgs[space]
                val unsynced = wl.unsynced
                if (unsynced.isNotEmpty()) Surface(
                    color = MaterialTheme.colorScheme.errorContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("有 ${unsynced.size} 项只设了电池优化，HyperOS 省电策略不是无限制，退后台仍会被冻结",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = onSync, enabled = !syncing) { Text(if (syncing) "正在同步…" else "一键同步") }
                    }
                }
                if (space == 0) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        listOf(false to "用户应用", true to "系统应用").forEachIndexed { i, (sys, t) ->
                            val group = all.filter { it.system == sys }
                            SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                                Text("$t ${group.count { wl.on(it.pkg) }}/${group.size}")
                            }
                        }
                    }
                    if (tab == 1) Text("系统应用的白名单多数是系统自己加的，移除可能导致推送、账号等后台功能被限制。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                } else Text(
                    "${wl.spaces.firstOrNull { it.id == space }?.title}里的应用 ${all.count { it.pkg in inSpace.orEmpty() && wl.on(it.pkg) }}/${inSpace?.size ?: 0}。" +
                        "白名单按应用生效，这里打开后个人空间的同一应用也一起加入，和系统设置一致。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("搜索应用") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                val list = apps?.filter {
                    (if (space == 0) it.system == (tab == 1) else it.pkg in inSpace.orEmpty()) &&
                        (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
                }?.sortedBy { it.pkg !in pinned }
                if (list == null) LoadingHint("正在读取应用列表…")
                else LazyColumn(Modifier.padding(horizontal = 16.dp)) {
                    items(list, key = { it.pkg }) { a ->
                        val sys = a.pkg in wl.sys
                        val desc = buildList {
                            add(a.pkg)
                            if (sys) add("系统内置，无法更改")
                            if (a.pkg in wl.user && a.pkg in inSpace.orEmpty()) when (wl.bgOf(a.pkg, space)) {
                                "noRestrict" -> {}
                                "restrictBg" -> add("省电策略为限制后台，退后台会被冻结（重新打开开关可修正）")
                                else -> add("省电策略为智能限制，退后台仍会被冻结（重新打开开关可修正）")
                            }
                            if (a.pkg to space in wl.restricted) add("后台受限，加入白名单会一并解除")
                            if (space == 0) others.filter { a.pkg in wl.spacePkgs[it.id].orEmpty() }.takeIf { it.isNotEmpty() }
                                ?.let { add("也在：" + it.joinToString("、") { s -> s.title }) }
                        }.joinToString("\n")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(a.pkg)
                            Spacer(Modifier.width(12.dp))
                            Box(Modifier.weight(1f)) { SwitchRow(a.label, desc, wl.on(a.pkg), enabled = !sys) { onToggle(a.pkg, it) } }
                        }
                    }
                }
            }
        }
    }
}
