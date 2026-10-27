package com.dalong.pengpaitool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dalong.pengpaitool.Mod
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/** 一次采样，字段顺序与 charge.sh record() 一致 */
class Sample(
    val t: Long, val soc: Int, val ma: Int, val mv: Int, val bt: Int, val bd: Int,
    val sys: Int, val lvl: Int, val rm: Int, val scr: Int, val sc: Int, val act: String,
) {
    val watt get() = ma * mv / 1e6f
}

private fun parseSamples(text: String): List<Sample> = text.lines().mapNotNull { l ->
    val p = l.trim().split(',')
    if (p.size < 12) return@mapNotNull null
    val n = p.take(11).map { it.toLongOrNull() ?: return@mapNotNull null }
    Sample(n[0], n[1].toInt(), n[2].toInt(), n[3].toInt(), n[4].toInt(), n[5].toInt(), n[6].toInt(), n[7].toInt(), n[8].toInt(), n[9].toInt(), n[10].toInt(), p[11])
}

class SessionSum(
    val id: String, val t0: Long, val t1: Long, val s0: Int, val s1: Int, val pmax: Float, val pavg: Float,
    val tmax: Int, val bmax: Int, val n: Int, val mah: Int, val ongoing: Boolean,
)

private fun parseSessions(text: String): List<SessionSum> = text.lines().mapNotNull { l ->
    val p = l.trim().split('|')
    if (p.size < 12) return@mapNotNull null
    runCatching {
        SessionSum(p[0], p[1].toLong(), p[2].toLong(), p[3].toInt(), p[4].toInt(), p[5].toFloat(), p[6].toFloat(),
            p[7].toInt(), p[8].toInt(), p[9].toInt(), p[10].toInt(), p[11] == "1")
    }.getOrNull()
}

private val DAY = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
private val CLOCK = SimpleDateFormat("HH:mm", Locale.CHINA)
private val CLOCK_S = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

fun fmtDur(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return when {
        h > 0 -> "${h}小时${m}分"
        m > 0 -> "${m}分${s}秒"
        else -> "${s}秒"
    }
}

private val ACT_NAMES = mapOf("fast" to "解除温控快充", "tier" to "阶梯限流", "system" to "系统温控", "hot" to "过热停充", "full" to "到达上限停充", "off" to "系统默认", "idle" to "系统默认")
private val ACT_COLORS = mapOf("fast" to ChartGreen, "tier" to ChartTeal, "system" to ChartBlue, "hot" to ChartRed, "full" to ChartGray)
private val SCREEN_BG = Color(0x26FFB300)

@Composable
fun ChargeLogScreen(open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var st by remember { mutableStateOf(mapOf<String, String>()) }
    var list by remember { mutableStateOf<List<SessionSum>?>(null) }
    var busy by remember { mutableStateOf(false) }
    suspend fun reload() {
        st = Mod.raw("charge status").lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        list = parseSessions(Mod.raw("charge sessions"))
    }
    LaunchedEffect(Unit) { while (true) { reload(); delay(4000) } }

    val on = st["record"] == "1"
    Page(loaded = list != null) {
        item {
            Section("记录", "插上充电器后每 2 秒记录一次电量、电流、电压、电池温度、机身温度、系统温控上限、限速等级、亮屏和当前策略；充满后改为每 30 秒一次。保留最近 60 次充电。") {
                SwitchRow("记录充电曲线", if (on) (if (st["session"].isNullOrBlank()) "已开启，等待充电" else "正在记录本次充电") else "已关闭", on, enabled = st["supported"] == "1" && !busy) {
                    scope.launch {
                        busy = true
                        val r = Mod.result("charge set record ${if (it) 1 else 0}")
                        reload(); busy = false
                        scope.toast(snack, r.msg)
                    }
                }
            }
        }
        item {
            val l = list
            Section("充电记录", trailing = l?.let { "${it.size} 次" }) {
                when {
                    l == null -> {}
                    l.isEmpty() -> Text("还没有记录。插上充电器后会自动开始。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                    else -> l.forEachIndexed { i, s ->
                        if (i > 0) Divider()
                        NavRow(
                            DAY.format(Date(s.t0 * 1000)),
                            "${s.s0}% → ${s.s1}% · ${fmtDur(s.t1 - s.t0)} · " + "平均 %.1fW · 峰值 %.1fW · 最高 %.1f℃".format(s.pavg, s.pmax, s.tmax / 10f),
                            if (s.ongoing) "进行中" else null,
                        ) { open("chargelog:${s.id}") }
                    }
                }
            }
        }
    }
    if (busy) BusyOverlay()
}

