package com.dalong.pengpaitool

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dalong.pengpaitool.ui.AdScreen
import com.dalong.pengpaitool.ui.WlanLogScreen
import com.dalong.pengpaitool.ui.RotLockScreen
import com.dalong.pengpaitool.ui.fmtKb
import com.dalong.pengpaitool.ui.AppTheme
import com.dalong.pengpaitool.ui.Banner
import com.dalong.pengpaitool.ui.ChargeLogScreen
import com.dalong.pengpaitool.ui.LogScreen
import com.dalong.pengpaitool.ui.ChargeScreen
import com.dalong.pengpaitool.ui.ChargeSessionScreen
import com.dalong.pengpaitool.ui.ThermalWallScreen
import com.dalong.pengpaitool.ui.CycleScreen
import com.dalong.pengpaitool.ui.DevModelScreen
import com.dalong.pengpaitool.ui.DisplayScreen
import com.dalong.pengpaitool.ui.GuardScreen
import com.dalong.pengpaitool.ui.LocalSnack
import com.dalong.pengpaitool.ui.MyDeviceScreen
import com.dalong.pengpaitool.ui.NavRow
import com.dalong.pengpaitool.ui.NavSwitchRow
import com.dalong.pengpaitool.ui.Page
import com.dalong.pengpaitool.ui.PerfScreen
import com.dalong.pengpaitool.ui.Section
import com.dalong.pengpaitool.ui.SlimScreen
import com.dalong.pengpaitool.ui.SwitchRow
import com.dalong.pengpaitool.ui.ToolsScreen
import com.dalong.pengpaitool.ui.toast
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

object BuildInfo {
    fun versionName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "-"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        XpService.init()
        setContent { AppTheme { App() } }
    }
}

private enum class Gate { CHECKING, NO_ROOT, NO_MODULE, OK }

/** 每个子页面的路由名和标题 */
private val TITLES = mapOf(
    "home" to "澎湃小工具",
    "slim" to "系统精简", "ad" to "去广告", "adjump" to "广告设置", "antifraud" to "反诈管理",
    "fps" to "全局高刷", "font" to "字体大小", "icons" to "状态栏图标", "color" to "色彩风格", "rotlock" to "竖屏锁定",
    "battery" to "电池健康度", "cycle" to "电池循环伪装", "charge" to "充电控制", "whitelist" to "电池优化白名单",
    "chargelog" to "充电记录", "thermalwall" to "系统温度墙",
    "cpu" to "处理器调整", "dexopt" to "应用深度优化", "guard" to "远程进程守卫", "wlanlog" to "WLAN 日志清理",
    "devmodel" to "机型修改", "mydevice" to "我的设备美化", "dev" to "开发者选项", "adbdiag" to "无线调试诊断", "sync" to "账号同步", "wifi" to "WIFI 密码", "shortcuts" to "隐藏设置入口",
    "backup" to "备份与模块设置", "modlog" to "运行日志", "about" to "关于",
)

