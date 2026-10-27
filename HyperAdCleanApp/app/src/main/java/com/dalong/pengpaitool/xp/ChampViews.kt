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

// 银灰香槟：超大机型名页头 + 金属芯片实拍图 + 白色卡片、香槟色点缀

/** 页头：机型名（前缀深色、型号香槟色）、OS 版本，右侧斜放的手机 + HyperOS 胶囊，或米兔动图 / 自定义图片 */
@SuppressLint("ViewConstructor")
class ChampHero(context: Context, pal: Pal, cfg: MyDeviceCfg.Snapshot) : AView(context, pal) {
    private val art = HeroArt(this, cfg, "phone_champ.webp")
    private val artRect = RectF()
    var sys: SysInfo? = null
        set(v) { field = v; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(150f).toInt())
    }

    override fun verifyDrawable(who: android.graphics.drawable.Drawable) = who === art.custom || super.verifyDrawable(who)
    override fun onAttachedToWindow() { super.onAttachedToWindow(); art.visible(windowVisibility == VISIBLE) }
    override fun onDetachedFromWindow() { art.visible(false); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) art.visible(visibility == VISIBLE)
    }

    /** HyperOS 胶囊，左上角在 (bx, by) */
    private fun pill(c: Canvas, bx: Float, by: Float, major: String) {
        val fs = sp(14f)
        val tw = measure("HyperOS ", fs, 600) + measure(major, fs, 600)
        val bh = dp(28f)
        val bw = tw + dp(24f)
        path.roundBox(bx, by, bx + bw, by + bh, bh / 2)
        fill.shader = null
        fill.color = if (pal.dark) 0xF0262320.toInt() else 0xF7FFFFFF.toInt()
        fill.setShadowLayer(dp(8f), 0f, dp(2f), if (pal.dark) 0x66000000 else 0x24000000)
        c.drawPath(path, fill)
        fill.clearShadowLayer()
        val hw = text(c, "HyperOS ", bx + dp(12f), by + bh / 2 + fs * 0.36f, fs, pal.title, 600)
        text(c, major, bx + dp(12f) + hw, by + bh / 2 + fs * 0.36f, fs, pal.accent, 600)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = sys
        val major = s?.osMajor ?: "3"
        var phoneLeft = w
        artRect.setEmpty()
        val phone = art.phone
        c.save()
        c.clipRect(0f, 0f, w, h)
        when {
            art.mitu != null -> artRect.set(art.drawMitu(c, w - dp(6f), h - dp(2f), h - dp(10f)))
            art.custom != null -> artRect.set(art.drawCustom(c, RectF(w - dp(6f) - dp(132f), dp(8f), w - dp(6f), h - dp(6f))))
            phone != null -> {
                val pw = dp(106f)
                val ph = pw * phone.height / phone.width
                val px = w - pw - dp(4f)
                rect.set(px, dp(2f), px + pw, dp(2f) + ph)
                art.drawPhone(c, rect)
                artRect.set(px, 0f, px + pw, h)
            }
        }
        c.restore()
        if (!artRect.isEmpty) phoneLeft = artRect.left
        // 手机图：胶囊压在手机左下；米兔和自定义图片：胶囊放到机型名上方，不挡图
        if (phone != null) pill(c, phoneLeft + dp(14f), h - dp(28f) - dp(14f), major)
        else pill(c, dp(12f), dp(6f), major)

        zones.clear()
        if (art.mitu != null && !artRect.isEmpty) zones += Zone(RectF(artRect), 0f, art::tap, highlight = false)
        val x0 = dp(12f)
        val name = s?.name.orEmpty().ifBlank { android.os.Build.MODEL }
        val cut = name.trim().indexOf(' ')
        val head = if (cut > 0) name.substring(0, cut + 1) else ""
        val tail = if (cut > 0) name.substring(cut + 1) else name
        val maxW = phoneLeft - x0 - dp(4f)
        var size = sp(44f)
        val tw = measure(head, size, 800) + measure(tail, size, 800)
        if (tw > maxW) size *= maxW / tw
        val y1 = dp(76f)
        val hw = if (head.isNotEmpty()) gradText(c, head, x0, y1, size, pal.head0, pal.head1, 800) else 0f
        val tw2 = gradText(c, tail, x0 + hw, y1, size, pal.head2, pal.head3, 800)
        s?.onName?.let { zones += Zone(RectF(x0 - dp(6f), y1 - size * 0.85f, x0 + hw + tw2 + dp(6f), y1 + dp(8f)), dp(10f), it) }

        val y2 = dp(122f)
        val lw = text(c, "OS 版本：", x0, y2, sp(16f), pal.sub)
        val vw = text(c, s?.osVersion ?: "-", x0 + lw, y2, sp(16f), pal.accent, 600)
        s?.onVersion?.let { zones += Zone(RectF(x0 - dp(6f), y2 - dp(20f), x0 + lw + vw + dp(6f), y2 + dp(8f)), dp(8f), it) }
        drawPressed(c)
    }
}

