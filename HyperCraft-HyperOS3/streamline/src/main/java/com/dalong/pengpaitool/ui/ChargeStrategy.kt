package com.dalong.pengpaitool.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** 米系亮屏快充 1.3 的默认阶梯，与 charge.sh 的 TIERS_DEF 一致 */
const val TIERS_DEF = "33:10000,55:6000,65:4000,999:2000"

/** "温度:mA,..." → (温度上界 ℃, mA)，最后一档温度为 999 表示其余温度 */
fun parseTiers(s: String?): List<Pair<Int, Int>> =
    (s?.takeIf { it.isNotBlank() } ?: TIERS_DEF).split(',').mapNotNull { e ->
        val p = e.split(':')
        val t = p.getOrNull(0)?.toIntOrNull()
        val c = p.getOrNull(1)?.toIntOrNull()
        if (t == null || c == null) null else t to c
    }

private fun tierAt(tiers: List<Pair<Int, Int>>, tc: Float): Int =
    tiers.firstOrNull { tc < it.first }?.second ?: tiers.lastOrNull()?.second ?: FULL_MA

/** 阶梯编辑：每行「低于 X℃ → Y A」，最后一行是其余温度 */
@Composable
fun TierEditor(tiers: String?, enabled: Boolean, onSave: (String?) -> Unit) {
    val rows = remember(tiers) {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(parseTiers(tiers).map { (t, ma) -> (if (t >= 999) "" else t.toString()) to fmtNum(ma / 1000f) })
        }
    }
    rows.forEachIndexed { i, (t, a) ->
        val last = i == rows.lastIndex
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!last) {
                Text("低于", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(36.dp))
                OutlinedTextField(
                    t, { v -> rows[i] = v.filter(Char::isDigit).take(2) to a }, enabled = enabled, singleLine = true,
                    suffix = { Text("℃") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            } else {
                Text("其余温度", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(132.dp))
            }
            Text("→", modifier = Modifier.padding(horizontal = 8.dp))
            OutlinedTextField(
                a, { v -> rows[i] = t to v.filter { it.isDigit() || it == '.' }.take(4) }, enabled = enabled, singleLine = true,
                suffix = { Text("A") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(100.dp),
            )
            if (!last && rows.size > 2) IconButton(onClick = { rows.removeAt(i) }, enabled = enabled) { Icon(Icons.Filled.Close, "删除这一档") }
        }
    }
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (rows.size < 8) TextButton(onClick = { rows.add(rows.lastIndex, "" to "") }, enabled = enabled) { Text("添加一档") }
        TextButton(onClick = {
            rows.clear()
            rows.addAll(parseTiers(TIERS_DEF).map { (t, ma) -> (if (t >= 999) "" else t.toString()) to fmtNum(ma / 1000f) })
        }, enabled = enabled) { Text("恢复默认") }
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = {
            val out = rows.mapIndexed { i, (t, a) ->
                val ma = a.toFloatOrNull()?.let { (it * 1000).toInt() } ?: return@FilledTonalButton onSave(null)
                val tc = if (i == rows.lastIndex) 999 else t.toIntOrNull() ?: return@FilledTonalButton onSave(null)
                "$tc:$ma"
            }
            onSave(out.joinToString(","))
        }, enabled = enabled) { Text("保存阶梯") }
    }
}

/** 某一温度点上的充电上限及原因 */
private class Pt(val ma: Int, val why: String)

private fun fmtLimit(p: Pt) = if (p.ma <= 0) p.why else "${fmtA(p.ma)} · ${p.why}"

/**
 * 当前生效策略的温度墙：不论系统温控、自定义温控墙还是阶梯限流，都画出温度 → 充电电流上限，并列出各温度段。
 * 系统温控按机身温度画；另外两种按电池温度画，其中交还系统温控的温度段取当前机身温度下的系统上限。
 */
@Composable
fun StrategyWall(st: Map<String, String>, ti: ThermalInfo?) {
    val on = st["enable"] == "1"
    val mode = if (on) st["thermal"] ?: "custom" else "system"
    val title = when (mode) { "custom" -> "当前温度墙 · 自定义温控墙"; "tier" -> "当前温度墙 · 米系亮屏快充"; else -> "当前温度墙 · 系统温控" }
    if (ti == null) {
        Section(title) { LoadingHint("正在读取温控数据…", Modifier.fillMaxWidth().padding(vertical = 24.dp)) }
        return
    }
    val board = st["board"]?.toIntOrNull()
    val bt = st["temp"]?.toFloatOrNull()?.div(10f)
    val screenOn = st["screen"] == "1"
    if (mode == "system") SystemWall(title, ti, board, screenOn, on) else BatteryWall(title, mode, st, ti, board, bt, screenOn)
}

