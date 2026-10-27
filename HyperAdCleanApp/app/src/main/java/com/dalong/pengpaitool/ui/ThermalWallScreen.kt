package com.dalong.pengpaitool.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dalong.pengpaitool.Mod
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SCENE_NAMES = linkedMapOf(
    "NORMAL" to "常规使用", "CHARGE" to "充电", "CLASS0" to "日常应用", "VIDEO" to "视频", "DANMU" to "弹幕视频",
    "VIDEOCHAT" to "视频通话", "PHONE" to "通话", "CAMERA" to "相机", "4K" to "4K 录像", "NAVIGATION" to "导航",
    "MGAME" to "游戏", "TGAME" to "腾讯游戏", "YUANSHEN" to "原神", "XINGTIE" to "星穹铁道", "HIGHFPS" to "高帧率",
    "ARVR" to "AR/VR", "HUANJI" to "换机", "NOLIMITS" to "不限制",
)
private val SCENE_ORDER = SCENE_NAMES.keys.toList()

fun sceneName(k: String?): String = k?.let { SCENE_NAMES[it] ?: it } ?: "-"

/** 快充满速（mA），场景没有电流墙时作为上限参考 */
const val FULL_MA = 18400

/** mi_thermald 的电流墙：温度单位 m℃，电流单位 mA */
class SicRule(val trig: List<Int>, val clr: List<Int>, val target: List<Int>, val max: List<Int>, val min: List<Int>)

/** mi_thermald 的限速等级，编码为 亮屏等级*100+息屏等级 */
class LvlRule(val trig: List<Int>, val clr: List<Int>, val code: List<Int>) {
    fun level(i: Int, screenOn: Boolean) = if (i < 0) 0 else if (screenOn) code[i] / 100 else code[i] % 100
    fun indexAt(m: Int) = trig.indexOfLast { it <= m }
}

class Jeita(val lo: Int, val hi: Int, val curr: Int, val vterm: Int)
class QuickSeg(val lo: Int, val hi: Int, val curr: Int, val para: String)
class VStep(val volt: Int, val from: Int, val to: Int)