/** 金属芯片：底图表面是空白的，品牌、型号按处理器印上去；骁龙叠火焰 Logo，其余画品牌首字母 */
@SuppressLint("ViewConstructor")
class ChampChip(context: Context, pal: Pal, private val soc: SocInfo) : AView(context, pal) {
    private val base = AboutAssets.get(context, "chip_champ.webp")
    private val flame = if (soc.brand == SocInfo.Brand.QUALCOMM) AboutAssets.get(context, "flame_champ.webp") else null
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val ratio = base?.let { it.width.toFloat() / it.height } ?: 1f
        setMeasuredDimension((h * ratio).toInt(), h)
    }

    /** 刻字：底下错开一点的亮色 + 深色正文，看起来像蚀刻在金属上 */
    private fun engrave(c: Canvas, s: String, x: Float, y: Float, size: Float, weight: Int) {
        tp.shader = null
        tp.textSize = size
        tp.typeface = Fonts.w(weight)
        tp.textAlign = Paint.Align.LEFT
        tp.color = 0xB3FFFFFF.toInt()
        c.drawText(s, x, y + size * 0.035f, tp)
        tp.color = 0xFF5A544F.toInt()
        c.drawText(s, x, y, tp)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        base?.let { c.drawBitmap(it, null, rect, bmp) }
        val x = w * 0.197f
        val hasMark = flame != null || soc.brandEn.isNotEmpty()
        if (flame != null) {
            c.drawBitmap(flame, null, rect, bmp)
        } else if (soc.brandEn.isNotEmpty()) {
            val ms = h * 0.42f
            tp.typeface = Fonts.w(800)
            tp.textSize = ms
            tp.textAlign = Paint.Align.CENTER
            val cx = w * 0.69f
            val cy = h * 0.66f + ms * 0.36f
            tp.shader = null
            tp.color = 0xCCFFFFFF.toInt()
            c.drawText(soc.monogram, cx, cy + ms * 0.03f, tp)
            tp.color = 0xFF000000.toInt()
            tp.shader = LinearGradient(cx, cy - ms, cx, cy, 0xFFE4CBB0.toInt(), 0xFFA3836A.toInt(), Shader.TileMode.CLAMP)
            c.drawText(soc.monogram, cx, cy, tp)
            tp.shader = null
            tp.textAlign = Paint.Align.LEFT
        }
        val wide = w * 0.62f
        val narrow = if (hasMark) w * 0.29f else wide
        if (soc.brandEn.isEmpty()) {
            tp.typeface = Fonts.w(600)
            engrave(c, soc.name, x, h * 0.5f, tp.fit(soc.name, h * 0.12f, wide), 600)
            return
        }
        tp.typeface = Fonts.w(600)
        engrave(c, soc.brandEn, x, h * 0.262f, tp.fit(soc.brandEn, h * 0.118f, wide), 600)
        tp.typeface = Fonts.w(600)
        engrave(c, soc.brandCn, x, h * 0.372f, tp.fit(soc.brandCn, h * 0.088f, wide), 600)
        tp.typeface = Fonts.w(400)
        engrave(c, soc.series, x, h * 0.682f, tp.fit(soc.series, h * 0.17f, narrow, h * 0.08f), 400)
        // 芯片上只印档位：有中文档位（至尊版）就只印中文，完整名称在卡片右侧
        val cjk = soc.suffix.split(' ').filter { t -> t.any { it.code > 0x2E80 } }
        val suf = if (cjk.isNotEmpty()) cjk.joinToString(" ") else soc.suffix
        if (suf.isNotEmpty()) {
            tp.typeface = Fonts.w(600)
            engrave(c, suf, x, h * 0.8f, tp.fit(suf, h * 0.085f, narrow, h * 0.05f), 600)
        }
    }
}

