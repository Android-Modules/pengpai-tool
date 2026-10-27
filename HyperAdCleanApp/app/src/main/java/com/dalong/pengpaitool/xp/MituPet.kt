package com.dalong.pengpaitool.xp

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.BatteryManager
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import io.github.libxposed.api.XposedModule
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 「设置」里的米兔：趴在窗口左右边缘（露出大半个身子、身体朝屏幕内侧倾斜），
 * 可以拖到任意高度，松手吸附到最近的一边；单击探出身子挥手打招呼，双击跳一下，长按先藏起来。
 * 每个页面（Activity）各挂一只，位置在进程内共享并记在「设置」自己的 SharedPreferences 里。
 */
object MituPetHook {
    private const val TAG = "pengpai_mitu"

    fun install(m: XposedModule) {
        if (AboutAssets.apkPath == null) AboutAssets.apkPath = runCatching { m.moduleApplicationInfo.sourceDir }.getOrNull()
        m.hook(Activity::class.java.getDeclaredMethod("onPostResume")).intercept { chain ->
            val r = chain.proceed()
            (chain.thisObject as? Activity)?.let { a -> runCatching { attach(a) }.onFailure { MyDeviceHook.log("mitu: $it") } }
            r
        }
    }

    private fun attach(a: Activity) {
        val win = a.window ?: return
        val decor = win.decorView as? ViewGroup ?: return
        val old = decor.findViewWithTag<MituPet>(TAG)
        val c = MyDeviceHook.cfg()
        if (!c.pet || PetState.hidden || win.isFloating || a.isInPictureInPictureMode) {
            old?.remove()
            return
        }
        val night = a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val sig = "${c.mituAsset}|${c.theme}|$night|${c.petEco}"
        if (old != null && old.sig == sig) { old.resync(); return }
        old?.remove()
        val bmp = AboutAssets.get(a, c.mituAsset) ?: return
        PetState.load(a)
        val pet = MituPet(a, MituStrip(bmp), Pal.resolve(a, c.theme), sig, c.petEco).apply { tag = TAG }
        pet.addTo(decor)
    }
}

/** 位置：靠哪一边、高度占窗口的比例；长按藏起来后本次「设置」进程内不再出现 */
internal object PetState {
    var right = true
    var yFrac = 0.62f
    var hidden = false
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences("pengpai_mitu", Context.MODE_PRIVATE)
        right = p.getBoolean("right", true)
        yFrac = p.getFloat("y", 0.62f)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("pengpai_mitu", Context.MODE_PRIVATE).edit().putBoolean("right", right).putFloat("y", yFrac).apply()
    }
}