class ThermalInfo(
    val kv: Map<String, String>,
    val sic: Map<String, SicRule>,
    val lvl: Map<String, LvlRule>,
    /** 快充协议下 1 级起每级的电流上限（mA） */
    val levelTable: List<Int>,
    val jeita: List<Jeita>,
    val quick: List<QuickSeg>,
    val volt: Map<String, List<VStep>>,
) {
    val scene get() = kv["scene"].orEmpty()
    val scenes: List<String>
        get() = (sic.keys + lvl.keys).distinct().sortedBy { SCENE_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }

    fun num(k: String) = kv[k]?.toLongOrNull()

    /** 限速等级换算成快充电流上限（mA），0 级不限速 */
    fun levelCurr(level: Int): Int? = when {
        level <= 0 || levelTable.isEmpty() -> null
        else -> levelTable[(level - 1).coerceAtMost(levelTable.lastIndex)]
    }

    /** 场景 scene 下机身温度 m（m℃）时系统温控给的快充上限（mA）：电流墙与限速等级取小 */
    fun sysLimit(scene: String, m: Int, screenOn: Boolean): Int {
        val s = sic[scene]
        val l = lvl[scene]
        val sicA = s?.let { r -> r.max.getOrNull(r.trig.indexOfLast { it <= m }) } ?: s?.max?.firstOrNull() ?: FULL_MA
        val lvA = l?.let { r -> levelCurr(r.level(r.indexAt(m), screenOn)) } ?: Int.MAX_VALUE
        return minOf(sicA, lvA)
    }

    /** 电池温度 tc（℃）下充电驱动允许的最大电流（mA）：快充关闭的温度段按普通充电算，0 为停充 */
    fun protectAt(tc: Float): Int {
        if (quick.isEmpty() && jeita.isEmpty()) return FULL_MA
        val q = quick.firstOrNull { tc >= it.lo && tc < it.hi }
        if (q != null && q.curr > 0) return q.curr
        return jeita.firstOrNull { tc >= it.lo && tc < it.hi }?.curr ?: 0
    }

    /** 当前电池温度对应的电压阶梯 */
    fun voltSteps(tempDeci: Long?): Pair<String, List<VStep>>? {
        val t = (tempDeci ?: 300) / 10
        val key = quick.firstOrNull { t >= it.lo && t < it.hi && it.para != "null" }?.para?.split('_')?.takeLast(2)?.joinToString("_")
        val k = key?.takeIf { volt.containsKey(it) } ?: volt.keys.firstOrNull { it == "23_35" } ?: volt.keys.firstOrNull() ?: return null
        return k to volt.getValue(k)
    }

    companion object {
        private fun ints(s: String) = s.trim().split(' ').mapNotNull { it.toIntOrNull() }
        private fun tokens(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        fun parse(text: String): ThermalInfo {
            val kv = mutableMapOf<String, String>()
            val sic = mutableMapOf<String, SicRule>()
            val lvl = mutableMapOf<String, LvlRule>()
            var table = emptyList<Int>()
            var jeita = emptyList<Jeita>()
            var quick = emptyList<QuickSeg>()
            val volt = mutableMapOf<String, List<VStep>>()
            text.lines().forEach { line ->
                val p = line.split('|')
                when (p[0]) {
                    "T" -> if (p.size >= 6) {
                        if (p[2] == "sic" && p.size >= 8) sic[p[1]] = SicRule(ints(p[3]), ints(p[4]), ints(p[5]), ints(p[6]), ints(p[7]))
                        if (p[2] == "lvl") lvl[p[1]] = LvlRule(ints(p[3]), ints(p[4]), ints(p[5]))
                    }
                    // 每级 10 列（不同充电协议），第 5 列起是 PD/PPS 快充
                    "W" -> table = ints(p.getOrElse(1) { "" }).chunked(10).mapNotNull { it.getOrNull(4) }
                    "J" -> jeita = tokens(p.getOrElse(1) { "" }).chunked(8).mapNotNull { c ->
                        if (c.size < 6) null else Jeita(c[0].toIntOrNull() ?: return@mapNotNull null, c[1].toIntOrNull() ?: 0, c[4].toIntOrNull() ?: 0, c[5].toIntOrNull() ?: 0)
                    }
                    "Q" -> quick = tokens(p.getOrElse(1) { "" }).chunked(9).mapNotNull { c ->
                        if (c.size < 6) null else QuickSeg(c[0].toIntOrNull() ?: return@mapNotNull null, c[1].toIntOrNull() ?: 0, c[4].toIntOrNull() ?: 0, c[5])
                    }
                    "V" -> if (p.size >= 3) volt[p[1]] = tokens(p[2]).mapNotNull { it.toIntOrNull() }.chunked(3).filter { it.size == 3 }.map { VStep(it[0], it[1], it[2]) }
                    else -> if ('=' in line) kv[line.substringBefore('=')] = line.substringAfter('=')
                }
            }
            return ThermalInfo(kv, sic, lvl, table, jeita, quick, volt)
        }
    }
}

object ThermalCache {
    var info: ThermalInfo? = null
    suspend fun load(): ThermalInfo = ThermalInfo.parse(Mod.raw("charge thermal")).also { info = it }
}

fun fmtC(m: Int): String = fmtNum(m / 1000f) + "℃"
fun fmtA(ma: Int?): String = ma?.let { "%.1f A".format(it / 1000f).replace(".0 A", " A") } ?: "不限"

private fun tempRange(lo: Int, hi: Int) = when {
    lo <= -1000 -> "< $hi℃"
    hi >= 1000 -> "≥ $lo℃"
    else -> "$lo~$hi℃"
}

@Composable
fun ThermalWallScreen() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var info by remember { mutableStateOf(ThermalCache.info) }
    var pick by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { if (!busy) info = ThermalCache.load(); delay(5000) } }

    val ti = info
    Page(loaded = ti != null) {
        if (ti == null) return@Page
        item {
            Note("充电速度由四层规则共同决定，取其中最小的电流：① 机身温度电流墙 ② 场景限速等级（亮屏/息屏分开）③ 电池温度保护 ④ 电池电压阶梯。「充电控制」里的自定义温控墙解除的是 ①②，③④ 是充电驱动对电池的保护，始终生效。")
        }
        val board = ti.num("board")?.toInt()
        val screenOn = ti.kv["screen"] == "1"
        val level = ti.num("ctrl_limit")?.toInt() ?: 0
        item {
            Section("当前状态", trailing = if (ti.kv["online"] == "1") "已连接充电器" else "未连接") {
                InfoRow("温控场景", sceneName(ti.scene) + (ti.kv["sconfig"]?.let { " · 编号 $it" } ?: ""))
                InfoRow("机身温度（虚拟板温）", board?.let { fmtC(it) } ?: "-")
                InfoRow("电池温度", ti.num("temp")?.let { "%.1f℃".format(it / 10.0) } ?: "-")
                InfoRow("屏幕", if (screenOn) "亮屏" else "息屏")
                InfoRow("限速等级", if (level <= 0) "0 级 · 不限速" else "$level 级 · 快充最多 ${fmtA(ti.levelCurr(level))}")
                InfoRow("机身温度电流墙", ti.num("sys_curr")?.let { if (it >= 22_000_000) "不限制" else "%.1f A".format(it / 1e6) } ?: "-")
                InfoRow("系统温控墙", if (ti.kv["remove"] == "1") "已被充电控制解除" else "生效中")
            }
        }
        val scenes = ti.scenes
        val sel = pick?.takeIf { it in scenes } ?: ti.scene.takeIf { it in scenes } ?: scenes.firstOrNull()
        if (sel != null) item { SceneWall(ti, sel, scenes, board, screenOn) { pick = it } }
        if (ti.levelTable.isNotEmpty()) item { LevelTable(ti, level) }
        if (ti.jeita.isNotEmpty() || ti.quick.isNotEmpty()) item { BatteryTempWall(ti) }
        ti.voltSteps(ti.num("temp"))?.let { (k, steps) -> item { VoltWall(ti, k, steps) } }
        item {
            Section("温控数据") {
                ClickRow(
                    "重新读取全部场景",
                    "系统只会解密当前场景的规则。这里会依次切换 18 个温控场景让系统逐个解密（约 45 秒，期间性能调度会短暂变化，结束后恢复原场景）。已读取 ${scenes.size} 个场景。",
                    onClick = {
                        if (busy) return@ClickRow
                        scope.launch {
                            busy = true
                            val r = Mod.result("charge thermal-scan")
                            info = ThermalCache.load()
                            busy = false
                            scope.toast(snack, r.msg)
                        }
                    },
                )
            }
        }
    }
    if (busy) BusyOverlay()
}

