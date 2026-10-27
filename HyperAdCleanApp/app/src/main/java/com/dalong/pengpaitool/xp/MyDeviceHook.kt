package com.dalong.pengpaitool.xp

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 「设置 → 关于手机」（MiuiMyDeviceSettings）美化：整页换成雾蓝白 / 银灰香槟两套设计之一。
 * 原有的 Logo、设备卡片、按钮列表只隐藏不删除，新页面的文字和点击都转给它们，
 * 改名、系统更新、存储、各个入口等原功能不受影响。
 */
object MyDeviceHook {
    private const val FRAGMENT = "com.android.settings.device.MiuiMyDeviceSettings"
    private const val ANIM = "com.android.settings.device.MyDeviceAnimationController"
    private const val TAG_PAGE = "pengpai_about"
    private const val KEY_SIG = 0x7f0f5e01
    private const val KEY_HOOKED = 0x7f0f5e02
    private const val KEY_SYNC = 0x7f0f5e03

    /** 新页面接管后需要隐藏的原有视图 */
    private val HIDDEN = listOf("miui_version_card_view", "version_card_click_view", "device_basic_layout", "prefs_container")

    /**
     * 系统按条件隐藏、但用户开启后强制显示的按钮：
     * 换机记录在 Secure.data_trans_history 为空（没用小米换机迁移过）时隐藏，点开是空列表，不影响使用；
     * 以旧换新在小米商城服务未下发 trade_in_link 时隐藏，此时改为直接打开商城以旧换新频道页。
     */
    private val FORCE_SHOW = setOf("huanji_history", "trade_in")
    private const val TRADE_IN_PAGE = "https://m.mi.com/mfbs/dghd/m_universal?_rt=rn&pageid=17814&pdl=mishop&sign=2e0ff22a6b4ba501bbe42db771dcc7f7"
    private const val TRADE_IN_WEB = "https://www.mi.com/a/h/16769.html"

    private lateinit var module: XposedModule

