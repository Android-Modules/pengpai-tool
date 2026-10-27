package com.dalong.pengpaitool.xp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.cos
import kotlin.math.sin

internal fun View.dp(v: Float) = v * resources.displayMetrics.density
internal fun View.sp(v: Float) = v * resources.displayMetrics.scaledDensity
internal fun Context.dpx(v: Float) = v * resources.displayMetrics.density

/**
 * 用贝塞尔曲线拼圆角矩形。HyperOS 3 的「设置」进程会接管 drawRoundRect / addRoundRect，
 * 遇到描边会画成实心、遇到渐变会画成直角
 */
internal fun Path.roundBox(l: Float, t: Float, r: Float, b: Float, radius: Float): Path {
    val rad = radius.coerceAtMost(minOf(r - l, b - t) / 2)
    val k = rad * 0.4477f
    reset()
    moveTo(l + rad, t)
    lineTo(r - rad, t)
    cubicTo(r - k, t, r, t + k, r, t + rad)
    lineTo(r, b - rad)
    cubicTo(r, b - k, r - k, b, r - rad, b)
    lineTo(l + rad, b)
    cubicTo(l + k, b, l, b - k, l, b - rad)
    lineTo(l, t + rad)
    cubicTo(l, t + k, l + k, t, l + rad, t)
    close()
    return this
}

internal object Fonts {
    private val cache = HashMap<Int, Typeface>()
    fun w(weight: Int): Typeface = cache.getOrPut(weight) { Typeface.create(Typeface.DEFAULT, weight, false) }
}

/** 文字缩到 maxW 以内，返回实际字号 */
internal fun Paint.fit(text: String, size: Float, maxW: Float, min: Float = size * 0.5f): Float {
    textSize = size
    val w = measureText(text)
    if (w > maxW && w > 0) textSize = (size * maxW / w).coerceAtLeast(min)
    return textSize
}

internal fun uptimeText(): String {
    val m = SystemClock.elapsedRealtime() / 60000
    val d = m / 1440
    val h = m % 1440 / 60
    return if (d > 0) "$d 天 $h 时" else "$h 时 ${m % 60} 分"
}

/**
 * 切图放在本应用的 assets/about 里。「设置」进程拿不到本应用的 Resources，
 * 由 hook 侧把 [opener] 设为直接读模块 APK；App 里预览时读自己的 assets
 */
internal object AboutAssets {
    @Volatile var apkPath: String? = null
    private val cache = HashMap<String, Bitmap?>()

    fun get(ctx: Context, name: String): Bitmap? = synchronized(cache) {
        cache.getOrPut(name) { runCatching { open(ctx, "about/$name")?.use(BitmapFactory::decodeStream) }.getOrNull() }
    }

    private fun open(ctx: Context, path: String): InputStream? {
        val apk = apkPath
        if (apk != null) {
            val zip = ZipFile(apk)
            val e = zip.getEntry("assets/$path") ?: return null.also { zip.close() }
            return object : java.io.FilterInputStream(zip.getInputStream(e)) {
                override fun close() { super.close(); zip.close() }
            }
        }
        return ctx.assets.open(path)
    }
}

/** 卡片底：圆角 + 竖向渐变 + 细描边 + 柔和投影（投影画在边界外，父布局需关闭 clipChildren） */
internal class CardBg(
    private val pal: Pal,
    private val radius: Float,
    private val shadowR: Float,
    private val fill0: Int = pal.card0,
    private val fill1: Int = pal.card1,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        path.roundBox(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), radius)
        paint.shader = null
        paint.color = fill0
        if (shadowR > 0) {
            paint.setShadowLayer(shadowR, 0f, shadowR / 3, pal.shadow)
            c.drawPath(path, paint)
            paint.clearShadowLayer()
        }
        paint.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), fill0, fill1, android.graphics.Shader.TileMode.CLAMP)
        c.drawPath(path, paint)
        paint.shader = null
        stroke.strokeWidth = radius / 18f
        stroke.color = pal.cardStroke
        c.drawPath(path, stroke)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** 线条图标，24×24 坐标系；调用前把画布缩放好，[line] 为描边画笔 */
