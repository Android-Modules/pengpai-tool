package com.dalong.pengpaitool.xp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** 服务按钮：key、标题来自系统 Preference，点击转给 Preference */
class PanelItem(val key: String, val title: String, val onClick: (() -> Unit)? = null)

/** 页面上来自系统的内容，以及点击后转给系统原控件的动作 */
class SysInfo(
    val name: String,
    val version: String,
    val storage: String,
    val services: List<PanelItem>,
    val onName: (() -> Unit)? = null,
    val onVersion: (() -> Unit)? = null,
    val onStorage: (() -> Unit)? = null,
    val onParams: (() -> Unit)? = null,
) {
    /** 「OS3.0.306.0.WGNCNXM」→ 3.0.306.0 */
    val osVersion get() = Regex("\\d+(?:\\.\\d+){3}").find(version)?.value ?: version.ifBlank { "-" }
    val osMajor get() = osVersion.substringBefore('.').takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: "3"

    /** 「287.3GB/520GB（扩容8GB）」→ 已用、总量（GB） */
    val storageNums: Pair<Float, Float>? get() {
        val nums = Regex("([\\d.]+)\\s*(TB|GB|MB)").findAll(storage).mapNotNull { m ->
            val v = m.groupValues[1].toFloatOrNull() ?: return@mapNotNull null
            when (m.groupValues[2]) { "TB" -> v * 1024; "MB" -> v / 1024; else -> v }
        }.toList()
        return if (nums.size >= 2 && nums[1] > 0) nums[0] to nums[1] else null
    }
}

/** 一次实时采样后的展示数据 */
class LiveInfo(
    val temp: Float?,
    val ramPct: Int?,
    val curKhz: LongArray,
    val storageUsed: Float?,
    val storageTotal: Float?,
)

internal fun fmtNum(v: Float) = if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)

/** 自绘控件基类：常用画笔、点按反馈、单击区域 */
@SuppressLint("ViewConstructor")
abstract class AView(context: Context, protected val pal: Pal) : View(context) {
    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    protected val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    protected val path = Path()
    protected val rect = RectF()

    /** 可点击区域：按下时高亮对应区域，抬起时触发 */
    protected class Zone(val rect: RectF, val radius: Float, val action: () -> Unit, val highlight: Boolean = true)
    protected val zones = ArrayList<Zone>()
    private var down: Zone? = null
    private var dx = 0f
    private var dy = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = zones.firstOrNull { it.rect.contains(e.x, e.y) } ?: return false
                dx = e.x; dy = e.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (down != null && (abs(e.x - dx) > slop || abs(e.y - dy) > slop)) { down = null; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val z = down
                down = null
                invalidate()
                if (z != null) { playSoundEffect(android.view.SoundEffectConstants.CLICK); z.action() }
            }
            MotionEvent.ACTION_CANCEL -> { down = null; invalidate() }
        }
        return down != null
    }

    protected fun drawPressed(c: Canvas) {
        val z = down?.takeIf { it.highlight } ?: return
        fill.shader = null
        fill.color = pal.pressed
        c.drawPath(path.roundBox(z.rect.left, z.rect.top, z.rect.right, z.rect.bottom, z.radius), fill)
    }

    protected fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, weight: Int = 400, align: Paint.Align = Paint.Align.LEFT): Float {
        tp.shader = null
        tp.textSize = size
        tp.color = color
        tp.typeface = Fonts.w(weight)
        tp.textAlign = align
        c.drawText(s, x, y, tp)
        return tp.measureText(s)
    }

    /** 渐变文字，返回宽度 */
    protected fun gradText(c: Canvas, s: String, x: Float, y: Float, size: Float, c0: Int, c1: Int, weight: Int): Float {
        tp.textSize = size
        tp.typeface = Fonts.w(weight)
        tp.textAlign = Paint.Align.LEFT
        val w = tp.measureText(s)
        tp.color = 0xFF000000.toInt()
        tp.shader = LinearGradient(x, y - size, x + w, y, c0, c1, Shader.TileMode.CLAMP)
        c.drawText(s, x, y, tp)
        tp.shader = null
        return w
    }

    protected fun measure(s: String, size: Float, weight: Int): Float {
        tp.textSize = size
        tp.typeface = Fonts.w(weight)
        return tp.measureText(s)
    }

    protected fun icon(c: Canvas, key: String, cx: Float, cy: Float, size: Float, color: Int, stroke: Float = 1.6f) {
        c.save()
        c.translate(cx - size / 2, cy - size / 2)
        c.scale(size / 24f, size / 24f)
        line.strokeWidth = stroke
        line.color = color
        line.shader = null
        fill.shader = null
        fill.color = color
        Glyphs.draw(c, key, line, fill)
        c.restore()
    }

    protected fun chevron(c: Canvas, cx: Float, cy: Float, color: Int) {
        line.strokeWidth = dp(1.4f)
        line.color = color
        Glyphs.chevron(c, cx, cy, dp(10f), line)
    }

    /** 温度胶囊，右上角对齐 (right, top)，返回宽度 */
    protected fun tempPill(c: Canvas, temp: Float?, right: Float, top: Float, h: Float, size: Float): Float {
        if (temp == null) return 0f
        val s = "%.1f°C".format(temp)
        val tw = measure(s, size, 500)
        val w = tw + h * 1.45f
        fill.shader = null
        fill.color = pal.tempBg
        c.drawPath(path.roundBox(right - w, top, right, top + h, h / 2), fill)
        fill.color = pal.tempDot
        c.drawCircle(right - w + h * 0.55f, top + h / 2, h * 0.14f, fill)
        text(c, s, right - h * 0.42f, top + h / 2 + size * 0.36f, size, pal.tempText, 500, Paint.Align.RIGHT)
        return w
    }

    protected fun bar(c: Canvas, l: Float, t: Float, r: Float, b: Float, ratio: Float) {
        fill.shader = null
        fill.color = pal.track
        val rad = (b - t) / 2
        c.drawPath(path.roundBox(l, t, r, b, rad), fill)
        val fr = l + (r - l) * ratio.coerceIn(0f, 1f)
        if (fr - l < rad * 2) return
        fill.color = 0xFF000000.toInt()
        fill.shader = LinearGradient(l, 0f, fr, 0f, pal.accent0, pal.accent1, Shader.TileMode.CLAMP)
        c.drawPath(path.roundBox(l, t, fr, b, rad), fill)
        fill.shader = null
    }
}
