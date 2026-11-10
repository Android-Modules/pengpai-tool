package com.dalong.pengpaitool.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 一条曲线。ys 里的 NaN 表示断开；step 为阶梯线：xs[i]..xs[i+1] 之间保持 ys[i]，最后一段延伸到坐标轴末端 */
class Line(
    val name: String,
    val color: Color,
    val xs: FloatArray,
    val ys: FloatArray,
    val right: Boolean = false,
    val step: Boolean = false,
    val dashed: Boolean = false,
    val fill: Boolean = false,
    val width: Float = 2f,
)

/** 两条阶梯线之间的填充带（例如 PID 调节的电流范围） */
class Band(val color: Color, val xs: FloatArray, val lo: FloatArray, val hi: FloatArray)

/** x 区间色块：full 为整个高度的浅色背景，否则画在绘图区底部的细条 */
class Span(val from: Float, val to: Float, val color: Color, val full: Boolean = false)

/** 参考线：vertical 为竖线（x 值），否则为水平线（左轴 y 值） */
class Ref(val value: Float, val color: Color, val label: String = "", val vertical: Boolean = false)

class Axis(val min: Float, val max: Float, val ticks: Int = 4, val fmt: (Float) -> String = { fmtNum(it) })

fun fmtNum(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)

/** 阶梯线在 x 处的取值 */
fun stepAt(xs: FloatArray, ys: FloatArray, x: Float): Float {
    var r = Float.NaN
    for (i in xs.indices) if (xs[i] <= x) r = ys[i] else break
    return r
}

/** 折线上离 x 最近的采样下标（xs 递增） */
fun nearest(xs: FloatArray, x: Float): Int {
    if (xs.isEmpty()) return -1
    var lo = 0
    var hi = xs.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (xs[mid] < x) lo = mid + 1 else hi = mid
    }
    return if (lo > 0 && x - xs[lo - 1] < xs[lo] - x) lo - 1 else lo
}

/**
 * 通用图表：左轴 y，可选右轴 y2；手指点按或横向拖动设置 cursor（x 值），所有共享同一个 cursor 的图会一起显示竖线。
 * cursor 以 State 传入，只在绘制阶段读取，拖动时不会重建曲线路径。
 */