@Composable
private fun SystemWall(title: String, ti: ThermalInfo, board: Int?, screenOn: Boolean, controlOn: Boolean) {
    val scene = ti.scene
    val s = ti.sic[scene]
    val l = ti.lvl[scene]
    val trig = (s?.trig.orEmpty().drop(1) + l?.trig.orEmpty()).map { it / 1000f }
    val lo = kotlin.math.floor(((trig.minOrNull() ?: 30f) - 3f).coerceAtLeast(15f))
    val hi = kotlin.math.ceil(((trig.maxOrNull() ?: 45f) + 3f).coerceAtMost(55f))
    val xs = (listOf(lo) + s?.trig.orEmpty().map { it / 1000f } + l?.trig.orEmpty().map { it / 1000f })
        .filter { it >= lo && it < hi }.distinct().sorted()
    fun at(x: Float, scr: Boolean) = ti.sysLimit(scene, (x * 1000).toInt() + 1, scr)
    val onY = xs.map { at(it, true) }
    val offY = xs.map { at(it, false) }
    val desc = (if (controlOn) "" else "充电控制未开启，") +
        "按机身温度（虚拟板温）限速，当前场景「${sceneName(scene)}」。亮屏时额外叠加限速等级，所以同样温度下亮屏更慢。电池温度保护和电压阶梯另外生效，见「系统温度墙」。"
    Section(title, desc) {
        board?.let { b ->
            val now = ti.sysLimit(scene, b, screenOn)
            InfoRow("现在", "机身 ${fmtC(b)} · ${if (screenOn) "亮屏" else "息屏"} → ${fmtA(now)}")
        }
        Chart(
            listOf(
                Line("亮屏", ChartOrange, xs.toFloatArray(), onY.map { it / 1000f }.toFloatArray(), step = true, width = 2.5f),
                Line("息屏", ChartGreen, xs.toFloatArray(), offY.map { it / 1000f }.toFloatArray(), step = true, dashed = true, width = 2.5f),
            ),
            Axis(lo, hi), Axis(0f, 20f, fmt = { fmtNum(it) + "A" }), xLabel = { fmtNum(it) + "℃" },
            refs = listOfNotNull(board?.let { Ref(it / 1000f, ChartRed, "当前 ${fmtC(it)}", vertical = true) }),
            height = 190.dp,
        )
        Legend(listOfNotNull("亮屏上限" to ChartOrange, "息屏上限" to ChartGreen, board?.let { "当前机身温度" to ChartRed }))
        // 相邻温度段上限相同的合并成一行
        val rows = mutableListOf<Triple<Float, Int, Int>>()
        xs.indices.forEach { i -> if (rows.isEmpty() || rows.last().second != onY[i] || rows.last().third != offY[i]) rows += Triple(xs[i], onY[i], offY[i]) }
        val cur = board?.let { b -> rows.indexOfLast { it.first * 1000 <= b } } ?: -1
        SimpleTable(
            listOf("机身温度", "亮屏", "息屏"),
            rows.mapIndexed { i, (from, a, b) ->
                val next = rows.getOrNull(i + 1)?.first
                val range = when { i == 0 && next != null -> "< ${fmtNum(next)}℃"; next == null -> "≥ ${fmtNum(from)}℃"; else -> "${fmtNum(from)}~${fmtNum(next)}℃" }
                listOf(range, fmtA(a), fmtA(b))
            },
            listOf(1.2f, 1f, 1f), highlight = cur,
        )
    }
}

