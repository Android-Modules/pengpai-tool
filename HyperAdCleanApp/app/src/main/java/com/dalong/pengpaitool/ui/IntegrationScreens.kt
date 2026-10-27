package com.dalong.pengpaitool.ui

import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dalong.pengpaitool.Mod
import com.dalong.pengpaitool.Root
import com.dalong.pengpaitool.shq
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 旧的独立模块还启用时显示：说明由它负责，并提供一键停用 */
@Composable
fun LegacyCard(id: String, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var state by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(id) { state = Mod.rows("legacy-status").firstOrNull { it[0] == id } }
    val s = state ?: return
    if (s.getOrNull(2) != "active") return
    Column {
        Note("独立模块「${s[1]}」仍在启用，这项功能暂时由它负责，下面的设置不会生效。停用旧模块并重启后，由本模块接管（配置已自动迁移）。", warn = true)
        FilledTonalButton(onClick = { scope.launch {
            val r = Mod.result("legacy-disable $id"); state = Mod.rows("legacy-status").firstOrNull { it[0] == id }
            scope.toast(snack, r.msg); onChanged()
        } }) { Text("停用旧模块「${s[1]}」") }
    }
}

// ======================= 机型修改 =======================

private data class Preset(val id: String, val maker: String, val brand: String, val name: String, val model: String, val device: String)

private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
private fun unb64(s: String?) = runCatching { String(Base64.decode(s ?: "", Base64.DEFAULT)) }.getOrDefault("")

