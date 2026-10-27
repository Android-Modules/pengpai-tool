package com.dalong.pengpaitool.ui

import android.content.Intent
import android.graphics.ImageDecoder
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dalong.pengpaitool.Root
import com.dalong.pengpaitool.XpService
import com.dalong.pengpaitool.xp.AboutPage
import com.dalong.pengpaitool.xp.CustomArt
import com.dalong.pengpaitool.xp.MyDeviceCfg
import com.dalong.pengpaitool.xp.Pal
import com.dalong.pengpaitool.xp.PanelItem
import com.dalong.pengpaitool.xp.SocInfo
import com.dalong.pengpaitool.xp.SysInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyDeviceScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val svc = XpService.service
    var cfg by remember(svc) { mutableStateOf(MyDeviceCfg.read(XpService.prefs())) }
    var hasScope by remember(svc) { mutableStateOf(XpService.scope().contains("com.android.settings")) }
    val local = remember { SocInfo.get(ctx).model }
    var socModel by remember { mutableStateOf(local) }
    val ready = svc != null && hasScope
    val on = ready && cfg.enabled

    fun save(edit: android.content.SharedPreferences.Editor.() -> Unit) {
        val p = XpService.prefs()
        if (p == null) {
            scope.toast(snack, "未连接 LSPosed，无法保存")
            return
        }
        p.edit().apply(edit).apply()
        cfg = MyDeviceCfg.read(p)
    }

    LaunchedEffect(svc) { hasScope = XpService.scope().contains("com.android.settings") }
    remember { CustomArt.reader = { File(ctx.filesDir, MyDeviceCfg.FILE_ART).takeIf { it.isFile }?.readBytes() }; 0 }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val err = withContext(Dispatchers.IO) { uploadArt(ctx, uri) }
            if (err != null) scope.toast(snack, err)
            else {
                save { putString(MyDeviceCfg.K_ART, MyDeviceCfg.ART_CUSTOM); putLong(MyDeviceCfg.K_ART_VER, System.currentTimeMillis()) }
                scope.toast(snack, "已更换页头图片，重新进入「关于手机」生效")
            }
        }
    }
    val hasCustom = cfg.artVer > 0

    Page {
        if (svc == null) item {
            Banner("未连接 LSPosed。请在 LSPosed 管理器 →「模块」里启用「澎湃小工具」并勾选「设置」，然后彻底关闭本应用再打开。", warn = true)
        } else if (!hasScope) item {
            Banner("模块已启用，但作用域里没有「设置」。点这里申请添加。", warn = true) {
                XpService.requestSettingsScope { ok ->
                    hasScope = ok || XpService.scope().contains("com.android.settings")
                    scope.toast(snack, if (ok) "已添加「设置」到作用域，重启「设置」后生效" else "申请被拒绝，请在 LSPosed 里手动勾选「设置」")
                }
            }
        }

        item {
            Section("主题", "「关于手机」整页替换为所选设计，跟随系统深浅色。芯片上的品牌、型号、核心排布按处理器自动生成。", XpService.framework) {
                SwitchRow("启用「关于手机」美化", "关闭后恢复系统原样", cfg.enabled, enabled = ready) { v -> save { putBoolean(MyDeviceCfg.K_ENABLED, v) } }
                Divider()
                FlowRow(Modifier.padding(top = 10.dp)) {
                    MyDeviceCfg.THEMES.forEach { (v, label) ->
                        FilterChip(selected = cfg.theme == v, enabled = on, onClick = { save { putString(MyDeviceCfg.K_THEME, v) } },
                            label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        }

        item {
            Text("预览处理器", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            FlowRow(Modifier.padding(top = 2.dp, bottom = 6.dp)) {
                (listOf(local) + SocInfo.DEMOS.map { it.first }.filter { it != local }).forEach { m ->
                    val name = SocInfo.demo(ctx, m).name
                    FilterChip(selected = socModel == m, onClick = { socModel = m },
                        label = { Text(if (m == local) "本机 · $name" else name) }, modifier = Modifier.padding(end = 6.dp))
                }
            }
        }

        item { Preview(cfg, socModel, socModel != local) }

        item {
            Section("页头图片", "两套主题通用，显示在机型名右侧。米兔会自己挥手，点它会跳一下；自定义图片支持 GIF / WebP 动图。") {
                FlowRow {
                    MyDeviceCfg.ARTS.forEach { (v, label) ->
                        FilterChip(selected = cfg.art == v, enabled = on, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp),
                            onClick = { if (v == MyDeviceCfg.ART_CUSTOM && !hasCustom) pick.launch("image/*") else save { putString(MyDeviceCfg.K_ART, v) } })
                    }
                }
                if (cfg.art == MyDeviceCfg.ART_MITU) {
                    Text("米兔配色", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                    FlowRow {
                        MyDeviceCfg.MITU_COLORS.forEach { (v, label) ->
                            FilterChip(selected = cfg.mituColor == v, enabled = on, onClick = { save { putString(MyDeviceCfg.K_MITU_COLOR, v) } },
                                label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
                if (cfg.art == MyDeviceCfg.ART_CUSTOM) {
                    Divider()
                    NavRow(if (hasCustom) "更换图片" else "选择图片", "支持 PNG / JPG / GIF / WebP 动图，8MB 以内，透明背景效果最好") {
                        if (on) pick.launch("image/*")
                    }
                }
            }
        }

        item {
            Section("设置里的米兔", "打开「设置」后，米兔趴在窗口边上。按住可以拖到任意位置，松手自动贴到最近的一边。只在「设置」打开时活动，退到后台就停，不打开设置没有任何耗电。") {
                SwitchRow("显示米兔", "单击打招呼，双击跳一下，长按让它先躲起来（下次打开设置再出现）", cfg.pet, enabled = ready) { v ->
                    save { putBoolean(MyDeviceCfg.K_PET, v) }
                }
                Divider()
                SwitchRow("省电模式", "米兔不再呼吸摆动，只偶尔挥手、蹦跳，屏幕能一直保持低刷新", cfg.petEco, enabled = ready && cfg.pet) { v ->
                    save { putBoolean(MyDeviceCfg.K_PET_ECO, v) }
                }
            }
        }

        item {
            Section("服务按钮", "关掉的按钮不再显示。换机记录、以旧换新在系统隐藏时也会强制显示（没迁移过数据时换机记录为空；系统没下发以旧换新入口时打开小米商城以旧换新页）。服务与支持系统本身不显示，开关对它没有影响。") {
                MyDeviceCfg.PREFS.forEachIndexed { i, (k, label) ->
                    if (i > 0) Divider()
                    SwitchRow(label, null, k !in cfg.hidePrefs, enabled = on) { v ->
                        save { putString(MyDeviceCfg.K_HIDE_PREFS, MyDeviceCfg.join(if (v) cfg.hidePrefs - k else cfg.hidePrefs + k)) }
                    }
                }
            }
        }

        item {
            Section("生效") {
                Note("修改后重新进入「关于手机」即可看到效果。第一次启用模块、或更新本应用后，需要先重启「设置」。")
                NavRow("打开「关于手机」", "跳转到系统设置查看效果") {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                Divider()
                NavRow("重启「设置」", "结束系统设置进程，下次打开时重新加载模块") {
                    scope.launch {
                        val r = Root.exec("am force-stop com.android.settings")
                        scope.toast(snack, if (r.ok) "已重启「设置」" else "失败：${r.err.trim()}")
                    }
                }
            }
        }
    }
}

/** 预览：与注入「设置」的是同一套 View，系统内容用本机示例数据 */
@Composable
private fun Preview(cfg: MyDeviceCfg.Snapshot, model: String, demo: Boolean) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val pal = remember(cfg.theme, dark) { Pal.resolve(ctx, cfg.theme) }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(pal.page)).padding(10.dp)) {
        key(cfg.theme, dark, model, cfg.art, cfg.mituColor, cfg.artVer) {
            AndroidView(
                factory = { c -> AboutPage(c, pal, SocInfo.demo(c, model), demo, cfg) },
                update = { v -> v.bind(sampleSys(v.context, cfg)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 读入所选图片，校验能解码后写进 LSPosed 远程文件（给「设置」用）和本地副本（给预览用）；失败返回原因 */
private fun uploadArt(ctx: android.content.Context, uri: android.net.Uri): String? {
    val svc = XpService.service ?: return "未连接 LSPosed，无法保存"
    val max = 8 shl 20
    val bytes = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 shl 10)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) return "图片超过 8MB，换一张小一点的"
            }
            out.toByteArray()
        }
    }.getOrNull() ?: return "读取图片失败"
    runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) }.getOrNull()
        ?: return "无法识别这张图片"
    val err = runCatching {
        svc.openRemoteFile(MyDeviceCfg.FILE_ART).use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { o -> o.channel.truncate(0); o.channel.position(0); o.write(bytes) }
        }
    }.exceptionOrNull()
    if (err != null) return "写入模块失败：${err.message}"
    File(ctx.filesDir, MyDeviceCfg.FILE_ART).writeBytes(bytes)
    return null
}

/** 系统本身就不显示的按钮，预览里也不画 */
private val SYSTEM_HIDDEN = setOf("service_support")

private fun sampleSys(c: android.content.Context, cfg: MyDeviceCfg.Snapshot): SysInfo {
    val name = Settings.Global.getString(c.contentResolver, "device_name") ?: android.os.Build.MODEL
    val ver = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "ro.mi.os.version.incremental") as String
    }.getOrDefault("").ifBlank { android.os.Build.DISPLAY }
    val st = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
    val storage = st?.let {
        val used = (it.totalBytes - it.availableBytes) / 1e9
        "%.1fGB/%dGB".format(used, SocInfo.marketSize(it.totalBytes))
    }.orEmpty()
    val noop = {}
    return SysInfo(
        name = name,
        version = ver,
        storage = storage,
        services = MyDeviceCfg.PREFS.filter { it.first !in cfg.hidePrefs && it.first !in SYSTEM_HIDDEN }.map { PanelItem(it.first, it.second, noop) },
        onName = noop, onVersion = noop, onStorage = noop, onParams = noop,
    )
}