@Composable
fun Chart(
    lines: List<Line>,
    x: Axis,
    y: Axis,
    xLabel: (Float) -> String,
    modifier: Modifier = Modifier,
    y2: Axis? = null,
    height: Dp = 200.dp,
    bands: List<Band> = emptyList(),
    spans: List<Span> = emptyList(),
    refs: List<Ref> = emptyList(),
    cursor: State<Float?>? = null,
    onCursor: ((Float?) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val cursorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val style = TextStyle(fontSize = 10.sp, color = labelColor)
    val padL = 34.dp
    val padR = if (y2 != null) 34.dp else 10.dp
    val padT = 8.dp
    val padB = 20.dp

    // 绘制时记下宽度和左右内边距（px），手势里据此把触点换算成 x 值
    val geo = remember { FloatArray(3) }
    val emit by rememberUpdatedState(onCursor)
    fun pxToX(px: Float): Float {
        val w = geo[0] - geo[1] - geo[2]
        if (w <= 0) return x.min
        return (x.min + (px - geo[1]) / w * (x.max - x.min)).coerceIn(x.min, x.max)
    }

    val gestures = if (onCursor != null) Modifier
        .pointerInput(x.min, x.max) { detectTapGestures { emit?.invoke(if (cursor?.value != null) null else pxToX(it.x)) } }
        .pointerInput(x.min, x.max) { detectHorizontalDragGestures { change, _ -> change.consume(); emit?.invoke(pxToX(change.position.x)) } }
    else Modifier

    Box(
        modifier.fillMaxWidth().height(height).then(gestures).drawWithCache {
            geo[0] = size.width; geo[1] = padL.toPx(); geo[2] = padR.toPx()
            val a = Size(size.width - padL.toPx() - padR.toPx(), size.height - padT.toPx() - padB.toPx())
            val left = padL.toPx()
            val top = padT.toPx()
            val bottom = top + a.height
            fun xToPx(v: Float) = left + (v - x.min) / (x.max - x.min) * a.width
            fun yToPx(v: Float, ax: Axis) = top + (1f - (v.coerceIn(ax.min, ax.max) - ax.min) / (ax.max - ax.min)) * a.height
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

            fun linePath(l: Line, ax: Axis): Pair<Path, Path?> {
                val p = Path()
                val f = if (l.fill) Path() else null
                var open = false
                var firstX = 0f
                var lastX = 0f
                fun closeFill() { if (f != null && open) { f.lineTo(lastX, bottom); f.lineTo(firstX, bottom); f.close() } }
                for (i in l.xs.indices) {
                    val v = l.ys[i]
                    if (v.isNaN()) { closeFill(); open = false; continue }
                    val px = xToPx(l.xs[i]).coerceIn(left, left + a.width)
                    val py = yToPx(v, ax)
                    if (!open) {
                        p.moveTo(px, py); f?.moveTo(px, py); firstX = px; open = true
                    } else if (l.step) {
                        val prev = l.ys[i - 1]
                        val ppy = yToPx(prev, ax)
                        p.lineTo(px, ppy); p.lineTo(px, py); f?.lineTo(px, ppy); f?.lineTo(px, py)
                    } else {
                        p.lineTo(px, py); f?.lineTo(px, py)
                    }
                    lastX = px
                    if (l.step && i == l.xs.lastIndex) {
                        val end = left + a.width
                        p.lineTo(end, py); f?.lineTo(end, py); lastX = end
                    } else if (l.step && i + 1 < l.xs.size && l.ys[i + 1].isNaN()) {
                        val nx = xToPx(l.xs[i + 1]).coerceIn(left, left + a.width)
                        p.lineTo(nx, py); f?.lineTo(nx, py); lastX = nx
                    }
                }
                closeFill()
                return p to f
            }

            val paths = lines.map { l -> linePath(l, if (l.right && y2 != null) y2 else y) }
            val bandPaths = bands.map { b ->
                val p = Path()
                val end = left + a.width
                for (i in b.xs.indices) {
                    val x0 = xToPx(b.xs[i]).coerceIn(left, end)
                    val x1 = if (i + 1 < b.xs.size) xToPx(b.xs[i + 1]).coerceIn(left, end) else end
                    val yh = yToPx(b.hi[i], y)
                    val yl = yToPx(b.lo[i], y)
                    p.moveTo(x0, yh); p.lineTo(x1, yh); p.lineTo(x1, yl); p.lineTo(x0, yl); p.close()
                }
                p
            }
            val yLabels = (0..y.ticks).map { i -> y.min + (y.max - y.min) * i / y.ticks }
            val y2Labels = y2?.let { ax -> (0..y.ticks).map { i -> ax.min + (ax.max - ax.min) * i / y.ticks } }
            val xTicks = x.ticks
            val xLabels = (0..xTicks).map { i -> x.min + (x.max - x.min) * i / xTicks }

            onDrawBehind {
                spans.forEach { s ->
                    val x0 = xToPx(s.from).coerceIn(left, left + a.width)
                    val x1 = xToPx(s.to).coerceIn(left, left + a.width)
                    if (x1 <= x0) return@forEach
                    if (s.full) drawRect(s.color, Offset(x0, top), Size(x1 - x0, a.height))
                    else drawRect(s.color, Offset(x0, bottom - 5.dp.toPx()), Size(x1 - x0, 5.dp.toPx()))
                }
                yLabels.forEachIndexed { i, v ->
                    val py = yToPx(v, y)
                    drawLine(grid, Offset(left, py), Offset(left + a.width, py), 1f)
                    val t = measurer.measure(y.fmt(v), style)
                    drawText(t, topLeft = Offset(left - t.size.width - 4.dp.toPx(), py - t.size.height / 2))
                    y2Labels?.let { l ->
                        val t2 = measurer.measure(y2!!.fmt(l[i]), style)
                        drawText(t2, topLeft = Offset(left + a.width + 4.dp.toPx(), py - t2.size.height / 2))
                    }
                }
                xLabels.forEachIndexed { i, v ->
                    val t = measurer.measure(xLabel(v), style)
                    val px = xToPx(v)
                    val tx = when (i) { 0 -> px; xLabels.lastIndex -> px - t.size.width; else -> px - t.size.width / 2 }
                    drawText(t, topLeft = Offset(tx, bottom + 4.dp.toPx()))
                }
                bandPaths.forEachIndexed { i, p -> drawPath(p, bands[i].color) }
                paths.forEachIndexed { i, (p, f) ->
                    val l = lines[i]
                    if (f != null) drawPath(f, l.color.copy(alpha = 0.14f))
                    drawPath(p, l.color, style = Stroke(l.width.dp.toPx(), pathEffect = if (l.dashed) dash else null))
                }
                // 竖线标签已占用的横向区间，按行记录；重叠时放到下一行
                val placed = mutableListOf<Triple<Int, Float, Float>>()
                refs.forEach { r ->
                    if (r.vertical) {
                        if (r.value < x.min || r.value > x.max) return@forEach
                        val px = xToPx(r.value)
                        drawLine(r.color, Offset(px, top), Offset(px, bottom), 1.5.dp.toPx(), pathEffect = dash)
                        if (r.label.isNotEmpty()) {
                            val t = measurer.measure(r.label, style.copy(color = r.color))
                            val tx = if (px + t.size.width + 4.dp.toPx() > left + a.width) px - t.size.width - 3.dp.toPx() else px + 3.dp.toPx()
                            val tr = tx + t.size.width
                            var row = 0
                            while (placed.any { it.first == row && tx < it.third + 2.dp.toPx() && tr > it.second - 2.dp.toPx() }) row++
                            placed += Triple(row, tx, tr)
                            drawText(t, topLeft = Offset(tx, top + row * (t.size.height + 1.dp.toPx())))
                        }
                    } else {
                        if (r.value < y.min || r.value > y.max) return@forEach
                        val py = yToPx(r.value, y)
                        drawLine(r.color, Offset(left, py), Offset(left + a.width, py), 1.5.dp.toPx(), pathEffect = dash)
                        if (r.label.isNotEmpty()) {
                            val t = measurer.measure(r.label, style.copy(color = r.color))
                            drawText(t, topLeft = Offset(left + 4.dp.toPx(), py - t.size.height - 1.dp.toPx()))
                        }
                    }
                }
                val c = cursor?.value
                if (c != null && c >= x.min && c <= x.max) {
                    val px = xToPx(c)
                    drawLine(cursorColor, Offset(px, top), Offset(px, bottom), 1.dp.toPx())
                    lines.forEach { l ->
                        val v = if (l.step) stepAt(l.xs, l.ys, c) else nearest(l.xs, c).let { if (it >= 0) l.ys[it] else Float.NaN }
                        if (!v.isNaN()) drawCircle(l.color, 3.5.dp.toPx(), Offset(px, yToPx(v, if (l.right && y2 != null) y2 else y)))
                    }
                }
            }
        },
    )
}

/** 图例：色块 + 名称 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Legend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)) {
        items.forEach { (name, color) ->
            Row(Modifier.padding(end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = color, shape = RoundedCornerShape(2.dp), modifier = Modifier.size(10.dp)) {}
                Spacer(Modifier.width(4.dp))
                Text(name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 简单表格：第一行为表头，列宽按 weights 分配 */
@Composable
fun SimpleTable(header: List<String>, rows: List<List<String>>, weights: List<Float> = header.map { 1f }, highlight: Int = -1) {
    val hl = MaterialTheme.colorScheme.primaryContainer
    Box(Modifier.padding(vertical = 6.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                header.forEachIndexed { i, h ->
                    Text(h, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(weights[i]))
                }
            }
            Divider()
            rows.forEachIndexed { r, row ->
                Surface(color = if (r == highlight) hl else Color.Transparent, shape = RoundedCornerShape(6.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        row.forEachIndexed { i, c ->
                            Text(c, fontSize = 12.sp, modifier = Modifier.weight(weights[i]))
                        }
                    }
                }
            }
        }
    }
}

val ChartBlue = Color(0xFF3482FF)
val ChartGreen = Color(0xFF22B573)
val ChartOrange = Color(0xFFFF8A1F)
val ChartRed = Color(0xFFE5482D)
val ChartPurple = Color(0xFF8E5BE8)
val ChartGray = Color(0xFF9AA0AA)
val ChartTeal = Color(0xFF14A3B8)