internal object Glyphs {
    private val path = Path()
    private val rect = RectF()

    private fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, p: Paint) =
        c.drawPath(Path().roundBox(l, t, r, b, rad), p)

    private fun arrowHead(c: Canvas, tx: Float, ty: Float, dx: Float, dy: Float, p: Paint) {
        val len = 3.2f
        val n = kotlin.math.hypot(dx, dy)
        val ux = dx / n * len
        val uy = dy / n * len
        c.drawLine(tx, ty, tx - ux + uy * 0.8f, ty - uy - ux * 0.8f, p)
        c.drawLine(tx, ty, tx - ux - uy * 0.8f, ty - uy + ux * 0.8f, p)
    }

    fun chevron(c: Canvas, cx: Float, cy: Float, size: Float, p: Paint) {
        val h = size / 2
        c.drawLine(cx - h / 2, cy - h, cx + h / 2, cy, p)
        c.drawLine(cx + h / 2, cy, cx - h / 2, cy + h, p)
    }

    fun draw(c: Canvas, key: String, line: Paint, fill: Paint) {
        path.reset()
        when (key) {
            "device_more_parameter" -> {
                box(c, 5f, 3f, 19f, 21f, 3f, line)
                c.drawLine(8.8f, 8.5f, 15.2f, 8.5f, line)
                c.drawLine(8.8f, 12.5f, 15.2f, 12.5f, line)
                c.drawLine(8.8f, 16.5f, 12.5f, 16.5f, line)
            }
            "one_key_migrate" -> {
                c.drawLine(4.5f, 8.5f, 19.5f, 8.5f, line); arrowHead(c, 19.5f, 8.5f, 1f, 0f, line)
                c.drawLine(19.5f, 15.5f, 4.5f, 15.5f, line); arrowHead(c, 4.5f, 15.5f, -1f, 0f, line)
            }
            "privacy_settings" -> cloud(c, line)
            "huanji_history", "clock" -> {
                c.drawCircle(12f, 12f, 8.5f, line)
                path.moveTo(12f, 7.5f); path.lineTo(12f, 12f); path.lineTo(15.2f, 14f); c.drawPath(path, line)
            }
            "after_sale_service" -> {
                rect.set(4.5f, 4f, 19.5f, 19f); c.drawArc(rect, 180f, 180f, false, line)
                box(c, 3.5f, 11.5f, 7.5f, 17.5f, 1.6f, line)
                box(c, 16.5f, 11.5f, 20.5f, 17.5f, 1.6f, line)
                path.moveTo(18.5f, 17.5f); path.quadTo(18.5f, 20.5f, 14f, 20.5f); c.drawPath(path, line)
            }
            "service_support" -> {
                c.drawCircle(12f, 12f, 8.5f, line)
                rect.set(9.4f, 6.8f, 14.6f, 12f); c.drawArc(rect, 190f, 250f, false, line)
                c.drawLine(12f, 12f, 12f, 13.8f, line)
                c.drawCircle(12f, 16.8f, 1.1f, fill)
            }
            "trade_in" -> {
                rect.set(4.5f, 4.5f, 19.5f, 19.5f)
                c.drawArc(rect, 200f, 130f, false, line); arrowHead(c, 18.5f, 8.2f, 1f, 0.9f, line)
                c.drawArc(rect, 20f, 130f, false, line); arrowHead(c, 5.5f, 15.8f, -1f, -0.9f, line)
            }
            "maintenancemode_key" -> gear(c, line)
            "factory_reset_key" -> {
                rect.set(4.5f, 4.5f, 19.5f, 19.5f); c.drawArc(rect, -70f, -290f, false, line)
                arrowHead(c, 14.6f, 4.95f, 1f, 0.15f, line)
            }
            "credentials" -> {
                path.moveTo(12f, 3.5f); path.lineTo(19f, 6.3f); path.lineTo(19f, 11.2f)
                path.cubicTo(19f, 15.6f, 16f, 19f, 12f, 20.8f)
                path.cubicTo(8f, 19f, 5f, 15.6f, 5f, 11.2f)
                path.lineTo(5f, 6.3f); path.close(); c.drawPath(path, line)
                path.reset(); path.moveTo(8.9f, 12.1f); path.lineTo(11.1f, 14.3f); path.lineTo(15.3f, 9.9f); c.drawPath(path, line)
            }
            "cpu" -> {
                box(c, 6f, 6f, 18f, 18f, 2.2f, line)
                box(c, 9.3f, 9.3f, 14.7f, 14.7f, 1f, line)
                for (i in 0..2) {
                    val o = 9f + i * 3f
                    c.drawLine(o, 3f, o, 6f, line); c.drawLine(o, 18f, o, 21f, line)
                    c.drawLine(3f, o, 6f, o, line); c.drawLine(18f, o, 21f, o, line)
                }
            }
            "ram" -> {
                box(c, 3.5f, 7f, 20.5f, 16f, 2f, line)
                for (i in 0..2) box(c, 6.5f + i * 4f, 9.8f, 8.7f + i * 4f, 13.2f, 0.5f, line)
                for (i in 0..3) c.drawLine(6.5f + i * 3.7f, 16f, 6.5f + i * 3.7f, 18.5f, line)
            }
            "gpu" -> {
                box(c, 5f, 5f, 19f, 19f, 2.6f, line)
                box(c, 8.6f, 8.6f, 15.4f, 15.4f, 1.2f, line)
                c.drawLine(10.6f, 12f, 13.4f, 12f, line)
                for (i in 0..1) {
                    val o = 10f + i * 4f
                    c.drawLine(o, 2.5f, o, 5f, line); c.drawLine(o, 19f, o, 21.5f, line)
                    c.drawLine(2.5f, o, 5f, o, line); c.drawLine(19f, o, 21.5f, o, line)
                }
            }
            "storage" -> {
                rect.set(5f, 3.5f, 19f, 8.5f); c.drawArc(rect, 0f, 360f, false, line)
                c.drawLine(5f, 6f, 5f, 18f, line); c.drawLine(19f, 6f, 19f, 18f, line)
                rect.set(5f, 9.5f, 19f, 14.5f); c.drawArc(rect, 0f, 180f, false, line)
                rect.set(5f, 15.5f, 19f, 20.5f); c.drawArc(rect, 0f, 180f, false, line)
            }
            "power" -> {
                rect.set(4f, 4.5f, 20f, 20.5f); c.drawArc(rect, -55f, 290f, false, line)
                c.drawLine(12f, 3.5f, 12f, 11f, line)
            }
            else -> {
                c.drawCircle(12f, 12f, 8.5f, line)
                c.drawCircle(12f, 12f, 1.6f, fill)
            }
        }
    }

    private fun cloud(c: Canvas, p: Paint) {
        path.reset()
        path.moveTo(7.5f, 18.5f)
        rect.set(3f, 10.5f, 11f, 18.5f); path.arcTo(rect, 90f, 150f)
        rect.set(7f, 5f, 17f, 15f); path.arcTo(rect, 205f, 150f)
        rect.set(13f, 10.5f, 21f, 18.5f); path.arcTo(rect, 285f, 165f)
        path.close()
        c.drawPath(path, p)
    }

    private fun gear(c: Canvas, p: Paint) {
        path.reset()
        val teeth = 8
        val ro = 9.4f
        val ri = 7.2f
        for (i in 0 until teeth * 4) {
            val a = Math.toRadians(i * 360.0 / (teeth * 4) - 90 + 360.0 / (teeth * 8))
            val r = if ((i / 2) % 2 == 0) ro else ri
            val x = 12f + (cos(a) * r).toFloat()
            val y = 12f + (sin(a) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
        c.drawCircle(12f, 12f, 3f, p)
    }
}