/** 处理器卡片：芯片图 + 名称 + 型号，右上温度 */
@SuppressLint("ViewConstructor")
class ChampProcessor(context: Context, pal: Pal, private val soc: SocInfo) : LinearLayout(context) {
    private val info = object : AView(context, pal) {
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            tempPill(c, temp, w, dp(2f), dp(26f), sp(13f))
            text(c, "处理器", 0f, h / 2 - dp(16f), sp(13.5f), pal.sub)
            tp.typeface = Fonts.w(700)
            val ns = tp.fit(soc.name, sp(23f), w, sp(15f))
            text(c, soc.name, 0f, h / 2 + dp(14f), ns, pal.title, 700)
            val spec = listOf(soc.model, soc.node).filter { it.isNotBlank() }.joinToString("  ·  ")
            text(c, spec, 0f, h / 2 + dp(38f), sp(15f), pal.sub)
        }
    }
    var temp: Float? = null
        set(v) { if (field != v) { field = v; info.invalidate() } }

    init {
        orientation = HORIZONTAL
        background = CardBg(pal, context.dpx(20f), context.dpx(8f))
        val p = context.dpx(10f).toInt()
        setPadding(p, p, context.dpx(12f).toInt(), p)
        addView(ChampChip(context, pal, soc), LayoutParams(LayoutParams.WRAP_CONTENT, context.dpx(110f).toInt()))
        addView(info, LayoutParams(0, context.dpx(110f).toInt(), 1f).apply { marginStart = context.dpx(14f).toInt() })
    }
}

/** CPU 各核心实时频率柱：按簇着色，图例显示各簇当前频率 */
@SuppressLint("ViewConstructor")
class ChampCpu(context: Context, pal: Pal, private val soc: SocInfo) : AView(context, pal) {
    private val order = soc.clusters.flatMapIndexed { i, cl -> cl.cpus.map { it to i } }
    private var cur = LongArray(0)
    var onClick: (() -> Unit)? = null

    private val colors = if (!pal.dark) listOf(
        0xFFC1A083.toInt() to 0xFFE6CDB5.toInt(),
        0xFFEBD6C0.toInt() to 0xFFFAF2E8.toInt(),
        0xFFD6BCA1.toInt() to 0xFFF0E2D2.toInt(),
        0xFFE2CDB8.toInt() to 0xFFF6ECE1.toInt(),
    ) else listOf(
        0xFFC9A888.toInt() to 0xFF8E725C.toInt(),
        0xFF8F7A66.toInt() to 0xFF5E5045.toInt(),
        0xFFAA8F76.toInt() to 0xFF6E5E50.toInt(),
        0xFF7E6E60.toInt() to 0xFF524840.toInt(),
    )
    private val track0 = if (pal.dark) 0xFF2C2825.toInt() else 0xFFEEE7E0.toInt()
    private val track1 = if (pal.dark) 0xFF221F1C.toInt() else 0xFFFBF9F6.toInt()

    init { background = CardBg(pal, context.dpx(20f), context.dpx(8f)) }

