package com.dalong.pengpaitool.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String, val system: Boolean, val enabled: Boolean = true)

/**
 * 本机全部带代码的应用（含系统组件、已冻结的），用于精简选择。
 * 包名列表通过 root 的 pm 拿：HyperOS 会对 getInstalledApplications 弹「获取已安装应用」授权，
 * 逐个 getApplicationInfo 则不会。过滤掉 overlay 这类没有代码的纯资源包。
 */
suspend fun loadAllApps(ctx: Context): List<AppEntry> = withContext(Dispatchers.IO) {
    val pm = ctx.packageManager
    val pkgs = com.dalong.pengpaitool.Root.exec("pm list packages --user 0").lines
        .map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() && it != ctx.packageName }
    pkgs.mapNotNull { pkg ->
        runCatching {
            val ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
            if (ai.flags and ApplicationInfo.FLAG_HAS_CODE == 0) null
            else AppEntry(pkg, pm.getApplicationLabel(ai).toString(), ai.flags and ApplicationInfo.FLAG_SYSTEM != 0, ai.enabled)
        }.getOrNull()
    }.sortedBy { it.label.lowercase() }
}

/** 有桌面图标的应用；userOnly 时只保留用户自己装的（含系统应用的更新版不算） */
suspend fun loadApps(ctx: Context, userOnly: Boolean): List<AppEntry> = withContext(Dispatchers.IO) {
    val pm = ctx.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        .map { it.activityInfo.packageName }.distinct()
        .filter { it != ctx.packageName }
        .mapNotNull { pkg ->
            runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                AppEntry(pkg, pm.getApplicationLabel(ai).toString(), ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            }.getOrNull()
        }
        .filter { !userOnly || !it.system }
        .sortedBy { it.label.lowercase() }
}

/** 列表里按需加载图标，避免一次性解码几百个 */
@Composable
fun AppIcon(pkg: String, size: Int = 40) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(size * 3, size * 3).asImageBitmap() }.getOrNull()
        }
    }
    val m = Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp))
    icon?.let { Image(it, null, m) } ?: Box(m.background(MaterialTheme.colorScheme.surfaceContainerHighest))
}