private val DM_ERR = mapOf(2 to "参数不合法", 3 to "写入失败", 4 to "属性没有全部生效，已回滚", 5 to "有其他操作正在进行", 6 to "恢复上次未完成的操作失败")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevModelScreen() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var st by remember { mutableStateOf(mapOf<String, String>()) }
    var presets by remember { mutableStateOf(listOf<Preset>()) }
    var query by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Preset?>(null) }
    var restartAsk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var maker by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var product by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    suspend fun reload() {
        st = Mod.raw("dm status").lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }
    LaunchedEffect(Unit) {
        reload()
        presets = Mod.raw("dm list").lines().mapNotNull { l ->
            val f = l.split('|'); if (f.size < 5) null else Preset(f[0], f[1], f[2], f[3], f[4], f.getOrElse(5) { "" })
        }.distinctBy { it.id }
        loaded = true
    }
    suspend fun run(args: String, okMsg: String) {
        busy = true
        val r = Root.exec("sh ${Mod.DIR}/adclean.sh dm $args")
        busy = false
        val out = r.out.trim()
        scope.toast(snack, when {
            out.startsWith("ERR|") -> out.split('|').getOrElse(2) { "失败" }
            r.code == 0 && out.startsWith("OK") -> if (out.contains("reboot-required")) "已停用，完整重启手机后恢复原机属性" else okMsg
            else -> "失败：${DM_ERR[r.code] ?: "错误码 ${r.code}"}"
        })
        reload()
    }

    val enabled = st["enabled"] == "1"
    Page(loaded = loaded) {
        item { LegacyCard("changephone") { scope.launch { reload() } } }
        item {
            Section("当前机型", trailing = if (enabled) "已修改" else "原机") {
                InfoRow("市场名称", st["current_marketname"] ?: "-")
                InfoRow("认证型号", st["current_model"] ?: "-")
                InfoRow("品牌 / 厂商", "${st["current_brand"] ?: "-"} / ${st["current_manufacturer"] ?: "-"}")
                InfoRow("产品名称", st["current_product"] ?: "-")
                val origName = st["baseline_marketname"].orEmpty().ifBlank { st["stock_marketname"].orEmpty() }
                val origModel = st["baseline_model"].orEmpty().ifBlank { st["stock_model"].orEmpty() }
                InfoRow("原机（固件）", "$origName $origModel".trim().ifBlank { "未记录" })
                if (st["restore_pending_reboot"] == "1") Note("原机属性需要完整重启手机才能恢复。", warn = true)
                Note("属性立即更新；已打开的 App 读的是缓存的机型，需要「重启框架」或重启手机后才读到新机型。")
                Row(Modifier.padding(vertical = 6.dp)) {
                    OutlinedButton(enabled = enabled && !busy, onClick = { scope.launch { run("disable", "已恢复原机属性") } }) { Text("恢复原机") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(enabled = !busy, onClick = { restartAsk = true }) { Text("重启框架") }
                }
            }
        }
        item {
            val list = presets.filter { query.isBlank() || it.name.contains(query, true) || it.model.contains(query, true) || it.id.contains(query, true) }
            Section("预设机型", "共 ${presets.size} 款小米、Redmi、POCO 机型，点一下即可切换。", "${list.size} 款") {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("搜索名称或型号，如 K80、14 Ultra") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                list.take(if (query.isBlank()) 30 else 200).forEachIndexed { i, p ->
                    if (i > 0) Divider()
                    val cur = st["preset_id"] == p.id && enabled
                    Row(Modifier.fillMaxWidth().clickable { confirm = p }.padding(vertical = 12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, color = if (cur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text("${p.brand} · ${p.model}" + if (p.device.isNotBlank()) " · ${p.device}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (cur) Text("当前", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (query.isBlank() && presets.size > 30) Text("只显示前 30 款，搜索可查看全部", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        item {
            Section("自定义机型", "市场名称和认证型号必填；内部代号会同时用于产品名称和设备代号。") {
                listOf(
                    Triple("市场名称 *", name) { v: String -> name = v },
                    Triple("认证型号 *", model) { v: String -> model = v },
                    Triple("品牌（如 Xiaomi、Redmi）", brand) { v: String -> brand = v },
                    Triple("厂商（通常是 Xiaomi）", maker) { v: String -> maker = v },
                    Triple("内部代号（可选）", product) { v: String -> product = v },
                ).forEach { (label, v, set) ->
                    OutlinedTextField(v, set, singleLine = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                FilledTonalButton(enabled = !busy && name.isNotBlank() && model.isNotBlank(), modifier = Modifier.padding(vertical = 8.dp), onClick = { scope.launch {
                    run("apply-custom ${b64(maker.trim())} ${b64(brand.trim())} ${b64(name.trim())} ${b64(model.trim())} ${b64(product.trim())}", "已应用自定义机型")
                } }) { Text("应用自定义机型") }
            }
        }
    }

    confirm?.let { p ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("切换为 ${p.name}？") }, text = {
            Text("认证型号 ${p.model}，品牌 ${p.brand}。属性立即生效，App 读取新机型需要重启框架或手机。")
        }, confirmButton = { TextButton(onClick = { confirm = null; scope.launch { run("apply-preset ${shq(p.id)}", "已切换为 ${p.name}") } }) { Text("切换") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } })
    }
    if (restartAsk) {
        AlertDialog(onDismissRequest = { restartAsk = false }, title = { Text("重启 Android 框架？") }, text = {
            Text("会关闭所有正在运行的 App（包括本应用），屏幕会黑一下，然后重新进入桌面。重启后所有 App 都会读到新的机型。")
        }, confirmButton = { TextButton(onClick = { restartAsk = false; scope.launch { Root.exec("sh ${Mod.DIR}/adclean.sh dm restart-framework") } }) { Text("重启框架") } },
            dismissButton = { TextButton(onClick = { restartAsk = false }) { Text("取消") } })
    }
    if (busy) BusyOverlay()
}

// ======================= 电池循环次数伪装 =======================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CycleScreen() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var j by remember { mutableStateOf<JSONObject?>(null) }
    var fixed by remember { mutableStateOf("20") }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun reload() { j = runCatching { JSONObject(Mod.raw("cycle status").trim()) }.getOrNull() }
    LaunchedEffect(Unit) { reload(); j?.let { if (it.optString("mode") == "fixed") fixed = it.optInt("fake").toString() }; loaded = true }
    fun setMode(args: String) = scope.launch {
        busy = true
        val out = Mod.raw("cycle setmode $args").trim().lines().lastOrNull { it.isNotBlank() }.orEmpty()
        reload(); busy = false
        scope.toast(snack, out.removePrefix("OK: ").removePrefix("FAIL: ").ifBlank { "完成" })
    }

    val mode = j?.optString("mode") ?: "off"
    val bound = j?.optInt("bound_bat") == 1
    Page(loaded = loaded) {
        item { LegacyCard("fake_battery_cycle") { scope.launch { reload() } } }
        item {
            Section("循环次数", "用 bind-mount 覆盖系统读取的循环次数节点，不写电量计芯片；关闭即恢复真实值。", if (bound && mode != "off") "伪装中" else "未伪装") {
                InfoRow("系统读到的次数", "${j?.optInt("fake") ?: "-"} 次")
                InfoRow("真实循环次数", "${j?.optInt("real") ?: "-"} 次")
                InfoRow("真实值记录时间", j?.optString("real_captured_at")?.ifBlank { "-" } ?: "-")
                Note("真实循环次数始终显示在这里。转让或出售手机时请如实告知电池情况。")
            }
        }
        item {
            Section("伪装方式") {
                FlowRow(Modifier.padding(vertical = 6.dp)) {
                    listOf("off" to "关闭", "map" to "梯度映射", "fixed" to "固定值").forEach { (m, t) ->
                        FilterChip(selected = mode == m, enabled = !busy, onClick = {
                            when (m) { "fixed" -> setMode("fixed ${fixed.ifBlank { "20" }}"); else -> setMode(m) }
                        }, label = { Text(t) }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                Text(when (mode) {
                    "map" -> "按真实次数分档显示：<500 → 20，≤600 → 50，≤900 → 80，更多 → 200"
                    "fixed" -> "始终显示固定的次数"
                    else -> "不伪装，系统读取真实循环次数"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(vertical = 8.dp)) {
                    OutlinedTextField(fixed, { fixed = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text("固定值") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(enabled = !busy && fixed.isNotBlank(), onClick = { setMode("fixed $fixed") }, modifier = Modifier.padding(top = 8.dp)) { Text("应用") }
                }
            }
        }
        item {
            Section("电池信息") {
                InfoRow("健康度", "${j?.optString("health_pct") ?: "-"}%")
                InfoRow("容量", "${j?.optInt("charge_full") ?: "-"} / ${j?.optInt("charge_design") ?: "-"} mAh")
                InfoRow("温度", "${j?.optString("temp") ?: "-"} ℃")
                InfoRow("电量", "${j?.optInt("level") ?: "-"}%")
            }
        }
    }
    if (busy) BusyOverlay()
}

// ======================= 远程进程守卫 =======================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuardScreen() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var g by remember { mutableStateOf(listOf<String>()) }
    var log by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    suspend fun reload() {
        g = Mod.rows("guard-status").firstOrNull() ?: emptyList()
        log = Mod.raw("guard-log").lines().filter { it.isNotBlank() }.reversed().joinToString("\n")
    }
    LaunchedEffect(Unit) { reload(); loaded = true }
    fun set(k: String, v: String) = scope.launch { val r = Mod.result("guard-set $k $v"); reload(); scope.toast(snack, r.msg) }

    val on = g.getOrNull(0) == "1"
    val running = g.getOrNull(1) == "1"
    Page(loaded = loaded) {
        item { LegacyCard("baize_remote_cpu_guard") { scope.launch { reload() } } }
        item {
            Section("远程进程守卫", "安全中心的远程进程（com.miui.securitycenter.remote）偶尔会失控占满 CPU、发热耗电。超过阈值时结束它，系统会按需重新拉起。只结束这个进程，不动安全中心主进程。") {
                SwitchRow("开启守卫", if (on) (if (running) "运行中" else "未运行") else "已关闭", on) { set("enable", if (it) "1" else "0") }
            }
        }
        item {
            Section("阈值") {
                Text("一个采样窗口内平均 CPU 占用超过多少时结束进程", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                FlowRow(Modifier.padding(vertical = 6.dp)) {
                    listOf("50", "80", "100", "150", "200").forEach { v ->
                        FilterChip(selected = g.getOrNull(2) == v, onClick = { set("threshold", v) }, label = { Text("$v%") }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                Text("采样间隔", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(Modifier.padding(vertical = 6.dp)) {
                    listOf("3", "5", "10", "15").forEach { v ->
                        FilterChip(selected = g.getOrNull(3) == v, onClick = { set("interval", v) }, label = { Text("$v 秒") }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        }
        item {
            Section("记录", trailing = "累计 ${g.getOrNull(4) ?: 0} 次") {
                InfoRow("上次 CPU", g.getOrNull(5)?.takeIf { it.isNotBlank() && it != "--" }?.let { "$it%" } ?: "暂无")
                InfoRow("上次时间", g.getOrNull(6)?.takeIf { it.isNotBlank() } ?: "暂无")
                if (log.isNotBlank()) SelectionContainer(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                    Text(log, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