@Composable
private fun BatteryWall(title: String, mode: String, st: Map<String, String>, ti: ThermalInfo, board: Int?, bt: Float?, screenOn: Boolean) {
    val wall = st["wall"]?.toFloatOrNull() ?: 42f
    val stop = st["stop_temp"]?.toFloatOrNull() ?: 46f
    val fastSoc = st["fast_soc"]?.toIntOrNull() ?: 100
    val limit = st["limit"]?.toIntOrNull() ?: 100
    val tiers = parseTiers(st["tiers"])
    val scene = ti.scene
    val lo = 10f
    val hi = 60f

    // 交还系统温控时的上限：取当前机身温度下的值（随机身温度变化）
    fun sys(scr: Boolean) = board?.let { ti.sysLimit(scene, it, scr) } ?: FULL_MA
    fun eval(tc: Float, scr: Boolean): Pt {
        if (tc >= stop) return Pt(0, "过热停充")
        val p = ti.protectAt(tc)
        if (p <= 0) return Pt(0, "驱动停充")
        return if (mode == "tier") {
            val cap = tierAt(tiers, tc)
            if (p < cap) Pt(p, "电池保护") else Pt(cap, "阶梯")
        } else {
            val fast = st[if (scr) "screen_on" else "screen_off"] == "1" && tc < wall
            if (fast) Pt(p, "解除温控") else Pt(minOf(p, sys(scr)), "系统温控")
        }
    }

    val bps = sortedSetOf(lo, stop)
    if (mode == "tier") tiers.forEach { bps += it.first.toFloat() } else bps += wall
    ti.quick.forEach { bps += it.lo.toFloat(); bps += it.hi.toFloat() }
    ti.jeita.forEach { bps += it.lo.toFloat(); bps += it.hi.toFloat() }
    val xs = bps.filter { it >= lo && it < hi }
    val onP = xs.map { eval(it + 0.05f, true) }
    val offP = xs.map { eval(it + 0.05f, false) }
    val same = onP.indices.all { onP[it].ma == offP[it].ma && onP[it].why == offP[it].why }

    val desc = buildString {
        if (mode == "tier") append("亮屏、息屏都按电池温度分档限流，不再按机身温度和亮屏等级降速；")
        else append("电池低于 ${fmtNum(wall)}℃ 时，下面开着快充的屏幕状态会解除系统温控；其余情况交还系统温控（按机身温度限速，图中取当前机身温度下的值）；")
        append("${fmtNum(stop)}℃ 暂停充电。实际电流还受电池温度保护和电压阶梯限制，已算进图里。")
    }
    Section(title, desc) {
        bt?.let { t ->
            val now = eval(t, screenOn)
            InfoRow("现在", "电池 %.1f℃ · ${if (screenOn) "亮屏" else "息屏"} → ${fmtLimit(now)}".format(t))
        }
        val lines = if (same) {
            listOf(Line("充电上限", ChartTeal, xs.toFloatArray(), onP.map { it.ma / 1000f }.toFloatArray(), step = true, width = 2.5f, fill = true))
        } else {
            listOf(
                Line("亮屏", ChartOrange, xs.toFloatArray(), onP.map { it.ma / 1000f }.toFloatArray(), step = true, width = 2.5f),
                Line("息屏", ChartGreen, xs.toFloatArray(), offP.map { it.ma / 1000f }.toFloatArray(), step = true, dashed = true, width = 2.5f),
            )
        }
        Chart(
            lines, Axis(lo, hi, ticks = 5), Axis(0f, 20f, fmt = { fmtNum(it) + "A" }), xLabel = { fmtNum(it) + "℃" },
            spans = listOf(Span(stop, hi, ChartRed.copy(alpha = 0.08f), full = true)),
            refs = listOfNotNull(
                bt?.let { Ref(it, ChartRed, "当前 %.1f℃".format(it), vertical = true) },
                if (mode == "custom") Ref(wall, ChartBlue, "温控墙 ${fmtNum(wall)}℃", vertical = true) else null,
            ),
            height = 200.dp,
        )
        Legend(
            (if (same) listOf("充电上限" to ChartTeal) else listOf("亮屏上限" to ChartOrange, "息屏上限" to ChartGreen)) +
                listOfNotNull(bt?.let { "当前电池温度" to ChartRed }, "停充区间" to ChartRed.copy(alpha = 0.3f)),
        )
        // 相邻温度段结果相同的合并成一行
        val rows = mutableListOf<Triple<Float, Pt, Pt>>()
        xs.indices.forEach { i ->
            val l = rows.lastOrNull()
            if (l == null || l.second.ma != onP[i].ma || l.second.why != onP[i].why || l.third.ma != offP[i].ma || l.third.why != offP[i].why) rows += Triple(xs[i], onP[i], offP[i])
        }
        val cur = bt?.let { t -> rows.indexOfLast { it.first <= t } } ?: -1
        fun range(i: Int): String {
            val from = rows[i].first
            val next = rows.getOrNull(i + 1)?.first
            return when { i == 0 && next != null -> "< ${fmtNum(next)}℃"; next == null -> "≥ ${fmtNum(from)}℃"; else -> "${fmtNum(from)}~${fmtNum(next)}℃" }
        }
        if (same) {
            SimpleTable(listOf("电池温度", "充电上限", "依据"), rows.indices.map { i -> listOf(range(i), if (rows[i].second.ma <= 0) "停充" else fmtA(rows[i].second.ma), rows[i].second.why) }, listOf(1.1f, 1f, 1f), highlight = cur)
        } else {
            SimpleTable(listOf("电池温度", "亮屏", "息屏"), rows.indices.map { i -> listOf(range(i), fmtLimit(rows[i].second), fmtLimit(rows[i].third)) }, listOf(0.9f, 1.3f, 1.3f), highlight = cur)
        }
        val notes = buildList {
            if (mode == "custom" && board != null) add("系统温控的值按当前机身 ${fmtC(board)} 计算，机身越热越低。")
            if (fastSoc < 100) add("电量达到 $fastSoc% 后交还系统温控。")
            if (limit < 100) add("充到 $limit% 暂停充电。")
            st["tier_fix"]?.toIntOrNull()?.takeIf { it > 0 && mode == "tier" }?.let { add("本次充电系统已 $it 次改回自己的限流，均已自动重写。") }
        }
        if (notes.isNotEmpty()) Text(notes.joinToString(""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
    }
}