@Composable
private fun App() {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var gate by remember { mutableStateOf(Gate.CHECKING) }
    val stack = remember { mutableStateListOf("home") }

    suspend fun check() {
        gate = Gate.CHECKING
        gate = when {
            !Root.granted() -> Gate.NO_ROOT
            !Mod.installed() -> Gate.NO_MODULE
            else -> Gate.OK
        }
        Access.root = gate != Gate.NO_ROOT
        Access.module = gate == Gate.OK
        HomeCache.h = null
    }
    LaunchedEffect(Unit) { check() }
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    CompositionLocalProvider(LocalSnack provides snack) {
        if (gate == Gate.CHECKING) {
            GateScreen(gate) { scope.launch { check() } }
            return@CompositionLocalProvider
        }
        val route = stack.last()
        // 每个页面单独的滚动状态，切换页面时标题栏重新展开
        key(route, stack.size) {
            val title = TITLES[route] ?: if (route.startsWith("chargelog:")) "充电详情" else ""
            HyperPage(title, snack, onBack = if (stack.size > 1) ({ stack.removeAt(stack.lastIndex) }) else null) {
                if (route.startsWith("chargelog:")) {
                    ChargeSessionScreen(route.substringAfter(':')) { stack.removeAt(stack.lastIndex) }
                    return@HyperPage
                }
                when (route) {
                    "home" -> if (gate == Gate.OK) HomeScreen { stack.add(it) } else LimitedHome(gate, { scope.launch { check() } }) { stack.add(it) }
                    "slim" -> SlimScreen()
                    "ad" -> AdScreen()
                    "adjump" -> com.dalong.pengpaitool.ui.AdJumpScreen()
                    "fps", "font", "icons", "color" -> DisplayScreen(route)
                    "battery", "whitelist", "cpu", "dexopt" -> PerfScreen(route) { stack.removeAt(stack.lastIndex) }
                    "cycle" -> CycleScreen()
                    "charge" -> ChargeScreen { stack.add(it) }
                    "chargelog" -> ChargeLogScreen { stack.add(it) }
                    "thermalwall" -> ThermalWallScreen()
                    "guard" -> GuardScreen()
                    "wlanlog" -> WlanLogScreen()
                    "antifraud" -> com.dalong.pengpaitool.ui.AntiFraudScreen()
                    "sync" -> com.dalong.pengpaitool.ui.SyncScreen()
                    "adbdiag" -> com.dalong.pengpaitool.ui.AdbDiagScreen()
                    "rotlock" -> RotLockScreen()
                    "devmodel" -> DevModelScreen()
                    "mydevice" -> MyDeviceScreen()
                    "modlog" -> LogScreen()
                    else -> ToolsScreen(route) { stack.add(it) }
                }
            }
        }
    }
}

/** HyperOS 设置风格页面：大标题（滚动后收起）、浅灰背景，子页面带返回箭头 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HyperPage(title: String, snack: SnackbarHostState, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val bg = MaterialTheme.colorScheme.background
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = bg,
        topBar = {
            LargeTopAppBar(
                title = { Text(title, fontWeight = FontWeight.Medium) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, scrolledContainerColor = bg),
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad -> Box(Modifier.padding(pad)) { content() } }
}

/** 首页各入口的摘要 */
private data class Home(
    val applied: Int? = null, val hosts: String? = null, val ota: Boolean? = null,
    val fps: String? = null, val font: String? = null, val hidden: Int? = null, val color: String? = null,
    val health: String? = null, val cycle: String? = null, val charge: String? = null, val wl: Int? = null, val cpu: Boolean? = null,
    val guard: String? = null, val model: String? = null, val devOn: Boolean? = null, val legacy: List<String> = emptyList(),
    val modVer: String? = null, val wlog: String? = null, val rot: String? = null, val af: String? = null,
    val sync: String? = null,
)

private object HomeCache { var h: Home? = null }