    fun set(khz: LongArray) { cur = khz; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(136f).toInt())
    }

    private fun khzOf(cpu: Int) = cur.getOrNull(cpu) ?: 0L

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        zones.clear()
        onClick?.let { zones += Zone(RectF(0f, 0f, w, height.toFloat()), dp(20f), it) }
        drawPressed(c)
        val pad = dp(16f)
        val hw = text(c, "CPU · ${soc.cores} 核", pad, dp(30f), sp(15f), pal.sub)
        // 图例：各簇当前频率，从右往左排；放不下时先去掉单位，再整体缩小
        val room = w - pad * 2 - hw - dp(12f)
        val ghz = soc.clusters.map { SocInfo.ghz(khzOf(it.cpus.first())) }
        fun legendW(labels: List<String>, size: Float) = labels.sumOf { (measure(it, size, 400) + dp(22f)).toDouble() }.toFloat()
        var ls = sp(12f)
        var labels = ghz.map { "$it GHz" }
        if (legendW(labels, ls) > room) labels = ghz
        val dots = labels.size * dp(22f)
        val lw = legendW(labels, ls) - dots
        if (lw + dots > room) ls *= ((room - dots) / lw).coerceIn(0.6f, 1f)
        var rx = w - pad
        for (i in soc.clusters.indices.reversed()) {
            val tw = text(c, labels[i], rx, dp(30f) - dp(1f), ls, pal.sub, 400, Paint.Align.RIGHT)
            fill.shader = null
            fill.color = colors[i % colors.size].first
            c.drawCircle(rx - tw - dp(8f), dp(30f) - ls * 0.36f - dp(1f), dp(4f), fill)
            rx -= tw + dp(22f)
        }
        val n = order.size.coerceAtLeast(1)
        val slot = (w - pad * 2) / n
        val bw = min(dp(22f), slot * 0.56f)
        val top = dp(46f)
        val bh = dp(54f)
        order.forEachIndexed { k, (cpu, ci) ->
            val cx = pad + slot * (k + 0.5f)
            val l = cx - bw / 2
            val r = cx + bw / 2
            val rad = dp(6f)
            path.roundBox(l, top, r, top + bh, rad)
            fill.color = 0xFF000000.toInt()
            fill.shader = LinearGradient(0f, top, 0f, top + bh, track0, track1, Shader.TileMode.CLAMP)
            c.drawPath(path, fill)
            val max = soc.clusters[ci].maxKhz
            val ratio = if (max > 0) (khzOf(cpu).toFloat() / max).coerceIn(0.12f, 1f) else 0.12f
            val ft = top + bh * (1 - ratio)
            val (c0, c1) = colors[ci % colors.size]
            fill.shader = LinearGradient(0f, ft, 0f, top + bh, c0, c1, Shader.TileMode.CLAMP)
            c.drawPath(path.roundBox(l, ft, r, top + bh, rad), fill)
            fill.shader = null
            val fs = min(sp(14f), slot * 0.36f)
            text(c, SocInfo.ghz(khzOf(cpu)), cx, top + bh + dp(20f), fs, pal.title, 500, Paint.Align.CENTER)
            text(c, "GHz", cx, top + bh + dp(34f), fs * 0.8f, pal.sub, 400, Paint.Align.CENTER)
        }
    }
}

/** 运行内存 / 存储空间小卡片：图标 + 标题 + 数值 + 百分比 + 进度条 */
@SuppressLint("ViewConstructor")
class ChampStat(context: Context, pal: Pal, private val iconKey: String, private val title: String) : AView(context, pal) {
    private var value = "-"
    private var ratio = 0f
    var onClick: (() -> Unit)? = null

    init { background = CardBg(pal, context.dpx(20f), context.dpx(8f)) }

