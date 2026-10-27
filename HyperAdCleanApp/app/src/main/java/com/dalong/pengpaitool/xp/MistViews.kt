package com.dalong.pengpaitool.xp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.widget.LinearLayout
import kotlin.math.min

// 雾蓝白：大号 HyperOS 页头卡片 + 竖版芯片示意图 + 浅蓝玻璃卡片

/** 页头：HyperOS 大字、机型名、OS 版本，右侧是手机图（被卡片底边裁掉）、米兔动图或自定义图片 */
@SuppressLint("ViewConstructor")
class MistHero(context: Context, pal: Pal, cfg: MyDeviceCfg.Snapshot) : AView(context, pal) {
    private val art = HeroArt(this, cfg, "phone_mist.webp")
    private val card = RectF()
    private val artRect = RectF()
    var sys: SysInfo? = null
        set(v) { field = v; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(140f).toInt())
    }

    override fun verifyDrawable(who: android.graphics.drawable.Drawable) = who === art.custom || super.verifyDrawable(who)
    override fun onAttachedToWindow() { super.onAttachedToWindow(); art.visible(windowVisibility == VISIBLE) }
    override fun onDetachedFromWindow() { art.visible(false); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) art.visible(visibility == VISIBLE)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = dp(20f)
        val s = sys
        card.set(0f, 0f, w, h)
        path.roundBox(0f, 0f, w, h, r)
        fill.shader = null
        fill.color = pal.card0
        fill.setShadowLayer(dp(10f), 0f, dp(3f), pal.shadow)
        c.drawPath(path, fill)
        fill.clearShadowLayer()
        val (g0, g1) = if (pal.dark) 0xFF1B2638.toInt() to 0xFF111925.toInt() else 0xFFFAFCFF.toInt() to 0xFFE6F0FB.toInt()
        fill.color = 0xFF000000.toInt()
        fill.shader = LinearGradient(0f, 0f, w, h, g0, g1, Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null

        c.save()
        c.clipPath(path)
        // 壁纸式的弧线
        fill.color = if (pal.dark) 0x0CFFFFFF else 0x55FFFFFF
        c.drawCircle(w * 1.04f, h * 2.0f, h * 1.6f, fill)
        line.strokeWidth = dp(1.2f)
        line.color = if (pal.dark) 0x26FFFFFF else 0xEEFFFFFF.toInt()
        c.drawCircle(w * 1.04f, h * 2.0f, h * 1.6f, line)
        c.drawCircle(w * 0.28f, h * 2.45f, h * 1.85f, line)
        var phoneLeft = w
        artRect.setEmpty()
        val phone = art.phone
        when {
            art.mitu != null -> {
                artRect.set(art.drawMitu(c, w - dp(14f), h - dp(2f), h - dp(10f)))
                phoneLeft = artRect.left
            }
            art.custom != null -> {
                artRect.set(art.drawCustom(c, RectF(w - dp(14f) - dp(124f), dp(10f), w - dp(14f), h - dp(10f))))
                phoneLeft = artRect.left
            }
            phone != null -> {
                val pw = dp(97f)
                val ph = pw * phone.height / phone.width
                val px = w - dp(18f) - pw
                phoneLeft = px
                rect.set(px, dp(12f), px + pw, dp(12f) + ph)
                art.drawPhone(c, rect)
            }
        }
        c.restore()
        line.strokeWidth = dp(1f)
        line.color = pal.cardStroke
        c.drawPath(path.roundBox(0f, 0f, w, h, r), line)

        val x0 = dp(18f)
        val maxW = phoneLeft - x0 - dp(8f)
        val major = s?.osMajor ?: "3"
        val big = sp(40f).let { size ->
            val tw = measure("Hyper", size, 500) + measure("OS $major", size, 500) + dp(4f)
            if (tw > maxW) size * maxW / tw else size
        }
        val y1 = dp(25f) + big * 0.74f
        val hw = gradText(c, "Hyper", x0, y1, big, pal.head0, pal.head1, 500)
        gradText(c, "OS $major", x0 + hw + dp(4f), y1, big, pal.head2, pal.head3, 500)

        zones.clear()
        if (art.mitu != null && !artRect.isEmpty) zones += Zone(RectF(artRect), 0f, art::tap, highlight = false)
        val name = s?.name.orEmpty().ifBlank { android.os.Build.MODEL }
        tp.typeface = Fonts.w(700)
        val ns = tp.fit(name, sp(22f), maxW)
        val y2 = y1 + dp(30f)
        val nw = text(c, name, x0, y2, ns, pal.title, 700)
        s?.onName?.let { zones += Zone(RectF(x0 - dp(6f), y2 - ns - dp(4f), x0 + nw + dp(6f), y2 + dp(7f)), dp(8f), it) }

        val y3 = y2 + dp(23f)
        val lw = text(c, "OS 版本：", x0, y3, sp(14f), pal.sub)
        val vw = text(c, s?.osVersion ?: "-", x0 + lw, y3, sp(14f), pal.accent, 500)
        s?.onVersion?.let { zones += Zone(RectF(x0 - dp(6f), y3 - dp(18f), x0 + lw + vw + dp(6f), y3 + dp(7f)), dp(8f), it) }
        drawPressed(c)
    }
}