@Composable
private fun SceneWall(ti: ThermalInfo, sel: String, scenes: List<String>, board: Int?, screenOn: Boolean, onPick: (String) -> Unit) {
    val s = ti.sic[sel]
    val l = ti.lvl[sel]
    Section(
        "机身温度 → 充电电流",
        "mi_thermald 每 2 秒按机身温度（多个传感器加权算出的虚拟板温）选档。进入一档后，PID 把电流在阴影范围内调节，让机身温度稳定在该档的目标温度；温度回落到「解除」以下才退回上一档。亮屏/息屏再叠加限速等级，实际取两者中更小的值。",
    ) {
        Chips("场景", sel, scenes.map { it to sceneName(it) + if (it == ti.scene) " · 当前" else "" }, true, onPick)
        val temps = (s?.trig.orEmpty().drop(1) + l?.trig.orEmpty()).map { it / 1000f }
        val lo = kotlin.math.floor(((temps.minOrNull() ?: 30f) - 3f).coerceAtLeast(15f))
        val hi = kotlin.math.ceil(((temps.maxOrNull() ?: 45f) + 3f).coerceAtMost(55f))
        fun limitAt(xc: Float, on: Boolean): Float = ti.sysLimit(sel, (xc * 1000).toInt(), on) / 1000f
        val bps = (listOf(lo) + s?.trig.orEmpty().map { it / 1000f } + l?.trig.orEmpty().map { it / 1000f })
            .filter { it >= lo }.distinct().sorted().toFloatArray()
        val onLine = Line("亮屏实际上限", ChartOrange, bps, FloatArray(bps.size) { limitAt(bps[it], true) }, step = true, width = 2.5f)
        val offLine = Line("息屏实际上限", ChartGreen, bps, FloatArray(bps.size) { limitAt(bps[it], false) }, step = true, dashed = true, width = 2.5f)
        val lines = mutableListOf<Line>()
        val bands = mutableListOf<Band>()
        if (s != null) {
            val xs = s.trig.map { it / 1000f }.toFloatArray()
            bands += Band(ChartBlue.copy(alpha = 0.16f), xs, s.min.map { it / 1000f }.toFloatArray(), s.max.map { it / 1000f }.toFloatArray())
            lines += Line("电流墙上限", ChartBlue, xs, s.max.map { it / 1000f }.toFloatArray(), step = true, width = 1.5f)
        }
        lines += onLine
        lines += offLine
        Chart(
            lines, Axis(lo, hi), Axis(0f, 20f, fmt = { fmtNum(it) + "A" }), xLabel = { fmtNum(it) + "℃" },
            bands = bands,
            refs = listOfNotNull(board?.let { Ref(it / 1000f, ChartRed, "当前 ${fmtC(it)}", vertical = true) }),
            height = 220.dp,
        )
        Legend(
            listOfNotNull(
                s?.let { "电流墙上限" to ChartBlue }, s?.let { "PID 调节范围" to ChartBlue.copy(alpha = 0.3f) },
                "亮屏实际上限" to ChartOrange, "息屏实际上限" to ChartGreen, board?.let { "当前机身温度" to ChartRed },
            ),
        )
        if (s == null) Note("这个场景没有电流墙，只按限速等级限速。")
        if (s != null) {
            val cur = board?.let { b -> s.trig.indexOfLast { it <= b } } ?: -1
            Text("电流墙档位", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            SimpleTable(
                listOf("触发", "解除", "目标温度", "电流范围"),
                s.trig.indices.map { i ->
                    val range = if (s.min[i] == s.max[i]) fmtA(s.max[i]) else "${fmtNum(s.min[i] / 1000f)}~${fmtA(s.max[i])}"
                    listOf(fmtC(s.trig[i]), fmtC(s.clr.getOrElse(i) { 0 }), if (s.target.getOrElse(i) { 0 } == 0) "-" else fmtC(s.target[i]), range)
                },
                listOf(1f, 1f, 1f, 1.3f), highlight = cur,
            )
        }
        if (l != null) {
            val cur = board?.let { l.indexAt(it) } ?: -1
            fun lv(n: Int) = if (n <= 0) "不限速" else "$n 级 · ${fmtA(ti.levelCurr(n))}"
            Text("限速等级档位", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            SimpleTable(
                listOf("触发", "解除", "亮屏", "息屏"),
                listOf(listOf("< ${fmtC(l.trig.first())}", "", "不限速", "不限速")) +
                    l.trig.indices.map { i -> listOf(fmtC(l.trig[i]), fmtC(l.clr.getOrElse(i) { 0 }), lv(l.level(i, true)), lv(l.level(i, false))) },
                listOf(1f, 1f, 1.4f, 1.4f), highlight = cur + 1,
            )
        }
    }
}

@Composable
private fun LevelTable(ti: ThermalInfo, cur: Int) {
    Section("限速等级 → 电流", "充电驱动里每一级对应的快充（PD/PPS）电流上限，0 级不限速。上面场景规则里的等级就按这张表换算。") {
        val maxMa = ti.levelTable.maxOrNull()?.coerceAtLeast(1) ?: 1
        ti.levelTable.forEachIndexed { i, ma ->
            val n = i + 1
            val on = n == cur
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$n 级", fontSize = 12.sp, modifier = Modifier.width(44.dp), color = if (on) ChartRed else MaterialTheme.colorScheme.onSurface)
                Box(Modifier.weight(1f).height(10.dp)) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth().height(10.dp)) {}
                    Surface(color = if (on) ChartRed else ChartBlue, shape = RoundedCornerShape(5.dp), modifier = Modifier.fillMaxWidth(ma.toFloat() / maxMa).height(10.dp)) {}
                }
                Text(fmtA(ma) + if (on) " · 当前" else "", fontSize = 12.sp, modifier = Modifier.width(78.dp).padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun BatteryTempWall(ti: ThermalInfo) {
    val t = ti.num("temp")?.let { it / 10f }
    val noFast = ti.quick.firstOrNull { it.curr == 0 && it.lo <= -1000 }?.hi
    val hotFast = ti.quick.lastOrNull { it.curr == 0 && it.hi >= 1000 }?.lo
    val stop = ti.jeita.lastOrNull { it.curr == 0 && it.hi >= 1000 }?.lo
    val warm = ti.jeita.lastOrNull { it.curr > 0 && hotFast != null && it.lo >= hotFast }
    val desc = buildString {
        append("这一层由充电驱动按电池温度执行，本模块不会也无法解除。")
        noFast?.let { append("电池低于 ${it}℃ 不走快充；") }
        warm?.let { append("${it.lo}℃ 以上只用普通充电，最多 ${fmtA(it.curr)}，截止电压降到 ${"%.2f".format(it.vterm / 1000f)}V（充不满）；") }
        stop?.let { append("${it}℃ 以上停止充电。") }
    }
    Section("电池温度 → 充电电流", desc) {
        fun steps(lo: List<Int>, v: List<Int>) = lo.map { if (it <= -1000) -100f else it.toFloat() }.toFloatArray() to v.map { it / 1000f }.toFloatArray()
        val lines = mutableListOf<Line>()
        if (ti.quick.isNotEmpty()) {
            val (xs, ys) = steps(ti.quick.map { it.lo }, ti.quick.map { it.curr })
            lines += Line("快充上限", ChartBlue, xs, ys, step = true, width = 2.5f)
        }
        if (ti.jeita.isNotEmpty()) {
            val (xs, ys) = steps(ti.jeita.map { it.lo }, ti.jeita.map { it.curr })
            lines += Line("普通充电上限", ChartGray, xs, ys, step = true, dashed = true)
        }
        Chart(
            lines, Axis(0f, 60f), Axis(0f, 20f, fmt = { fmtNum(it) + "A" }), xLabel = { fmtNum(it) + "℃" },
            refs = listOfNotNull(t?.let { Ref(it, ChartRed, "当前 %.1f℃".format(it), vertical = true) }),
            height = 190.dp,
        )
        Legend(listOfNotNull("快充上限" to ChartBlue, "普通充电上限" to ChartGray, t?.let { "当前电池温度" to ChartRed }))
        val cur = t?.let { tv -> ti.jeita.indexOfFirst { tv >= it.lo && tv < it.hi } } ?: -1
        SimpleTable(
            listOf("电池温度", "快充", "普通充电", "截止电压"),
            ti.jeita.map { j ->
                val q = ti.quick.firstOrNull { j.lo >= it.lo && j.lo < it.hi }
                listOf(tempRange(j.lo, j.hi), q?.let { if (it.curr == 0) "关闭" else fmtA(it.curr) } ?: "-", if (j.curr == 0) "停充" else fmtA(j.curr), "%.2f V".format(j.vterm / 1000f))
            },
            listOf(1.2f, 1f, 1f, 1f), highlight = cur,
        )
    }
}

@Composable
private fun VoltWall(ti: ThermalInfo, key: String, steps: List<VStep>) {
    val v = ti.num("volt")?.let { it / 1e6f }
    val range = key.replace('_', '~') + "℃"
    Section(
        "电池电压 → 充电电流",
        "快充按电池电压逐级降流（恒流转恒压）。电压随电量升高，越接近充满电流越小，这是 80% 以后明显变慢的主要原因，和温度无关。下图是电池温度 $range 时使用的阶梯。",
    ) {
        val xs = (listOf(3.8f) + steps.map { it.volt / 1000f }).toFloatArray()
        val ys = (listOf(steps.first().from / 1000f) + steps.map { it.to / 1000f }).toFloatArray()
        Chart(
            listOf(Line("快充电流", ChartPurple, xs, ys, step = true, width = 2.5f, fill = true)),
            Axis(3.8f, 4.6f), Axis(0f, 20f, fmt = { fmtNum(it) + "A" }), xLabel = { "%.1fV".format(it) },
            refs = listOfNotNull(v?.let { Ref(it, ChartRed, "当前 %.3fV".format(it), vertical = true) }),
            height = 170.dp,
        )
        fun fv(mv: Int) = if (mv % 10 == 0) "%.2f".format(mv / 1000f) else "%.3f".format(mv / 1000f)
        SimpleTable(
            listOf("电池电压", "快充电流"),
            listOf(listOf("< ${fv(steps.first().volt)} V", fmtA(steps.first().from))) +
                steps.mapIndexed { i, s ->
                    val next = steps.getOrNull(i + 1)?.volt
                    listOf(if (next != null) "${fv(s.volt)}~${fv(next)} V" else "≥ ${fv(s.volt)} V（截止）", fmtA(s.to))
                },
            listOf(1.4f, 1f),
            highlight = v?.let { vv -> if (vv * 1000 < steps.first().volt) 0 else steps.indexOfLast { it.volt <= vv * 1000 } + 1 } ?: -1,
        )
    }
}