    fun set(v: String, r: Float) { if (v != value || r != ratio) { value = v; ratio = r; invalidate() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(74f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        zones.clear()
        onClick?.let { zones += Zone(RectF(0f, 0f, w, height.toFloat()), dp(20f), it) }
        drawPressed(c)
        val pad = dp(13f)
        icon(c, iconKey, pad + dp(11f), dp(27f), dp(22f), pal.icon, 1.5f)
        val x = pad + dp(30f)
        text(c, title, x, dp(22f), sp(12.5f), pal.sub)
        val pct = "${(ratio * 100).toInt()}%"
        val pw = text(c, pct, w - pad, dp(45f), sp(12f), pal.sub, 400, Paint.Align.RIGHT)
        tp.typeface = Fonts.w(700)
        text(c, value, x, dp(45f), tp.fit(value, sp(17f), w - x - pad - pw - dp(6f)), pal.title, 700)
        bar(c, pad, dp(56f), w - pad, dp(62f), ratio)
    }
}

/** GPU | 开机时长 双栏卡片 */
@SuppressLint("ViewConstructor")
class ChampDuo(context: Context, pal: Pal, private val soc: SocInfo) : AView(context, pal) {
    var uptime = ""
        set(v) { if (field != v) { field = v; invalidate() } }
    var onGpu: (() -> Unit)? = null

    init { background = CardBg(pal, context.dpx(20f), context.dpx(8f)) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(58f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = w / 2
        zones.clear()
        onGpu?.let { zones += Zone(RectF(0f, 0f, mid, h), dp(20f), it) }
        drawPressed(c)
        val pad = dp(16f)
        icon(c, "gpu", pad + dp(11f), h / 2, dp(22f), pal.icon, 1.5f)
        text(c, "GPU", pad + dp(32f), dp(23f), sp(12.5f), pal.sub)
        tp.typeface = Fonts.w(600)
        text(c, soc.gpu, pad + dp(32f), dp(44f), tp.fit(soc.gpu, sp(16f), mid - pad - dp(32f) - dp(26f)), pal.title, 600)
        if (onGpu != null) chevron(c, mid - dp(18f), h / 2, pal.faint)
        line.strokeWidth = dp(0.8f)
        line.color = pal.divider
        c.drawLine(mid, dp(14f), mid, h - dp(14f), line)
        icon(c, "power", mid + pad + dp(11f), h / 2, dp(22f), pal.icon, 1.5f)
        text(c, "开机时长", mid + pad + dp(32f), dp(23f), sp(12.5f), pal.sub)
        text(c, uptime, mid + pad + dp(32f), dp(44f), sp(16f), pal.title, 600)
    }
}

/** 服务列表：图标 + 标题 + 箭头，行间分隔线从文字处开始 */
@SuppressLint("ViewConstructor")
class ChampList(context: Context, private val pal: Pal) : LinearLayout(context) {
    private var keys: List<String> = emptyList()

    init {
        orientation = VERTICAL
        background = CardBg(pal, context.dpx(20f), context.dpx(8f))
        val p = context.dpx(6f).toInt()
        setPadding(0, p, 0, p)
    }

    fun update(items: List<PanelItem>) {
        val k = items.map { it.key + it.title }
        if (k == keys) return
        keys = k
        removeAllViews()
        items.forEachIndexed { i, it -> addView(ChampRow(context, pal, it, i < items.size - 1)) }
    }
}

@SuppressLint("ViewConstructor")
class ChampRow(context: Context, pal: Pal, private val item: PanelItem, private val divider: Boolean) : AView(context, pal) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(48f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        zones.clear()
        item.onClick?.let { zones += Zone(RectF(dp(6f), 0f, w - dp(6f), h), dp(12f), it) }
        drawPressed(c)
        icon(c, item.key, dp(30f), h / 2, dp(21f), pal.icon, 1.5f)
        val x = dp(60f)
        tp.typeface = Fonts.w(400)
        val fs = tp.fit(item.title, sp(15.5f), w - x - dp(40f))
        text(c, item.title, x, h / 2 + fs * 0.36f, fs, pal.text)
        if (item.onClick != null) chevron(c, w - dp(22f), h / 2, pal.faint)
        if (divider) {
            line.strokeWidth = dp(0.8f)
            line.color = pal.divider
            c.drawLine(x, h - dp(0.4f), w - dp(18f), h - dp(0.4f), line)
        }
    }
}