/** 竖版芯片示意图：品牌 + 各簇核心 + 缓存 + GPU / ISP / 基带，按处理器排布 */
@SuppressLint("ViewConstructor")
class MistChip(context: Context, pal: Pal, private val soc: SocInfo) : AView(context, pal) {
    private val flame = if (soc.brand == SocInfo.Brand.QUALCOMM) AboutAssets.get(context, "flame_mist.webp") else null
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val d = pal.dark
    private val pin = if (d) 0xFF2B3B55.toInt() else 0xFFD9E6F5.toInt()
    private val pinStroke = if (d) 0xFF3D5172.toInt() else 0xFFB9CDE5.toInt()
    private val frame0 = if (d) 0xFF26344F.toInt() else 0xFFF0F5FC.toInt()
    private val frame1 = if (d) 0xFF1B263B.toInt() else 0xFFD6E3F3.toInt()
    private val frameStroke = if (d) 0xFF3A4C6B.toInt() else 0xFFBCD0E8.toInt()
    private val panel0 = if (d) 0xFF111A2A.toInt() else 0xFFC9D8EB.toInt()
    private val panel1 = if (d) 0xFF0D1522.toInt() else 0xFFB8CAE1.toInt()
    private val tile0 = if (d) 0xFF2B3A58.toInt() else 0xFFF9FBFF.toInt()
    private val tile1 = if (d) 0xFF1F2B44.toInt() else 0xFFE0EAF7.toInt()
    private val tileStroke = if (d) 0x26FFFFFF else 0xD9FFFFFF.toInt()
    private val ink = if (d) 0xFFE6EEFB.toInt() else 0xFF17203A.toInt()
    private val inkSub = if (d) 0xFFA9B9D3.toInt() else 0xFF34415F.toInt()

