package com.dalong.pengpaitool.xp

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** 米兔挥手精灵条：8 帧横排，第 0 帧是站姿，1~7 帧是一次挥手 */
internal class MituStrip(val bmp: Bitmap) {
    val frames = 8
    val fw = bmp.width / frames
    val fh = bmp.height
    private val src = Rect()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** 按目标高度算宽度 */
    fun widthFor(h: Float) = h * fw / fh

    fun draw(c: Canvas, frame: Int, dst: RectF, mirror: Boolean = false) {
        val f = frame.coerceIn(0, frames - 1)
        src.set(f * fw, 0, (f + 1) * fw, fh)
        if (!mirror) { c.drawBitmap(bmp, src, dst, paint); return }
        c.save()
        c.scale(-1f, 1f, dst.centerX(), dst.centerY())
        c.drawBitmap(bmp, src, dst, paint)
        c.restore()
    }
}

/**
 * 米兔动作：逐帧挥手（每帧单独 postDelayed，只在换帧时重绘）和点击时的小跳。
 * 只在宿主可见时运行；[auto] 为 true 时挥完歇一会儿再挥（页头用）。
 */
internal class MituMotion(private val host: View) {
    var frame = 0
        private set
    /** 向上跳起的位移（像素） */
    var hop = 0f
        private set
    var auto = false
    private var seq = IntArray(0)
    private var i = 0
    private var hopAnim: ValueAnimator? = null

    private fun live() = host.isAttachedToWindow && host.windowVisibility == View.VISIBLE

    private val tick = object : Runnable {
        override fun run() {
            if (!live()) { frame = 0; return }
            if (i < seq.size) {
                frame = seq[i++]
                host.invalidate()
                host.postDelayed(this, STEP)
            } else {
                frame = 0
                host.invalidate()
                if (auto) host.postDelayed(loop, IDLE)
            }
        }
    }
    private val loop = Runnable { if (live()) wave(2) }

    /** 挥手 [times] 次后回到站姿 */
    fun wave(times: Int) = play(IntArray(times * 7) { 1 + it % 7 })

    /** 按给定帧序列播一遍后回到站姿 */
    fun play(frames: IntArray) {
        host.removeCallbacks(tick)
        host.removeCallbacks(loop)
        seq = frames
        i = 0
        host.post(tick)
    }

    val busy get() = i < seq.size || hopAnim?.isRunning == true

    /** 停在某一帧（拖动时举手） */
    fun hold(f: Int) {
        host.removeCallbacks(tick)
        host.removeCallbacks(loop)
        frame = f
        host.invalidate()
    }

    fun hop(height: Float, times: Int = 1) {
        hopAnim?.cancel()
        hopAnim = ValueAnimator.ofFloat(0f, times.toFloat()).apply {
            duration = 420L * times
            interpolator = LinearInterpolator()
            var last = 0L
            addUpdateListener { a ->
                hop = height * sin(PI * ((a.animatedValue as Float) % 1f)).toFloat()
                // 跳跃 30fps 足够，省点电
                val now = android.os.SystemClock.uptimeMillis()
                if (now - last >= 30) { last = now; host.invalidate() }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { hop = 0f; host.invalidate() }
            })
            start()
        }
    }

    /** 宿主可见时调用：页头开始循环 */
    fun start() {
        host.removeCallbacks(loop)
        if (auto && i >= seq.size) host.postDelayed(loop, 700)
    }

    fun stop() {
        host.removeCallbacks(tick)
        host.removeCallbacks(loop)
        hopAnim?.cancel()
        hop = 0f
        frame = 0
        i = seq.size
    }

    companion object {
        const val STEP = 115L
        const val IDLE = 2600L
    }
}

/**
 * 自定义页头图片：hook 侧从 LSPosed 远程文件读，App 侧读本地副本（[reader] 由各自设置）。
 * 支持 GIF / WebP 动图和普通图片，按版本号缓存原始字节。
 */
internal object CustomArt {
    @Volatile var reader: (() -> ByteArray?)? = null
    private var cache: Pair<Long, ByteArray?>? = null
    private const val MAX_SIDE = 720

    private fun bytes(ver: Long): ByteArray? = synchronized(this) {
        cache?.takeIf { it.first == ver }?.let { return it.second }
        val b = runCatching { reader?.invoke() }.getOrNull()?.takeIf { it.isNotEmpty() }
        cache = ver to b
        b
    }

    /** 每个宿主各自解码一份：Drawable 只能挂一个 callback */
    fun drawable(ver: Long): Drawable? {
        val b = bytes(ver) ?: return null
        return runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(b))) { d, info, _ ->
                val s = max(info.size.width, info.size.height)
                if (s > MAX_SIDE) d.setTargetSize(info.size.width * MAX_SIDE / s, info.size.height * MAX_SIDE / s)
            }
        }.getOrNull()?.also { d ->
            (d as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        }
    }
}

/**
 * 页头右侧的图：手机图、米兔动图或自定义图片。米兔点一下会跳起来挥手。
 * 宿主需要在可见性变化时调用 [visible]，并在 verifyDrawable 里认 [custom]。
 */
internal class HeroArt(private val host: View, cfg: MyDeviceCfg.Snapshot, phoneAsset: String) {
    val custom: Drawable? = if (cfg.art == MyDeviceCfg.ART_CUSTOM) CustomArt.drawable(cfg.artVer) else null
    private val mituBmp = if (cfg.art == MyDeviceCfg.ART_MITU || (cfg.art == MyDeviceCfg.ART_CUSTOM && custom == null)) AboutAssets.get(host.context, cfg.mituAsset) else null
    val mitu = mituBmp?.let(::MituStrip)
    val phone: Bitmap? = if (mitu == null && custom == null) AboutAssets.get(host.context, phoneAsset) else null
    val motion = MituMotion(host).apply { auto = true }
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init { custom?.callback = host }

    fun visible(v: Boolean) {
        val anim = custom as? AnimatedImageDrawable
        if (v) { motion.start(); anim?.start() } else { motion.stop(); anim?.stop() }
    }

    fun tap() {
        motion.hop(host.resources.displayMetrics.density * 12f)
        motion.wave(2)
    }

    /** 米兔：底边对齐 [bottom]、右边对齐 [right]，高 [h]，返回绘制区域 */
    fun drawMitu(c: Canvas, right: Float, bottom: Float, h: Float): RectF {
        val m = mitu ?: return box.apply { set(right, bottom, right, bottom) }
        val w = m.widthFor(h)
        box.set(right - w, bottom - h, right, bottom)
        c.save()
        c.translate(0f, -motion.hop)
        m.draw(c, motion.frame, box)
        c.restore()
        return box
    }

    /** 自定义图片：等比缩放放进 [area]，靠右下对齐，返回实际区域 */
    fun drawCustom(c: Canvas, area: RectF): RectF {
        val d = custom ?: return box.apply { set(area.right, area.bottom, area.right, area.bottom) }
        val iw = d.intrinsicWidth.coerceAtLeast(1).toFloat()
        val ih = d.intrinsicHeight.coerceAtLeast(1).toFloat()
        val s = minOf(area.width() / iw, area.height() / ih)
        val w = iw * s
        val h = ih * s
        box.set(area.right - w, area.bottom - h, area.right, area.bottom)
        d.setBounds(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
        d.draw(c)
        return box
    }

    fun drawPhone(c: Canvas, dst: RectF) {
        phone?.let { c.drawBitmap(it, null, dst, bmp) }
    }
}
