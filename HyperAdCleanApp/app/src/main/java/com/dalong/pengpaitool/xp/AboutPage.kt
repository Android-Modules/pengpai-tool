package com.dalong.pengpaitool.xp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.random.Random

/**
 * 整页内容：按主题组装各卡片。系统内容通过 [bind] 传入，
 * 温度、内存、核心频率在页面可见时每秒采样一次。
 * [demo] 为 true 时（App 里预览其它处理器）频率用随机值模拟。
 */
@SuppressLint("ViewConstructor")
class AboutPage(
    context: Context,
    val pal: Pal,
    private val soc: SocInfo,
    private val demo: Boolean = false,
    private val cfg: MyDeviceCfg.Snapshot = MyDeviceCfg.read(null),
) : LinearLayout(context) {
    private var sys: SysInfo? = null

    // 雾蓝白
    private var mHero: MistHero? = null
    private var mHead: MistProcHead? = null
    private var mCpu: MistRow? = null
    private var mRam: MistRow? = null
    private var mUp: MistRow? = null
    private var mStorage: MistStorage? = null
    private var mServices: MistServices? = null

    // 银灰香槟
    private var cHero: ChampHero? = null
    private var cProc: ChampProcessor? = null
    private var cCpu: ChampCpu? = null
    private var cRam: ChampStat? = null
    private var cStorage: ChampStat? = null
    private var cDuo: ChampDuo? = null
    private var cList: ChampList? = null

    private fun d(v: Float) = context.dpx(v).toInt()

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        if (pal.mist) buildMist() else buildChamp()
    }

    private fun add(v: View, top: Float, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT): View {
        addView(v, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = d(top) })
        return v
    }

    private fun buildMist() {
        mHero = MistHero(context, pal, cfg).also { add(it, 4f) }
        val card = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = CardBg(pal, context.dpx(20f), context.dpx(10f))
            setPadding(d(10f), d(12f), d(14f), d(6f))
        }
        card.addView(MistChip(context, pal, soc), LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f))
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        mHead = MistProcHead(context, pal, soc).also { col.addView(it) }
        mCpu = MistRow(context, pal, "cpu", "CPU", true).also { col.addView(it) }
        mRam = MistRow(context, pal, "ram", "运行内存", false).also { col.addView(it) }
        mUp = MistRow(context, pal, "clock", "本次开机时长", false).also { col.addView(it) }
        card.addView(col, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = d(14f) })
        add(card, 10f)
        mStorage = MistStorage(context, pal).also { add(it, 10f) }
        mServices = MistServices(context, pal).also { add(it, 10f) }
        mCpu?.value = "${soc.cores} 核"
        mCpu?.sub = soc.coreSpec + " GHz"
    }

    private fun buildChamp() {
        cHero = ChampHero(context, pal, cfg).also { add(it, 0f) }
        cProc = ChampProcessor(context, pal, soc).also { add(it, 2f) }
        cCpu = ChampCpu(context, pal, soc).also { add(it, 10f) }
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; clipChildren = false }
        cRam = ChampStat(context, pal, "ram", "运行内存").also { row.addView(it, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        cStorage = ChampStat(context, pal, "storage", "存储空间").also {
            row.addView(it, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = d(10f) })
        }
        add(row, 10f)
        cDuo = ChampDuo(context, pal, soc).also { add(it, 10f) }
        cList = ChampList(context, pal).also { add(it, 10f) }
    }

    fun bind(s: SysInfo) {
        val first = sys == null
        sys = s
        mHero?.sys = s
        cHero?.sys = s
        mCpu?.onClick = s.onParams
        mRam?.onClick = s.onParams
        mUp?.onClick = s.onParams
        mStorage?.onClick = s.onStorage
        cCpu?.onClick = s.onParams
        cRam?.onClick = s.onParams
        cStorage?.onClick = s.onStorage
        cDuo?.onGpu = s.onParams
        mServices?.update(s.services)
        cList?.update(s.services)
        s.storageNums?.let { (u, t) -> storage(u, t) }
        if (first) apply(Sampler.last(context, soc, demo))
    }

    private fun storage(u: Float, t: Float) {
        mStorage?.set(u, t)
        cStorage?.set("${fmtNum(u)} / ${fmtNum(t)} GB", if (t > 0) u / t else 0f)
    }

    private fun apply(l: LiveInfo) {
        mHead?.temp = l.temp
        cProc?.temp = l.temp
        val ram = "${soc.ramGb} GB"
        val pct = l.ramPct
        mRam?.value = if (pct != null) "$ram · $pct%" else ram
        cRam?.set(ram, (pct ?: 0) / 100f)
        val up = uptimeText()
        mUp?.value = up
        cDuo?.uptime = up
        cCpu?.set(l.curKhz)
        if (sys?.storageNums == null && l.storageUsed != null && l.storageTotal != null) storage(l.storageUsed, l.storageTotal)
    }

    // ---------- 实时采样：后台线程读 sysfs，主线程刷新 ----------

    // 只在页面可见时运行：退到后台、锁屏、被其他页面盖住时彻底停掉，回到前台再启动
    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || windowVisibility != View.VISIBLE) return
            Sampler.post(context, soc, demo) { l -> if (isAttachedToWindow) apply(l) }
            postDelayed(this, 1000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(tick)
        post(tick)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        removeCallbacks(tick)
        if (visibility == View.VISIBLE && isAttachedToWindow) post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private object Sampler {
        private val bg by lazy { Handler(HandlerThread("pengpai-about").apply { start() }.looper) }
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var cache: LiveInfo? = null

        fun last(ctx: Context, soc: SocInfo, demo: Boolean): LiveInfo = cache?.takeIf { !demo && it.curKhz.size >= soc.cores }
            ?: LiveInfo(null, null, LongArray(soc.cores) { i -> soc.clusters.firstOrNull { i in it.cpus }?.maxKhz?.div(3) ?: 0 }, null, null)

        fun post(ctx: Context, soc: SocInfo, demo: Boolean, cb: (LiveInfo) -> Unit) {
            bg.post {
                val l = runCatching { sample(ctx, soc, demo) }.getOrNull() ?: return@post
                if (!demo) cache = l
                main.post { cb(l) }
            }
        }

        private fun sample(ctx: Context, soc: SocInfo, demo: Boolean): LiveInfo {
            val real = SocInfo.get(ctx)
            val s = SocLive.sample(ctx, real)
            val cur = if (demo) {
                LongArray(soc.cores) { i ->
                    val max = soc.clusters.firstOrNull { i in it.cpus }?.maxKhz ?: 0
                    (max * Random.nextDouble(0.18, 0.75)).toLong()
                }
            } else s.curKhz
            return LiveInfo(s.cpuTemp, s.ramUsedPct, cur, s.storageUsedGb?.toFloat(), s.storageTotalGb?.toFloat())
        }
    }
}