    private fun tile(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float = dp(5f)) {
        path.roundBox(l, t, r, b, rad)
        fill.color = 0xFF000000.toInt()
        fill.shader = LinearGradient(l, t, l, b, tile0, tile1, Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null
        line.strokeWidth = dp(0.8f)
        line.color = tileStroke
        c.drawPath(path, line)
    }

    /** 两行居中文字：数值 + 单位 */
    private fun twoLine(c: Canvas, l: Float, t: Float, r: Float, b: Float, main: String, unit: String, size: Float) {
        val cx = (l + r) / 2
        tp.typeface = Fonts.w(500)
        val ms = tp.fit(main, size, (r - l) * 0.86f)
        val us = ms * 0.66f
        val total = ms * 0.74f + us * 0.74f + ms * 0.32f
        val top = (t + b) / 2 - total / 2
        text(c, main, cx, top + ms * 0.74f, ms, ink, 500, Paint.Align.CENTER)
        text(c, unit, cx, top + total, us, inkSub, 400, Paint.Align.CENTER)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val p = dp(6f)
        // 引脚
        val pinW = dp(8f)
        val topN = 7
        val sideN = ((h - 2 * p) / dp(23f)).toInt().coerceAtLeast(5)
        fun pinAt(l: Float, t: Float, r: Float, b: Float) {
            path.roundBox(l, t, r, b, dp(2.2f))
            fill.shader = null
            fill.color = pin
            c.drawPath(path, fill)
            line.strokeWidth = dp(0.8f)
            line.color = pinStroke
            c.drawPath(path, line)
        }
        for (i in 0 until topN) {
            val x = p + dp(20f) + (w - 2 * p - dp(40f)) * i / (topN - 1)
            pinAt(x - pinW / 2, 0f, x + pinW / 2, p + dp(4f))
            pinAt(x - pinW / 2, h - p - dp(4f), x + pinW / 2, h)
        }
        for (i in 0 until sideN) {
            val y = p + dp(22f) + (h - 2 * p - dp(44f)) * i / (sideN - 1)
            pinAt(0f, y - pinW / 2, p + dp(4f), y + pinW / 2)
            pinAt(w - p - dp(4f), y - pinW / 2, w, y + pinW / 2)
        }
        // 外框
        path.roundBox(p, p, w - p, h - p, dp(14f))
        fill.color = 0xFF000000.toInt()
        fill.shader = LinearGradient(0f, p, 0f, h - p, frame0, frame1, Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null
        line.strokeWidth = dp(1f)
        line.color = frameStroke
        c.drawPath(path, line)
        line.color = if (d) 0x1FFFFFFF else 0xB3FFFFFF.toInt()
        c.drawPath(path.roundBox(p + dp(1.5f), p + dp(1.5f), w - p - dp(1.5f), h - p - dp(1.5f), dp(12.5f)), line)
        // 内衬
        val il = p + dp(9f)
        val it = p + dp(9f)
        val ir = w - p - dp(9f)
        val ib = h - p - dp(9f)
        path.roundBox(il, it, ir, ib, dp(9f))
        fill.color = 0xFF000000.toInt()
        fill.shader = LinearGradient(0f, it, 0f, ib, panel0, panel1, Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null

        val gap = dp(4f)
        val l = il + dp(5f)
        val r = ir - dp(5f)
        var y = it + dp(5f)
        val bottom = ib - dp(5f)

        // 各簇核心排成若干行：超大核每行最多 4 个，其余每行最多 6 个
        class Row(val n: Int, val khz: Long, val prime: Boolean)
        val rows = ArrayList<Row>()
        soc.clusters.forEachIndexed { i, cl ->
            val n = cl.cpus.size
            val per = if (i == 0) min(n, 4) else min(n, 6)
            var left = n
            while (left > 0) { val k = min(per, left); rows += Row(k, cl.maxKhz, i == 0 && soc.clusters.size > 1); left -= k }
        }
        val headW = if (rows.size >= 3) 2.1f else 2.5f
        val weights = listOf(headW) + rows.map { if (it.prime && it.n <= 4) 1.45f else 1f } + listOf(0.72f, 1.55f)
        val avail = bottom - y - gap * (weights.size - 1)
        val unit = avail / weights.sum()
        var wi = 0
        fun next(): Pair<Float, Float> { val t = y; val b = y + unit * weights[wi++]; y = b + gap; return t to b }

        // 品牌头
        val (ht, hb) = next()
        tile(c, l, ht, r, hb, dp(6f))
        val hh = hb - ht
        val tx = l + dp(8f)
        var textMax = (r - l) - dp(16f)
        if (flame != null) {
            val fh = hh * 0.8f
            val fw = fh * flame.width / flame.height
            rect.set(r - dp(6f) - fw, ht + (hh - fh) / 2, r - dp(6f), ht + (hh + fh) / 2)
            c.drawBitmap(flame, null, rect, bmp)
            textMax = rect.left - tx - dp(2f)
        } else if (soc.brandEn.isNotEmpty()) {
            val ms = hh * 0.62f
            tp.typeface = Fonts.w(700)
            tp.textSize = ms
            val mw = tp.measureText(soc.monogram)
            val mx = r - dp(10f) - mw
            val my = ht + hh / 2 + ms * 0.36f
            tp.color = 0xFF000000.toInt()
            tp.shader = LinearGradient(mx, my - ms, mx + mw, my, 0xFFF0D6A8.toInt(), 0xFFB98C52.toInt(), Shader.TileMode.CLAMP)
            tp.textAlign = Paint.Align.LEFT
            c.drawText(soc.monogram, mx, my, tp)
            tp.shader = null
            textMax = mx - tx - dp(4f)
        }
        if (soc.brandEn.isNotEmpty()) {
            tp.typeface = Fonts.w(500)
            text(c, soc.brandEn, tx, ht + hh * 0.27f, tp.fit(soc.brandEn, hh * 0.17f, textMax), ink, 500)
            text(c, soc.brandCn, tx, ht + hh * 0.46f, tp.fit(soc.brandCn, hh * 0.15f, textMax), ink, 400)
            tp.typeface = Fonts.w(500)
            text(c, soc.series, tx, ht + hh * 0.74f, tp.fit(soc.series, hh * 0.27f, textMax), ink, 500)
            if (soc.suffix.isNotEmpty()) {
                tp.typeface = Fonts.w(400)
                text(c, soc.suffix, tx, ht + hh * 0.9f, tp.fit(soc.suffix, hh * 0.14f, textMax), ink, 400)
            }
        } else {
            tp.typeface = Fonts.w(500)
            text(c, soc.name, tx, ht + hh * 0.55f, tp.fit(soc.name, hh * 0.22f, textMax), ink, 500)
        }

        // 核心
        rows.forEach { row ->
            val (t, b) = next()
            val tw = (r - l - gap * (row.n - 1)) / row.n
            val rh = b - t
            val big = row.prime && row.n <= 4
            for (k in 0 until row.n) {
                val x = l + k * (tw + gap)
                tile(c, x, t, x + tw, b, dp(if (big) 6f else 4f))
                val size = if (big) min(rh * 0.34f, tw * 0.32f) else min(rh * 0.3f, tw * 0.42f)
                twoLine(c, x, t, x + tw, b, SocInfo.ghz(row.khz), "GHz", size)
            }
        }
        // 缓存
        val (ct, cb) = next()
        tile(c, l, ct, r, cb)
        val cs = min((cb - ct) * 0.42f, sp(12f))
        text(c, "L2 / SLC Cache", (l + r) / 2, (ct + cb) / 2 + cs * 0.36f, cs, inkSub, 400, Paint.Align.CENTER)
        // GPU / ISP / 基带
        val (gt, gb) = next()
        val gw = (r - l) * 0.55f
        tile(c, l, gt, l + gw, gb, dp(6f))
        twoLine(c, l, gt, l + gw, gb, soc.gpu, "GPU", min((gb - gt) * 0.27f, sp(15f)))
        val sw = (r - l - gw - gap * 2) / 2
        listOf("ISP", soc.modem).forEachIndexed { i, s ->
            val x = l + gw + gap + i * (sw + gap)
            tile(c, x, gt, x + sw, gb)
            tp.typeface = Fonts.w(400)
            val fs = tp.fit(s, min((gb - gt) * 0.24f, sp(12f)), sw * 0.8f)
            text(c, s, x + sw / 2, (gt + gb) / 2 + fs * 0.36f, fs, ink, 400, Paint.Align.CENTER)
        }
    }
}

/** 处理器卡片右上：标签、温度、名称（最多两行）、型号 · 制程 */
@SuppressLint("ViewConstructor")
class MistProcHead(context: Context, pal: Pal, private val soc: SocInfo) : AView(context, pal) {
    private var lines: List<String> = listOf(soc.name)
    var temp: Float? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        lines = wrap(soc.name, w)
        setMeasuredDimension(w.toInt(), (dp(30f) + lines.size * dp(24f) + dp(30f)).toInt())
    }

    private fun wrap(s: String, w: Float): List<String> {
        val size = sp(18f)
        // 稍缩小能放下一行就不换行，绘制时 fit 会缩到合适字号
        if (measure(s, sp(16f), 700) <= w) return listOf(s)
        // 换行选两行最均衡的断点，避免「Gen」和数字被拆开、末尾单独剩一个词
        val words = s.split(' ')
        val best = (1 until words.size).map { i ->
            listOf(words.take(i).joinToString(" "), words.drop(i).joinToString(" "))
        }.minByOrNull { (a, b) -> maxOf(measure(a, size, 700), measure(b, size, 700)) }
        return best ?: listOf(s)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        text(c, "处理器", 0f, dp(18f), sp(13f), pal.sub)
        tempPill(c, temp, w, 0f, dp(24f), sp(12.5f))
        var y = dp(30f)
        lines.forEach { s ->
            y += dp(24f)
            tp.typeface = Fonts.w(700)
            text(c, s, 0f, y - dp(5f), tp.fit(s, sp(18f), w), pal.title, 700)
        }
        val spec = listOf(soc.model, soc.node).filter { it.isNotBlank() }.joinToString("  ·  ")
        text(c, spec, 0f, y + dp(18f), sp(14.5f), pal.sub)
    }
}

/** 处理器卡片右侧的一行：图标块 + 标题 / 数值 / 补充说明 + 箭头 */
@SuppressLint("ViewConstructor")
class MistRow(context: Context, pal: Pal, private val iconKey: String, private val title: String, private val hasSub: Boolean) : AView(context, pal) {
    var value = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var sub = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var onClick: (() -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(if (hasSub) 66f else 58f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        zones.clear()
        onClick?.let { zones += Zone(RectF(-dp(4f), dp(3f), w + dp(4f), h - dp(3f)), dp(10f), it) }
        drawPressed(c)
        line.strokeWidth = dp(0.8f)
        line.color = pal.divider
        c.drawLine(0f, dp(0.5f), w, dp(0.5f), line)
        val ts = dp(32f)
        val ty = h / 2 - ts / 2
        fill.shader = null
        fill.color = pal.tile
        c.drawPath(path.roundBox(0f, ty, ts, ty + ts, dp(10f)), fill)
        icon(c, iconKey, ts / 2, h / 2, dp(18f), pal.icon)
        val x = ts + dp(12f)
        val maxW = w - x - dp(18f)
        if (hasSub) {
            text(c, title, x, dp(21f), sp(13f), pal.sub)
            text(c, value, x, dp(42f), sp(16f), pal.title, 500)
            tp.typeface = Fonts.w(400)
            text(c, sub, x, dp(58f), tp.fit(sub, sp(11.5f), maxW), pal.faint)
        } else {
            text(c, title, x, dp(23f), sp(13f), pal.sub)
            text(c, value, x, dp(45f), sp(16f), pal.title, 500)
        }
        if (onClick != null) chevron(c, w - dp(5f), h / 2, pal.faint)
    }
}

/** 存储空间：已用 / 总量 + 大号百分比 + 进度条 */
@SuppressLint("ViewConstructor")
class MistStorage(context: Context, pal: Pal) : AView(context, pal) {
    private var used = 0f
    private var total = 0f
    var onClick: (() -> Unit)? = null

    init { background = CardBg(pal, context.dpx(20f), context.dpx(10f)) }

    fun set(u: Float, t: Float) { if (u != used || t != total) { used = u; total = t; invalidate() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(88f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        zones.clear()
        onClick?.let { zones += Zone(RectF(0f, 0f, w, height.toFloat()), dp(20f), it) }
        drawPressed(c)
        val pad = dp(16f)
        val ts = dp(34f)
        fill.shader = null
        fill.color = pal.tile
        c.drawPath(path.roundBox(pad, dp(13f), pad + ts, dp(13f) + ts, dp(10f)), fill)
        icon(c, "storage", pad + ts / 2, dp(13f) + ts / 2, dp(19f), pal.icon)
        val x = pad + ts + dp(12f)
        text(c, "存储空间", x, dp(26f), sp(13f), pal.sub)
        val ratio = if (total > 0) used / total else 0f
        if (total > 0) {
            val uw = text(c, fmtNum(used), x, dp(52f), sp(22f), pal.title, 700)
            text(c, " / ${fmtNum(total)} GB", x + uw, dp(52f), sp(16f), pal.title, 400)
            val pct = (ratio * 100).toInt().toString()
            val ps = sp(13f) * 2.6f
            val unitW = measure("%", sp(20f), 500)
            text(c, "%", w - pad, dp(50f), sp(20f), pal.accent1, 500, Paint.Align.RIGHT)
            val pw = measure(pct, ps, 600)
            gradText(c, pct, w - pad - unitW - dp(2f) - pw, dp(50f), ps, pal.accent0, pal.accent1, 600)
        } else {
            text(c, "-", x, dp(52f), sp(22f), pal.title, 700)
        }
        bar(c, pad, dp(64f), w - pad, dp(74f), ratio)
    }
}

/** 服务按钮：两列胶囊，单数时最后一个占整行 */
@SuppressLint("ViewConstructor")
class MistServices(context: Context, private val pal: Pal) : LinearLayout(context) {
    private var keys: List<String> = emptyList()

    init {
        orientation = VERTICAL
        background = CardBg(pal, context.dpx(20f), context.dpx(10f))
        val p = context.dpx(10f).toInt()
        setPadding(p, 0, p, p)
        clipChildren = false
        clipToPadding = false
    }

    fun update(items: List<PanelItem>) {
        val k = items.map { it.key + it.title }
        if (k == keys) return
        keys = k
        removeAllViews()
        addView(object : AView(context, pal) {
            override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(44f).toInt())
            override fun onDraw(c: Canvas) { text(c, "设备与服务", dp(8f), dp(29f), sp(15f), pal.title, 500) }
        })
        val gap = context.dpx(8f).toInt()
        items.chunked(2).forEachIndexed { i, pair ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL; clipChildren = false }
            pair.forEachIndexed { j, it ->
                val lp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                if (j == 1) lp.marginStart = gap
                row.addView(MistCell(context, pal, it), lp)
            }
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.topMargin = gap
            addView(row, lp)
        }
    }
}

@SuppressLint("ViewConstructor")
class MistCell(context: Context, pal: Pal, private val item: PanelItem) : AView(context, pal) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(46f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = dp(14f)
        path.roundBox(0f, 0f, w, h, r)
        fill.shader = null
        fill.color = pal.cell
        fill.setShadowLayer(dp(4f), 0f, dp(1.5f), pal.shadow)
        c.drawPath(path, fill)
        fill.clearShadowLayer()
        zones.clear()
        item.onClick?.let { zones += Zone(RectF(0f, 0f, w, h), r, it) }
        drawPressed(c)
        val ts = dp(32f)
        val tx = dp(7f)
        val ty = (h - ts) / 2
        fill.color = pal.tile
        c.drawPath(path.roundBox(tx, ty, tx + ts, ty + ts, dp(10f)), fill)
        icon(c, item.key, tx + ts / 2, h / 2, dp(18f), pal.icon)
        val x = tx + ts + dp(12f)
        tp.typeface = Fonts.w(400)
        val fs = tp.fit(item.title, sp(14.5f), w - x - dp(22f))
        text(c, item.title, x, h / 2 + fs * 0.36f, fs, pal.text)
        if (item.onClick != null) chevron(c, w - dp(14f), h / 2, pal.faint)
    }
}
