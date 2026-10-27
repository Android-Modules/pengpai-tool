package com.dalong.pengpaitool.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dalong.pengpaitool.Mod
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private val STATUS = mapOf("Charging" to "充电中", "Discharging" to "未充电", "Full" to "已充满", "Not charging" to "未在充电")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Chips(label: String, cur: String?, opts: List<Pair<String, String>>, enabled: Boolean, onPick: (String) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    FlowRow(Modifier.padding(vertical = 6.dp)) {
        opts.forEach { (v, t) ->
            FilterChip(selected = cur == v, enabled = enabled, onClick = { onPick(v) }, label = { Text(t) }, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

@Composable
fun ChargeScreen(open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var st by remember { mutableStateOf(mapOf<String, String>()) }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() {
        st = Mod.raw("charge status").lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }
    var ti by remember { mutableStateOf(ThermalCache.info) }
    LaunchedEffect(Unit) { while (true) { reload(); delay(2000) } }
    // 温控场景会随前台应用变化，温度墙数据定期重读
    LaunchedEffect(Unit) { while (true) { runCatching { ThermalCache.load() }.getOrNull()?.let { ti = it }; delay(15000) } }
    fun set(k: String, v: String) = scope.launch {
        busy = true
        val r = Mod.result("charge set $k $v")
        reload(); busy = false
        scope.toast(snack, r.msg)
    }

    fun num(k: String) = st[k]?.toLongOrNull()
    val supported = st["supported"] == "1"
    // qcom：小米 13 Ultra 这类高通 qcom-battery 充电驱动；其余为小米 MCA 框架
    val qcom = st["hw"] == "qcom"
    val on = st["enable"] == "1"
    val custom = st["thermal"] == "custom"
    val tier = st["thermal"] == "tier"
    val online = st["online"] == "1"
    val curr = num("curr")?.let { abs(it) }
    val volt = num("volt")
    Page(loaded = st.isNotEmpty()) {
        item {
            Section("实时状态", trailing = if (online) "已连接充电器" else "未连接") {
                InfoRow("状态", "${STATUS[st["status"]] ?: st["status"] ?: "-"} · ${st["soc"] ?: "-"}%")
                if (online && curr != null && volt != null) {
                    InfoRow("功率", "%.1f W（%.2f A × %.2f V）".format(curr * volt / 1e12, curr / 1e6, volt / 1e6))
                }
                InfoRow("电池温度", num("temp")?.let { "%.1f ℃".format(it / 10.0) } ?: "-")
                InfoRow("机身温度", num("board")?.let { "%.1f ℃".format(it / 1000.0) } ?: "-")
                if (online) InfoRow("充电协议", "${st["real_type"] ?: "-"}" + (num("power_max")?.takeIf { it > 0 }?.let { " · 最大 $it W" } ?: ""))
                if (qcom) {
                    InfoRow("充电电流上限", num("sys_curr")?.let { c ->
                        if (c == 0L) "0 A · 停充（充电器继续供电）" else "%.1f A".format(c / 1e6) + if (c >= (num("curr_max") ?: 6_000_000)) " · 驱动默认" else ""
                    } ?: "-")
                } else {
                    InfoRow("系统温控电流上限", num("sys_curr")?.let { if (it >= 22_000_000) "不限制" else "%.1f A".format(it / 1e6) } ?: "-")
                }
                num("ctrl_limit")?.let { lv ->
                    InfoRow(if (qcom) "温控档位" else "限速等级", when {
                        lv <= 0 -> "0 级 · 不限速"
                        qcom -> "$lv 级（共 16 级，越高越慢）"
                        else -> "$lv 级" + (ti?.levelCurr(lv.toInt())?.let { " · 快充最多 ${fmtA(it)}" } ?: "")
                    })
                }
                InfoRow("系统温控墙", if (st["remove"] == "1") "已解除" else "生效中")
                if (qcom && st["suspend"] == "1") InfoRow("充电输入", "已断开")
                if (on) InfoRow("当前策略", st["action_msg"]?.ifBlank { "-" } ?: "-")
                if (qcom && (num("night") ?: 0) != 0L) Note("系统「夜间充电」正在生效：充到 80% 后暂停，等到起床前再充满。不需要的话到 设置 → 电池 → 充电保护 里关闭。", warn = true)
                else if ((num("night") ?: 0) != 0L) Note("系统「夜间智能充电」正在生效：会先充到 80% 再等到起床前充满，夜里看起来像充得很慢。不需要的话到 设置 → 电池 → 充电保护 里关闭。", warn = true)
            }
        }
        item {
            Section("曲线与温控") {
                NavRow("充电记录", "每次充电的功率、电流、温度曲线", when {
                    st["record"] != "1" -> "已关闭"
                    !st["session"].isNullOrBlank() -> "记录中"
                    else -> null
                }) { open("chargelog") }
                NavRow("系统温度墙", "各场景下温度对应的充电电流，用图表展示", ti?.scene?.let { "当前：${sceneName(it)}" }) { open("thermalwall") }
            }
        }
        item {
            Section("充电控制",
                if (qcom) "接管有线充电的温控墙和停充（已适配小米 13 Ultra 这类高通 qcom-battery 充电驱动）。系统被改回的设置会自动重写；电池温度保护仍然有效；关闭后立即恢复系统默认。"
                else "接管有线充电的温控墙和停充。只投本模块自己的票：系统自带的高温停充、电池温度保护仍然有效；关闭后立即恢复系统默认。") {
                if (st.isNotEmpty() && !supported) Note("本机没有可用的充电控制节点（已支持小米 MCA 充电框架和 13 Ultra 这类 qcom-battery 驱动），不支持此功能。", warn = true)
                SwitchRow("开启充电控制", if (on) (if (st["running"] == "1") "运行中" else "未运行") else "已关闭，使用系统默认", on, enabled = supported && !busy) {
                    set("enable", if (it) "1" else "0")
                }
            }
        }
        item {
            val desc = when {
                tier && qcom -> "来自「米系亮屏快充 1.3」：亮屏、息屏都不再按机身温度和温控档位降速，改为按电池温度分档限流（写 constant_charge_current，驱动最高 ${fmtA(((num("curr_max") ?: 6_000_000) / 1000).toInt())}，超过的档位按最高值）。系统改回时 1 秒内重写；过热停充和电池温度保护仍然生效。"
                custom && qcom -> "电池温度低于温控墙时解除系统温控（同时把亮屏温控档位压到 0）满速充电，达到温控墙后交还系统温控，达到停充温度时暂停充电。"
                tier -> "来自「米系亮屏快充 1.3」：亮屏、息屏都不再按机身温度和亮屏等级降速，改为按电池温度分档限流。原模块写的 constant_charge_current 在本机只读无效，这里改为接管系统温控电流（wired_chg_curr）实现同样效果；系统改回时 1 秒内重写。过热停充和电池温度保护仍然生效。"
                custom -> "电池温度低于温控墙时解除系统温控满速充电，达到温控墙后交还系统温控，达到停充温度时暂停充电。"
                else -> "系统温控按机身温度（虚拟板温）压电流，亮屏时再按${if (qcom) "温控档位" else "限速等级"}额外降速，这就是“温控墙”。下方是当前场景的具体数值。"
            }
            Section("温控", desc) {
                Chips("温控模式", st["thermal"], listOf("system" to "系统温控", "custom" to "自定义温控墙", "tier" to "米系亮屏快充"), on && !busy) { set("thermal", it) }
                if (custom) Chips("温控墙（电池温度）", st["wall"], listOf("38", "40", "42", "44", "45").map { it to "$it℃" }, on && !busy) { set("wall", it) }
                Chips("过热停充（回落 3℃ 后恢复）", st["stop_temp"], listOf("44", "46", "48").map { it to "$it℃" }, on && !busy) { set("stop_temp", it) }
            }
        }
        if (tier) item {
            Section("阶梯限流", "按电池温度从低到高分档，电池温度低于某档温度时使用该档电流。默认值与原模块一致；46℃ 以上会过热停充，48℃ 以上充电驱动本身也只允许普通充电，所以高温档实际不会用到。") {
                TierEditor(st["tiers"], on && !busy) { v ->
                    if (v == null) scope.toast(snack, "请把每一档的温度和电流填完整") else set("tiers", v)
                }
            }
        }
        item {
            Section("快充场景", if (tier) "阶梯限流模式下亮屏、息屏都按阶梯充电，只有电量区间生效。" else "只在下面勾选的场景里解除系统温控；其余场景交还系统温控。") {
                SwitchRow("亮屏快充", "默认关闭。亮屏时系统会按限速等级额外降速，打开后亮屏也满速充电，边用边充发热明显", st["screen_on"] == "1", enabled = on && custom && !busy) { set("screen_on", if (it) "1" else "0") }
                SwitchRow("息屏快充", "息屏时满速充电", st["screen_off"] == "1", enabled = on && custom && !busy) { set("screen_off", if (it) "1" else "0") }
                Chips("快充电量区间（电量低于它时快充）", st["fast_soc"], listOf("50", "70", "80", "90", "100").map { it to if (it == "100") "全程" else "<$it%" }, on && (custom || tier) && !busy) { set("fast_soc", it) }
            }
        }
        item { StrategyWall(st, ti) }
        item {
            Section("充电上限", "充到上限后暂停充电，电量回落到「上限 − 回充差值」后继续充。100% 表示不限制。") {
                Chips("充到多少停止", st["limit"], listOf("60", "70", "80", "85", "90", "95", "100").map { it to "$it%" }, on && !busy) { set("limit", it) }
                Chips("回充差值", st["recharge"], listOf("3", "5", "10").map { it to "$it%" }, on && !busy) { set("recharge", it) }
                Chips("停充方式", st["stop_mode"], listOf("enable" to "停止充电（充电器继续供电）", "suspend" to "断开充电输入"), on && !busy) { set("stop_mode", it) }
            }
        }
    }
    if (busy) BusyOverlay()
}
