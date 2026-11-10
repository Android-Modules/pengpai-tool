package com.dalong.pengpaitool

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dalong.pengpaitool.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

object BuildInfo {
    fun versionName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "-"
}

/** Internal tool destination; a first grant is manual and subsequent sessions recheck it. */
class StreamlineActivity : ComponentActivity() {
    private var route by mutableStateOf("home")
    private var sequence by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        route = savedInstanceState?.getString("route") ?: RouteCatalog.validRoute(intent.getStringExtra(EXTRA_ROUTE))
        sequence = savedInstanceState?.getInt("sequence") ?: 0
        enableEdgeToEdge()
        setContent { AppTheme { ToolPages(route, sequence, ::finish) } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route = RouteCatalog.validRoute(intent.getStringExtra(EXTRA_ROUTE))
        sequence++
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("route", route)
        outState.putInt("sequence", sequence)
        super.onSaveInstanceState(outState)
    }
    companion object { const val EXTRA_ROUTE = "route" }
}

private enum class Gate { IDLE, NO_ROOT, NO_MODULE, OK }

@Composable
private fun ToolPages(initial: String, request: Int, finish: () -> Unit) {
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var stack by rememberSaveable { mutableStateOf(listOf(initial)) }
    var consumedRequest by rememberSaveable { mutableIntStateOf(request) }
    var gate by remember { mutableStateOf(if (Access.module) Gate.OK else Gate.IDLE) }
    var checking by remember { mutableStateOf(false) }
    var legacyConflict by remember { mutableStateOf(false) }
    var confirmLegacy by remember { mutableStateOf(false) }
    var legacyNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val back = { if (stack.size > 1) stack = stack.dropLast(1) else finish() }
    val open: (String) -> Unit = { stack = stack + it }
    LaunchedEffect(request) {
        if (consumedRequest != request) {
            consumedRequest = request
            stack = listOf(initial)
        }
    }
    BackHandler(onBack = back)

    suspend fun prepare(manual: Boolean) {
        if (checking) return
        checking = true
        try {
            // Keep an already authorized page composed while its permission is rechecked.
            // Replacing it with a loading page would discard drafts and pairing dialogs.
            gate = Gate.valueOf(ToolAccessSession.ensure(context.applicationContext, manual).name)
            legacyConflict = gate == Gate.NO_MODULE && Access.root && BackendRuntime.legacyConflict(context.applicationContext)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            Access.module = false
            gate = if (Access.root) Gate.NO_MODULE else Gate.NO_ROOT
            scope.toast(snack, "准备失败：${error.message ?: error.javaClass.simpleName}")
        } finally { checking = false }
    }
    fun authorize() { scope.launch { prepare(manual = true) } }
    LifecycleResumeEffect(request) {
        if (stack.last() != "about" && ToolAccessSession.shouldRestore(context))
            scope.launch { prepare(manual = false) }
        onPauseOrDispose { }
    }

    CompositionLocalProvider(LocalSnack provides snack) {
        val route = stack.last()
        CompactPage(RouteCatalog.title(route), snack, back) {
            key(route, stack.size) {
                when {
                    checking && gate != Gate.OK -> PreparationPage()
                    RouteCatalog.requiresRoot(route) && gate != Gate.OK -> AccessPage(gate, RouteCatalog.title(route), ::authorize,
                        legacyConflict, legacyNotice) { confirmLegacy = true }
                    route == "home" || RouteCatalog.groups().any { it.route == route } -> GroupPage(route, gate, ::authorize, open)
                    route.startsWith("chargelog:") -> ChargeSessionScreen(route.substringAfter(':')) { back() }
                    else -> when (route) {
                        "slim" -> SlimScreen()
                        "ad" -> AdScreen()
                        "adjump" -> AdJumpScreen()
                        "fps", "font", "icons", "color" -> DisplayScreen(route)
                        "battery", "whitelist", "cpu", "dexopt" -> PerfScreen(route) { back() }
                        "cycle" -> CycleScreen()
                        "charge" -> ChargeScreen(open)
                        "chargelog" -> ChargeLogScreen(open)
                        "thermalwall" -> ThermalWallScreen()
                        "guard" -> GuardScreen()
                        "wlanlog" -> WlanLogScreen()
                        "antifraud" -> AntiFraudScreen()
                        "sync" -> SyncScreen()
                        "adbdiag" -> AdbDiagScreen()
                        "rotlock" -> RotLockScreen()
                        "devmodel" -> DevModelScreen()
                        "mydevice" -> MyDeviceScreen()
                        "modlog" -> LogScreen()
                        "ota" -> UpdateBlockingPage()
                        "about" -> AboutScreen()
                        else -> ToolsScreen(route, open)
                    }
                }
            }
        }
    }
    if (confirmLegacy) AlertDialog(onDismissRequest = { confirmLegacy = false }, title = { Text("停用旧工具后台") },
        text = { Text("停用旧澎湃工具的 Root 开机入口与守护进程，解除属于旧工具的充电控制和绑定。旧应用、配置及备份会保留，不会导入到澎湃工坊。旧的早期机型、色彩或降帧参数可能需要完整重启手机才能清除。之后请勿再在旧应用中启动 Root 功能。") },
        confirmButton = { TextButton(onClick = {
            confirmLegacy = false
            scope.launch {
                if (checking) return@launch
                checking = true
                var retryPreparation = false
                try {
                    val result = BackendRuntime.deactivateLegacy(context.applicationContext)
                    val pendingRestart = result.lines.any { it.startsWith("WARN|") }
                    legacyNotice = if (result.ok) {
                        if (pendingRestart) "旧后台已停用。请完整重启手机后重新进入；重启前不会启用新后台。"
                        else "旧后台已停用，旧应用与配置已保留。"
                    } else result.lines.firstOrNull { it.startsWith("ERR|") }?.substringAfterLast('|')
                        ?: result.err.ifBlank { "旧后台未完全停用，请检查后重试。" }
                    gate = Gate.NO_MODULE
                    if (result.ok && !pendingRestart) retryPreparation = true
                    else legacyConflict = BackendRuntime.legacyConflict(context.applicationContext)
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    gate = Gate.NO_MODULE
                    legacyNotice = "停用失败：${error.message ?: error.javaClass.simpleName}"
                } finally { checking = false }
                if (retryPreparation) prepare(manual = true)
            }
        }) { Text("停用并继续") } },
        dismissButton = { TextButton(onClick = { confirmLegacy = false }) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactPage(title: String, snack: SnackbarHostState, back: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        CenterAlignedTopAppBar(title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }, snackbarHost = { SnackbarHost(snack) }) { pad -> Box(Modifier.padding(pad)) { content() } }
}

@Composable
private fun GroupPage(route: String, gate: Gate, authorize: () -> Unit, open: (String) -> Unit) {
    Page {
        if (route == "records") item {
            Banner("各功能分别记录日志：精简与操作行为见运行日志，充电曲线见“电池与性能 → 充电记录”，无线调试记录见“系统工具 → 无线调试诊断”。")
        }
        item {
            Banner(if (gate == Gate.OK) "Root 工具已就绪 · 各功能仍按保存的设置运行" else "Root 工具尚未准备，进入功能后可按需授权。设备美化使用 LSPosed。")
        }
        if (route == "home") {
            RouteCatalog.groups().forEach { group -> item {
                Section(group.title) { NavRow(group.title, group.subtitle) { open(group.route) } }
            } }
            if (gate != Gate.OK) item { Section("运行环境") { Button(onClick = authorize) { Text("授权并准备工具") } } }
        } else item {
            Section(RouteCatalog.title(route)) {
                RouteCatalog.entriesFor(route).forEach { entry ->
                    NavRow(entry.title, entry.subtitle, if (entry.isRequiresRoot && gate != Gate.OK) "需 Root" else null) { open(entry.route) }
                }
            }
        }
    }
}

private val ROOT_MANAGERS = listOf("me.weishu.kernelsu", "com.sukisu.ultra", "com.rifsxd.ksunext", "me.bmax.apatch", "com.topjohnwu.magisk")

@Composable
private fun AccessPage(gate: Gate, title: String, authorize: () -> Unit, legacyConflict: Boolean,
                       legacyNotice: String?, deactivateLegacy: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    Page {
        item { Section(title) {
            Text("此功能需要 Root 运行环境。准备完成后可以查看和修改具体选项；组件精简、充电控制及其他系统改动均由对应页面操作。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp))
            if (gate == Gate.NO_ROOT) Text("未获得 Root 授权，请在管理器中允许“澎湃工坊”。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (gate == Gate.NO_MODULE) Text(BackendRuntime.error ?: "工具运行环境尚未就绪，请检查后重试。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            legacyNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            if (legacyConflict) OutlinedButton(onClick = deactivateLegacy, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("停用旧工具后台")
            }
            Button(onClick = authorize, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("授权并准备工具") }
            TextButton(onClick = {
                val intent = ROOT_MANAGERS.firstNotNullOfOrNull { ctx.packageManager.getLaunchIntentForPackage(it) }
                if (intent != null) ctx.startActivity(intent) else scope.toast(snack, "没有找到 Root 管理器")
            }) { Text("打开 Root 管理器") }
        } }
    }
}

@Composable
private fun PreparationPage() {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text("正在检查 Root 并准备工具…", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Text("首次授权时，请在 Root 管理器弹窗中选择是否允许。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UpdateBlockingPage() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var on by remember { mutableStateOf(false) }
    var supported by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    suspend fun reload() {
        supported = Mod.status(listOf("com.android.updater"))["com.android.updater"]?.st in setOf("e", "d")
        on = supported && Mod.rows("ota-status").firstOrNull()?.getOrNull(0) == "1"
        loaded = true
    }
    LaunchedEffect(Unit) { reload() }
    Page(loaded = loaded) { item { Section("系统更新") {
        if (!supported) Note("本机没有本功能支持的系统更新应用，无法屏蔽更新或清除其角标。相关操作已停用。", warn = true)
        SwitchRow("屏蔽系统更新", "停止更新检查、通知及设置中的新版本角标", on, enabled = supported && !busy) { requested ->
            scope.launch {
                busy = true
                try { val result = Mod.result(if (requested) "ota-on" else "ota-off"); reload(); scope.toast(snack, result.msg) }
                finally { busy = false }
            }
        }
    } } }
}