    fun install(m: XposedModule, cl: ClassLoader) {
        module = m
        AboutAssets.apkPath = runCatching { m.moduleApplicationInfo.sourceDir }.getOrNull()
        CustomArt.reader = {
            m.openRemoteFile(MyDeviceCfg.FILE_ART).let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } }
        }
        val cls = cl.loadClass(FRAGMENT)
        val onCreateView = cls.getDeclaredMethod("onCreateView", LayoutInflater::class.java, ViewGroup::class.java, Bundle::class.java)
        m.hook(onCreateView).intercept { chain ->
            val v = chain.proceed()
            if (v is View) runCatching { decorate(chain.thisObject, v) }.onFailure { log("decorate: $it") }
            v
        }
        m.hook(cls.getDeclaredMethod("onResume")).intercept { chain ->
            val r = chain.proceed()
            val root = runCatching { cls.getMethod("getView").invoke(chain.thisObject) as? View }.getOrNull()
            if (root != null) runCatching { decorate(chain.thisObject, root) }.onFailure { log("decorate: $it") }
            r
        }
        // 原页面随滚动（含弹性下拉回弹的每一帧）按 Logo 位置改标题和顶部特效层的透明度，
        // Logo 已隐藏，接管后标题保持常显，否则和下面的 PreDraw 修正来回争抢导致闪烁
        runCatching {
            val anim = cl.loadClass(ANIM)
            val start = anim.declaredMethods.first { it.name == "startAnimation" && it.parameterTypes.size == 6 }
            m.hook(start).intercept { chain ->
                if (last?.enabled != true) return@intercept chain.proceed()
                (chain.args[4] as? View)?.alpha = 1f
                null
            }
            m.hook(anim.getDeclaredMethod("setActionBarAlpha", View::class.java)).intercept { chain ->
                if (last?.enabled != true) return@intercept chain.proceed()
                (chain.args[0] as? View)?.alpha = 1f
                null
            }
        }.onFailure { log("anim hook: $it") }
    }

    internal fun cfg(): MyDeviceCfg.Snapshot =
        MyDeviceCfg.read(runCatching { module.getRemotePreferences(MyDeviceCfg.GROUP) }.getOrNull())

    private fun id(v: View, name: String) = v.resources.getIdentifier(name, "id", Entry.SETTINGS)

    /** 可重复调用：onCreateView 和每次 onResume 都会走一遍，主题或深浅色变化时重建 */
    private fun decorate(fragment: Any, root: View) {
        val c = cfg()
        if (!c.enabled) return
        val ctx = root.context
        val scroll = root.findViewById<ViewGroup>(id(root, "scroll_layout")) as? LinearLayout ?: return
        val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val sig = "${c.theme}|$night|${c.art}|${c.mituColor}|${c.artVer}"
        var page = scroll.findViewWithTag<AboutPage>(TAG_PAGE)
        if (page == null || page.getTag(KEY_SIG) != sig) {
            page?.let(scroll::removeView)
            val pal = Pal.resolve(ctx, c.theme)
            page = AboutPage(ctx, pal, SocInfo.get(ctx), cfg = c).apply {
                tag = TAG_PAGE
                setTag(KEY_SIG, sig)
            }
            val dp = ctx.resources.displayMetrics.density
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (12 * dp).toInt()
                marginEnd = (12 * dp).toInt()
                bottomMargin = (18 * dp).toInt()
            }
            scroll.addView(page, 0, lp)
            scroll.clipChildren = false
            root.setBackgroundColor(pal.page)
            pageColor = pal.page
            // 入场：各卡片依次淡入上移
            for (i in 0 until page.childCount) {
                val v = page.getChildAt(i)
                v.alpha = 0f
                v.translationY = 16 * dp
                v.animate().alpha(1f).translationY(0f).setStartDelay(i * 60L).setDuration(420)
                    .setInterpolator(DecelerateInterpolator(1.6f)).start()
            }
        }
        last = c
        hideStock(root)
        sync(fragment, root, page, c)
        startSync(fragment, root)
        if (root.getTag(KEY_HOOKED) == null) {
            root.setTag(KEY_HOOKED, true)
            // 原有卡片的可见性会被系统异步改回来（保修期、权益数据加载完等），每次布局后再压一次
            root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (root.isAttachedToWindow && last?.enabled == true) hideStock(root)
                }
            })
            // 兜底：其他系统版本若没有 MyDeviceAnimationController，标题仍可能被设成透明
            root.viewTreeObserver.addOnPreDrawListener {
                if (last?.enabled == true) {
                    root.rootView.findViewById<View>(id(root, "action_bar_title"))?.let { if (it.alpha != 1f) it.alpha = 1f }
                }
                true
            }
        }
    }

    @Volatile private var last: MyDeviceCfg.Snapshot? = null

    private fun hideStock(root: View) {
        HIDDEN.forEach { name -> root.findViewById<View>(id(root, name))?.let { if (it.visibility != View.GONE) it.visibility = View.GONE } }
        // 窗口顶部的淡紫渐变特效层，和两套主题的底色冲突；隐藏后标题栏会改画默认灰底，换成页面底色
        root.rootView.findViewById<View>(id(root, "bgEffectView"))?.let { if (it.visibility != View.INVISIBLE) it.visibility = View.INVISIBLE }
        val color = pageColor
        val bar = root.rootView.findViewById<View>(id(root, "action_bar_container")) ?: return
        if (color != 0 && bar.getTag(KEY_SIG) != color) {
            bar.setTag(KEY_SIG, color)
            runCatching { method(bar, "setPrimaryBackground", android.graphics.drawable.Drawable::class.java)?.invoke(bar, android.graphics.drawable.ColorDrawable(color)) }
        }
    }

    @Volatile private var pageColor = 0

    /** 页面可见期间每秒同步一次：存储、设备名等是异步加载的 */
    private fun startSync(fragment: Any, root: View) {
        (root.getTag(KEY_SYNC) as? Runnable)?.let(root::removeCallbacks)
        val r = object : Runnable {
            override fun run() {
                if (!root.isAttachedToWindow || root.windowVisibility != View.VISIBLE) return
                val scroll = root.findViewById<ViewGroup>(id(root, "scroll_layout"))
                val page = scroll?.findViewWithTag<AboutPage>(TAG_PAGE) ?: return
                runCatching { sync(fragment, root, page, last ?: return) }
                root.postDelayed(this, 1000)
            }
        }
        root.setTag(KEY_SYNC, r)
        root.postDelayed(r, 1000)
    }

    private fun sync(fragment: Any, root: View, page: AboutPage, c: MyDeviceCfg.Snapshot) {
        fun card(name: String) = root.findViewById<View>(id(root, name))
        fun summary(v: View?) = v?.findViewById<TextView>(id(root, "summary"))?.text?.toString().orEmpty()
        val nameCard = card("device_name_card_view")
        val verCard = card("version_name_card_view")
        val memCard = card("device_memory_card_view")

        val screen = method(fragment, "getPreferenceScreen")?.invoke(fragment)
        val find = screen?.let { method(it, "findPreference", CharSequence::class.java) }
        fun pref(key: String) = find?.invoke(screen, key)
        fun click(p: Any?): (() -> Unit)? = p?.let { { runCatching { method(it, "performClick")?.invoke(it) }; Unit } }

        val services = MyDeviceCfg.PREFS.mapNotNull { (key, label) ->
            if (key in c.hidePrefs) return@mapNotNull null
            val p = pref(key) ?: return@mapNotNull null
            if (method(p, "isVisible")?.invoke(p) != true && key !in FORCE_SHOW) return@mapNotNull null
            val title = (method(p, "getTitle")?.invoke(p) as? CharSequence)?.toString().orEmpty().ifBlank { label }
            val onClick = if (key == "trade_in" && tradeInLink(root.context).isEmpty()) ({ openTradeIn(root.context) }) else click(p)
            PanelItem(key, title, onClick)
        }
        page.bind(
            SysInfo(
                name = summary(nameCard),
                version = summary(verCard),
                storage = summary(memCard),
                services = services,
                onName = nameCard?.let { v -> { v.performClick(); Unit } },
                onVersion = verCard?.let { v -> { v.performClick(); Unit } },
                onStorage = memCard?.let { v -> { v.performClick(); Unit } },
                onParams = click(pref("device_more_parameter")),
            ),
        )
    }

    private fun tradeInLink(ctx: Context): String =
        runCatching { ctx.getSharedPreferences("micare_info", Context.MODE_PRIVATE).getString("trade_in_link", "") }.getOrNull().orEmpty()

    private fun openTradeIn(ctx: Context) {
        val shop = Intent(Intent.ACTION_VIEW, Uri.parse(
            "mishop://m.mi.com/p?pid=111&root=com.xiaomi.shop2.plugin.webview.RootFragment&url=" + Uri.encode(TRADE_IN_PAGE),
        )).setPackage("com.xiaomi.shop").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(shop) }.onFailure {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TRADE_IN_WEB)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    // ---------- Preference（androidx，未混淆，按方法名反射） ----------

    private val methods = HashMap<String, Method?>()

    private fun method(obj: Any, name: String, vararg types: Class<*>): Method? = methods.getOrPut("${obj.javaClass.name}#$name") {
        var k: Class<*>? = obj.javaClass
        while (k != null) {
            runCatching { return@getOrPut k!!.getDeclaredMethod(name, *types).apply { isAccessible = true } }
            k = k.superclass
        }
        null
    }

    fun log(msg: String) = runCatching { module.log(Log.INFO, Entry.TAG, msg) }
}