private suspend fun loadHome(): Home = coroutineScope {
    val applied = async { Mod.rows("status").count { it.getOrNull(2)?.isNotEmpty() == true } }
    val hosts = async { Mod.rows("hosts-status").firstOrNull()?.getOrNull(4) }
    val ota = async { Mod.rows("ota-status").firstOrNull()?.getOrNull(0) == "1" }
    val fps = async { Mod.rows("fps-status").firstOrNull() }
    val font = async { Sys.get("system", "font_scale") }
    val hidden = async { Sys.get("system", "status_bar_hide_icon_slot_name").takeIf { it != "null" }?.split(',')?.count { it.isNotBlank() } ?: 0 }
    val color = async { Mod.rows("color-status").firstOrNull() }
    val bat = async { Root.exec("cat /sys/class/power_supply/battery/charge_full /sys/class/power_supply/battery/charge_full_design").lines }
    val cyc = async { runCatching { org.json.JSONObject(Mod.raw("cycle status").trim()) }.getOrNull() }
    val wl = async { Mod.raw("wl-list").lines().count { it.startsWith("w|") && it.trim().endsWith("|user") } }
    val cpu = async { Mod.rows("cpu-status").firstOrNull { it[0] == "enable" }?.getOrNull(1) == "1" }
    val guard = async { Mod.rows("guard-status").firstOrNull() }
    val wlog = async { Mod.rows("wlog-status").firstOrNull() }
    val rot = async { Mod.rows("rot-status").firstOrNull() }
    val af = async { Mod.rows("af-status").firstOrNull() }
    val chg = async { Mod.raw("charge status").lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') } }
    val dm = async { Mod.raw("dm status").lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') } }
    val dev = async { Sys.get("global", "development_settings_enabled") == "1" }
    val sync = async { Mod.rows("sync-status") }
    val legacy = async { Mod.rows("legacy-status").filter { it.getOrNull(2) == "active" }.map { it[1] } }
    val ver = async { Root.exec("grep ^version= ${Mod.DIR}/module.prop | cut -d= -f2").out.trim() }
    val b = bat.await().mapNotNull { it.toLongOrNull() }
    val c = cyc.await()
    val g = guard.await()
    val d = dm.await()
    Home(
        applied = applied.await(), hosts = hosts.await(), ota = ota.await(),
        fps = fps.await()?.let { f ->
            val hz = f.getOrNull(0) ?: "0"
            when { hz == "0" -> "系统默认"; f.getOrNull(4) == "force" -> "强制 ${hz}Hz"; f.getOrNull(4) == "hold" -> "省电强制 ${hz}Hz"; else -> "最高 ${hz}Hz" }
        },
        font = font.await().toFloatOrNull()?.let { "%.2f".format(it) },
        hidden = hidden.await(),
        color = color.await()?.let { c ->
            when {
                c.getOrNull(1) == "1" -> "完整版"
                c.getOrNull(0) == "1" -> "重启后生效"
                c.getOrNull(2) == "true" -> "官方精简版"
                else -> null
            }
        },
        health = if (b.size == 2 && b[1] > 0) "%.1f%%".format(b[0] * 100.0 / b[1]) else null,
        cycle = c?.let { if (it.optString("mode") == "off" || it.optInt("bound_bat") != 1) "未伪装" else "显示 ${it.optInt("fake")} 次 · 真实 ${it.optInt("real")} 次" },
        charge = chg.await().let {
            when {
                it["supported"] != "1" -> null
                it["enable"] != "1" -> "系统默认"
                else -> (when (it["thermal"]) { "custom" -> "自定义温控墙 ${it["wall"]}℃"; "tier" -> "米系亮屏快充"; else -> "系统温控" }) +
                    (it["limit"]?.takeIf { l -> l != "100" }?.let { l -> " · 充到 $l%" } ?: "")
            }
        },
        wl = wl.await(), cpu = cpu.await(),
        guard = g?.let {
            when {
                it.getOrNull(7) == "1" -> "由独立模块负责"
                it.getOrNull(0) != "1" -> "已关闭"
                it.getOrNull(1) == "1" -> "运行中 · 已结束 ${it.getOrNull(4) ?: 0} 次"
                else -> "已开启 · 未运行"
            }
        },
        model = d["current_marketname"]?.let { n -> if (d["enabled"] == "1") "已修改为 $n" else n },
        devOn = dev.await(), legacy = legacy.await(), modVer = ver.await(),
        sync = sync.await().takeIf { it.isNotEmpty() }?.let { s ->
            val on = s.count { (it[0] == "user" && it.getOrNull(3) == "1") || (it[0] == "wifi" && it.getOrNull(1) == "1") }
            val boot = s.any { it[0] == "boot" && it.getOrNull(1) == "1" }
            (if (on == 0) "已全部关闭" else "开启 $on 项") + if (boot) " · 开机关闭" else ""
        },
        rot = rot.await()?.let { r ->
            when {
                r.getOrNull(0) != "1" -> "已关闭"
                r.getOrNull(1) == "1" -> "已开启"
                else -> "已开启 · 未运行"
            }
        },
        af = af.await()?.let { a ->
            val off = a.getOrNull(0) == "1"
            val up = a.getOrNull(8) == "1"
            when {
                off && up -> "已禁用 · 屏蔽上传"
                off -> "已禁用"
                up -> "屏蔽上传"
                else -> null
            }
        },
        wlog = wlog.await()?.let { w ->
            val size = fmtKb(w.getOrNull(9)?.toLongOrNull() ?: 0)
            when {
                w.getOrNull(10).orEmpty().isNotBlank() -> "抓包中 · $size"
                w.getOrNull(0) == "1" -> "已开启 · $size"
                w.getOrNull(4) == "1" -> "拦截中 · $size"
                else -> "占用 $size"
            }
        },
    )
}

@Composable
private fun HomeScreen(open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    // 从子页面返回时先用上次的摘要，后台再刷新，避免每次回首页都转圈
    var h by remember { mutableStateOf(HomeCache.h ?: Home()) }
    var loaded by remember { mutableStateOf(HomeCache.h != null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { h = loadHome().also { HomeCache.h = it }; loaded = true }

    Page(loaded = loaded) {
        if (h.legacy.isNotEmpty()) item {
            Banner("检测到独立模块仍在启用：${h.legacy.joinToString("、")}。相关功能已集成到本模块，进入对应页面可一键停用旧模块。", warn = true)
        }
        item {
            Section("系统精简") {
                NavRow("系统精简", "冻结或卸载广告、统计等系统组件", h.applied?.let { "已处理 $it 项" }) { open("slim") }
                NavRow("去广告", "拦截广告与统计域名、关闭推荐开关", h.hosts?.let { "拦截 $it 个域名" }) { open("ad") }
                NavRow("广告设置", "一键直达各系统应用的广告与推荐开关") { open("adjump") }
                NavRow("WLAN 日志清理", "清理调试日志，拦截云控抓包", h.wlog) { open("wlanlog") }
                NavRow("反诈管理", "禁用手机管家反诈监控和数据上传", h.af) { open("antifraud") }
                SwitchRow("屏蔽系统更新", "不检查、不提示更新，设置里无新版本角标", h.ota == true, enabled = !busy && h.ota != null) { on ->
                    scope.launch {
                        busy = true
                        val r = Mod.result(if (on) "ota-on" else "ota-off")
                        h = h.copy(ota = on); HomeCache.h = h; busy = false
                        scope.toast(snack, r.msg)
                    }
                }
            }
        }
        item {
            Section("锁屏与显示") {
                NavRow("全局高刷", "锁定或提高刷新率，静止时自动降帧", h.fps) { open("fps") }
                NavRow("字体大小", "对系统字体大小无极缩放", h.font) { open("font") }
                NavRow("状态栏图标", "隐藏状态栏图标、通知图标显示", h.hidden?.let { "已隐藏 $it 个" }) { open("icons") }
                NavRow("色彩风格", "恢复鲜艳、高级模式等完整色彩选项", h.color) { open("color") }
                NavRow("竖屏锁定", "关闭自动旋转，被改回时自动恢复", h.rot) { open("rotlock") }
            }
        }
        item {
            Section("电池和性能") {
                NavRow("电池健康度", "查看当前电池健康程度", h.health) { open("battery") }
                NavRow("电池循环伪装", h.cycle ?: "伪装系统读取的电池循环次数") { open("cycle") }
                NavRow("充电控制", "自定义温控墙、亮屏/息屏快充、充电上限", h.charge) { open("charge") }
                NavRow("电池优化白名单", "为特定应用保持白名单，避免消息延迟", h.wl?.let { "$it 个" }) { open("whitelist") }
                NavRow("处理器调整", "限制处理器性能节省电量", h.cpu?.let { if (it) "已限频" else "不限制" }) { open("cpu") }
                NavRow("应用深度优化", "提升应用启动速度，降低耗电量") { open("dexopt") }
                NavRow("远程进程守卫", h.guard ?: "结束失控的安全中心远程进程") { open("guard") }
            }
        }
        item {
            Section("系统设置") {
                NavRow("机型修改", h.model ?: "动态修改设备机型属性") { open("devmodel") }
                NavRow("我的设备美化", "「关于手机」雾蓝白 / 银灰香槟两套主题（需 LSPosed）",
                    XpService.service?.let { "已连接" } ?: "未启用") { open("mydevice") }
                NavSwitchRow("开发者选项", "无需插卡打开开发者选项和调试；下拉通知栏也可添加快捷开关", h.devOn == true, enabled = h.devOn != null, onChange = { on ->
                    scope.launch {
                        Sys.put("global", "development_settings_enabled", if (on) "1" else "0")
                        h = h.copy(devOn = Sys.get("global", "development_settings_enabled") == "1"); HomeCache.h = h
                        scope.toast(snack, if (h.devOn == true) "已开启开发者选项" else "已关闭开发者选项")
                    }
                }) { open("dev") }
                NavRow("账号同步", "自动同步一键关闭、开机自动关闭", h.sync) { open("sync") }
                NavRow("WIFI 密码", "查看已保存的 WIFI 网络密码") { open("wifi") }
                NavRow("隐藏设置入口", "打开系统里藏起来的原生设置页") { open("shortcuts") }
            }
        }
        item {
            Section("关于") {
                NavRow("备份与模块设置", "备份目录、开机保持、卸载恢复") { open("backup") }
                NavRow("运行日志", "模块的操作记录和开机检查，可手动清空") { open("modlog") }
                NavRow("关于", "版本与设备信息", h.modVer) { open("about") }
            }
        }
    }
}

/** Root 管理器：KernelSU 及常见分支、APatch、Magisk */
private val ROOT_MANAGERS = listOf("me.weishu.kernelsu", "com.sukisu.ultra", "com.rifsxd.ksunext", "me.bmax.apatch", "com.topjohnwu.magisk")

/** 没有 Root 或模块时的首页：说明缺什么，只列出不依赖模块的功能 */
@Composable
private fun LimitedHome(gate: Gate, onRetry: () -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val root = gate != Gate.NO_ROOT
    Page {
        item {
            Section(if (root) "模块未启用" else "未授权 Root") {
                Text(
                    if (root) "没有找到「澎湃小工具」模块，或模块已被停用。在 Root 管理器中安装并启用后重启手机，即可使用全部功能。"
                    else "大部分功能需要 Root。打开 Root 管理器 →「超级用户」→ 给「澎湃小工具」授权，然后点「重新检查」。下面是现在就能用的功能。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                )
                Row(Modifier.padding(bottom = 8.dp)) {
                    Button(onClick = {
                        val i = ROOT_MANAGERS.firstNotNullOfOrNull { ctx.packageManager.getLaunchIntentForPackage(it) }
                        if (i != null) ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else scope.toast(snack, "没有找到 Root 管理器")
                    }) { Text("打开 Root 管理器") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onRetry) { Text("重新检查") }
                }
            }
        }
        item {
            Section("可用功能") {
                NavRow("广告设置", "一键直达各系统应用的广告与推荐开关") { open("adjump") }
                NavRow("隐藏设置入口", "打开系统里藏起来的原生设置页") { open("shortcuts") }
                NavRow("我的设备美化", "「关于手机」雾蓝白 / 银灰香槟两套主题（需 LSPosed）",
                    XpService.service?.let { "已连接" } ?: "未启用") { open("mydevice") }
                if (root) {
                    NavRow("开发者选项", "无需插卡打开开发者选项和调试") { open("dev") }
                    NavRow("WIFI 密码", "查看已保存的 WIFI 网络密码") { open("wifi") }
                }
                NavRow("关于", "版本与设备信息") { open("about") }
            }
        }
    }
}

@Composable
private fun GateScreen(gate: Gate, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(Modifier.padding(bottom = 16.dp))
        Text("正在检查 Root 权限…", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("如果一直停在这里，说明 Root 管理器还没给本应用授权。", style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onRetry) { Text("重新检查") }
    }
}
