package com.dalong.pengpaitool.xp

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
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
    private const val KEY_STATE = 0x7f0f5e02

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
            if (v is View) decorateSafely(chain.thisObject, v)
            v
        }
        m.hook(cls.getDeclaredMethod("onResume")).intercept { chain ->
            val r = chain.proceed()
            val root = runCatching { cls.getMethod("getView").invoke(chain.thisObject) as? View }.getOrNull()
            if (root != null) decorateSafely(chain.thisObject, root)
            r
        }
        runCatching {
            m.hook(cls.getDeclaredMethod("onDestroyView")).intercept { chain ->
                val root = runCatching { cls.getMethod("getView").invoke(chain.thisObject) as? View }.getOrNull()
                (root?.getTag(KEY_STATE) as? Decoration)?.dispose()
                chain.proceed()
            }
        }.onFailure { log("destroy view hook: $it") }
        // 原页面随滚动（含弹性下拉回弹的每一帧）按 Logo 位置改标题和顶部特效层的透明度，
        // Logo 已隐藏，接管后标题保持常显，否则和下面的 PreDraw 修正来回争抢导致闪烁
        runCatching {
            val anim = cl.loadClass(ANIM)
            // HyperOS 4 增加了七参数重载，滚动路径会直接调用它。
            anim.declaredMethods.filter { it.name == "startAnimation" && it.parameterTypes.size in 6..7 }.forEach { start ->
                m.hook(start).intercept { chain ->
                    if (last?.enabled != true) return@intercept chain.proceed()
                    // HyperOS 3 supplies the action bar as the fifth argument.
                    if (start.parameterTypes.size == 6) (chain.args[4] as? View)?.alpha = 1f
                    null
                }
            }
            anim.declaredMethods.firstOrNull { it.name == "setActionBarAlpha" && it.parameterTypes.contentEquals(arrayOf(View::class.java)) }?.let { alpha ->
                m.hook(alpha).intercept { chain ->
                    if (last?.enabled != true) return@intercept chain.proceed()
                    (chain.args[0] as? View)?.alpha = 1f
                    null
                }
            }
        }.onFailure { log("anim hook: $it") }
        // OS4.0.0.15 改为在 Fragment 里直接切换标题可见性。
        cls.declaredMethods.filter {
            (it.name == "updateTitleVisibility" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))) ||
                (it.name == "hideTitleView" && it.parameterTypes.size == 1)
        }.forEach { visibility ->
            m.hook(visibility).intercept { chain ->
                if (last?.enabled == true) null else chain.proceed()
            }
        }
    }

    internal fun cfg(): MyDeviceCfg.Snapshot =
        MyDeviceCfg.read(runCatching { module.getRemotePreferences(MyDeviceCfg.GROUP) }.getOrNull())

    private fun id(v: View, name: String) = v.resources.getIdentifier(name, "id", Entry.SETTINGS)

    private fun decorateSafely(fragment: Any, root: View) {
        runCatching { decorate(fragment, root) }.onFailure {
            last = last?.copy(enabled = false)
            (root.getTag(KEY_STATE) as? Decoration)?.dispose()
            log("decorate: $it")
        }
    }

    /** 可重复调用：onCreateView 和每次 onResume 都会走一遍，主题或深浅色变化时重建 */
    private fun decorate(fragment: Any, root: View) {
        val c = cfg()
        // 页面构建成功后才拦截系统动画，固件布局不匹配时保留原页面行为。
        last = c.copy(enabled = false)
        if (!c.enabled) {
            (root.getTag(KEY_STATE) as? Decoration)?.dispose()
            return
        }
        val ctx = root.context
        val scroll = root.findViewById<ViewGroup>(id(root, "scroll_layout")) as? LinearLayout ?: return
        val state = (root.getTag(KEY_STATE) as? Decoration) ?: Decoration(fragment, root, scroll).also {
            root.setTag(KEY_STATE, it)
            root.addOnAttachStateChangeListener(it)
        }
        val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val sig = "${c.theme}|$night|${c.art}|${c.mituColor}|${c.artVer}|${c.pet}|${c.petEco}"
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
            state.pageColor = pal.page
            // 入场：各卡片依次淡入上移
            for (i in 0 until page.childCount) {
                val v = page.getChildAt(i)
                v.alpha = 0f
                v.translationY = 16 * dp
                v.animate().alpha(1f).translationY(0f).setStartDelay(i * 60L).setDuration(420)
                    .setInterpolator(DecelerateInterpolator(1.6f)).start()
            }
        }
        state.config = c
        last = c
        state.hideStock()
        sync(fragment, root, page, c)
        state.start()
    }

    @Volatile private var last: MyDeviceCfg.Snapshot? = null

    private class ViewState(val view: View) {
        private val visibility = view.visibility
        private val alpha = view.alpha
        fun restore() { view.visibility = visibility; view.alpha = alpha }
    }

    /** 状态只由页面拥有；窗口观察者和消息队列中的引用在 detach / destroy 时对称移除。 */
    private class Decoration(val fragment: Any, val root: View, val scroll: LinearLayout) : View.OnAttachStateChangeListener {
        var config = MyDeviceCfg.read(null)
        var pageColor = 0
        private val originalBackground = root.background
        private val originalClip = scroll.clipChildren
        private val stock = linkedMapOf<View, ViewState>()
        private val chrome = linkedMapOf<View, ViewState>()
        private var observer: ViewTreeObserver? = null
        private var primaryBar: View? = null
        private var primaryBackground: Drawable? = null
        private var customBackground: Boolean? = null
        private var appliedColor: Int? = null
        private val layout = ViewTreeObserver.OnGlobalLayoutListener {
            if (root.isAttachedToWindow && config.enabled) hideStock()
        }
        private val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (root.isAttachedToWindow && config.enabled) runCatching { showTitle() }
            true
        }
        private val syncTask = object : Runnable {
            override fun run() {
                if (!root.isAttachedToWindow || root.windowVisibility != View.VISIBLE || !config.enabled) return
                val page = scroll.findViewWithTag<AboutPage>(TAG_PAGE) ?: return
                runCatching { sync(fragment, root, page, config) }
                root.postDelayed(this, 1000)
            }
        }

        fun start() {
            if (!root.isAttachedToWindow) return
            if (observer == null) {
                observer = root.viewTreeObserver.also {
                    it.addOnGlobalLayoutListener(layout)
                    it.addOnPreDrawListener(preDraw)
                }
            }
            root.removeCallbacks(syncTask)
            root.postDelayed(syncTask, 1000)
        }

        override fun onViewAttachedToWindow(v: View) { hideStock(); start() }
        override fun onViewDetachedFromWindow(v: View) { stop(); restoreChrome() }

        private fun stop() {
            observer?.takeIf { it.isAlive }?.let {
                it.removeOnGlobalLayoutListener(layout)
                it.removeOnPreDrawListener(preDraw)
            }
            observer = null
            root.removeCallbacks(syncTask)
        }

        fun dispose() {
            stop()
            restoreChrome()
            stock.values.forEach { it.restore() }
            stock.clear()
            root.background = originalBackground
            scroll.clipChildren = originalClip
            scroll.findViewWithTag<AboutPage>(TAG_PAGE)?.let { page ->
                for (i in 0 until page.childCount) page.getChildAt(i).animate().cancel()
                scroll.removeView(page)
            }
            root.removeOnAttachStateChangeListener(this)
            root.setTag(KEY_STATE, null)
        }

        fun hideStock() {
            HIDDEN.forEach { name -> root.findViewById<View>(id(root, name))?.let {
                stock.getOrPut(it) { ViewState(it) }
                if (it.visibility != View.GONE) it.visibility = View.GONE
            } }
            if (!root.isAttachedToWindow) return
            root.rootView.findViewById<View>(id(root, "bgEffectView"))?.let {
                chrome.getOrPut(it) { ViewState(it) }
                if (it.visibility != View.INVISIBLE) it.visibility = View.INVISIBLE
            }
            val bar = root.rootView.findViewById<View>(id(root, "action_bar_container")) ?: return
            if (primaryBar == null) {
                // 只有能读取原背景时才替换，保证关闭美化可以完整交还系统。
                val getter = method(bar, "getPrimaryBackground") ?: return
                primaryBackground = runCatching { getter.invoke(bar) as? Drawable }.getOrElse { return }
                customBackground = runCatching { field(bar, "mCustomBackground")?.getBoolean(bar) }.getOrNull()
                primaryBar = bar
            }
            if (appliedColor != pageColor) runCatching {
                method(bar, "setPrimaryBackground", Drawable::class.java)?.invoke(bar, ColorDrawable(pageColor))
                appliedColor = pageColor
            }
        }

        private fun showTitle() {
            fun show(v: View?) { v?.let {
                chrome.getOrPut(it) { ViewState(it) }
                if (it.alpha != 1f) it.alpha = 1f
                if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE
            } }
            val bar = method(fragment, "getAppCompatActionBar")?.invoke(fragment)
            if (bar != null) {
                val titleView = method(bar, "getTitleView", Int::class.javaPrimitiveType!!)
                for (index in 0..2) show(titleView?.invoke(bar, index) as? View)
            }
            show(root.rootView.findViewById(id(root, "action_bar_title")))
        }

        private fun restoreChrome() {
            chrome.values.forEach { it.restore() }
            chrome.clear()
            primaryBar?.let { bar -> runCatching {
                method(bar, "setPrimaryBackground", Drawable::class.java)?.invoke(bar, primaryBackground)
                customBackground?.let { field(bar, "mCustomBackground")?.setBoolean(bar, it) }
            } }
            primaryBar = null
            primaryBackground = null
            customBackground = null
            appliedColor = null
        }
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

    private fun method(obj: Any, name: String, vararg types: Class<*>): Method? = methods.getOrPut("${obj.javaClass.name}#$name(${types.joinToString { it.name }})") {
        var k: Class<*>? = obj.javaClass
        while (k != null) {
            runCatching { return@getOrPut k!!.getDeclaredMethod(name, *types).apply { isAccessible = true } }
            k = k.superclass
        }
        null
    }

    private fun field(obj: Any, name: String): java.lang.reflect.Field? {
        var k: Class<*>? = obj.javaClass
        while (k != null) {
            runCatching { return k!!.getDeclaredField(name).apply { isAccessible = true } }
            k = k.superclass
        }
        return null
    }

    fun log(msg: String) = runCatching { module.log(Log.INFO, Entry.TAG, msg) }
}