@Composable
fun ChargeSessionScreen(id: String, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var data by remember { mutableStateOf<List<Sample>?>(null) }
    var ongoing by remember { mutableStateOf(false) }
    var conf by remember { mutableStateOf(mapOf<String, String>()) }
    var table by remember { mutableStateOf(ThermalCache.info) }
    val cursor = remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(id) {
        if (table == null) table = runCatching { ThermalCache.load() }.getOrNull()
        while (true) {
            conf = Mod.raw("charge status").lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            ongoing = conf["session"] == id
            val cur = data
            val from = cur?.lastOrNull()?.t ?: 0
            val more = parseSamples(Mod.raw("charge session $id $from"))
            if (cur == null || more.isNotEmpty()) data = (cur ?: emptyList()) + more
            if (!ongoing) break
            delay(3000)
        }
    }

    val d = data
    // 曲线数据只随采样变化而重算；拖动十字线时只重绘
    val v = remember(d, table) { d?.takeIf { it.size >= 2 }?.let { SessionView(it, table) } }
    val tempRefs = remember(conf) {
        buildList {
            if (conf["enable"] == "1" && conf["thermal"] == "custom") conf["wall"]?.toFloatOrNull()?.let { add(Ref(it, ChartGreen, "温控墙 ${fmtNum(it)}℃")) }
            if (conf["enable"] == "1") conf["stop_temp"]?.toFloatOrNull()?.let { add(Ref(it, ChartRed, "停充 ${fmtNum(it)}℃")) }
        }
    }
    val onCursor: (Float?) -> Unit = remember { { cursor.value = it } }
    val idx = v?.let { vv -> cursor.value?.let { nearest(vv.xs, it) }?.takeIf { it >= 0 } }
    val pick = idx?.let { v.d[it] }
    Page(loaded = d != null) {
        if (v == null) {
            item { Section("充电详情") { Text("数据太少，稍后再看。", modifier = Modifier.padding(vertical = 12.dp)) } }
            return@Page
        }

        item { Summary(v.d, ongoing) }
        item { SocSpeed(v.d) }
        item {
            Section("功率与电量") {
                Readout(pick, "点按或横向拖动图表查看任意时刻") { "%.1f W · %d%% · %s".format(it.watt, it.soc, ACT_NAMES[it.act] ?: it.act) }
                Chart(v.powerLines, v.xAxis, v.powerAxis, v.xLabel, y2 = v.socAxis, spans = v.powerSpans, cursor = cursor, onCursor = onCursor, height = 210.dp)
                Legend(v.powerLegend)
            }
        }
        item {
            Section("电流与系统温控上限", "虚线是系统温控当时允许的电流（机身温度电流墙和限速等级中较小的一个，空白表示不限或已被解除）。电流贴着虚线走，说明正被温控压着。") {
                Readout(pick, "") {
                    val c = v.cap[idx!!]
                    "%.2f A · %.3f V · 上限 %s · 等级 %d".format(it.ma / 1000f, it.mv / 1000f, if (c.isNaN()) "不限" else "%.1f A".format(c), it.lvl)
                }
                Chart(v.currLines, v.xAxis, v.currAxis, v.xLabel, spans = v.screenSpans, cursor = cursor, onCursor = onCursor)
                Legend(listOf("电流" to ChartPurple, "系统温控上限" to ChartRed, "亮屏" to SCREEN_BG.copy(alpha = 0.5f)))
            }
        }
        item {
            Section("温度") {
                Readout(pick, "") { "电池 %.1f℃ · 机身 %s · %s · %s".format(it.bt / 10f, if (it.bd > 0) "%.1f℃".format(it.bd / 10f) else "-", if (it.scr == 1) "亮屏" else "息屏", sceneName(sceneOf(it.sc))) }
                Chart(v.tempLines, v.xAxis, v.tempAxis, v.xLabel, spans = v.screenSpans, refs = tempRefs, cursor = cursor, onCursor = onCursor)
                Legend(listOf("电池温度" to ChartRed, "机身温度" to ChartTeal, "亮屏" to SCREEN_BG.copy(alpha = 0.5f)))
            }
        }
        if (pick != null) item {
            Section("选中时刻", trailing = CLOCK_S.format(Date(pick.t * 1000))) {
                InfoRow("电量", "${pick.soc}%")
                InfoRow("功率", "%.1f W".format(pick.watt))
                InfoRow("电流 / 电压", "%.2f A / %.3f V".format(pick.ma / 1000f, pick.mv / 1000f))
                InfoRow("电池 / 机身温度", "%.1f℃ / %s".format(pick.bt / 10f, if (pick.bd > 0) "%.1f℃".format(pick.bd / 10f) else "-"))
                InfoRow("机身温度电流墙", if (pick.sys >= 22000 || pick.sys <= 0) "不限制" else "%.1f A".format(pick.sys / 1000f))
                InfoRow("限速等级", if (pick.lvl <= 0) "0 级 · 不限速" else "${pick.lvl} 级 · ${fmtA(table?.levelCurr(pick.lvl))}")
                InfoRow("系统温控墙", if (pick.rm == 1) "已解除" else "生效中")
                InfoRow("屏幕 / 场景", "${if (pick.scr == 1) "亮屏" else "息屏"} / ${sceneName(sceneOf(pick.sc))}")
                InfoRow("策略", ACT_NAMES[pick.act] ?: pick.act)
            }
        }
        if (!ongoing) item {
            OutlinedButton(onClick = {
                scope.launch {
                    val r = Mod.result("charge session-del $id")
                    scope.toast(snack, r.msg)
                    if (r.ok) onDeleted()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("删除这条记录") }
        }
    }
}

/** 一次充电的全部曲线，x 轴为距开始的分钟数 */
private class SessionView(val d: List<Sample>, table: ThermalInfo?) {
    val t0 = d.first().t
    val xs = FloatArray(d.size) { (d[it].t - t0) / 60f }
    private val xMax = xs.last().coerceAtLeast(1f)
    val xAxis = Axis(0f, xMax)
    val xLabel: (Float) -> String = { CLOCK.format(Date((t0 + (it * 60).toLong()) * 1000)) }

    private fun spansOf(key: (Sample) -> Any?, color: (Any?) -> Color?, full: Boolean): List<Span> {
        val out = mutableListOf<Span>()
        var start = 0
        for (i in 1..d.size) {
            if (i == d.size || key(d[i]) != key(d[start])) {
                color(key(d[start]))?.let { out += Span(xs[start], if (i < d.size) xs[i] else xMax, it, full) }
                start = i
            }
        }
        return out
    }

    val screenSpans = spansOf({ it.scr == 1 }, { if (it == true) SCREEN_BG else null }, true)
    private val actSpans = spansOf({ it.act }, { ACT_COLORS[it] }, false)
    val powerSpans = screenSpans + actSpans

    val powerLines = listOf(
        Line("功率", ChartOrange, xs, FloatArray(d.size) { d[it].watt }, fill = true),
        Line("电量", ChartBlue, xs, FloatArray(d.size) { d[it].soc.toFloat() }, right = true),
    )
    // 坐标轴上限取 4 等分后仍是整数的值
    val powerAxis = Axis(0f, (ceil(d.maxOf { it.watt } / 20f) * 20f).coerceAtLeast(20f), fmt = { fmtNum(it) + "W" })
    val socAxis = Axis(0f, 100f, fmt = { fmtNum(it) + "%" })
    val powerLegend = listOf("功率" to ChartOrange, "电量" to ChartBlue, "亮屏" to SCREEN_BG.copy(alpha = 0.5f)) +
        d.map { it.act }.distinct().mapNotNull { a -> ACT_COLORS[a]?.let { (ACT_NAMES[a] ?: a) to it } }

    /** 系统温控当时允许的电流（A），NaN 表示不限或已被解除 */
    val cap = FloatArray(d.size) { i ->
        val s = d[i]
        if (s.rm == 1) Float.NaN else {
            val wall = if (s.sys in 1 until 22000) s.sys else Int.MAX_VALUE
            val lv = table?.levelCurr(s.lvl) ?: Int.MAX_VALUE
            minOf(wall, lv).takeIf { it != Int.MAX_VALUE }?.let { it / 1000f } ?: Float.NaN
        }
    }
    val currLines = listOf(
        Line("电流", ChartPurple, xs, FloatArray(d.size) { d[it].ma / 1000f }),
        Line("温控上限", ChartRed, xs, cap, step = true, dashed = true, width = 1.5f),
    )
    val currAxis = Axis(0f, (ceil(maxOf(d.maxOf { it.ma } / 1000f, cap.filter { !it.isNaN() }.maxOrNull() ?: 0f) / 4f) * 4f).coerceAtLeast(4f), fmt = { fmtNum(it) + "A" })

    private val bt = FloatArray(d.size) { d[it].bt / 10f }
    private val bd = FloatArray(d.size) { if (d[it].bd > 0) d[it].bd / 10f else Float.NaN }
    val tempLines = listOf(Line("电池温度", ChartRed, xs, bt), Line("机身温度", ChartTeal, xs, bd))
    val tempAxis = (bt.toList() + bd.filter { !it.isNaN() }).let { all ->
        val lo = floor((all.minOrNull() ?: 25f) - 1f)
        val span = (ceil(((all.maxOrNull() ?: 45f) + 1f - lo) / 4f) * 4f).coerceAtLeast(4f)
        Axis(lo, lo + span, fmt = { fmtNum(it) + "℃" })
    }
}

/** 温控场景编号 → 场景名（HyperOS 3 / K90 实测） */
private fun sceneOf(n: Int): String = when (n) {
    1 -> "HUANJI"; 5 -> "PHONE"; 6 -> "NOLIMITS"; 7 -> "CLASS0"; 9 -> "ARVR"; 10 -> "NAVIGATION"; 11 -> "VIDEO"
    14 -> "VIDEOCHAT"; 15 -> "CAMERA"; 16, 17 -> "4K"; 18 -> "TGAME"; 19 -> "MGAME"; 20 -> "YUANSHEN"
    25 -> "XINGTIE"; 26 -> "HIGHFPS"; 27 -> "CHARGE"; 28 -> "DANMU"
    else -> "NORMAL"
}

@Composable
private fun Readout(pick: Sample?, hint: String, text: (Sample) -> String) {
    val s = when {
        pick != null -> CLOCK_S.format(Date(pick.t * 1000)) + " · " + text(pick)
        hint.isNotEmpty() -> hint
        else -> return
    }
    Text(s, fontSize = 12.sp, color = if (pick != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun Summary(d: List<Sample>, ongoing: Boolean) {
    val first = d.first()
    val last = d.last()
    var mah = 0.0
    var wh = 0.0
    var e = 0.0
    var tt = 0L
    var screenOn = 0L
    val actTime = mutableMapOf<String, Long>()
    for (i in 1 until d.size) {
        val dt = (d[i].t - d[i - 1].t).takeIf { it in 1..60 } ?: continue
        val s = d[i - 1]
        mah += s.ma * dt / 3600.0
        wh += s.watt * dt / 3600.0
        if (s.ma > 100) { e += s.watt * dt; tt += dt }
        if (s.scr == 1) screenOn += dt
        actTime[s.act] = (actTime[s.act] ?: 0) + dt
    }
    val peak = d.maxBy { it.watt }
    Section("概览", trailing = if (ongoing) "正在充电" else null) {
        InfoRow("时间", "${DAY.format(Date(first.t * 1000))} → ${CLOCK.format(Date(last.t * 1000))}")
        InfoRow("时长", fmtDur(last.t - first.t))
        InfoRow("电量", "${first.soc}% → ${last.soc}%（+${last.soc - first.soc}%）")
        InfoRow("充入电量", "%.0f mAh · %.1f Wh".format(mah, wh))
        InfoRow("平均功率", if (tt > 0) "%.1f W".format(e / tt) else "-")
        InfoRow("峰值功率", "%.1f W（%s · %d%%）".format(peak.watt, CLOCK.format(Date(peak.t * 1000)), peak.soc))
        InfoRow("最高电池温度", "%.1f℃".format(d.maxOf { it.bt } / 10f))
        d.maxOf { it.bd }.takeIf { it > 0 }?.let { InfoRow("最高机身温度", "%.1f℃".format(it / 10f)) }
        InfoRow("亮屏时长", fmtDur(screenOn))
        actTime.entries.filter { it.key in ACT_COLORS }.sortedByDescending { it.value }.forEach { (a, sec) ->
            InfoRow(ACT_NAMES[a] ?: a, fmtDur(sec))
        }
    }
}

/** 每充 10% 用了多久 */
@Composable
private fun SocSpeed(d: List<Sample>) {
    val reach = mutableMapOf<Int, Long>()
    d.forEach { s -> if (!reach.containsKey(s.soc)) reach[s.soc] = s.t }
    fun firstAt(p: Int): Long? = reach.entries.filter { it.key >= p }.minOfOrNull { it.value }
    val start = d.first().soc
    val end = d.maxOf { it.soc }
    val rows = mutableListOf<Triple<String, Long, Boolean>>()
    var lo = (start / 10) * 10
    while (lo < end && lo < 100) {
        val hi = lo + 10
        val a = if (lo < start) d.first().t else firstAt(lo)
        val b = firstAt(hi) ?: if (hi > end) d.last().t else null
        if (a != null && b != null && b >= a) rows += Triple("$lo~$hi%", b - a, lo < start || hi > end)
        lo = hi
    }
    if (rows.isEmpty()) return
    val maxSec = rows.maxOf { it.second }.coerceAtLeast(1)
    Section("每 10% 用时", "数值越大说明这段充得越慢。带 * 的区间没有完整充过。") {
        rows.forEach { (label, sec, partial) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label + if (partial) "*" else "", fontSize = 12.sp, modifier = Modifier.width(64.dp))
                Box(Modifier.weight(1f).height(10.dp)) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().height(10.dp)) {}
                    Surface(color = ChartOrange, shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth(sec.toFloat() / maxSec).height(10.dp)) {}
                }
                Text(fmtDur(sec), fontSize = 12.sp, modifier = Modifier.width(84.dp).padding(start = 8.dp))
            }
        }
    }
}
