package com.dalong.pengpaitool.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dalong.pengpaitool.BuildInfo

/** Project information only: no Root, backend preparation, polling or retained page state. */
@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val version = remember(ctx) { BuildInfo.versionName(ctx) }
    val build = remember(ctx) {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toString() }
            .getOrDefault("—")
    }
    Page {
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    ProjectMark()
                    Spacer(Modifier.height(18.dp))
                    Text("澎湃工坊", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("HyperCraft", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(top = 14.dp)) {
                        Text(version, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                    }
                    Text("让系统更合心意", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
                }
            }
        }
        item {
            Section("本项目", "为 HyperOS 打造的系统定制工具，把日常想调整的细节放在一起。") {
                FeatureLine(Icons.Outlined.Tune, "设备工具", "组件管理、充电控制、调试与备份")
            }
        }
        item {
            Section("项目信息") {
                ProjectDetail("应用版本", "$version · $build")
                Divider()
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("应用包名", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer {
                            Text(ctx.packageName, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    IconButton(onClick = {
                        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("澎湃工坊包名", ctx.packageName))
                        scope.toast(snack, "已复制包名")
                    }) { Icon(Icons.Filled.ContentCopy, "复制应用包名", Modifier.size(18.dp)) }
                }
                Divider()
                ProjectDetail("当前系统", "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
                Divider()
                NavRow("项目仓库", "查看源代码与版本记录 · GitHub") {
                    val intent = Intent(Intent.ACTION_VIEW, "https://github.com/Android-Modules/hyper-home-v4".toUri())
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                    if (runCatching { ctx.startActivity(intent) }.isFailure)
                        scope.toast(snack, "未找到可以打开项目仓库的应用")
                }
            }
        }
        item {
            Text("LSPosed API 102 · Root 按需授权", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun ProjectMark() {
    Box(Modifier.size(64.dp).background(
        Brush.linearGradient(listOf(Color(0xFF3483FF), Color(0xFF5865E8))), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(34.dp)) {
            val cell = size.width * 0.4f
            val step = size.width - cell
            for (x in 0..1) for (y in 0..1) {
                drawRoundRect(Color.White, topLeft = Offset(x * step, y * step),
                    size = Size(cell, cell), cornerRadius = CornerRadius(2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun FeatureLine(icon: ImageVector, title: String, description: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(10.dp).size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun ProjectDetail(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