@SuppressLint("ViewConstructor")
internal class MituPet(
    context: Context,
    private val strip: MituStrip,
    private val pal: Pal,
    val sig: String,
    private val eco: Boolean,
) : View(context) {
    private val d = resources.displayMetrics.density
    private val bodyH = 92 * d
    private val bodyW = strip.widthFor(bodyH)
    /** 上方留出跳起、头顶冒符号的空间 */
    private val headroom = 36 * d
    private val motion = MituMotion(this)
    private val bubble = PetBubble(context, pal)
    private val dst = RectF()
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)

    private var docked = true
    private var dragging = false
    private var longFired = false
    private var downX = 0f
    private var downY = 0f
    private var startTx = 0f
    private var startTy = 0f
    private var lastTap = 0L
    private var vt: VelocityTracker? = null
    private var talks = 0
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val single = Runnable { greet() }
    private val long = Runnable { longFired = true; hideAway() }
    private val back = Runnable { dock(true) }
    private val idle = object : Runnable {
        override fun run() {
            if (!live()) return
            if (docked && !dragging && !motion.busy && bubble.visibility != VISIBLE) { idleAct(); stir(2500) }
            postDelayed(this, when {
                eco -> (15_000L..30_000L).random()
                sleepy() -> (9_000L..16_000L).random()
                else -> (5_000L..11_000L).random()
            })
        }
    }

    /**
     * 呼吸：身体轻轻一起一伏、左右微晃。只在做动作、被碰之后呼吸几秒，到点后等这一口气呼完（相位回到 0，
     * 缩放和摆动都正好归位）再停，停下来画面完全静止，屏幕能降回 LTPO 低刷新。省电模式下不呼吸。
     */
    private var phase = 0f
    private var lastFrame = 0L
    private var breathUntil = 0L
    private val breath = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { a ->
            phase = a.animatedValue as Float
            redraw()
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationRepeat(a: android.animation.Animator) {
                if (SystemClock.uptimeMillis() >= breathUntil) { a.cancel(); phase = 0f; invalidate() }
            }
        })
    }

    /** 小动作 30fps 足够，省点电 */
    private fun redraw() {
        val now = SystemClock.uptimeMillis()
        if (now - lastFrame >= 30) { lastFrame = now; invalidate() }
    }

    /** 动画按 30fps 重绘，结束时补画最后一帧，免得停在半路 */
    private fun ValueAnimator.drive(set: (Float) -> Unit) {
        addUpdateListener { set(it.animatedValue as Float); redraw() }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) { invalidate() }
        })
    }

    /** 接下来 [ms] 毫秒内保持呼吸 */
    private fun stir(ms: Long) {
        if (eco || !live()) return
        breathUntil = maxOf(breathUntil, SystemClock.uptimeMillis() + ms)
        breath.duration = if (sleepy()) 4200 else 2800
        if (!breath.isStarted) breath.start()
    }
    /** 伸懒腰：负数下蹲、正数拉长 */
    private var stretch = 0f
    /** 回头张望：临时朝另一边 */
    private var lookAway = false
    private val particles = ArrayList<Particle>()
    private val ptPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Fonts.w(700) }

    private class Particle(val s: String, val x: Float, val size: Float, val color: Int, val born: Long, val life: Long, val drift: Float)

    private fun live() = isAttachedToWindow && windowVisibility == VISIBLE
    private fun sleepy() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it >= 23 || it < 6 }

    init {
        pivotX = bodyW / 2
        pivotY = bodyH + headroom
    }

    fun addTo(decor: ViewGroup) {
        decor.addView(this, FrameLayout.LayoutParams(bodyW.toInt(), (bodyH + headroom).toInt()))
        decor.addView(bubble, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 首次出现：从边缘外滑进来
        alpha = 0f
        post {
            place(false)
            translationX += if (PetState.right) bodyW else -bodyW
            alpha = 1f
            dock(true)
        }
    }

    fun remove() {
        (parent as? ViewGroup)?.removeView(this)
        (bubble.parent as? ViewGroup)?.removeView(bubble)
    }

    /** 回到页面时按共享位置重新摆放（其他页面里可能拖动过） */
    fun resync() {
        if (!dragging) post { dock(false) }
    }

    /** 米兔贴在屏幕边缘，正好压在全面屏返回手势区，不排除的话一拖就退出页面了 */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        systemGestureExclusionRects = listOf(android.graphics.Rect(0, (headroom - 12 * d).toInt(), width, height))
    }

    private fun wake() {
        removeCallbacks(idle)
        postDelayed(idle, (3_000L..6_000L).random())
        stir(4000)
    }

    private fun sleep() {
        removeCallbacks(idle)
        breath.cancel()
        motion.stop()
        particles.clear()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (windowVisibility == VISIBLE) wake()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!isAttachedToWindow) return
        if (visibility == VISIBLE) wake() else sleep()
    }

    override fun onDetachedFromWindow() {
        sleep()
        super.onDetachedFromWindow()
    }

    // ---------- 闲着时的小动作 ----------

    private fun idleAct() {
        if (sleepy() && Math.random() < 0.6) { doze(); return }
        when ((0 until 100).random()) {
            in 0..15 -> { motion.wave(1); emit("♪", 1) }
            in 16..29 -> beckon()
            in 30..42 -> motion.hop(12 * d, 2)
            in 43..54 -> peek()
            in 55..66 -> look()
            in 67..77 -> wiggle()
            in 78..88 -> stretchUp()
            else -> { motion.wave(1); emit("♥", 3) }
        }
        // 偶尔自言自语一句
        if (Math.random() < 0.12) postDelayed({ if (docked && !dragging) say(mumble()) }, 700)
    }

    /** 招手：小幅度来回摆几下 */
    private fun beckon() = motion.play(intArrayOf(1, 2, 3, 4, 3, 2, 3, 4, 3, 2, 1))

    /** 缩进边缘里，再“噔”地探出来 */
    private fun peek() {
        val hideX = dockX() + if (PetState.right) bodyW * 0.4f else -bodyW * 0.4f
        animate().translationX(hideX).setDuration(380).setInterpolator(DecelerateInterpolator()).withEndAction {
            postDelayed({
                if (!docked || dragging) return@postDelayed
                animate().translationX(dockX()).setDuration(420).setInterpolator(OvershootInterpolator(2.2f))
                    .withEndAction { if (docked && !dragging) { motion.wave(1); emit("✦", 2) } }.start()
            }, (500L..1100L).random())
        }.start()
    }

    /** 回头往另一边看看 */
    private fun look() {
        lookAway = true
        invalidate()
        postDelayed({ lookAway = false; invalidate() }, (900L..1500L).random())
        postDelayed({ if (lookAway) emit("?", 1) }, 300)
    }

    /** 扭一扭 */
    private fun wiggle() {
        val l = leanAngle()
        ObjectAnimator.ofFloat(this, View.ROTATION, l, l + 7f, l - 6f, l + 5f, l - 3f, l).apply {
            duration = 720
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** 伸懒腰：先蹲一下再拉长，最后回弹 */
    private fun stretchUp() {
        ValueAnimator.ofFloat(0f, -1f, 1f, 1f, 0f).apply {
            duration = 1300
            drive { stretch = it }
            start()
        }
        postDelayed({ motion.hold(4) }, 450)
        postDelayed({ if (!dragging) motion.play(intArrayOf(4, 3, 2, 1)) }, 1100)
        postDelayed({ emit("✦", 2) }, 600)
    }

    /** 深夜犯困：飘 z */
    private fun doze() {
        emit("z", 3, stagger = 650L)
        ValueAnimator.ofFloat(0f, -0.6f, 0f).apply {
            duration = 2200
            drive { stretch = it }
            start()
        }
    }

    private fun mumble(): String = (if (sleepy()) listOf("好困呀……", "该睡觉啦", "zZZ……") else listOf(
        "无聊ing～", "有人陪我玩吗", "嘿嘿", "今天也要加油鸭", "（东张西望）", "我在这儿呢～",
    )).random()

    /** 头顶冒出符号，往上飘一会儿淡掉 */
    private fun emit(s: String, n: Int, stagger: Long = 220L) {
        val colors = intArrayOf(pal.accent, pal.accent0, pal.accent1)
        repeat(n) { k ->
            postDelayed({
                if (!live()) return@postDelayed
                val cx = if (PetState.right xor lookAway) width * 0.32f else width * 0.68f
                particles += Particle(
                    s, cx + (-8..8).random() * d, (if (s == "z") 14f + k * 4 else (16..20).random().toFloat()) * d,
                    if (s == "♥") 0xFFFF6B8A.toInt() else colors[k % colors.size],
                    SystemClock.uptimeMillis(), if (s == "z") 1900L else 1300L,
                    (if (PetState.right) -1 else 1) * (4..14).random() * d,
                )
                invalidate()
            }, k * stagger)
        }
    }

    // ---------- 位置 ----------

    private fun parentW() = (parent as? View)?.width ?: 0
    private fun parentH() = (parent as? View)?.height ?: 0

    private fun insets(): android.graphics.Insets? =
        rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())

    /** 可停靠的上下范围：避开状态栏 + 标题栏、导航栏 */
    private fun yRange(): Pair<Float, Float> {
        val ins = insets()
        val top = (ins?.top ?: 0) + 64 * d
        val bottom = parentH() - (ins?.bottom ?: 0) - bodyH - headroom - 12 * d
        return top to maxOf(top, bottom)
    }

    private fun dockX(): Float {
        val ins = insets()
        // 露出 72%，其余藏在屏幕边缘外
        return if (PetState.right) parentW() - (ins?.right ?: 0) - bodyW * 0.72f else (ins?.left ?: 0) - bodyW * 0.28f
    }

    private fun peekX(): Float {
        val ins = insets()
        return if (PetState.right) parentW() - (ins?.right ?: 0) - bodyW - 10 * d else (ins?.left ?: 0) + 10 * d
    }

    private fun dockY(): Float {
        val (t, b) = yRange()
        return (t + (b - t) * PetState.yFrac).coerceIn(t, b)
    }

    private fun place(lean: Boolean) {
        translationX = dockX()
        translationY = dockY()
        rotation = if (lean) leanAngle() else 0f
    }

    private fun leanAngle() = if (PetState.right) -10f else 10f

    /** 吸附到边缘趴着 */
    private fun dock(anim: Boolean) {
        removeCallbacks(back)
        docked = true
        invalidate()
        if (!anim) { place(true); return }
        animate().translationX(dockX()).translationY(dockY()).rotation(leanAngle()).scaleX(1f).scaleY(1f)
            .setDuration(420).setInterpolator(OvershootInterpolator(1.1f)).start()
    }

    // ---------- 互动 ----------

    private fun greet() {
        removeCallbacks(back)
        docked = false
        invalidate()
        animate().translationX(peekX()).rotation(0f).setDuration(320).setInterpolator(DecelerateInterpolator(1.6f))
            .withEndAction { say(line()) }.start()
        motion.wave(2)
        postDelayed(back, 3600)
    }

    private fun jump() {
        removeCallbacks(back)
        docked = false
        invalidate()
        animate().translationX(peekX()).rotation(0f).setDuration(240).start()
        motion.hop(26 * d, 2)
        motion.wave(1)
        say(listOf("嘿嘿，被你发现啦～", "跳一跳，精神好！", "再点我就要飞起来啦").random())
        postDelayed(back, 3000)
    }

    private fun hideAway() {
        removeCallbacks(back)
        say("我先躲起来啦，下次打开设置再见～")
        motion.wave(1)
        postDelayed({
            PetState.hidden = true
            bubble.hide()
            animate().translationX(if (PetState.right) parentW().toFloat() + bodyW else -bodyW * 2).alpha(0f)
                .setDuration(380).withEndAction { remove() }.start()
        }, 1500)
    }

    private fun line(): String {
        talks++
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val time = when (h) {
            in 5..10 -> "早上好呀～"
            in 11..12 -> "中午好，记得吃饭哦"
            in 13..17 -> "下午好～"
            in 18..22 -> "晚上好～"
            else -> "夜深了，早点休息吧"
        }
        if (talks == 1) return "$time 我是米兔！"
        val bm = context.getSystemService(BatteryManager::class.java)
        val bat = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        val pool = buildList {
            add(time)
            if (bat in 0..100) add(if (charging) "正在充电，已经 $bat% 啦" else if (bat <= 20) "电量只剩 $bat%，该充电啦" else "电量 $bat%，状态不错")
            add("按住我可以拖到别的地方趴着")
            add("长按我，我就先藏起来")
            add("想找什么设置？顶部搜索更快哦")
            add("今天也要元气满满！")
            add("双击我试试～")
        }
        return pool.random()
    }

    private fun say(text: String) {
        bubble.show(text) { bw, bh ->
            val ins = insets()
            val l = (ins?.left ?: 0) + 8 * d
            val r = parentW() - (ins?.right ?: 0) - 8 * d - bw
            val x = if (PetState.right) translationX - bw + 10 * d else translationX + bodyW - 10 * d
            x.coerceIn(l, maxOf(l, r)) to (translationY + headroom - bh * 0.35f).coerceAtLeast((ins?.top ?: 0) + 8 * d)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 头顶上方那块空白只用来画跳跃和符号，点到了交给下面的列表
                if (e.y < headroom - motion.hop - 12 * d) return false
                stir(6000)
                downX = e.rawX; downY = e.rawY
                startTx = translationX; startTy = translationY
                dragging = false; longFired = false
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                postDelayed(long, 600)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && !longFired && hypot(dx, dy) > slop) {
                    dragging = true
                    removeCallbacks(long); removeCallbacks(back); removeCallbacks(single)
                    bubble.hide()
                    docked = false
                    animate().cancel()
                    animate().scaleX(1.08f).scaleY(1.08f).setDuration(160).start()
                    motion.hold(2)
                }
                if (dragging) {
                    translationX = startTx + dx
                    translationY = startTy + dy
                    vt?.computeCurrentVelocity(1000)
                    // 被拎着时随横向速度左右晃
                    val target = ((vt?.xVelocity ?: 0f) / 90f).coerceIn(-22f, 22f)
                    rotation += (target - rotation) * 0.25f
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(long)
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                vt?.recycle(); vt = null
                if (dragging) {
                    dragging = false
                    val cx = translationX + bodyW / 2 + vx * 0.12f
                    PetState.right = cx > parentW() / 2f
                    val (t, b) = yRange()
                    PetState.yFrac = if (b > t) ((translationY - t) / (b - t)).coerceIn(0f, 1f) else 0.5f
                    PetState.save(context)
                    motion.wave(1)
                    dock(true)
                } else if (!longFired) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTap < 300) {
                        removeCallbacks(single); lastTap = 0; jump()
                    } else {
                        lastTap = now; postDelayed(single, 280)
                    }
                    playSoundEffect(android.view.SoundEffectConstants.CLICK)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(long)
                vt?.recycle(); vt = null
                if (dragging) { dragging = false; dock(true) }
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // 脚下的软阴影，跳起时变小变淡
        val k = 1f - (motion.hop / (30 * d)).coerceIn(0f, 0.7f)
        val sw = bodyW * 0.36f * k
        shadow.shader = RadialGradient(w / 2, h - 5 * d, sw.coerceAtLeast(1f), if (pal.dark) 0x66000000 else 0x33000000, 0, Shader.TileMode.CLAMP)
        c.save()
        c.scale(1f, 0.28f, w / 2, h - 5 * d)
        c.drawCircle(w / 2, h - 5 * d, sw.coerceAtLeast(1f), shadow)
        c.restore()
        dst.set(0f, headroom - motion.hop, bodyW, headroom + bodyH - motion.hop)
        val foot = dst.bottom
        val b = kotlin.math.sin(phase)
        val sy = 1f + 0.022f * b + 0.07f * stretch
        val sx = 1f - 0.012f * b - 0.045f * stretch
        c.save()
        c.rotate(1.6f * kotlin.math.sin(phase * 0.5f), w / 2, foot)
        c.scale(sx, sy, w / 2, foot)
        // 挥手的胳膊朝屏幕内侧：靠右时左右翻转；张望时临时反过来
        strip.draw(c, motion.frame, dst, mirror = PetState.right xor lookAway)
        c.restore()
        drawParticles(c)
    }

    private fun drawParticles(c: Canvas) {
        if (particles.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        particles.removeAll { now - it.born > it.life }
        for (p in particles) {
            val t = (now - p.born).toFloat() / p.life
            val y = headroom + 10 * d - t * 34 * d
            val x = p.x + p.drift * t + 3 * d * kotlin.math.sin(t * 9f)
            val a = (255 * (if (t < 0.15f) t / 0.15f else 1f - (t - 0.15f) / 0.85f)).toInt().coerceIn(0, 255)
            ptPaint.textSize = p.size * (0.7f + 0.3f * minOf(1f, t * 4))
            // 先描一圈浅色边，深浅背景上都看得清
            ptPaint.style = Paint.Style.STROKE
            ptPaint.strokeWidth = 3 * d
            ptPaint.color = if (pal.dark) 0xFF1E2128.toInt() else 0xFFFFFFFF.toInt()
            ptPaint.alpha = a * 3 / 4
            c.drawText(p.s, x, y, ptPaint)
            ptPaint.style = Paint.Style.FILL
            ptPaint.color = p.color
            ptPaint.alpha = a
            c.drawText(p.s, x, y, ptPaint)
        }
        // 没在呼吸（省电模式或歇着）时，由符号自己续帧，飘完就停
        if (particles.isNotEmpty() && !breath.isRunning) postInvalidateDelayed(33)
    }
}

/** 米兔说话的气泡：圆角卡片 + 小尾巴，显示几秒后淡出 */
@SuppressLint("ViewConstructor")
internal class PetBubble(context: Context, private val pal: Pal) : View(context) {
    private val d = resources.displayMetrics.density
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14 * resources.displayMetrics.scaledDensity
        color = pal.title
        typeface = Fonts.w(500)
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var layout: StaticLayout? = null
    private val padH = 14 * d
    private val padV = 10 * d
    private val hideR = Runnable { hide() }

    init { alpha = 0f; visibility = GONE }

    /** [pos] 根据气泡宽高给出左上角位置 */
    fun show(text: String, pos: (Float, Float) -> Pair<Float, Float>) {
        val maxW = (220 * d).toInt()
        val tw = minOf(tp.measureText(text).toInt() + 1, maxW)
        val l = StaticLayout.Builder.obtain(text, 0, text.length, tp, tw).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        layout = l
        val bw = l.width + padH * 2
        val bh = l.height + padV * 2
        (layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.width = bw.toInt(); lp.height = bh.toInt(); layoutParams = lp
        }
        val (x, y) = pos(bw, bh)
        translationX = x; translationY = y
        visibility = VISIBLE
        bringToFront()
        animate().cancel()
        scaleX = 0.9f; scaleY = 0.9f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        removeCallbacks(hideR)
        postDelayed(hideR, 2800)
        invalidate()
    }

    fun hide() {
        removeCallbacks(hideR)
        animate().alpha(0f).setDuration(200).withEndAction { visibility = GONE }.start()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        bg.color = if (pal.dark) 0xF2262A33.toInt() else 0xFAFFFFFF.toInt()
        bg.setShadowLayer(8 * d, 0f, 2 * d, if (pal.dark) 0x66000000 else 0x22000000)
        c.drawPath(path.roundBox(d, d, w - d, h - d * 2, 14 * d), bg)
        bg.clearShadowLayer()
        val l = layout ?: return
        c.save()
        c.translate(padH, padV)
        l.draw(c)
        c.restore()
    }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
}
