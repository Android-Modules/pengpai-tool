package com.dalong.pengpaitool.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val LocalSnack = compositionLocalOf<SnackbarHostState> { error("no snackbar") }

fun CoroutineScope.toast(host: SnackbarHostState, msg: String) = launch {
    host.currentSnackbarData?.dismiss()
    host.showSnackbar(msg)
}

private val HyperLight = lightColorScheme(
    primary = Color(0xFF3482FF), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EEFF), onPrimaryContainer = Color(0xFF1F5FCC),
    secondaryContainer = Color(0xFFE8F0FE), onSecondaryContainer = Color(0xFF2F6BD8),
    tertiaryContainer = Color(0xFFE6F6EC), onTertiaryContainer = Color(0xFF1E8E4E),
    errorContainer = Color(0xFFFFECE6), onErrorContainer = Color(0xFFD24A1F), error = Color(0xFFE5482D),
    background = Color(0xFFF7F7F7), onBackground = Color(0xFF000000),
    surface = Color(0xFFF7F7F7), onSurface = Color(0xFF000000),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF2F2F2), surfaceContainerHighest = Color(0xFFEFEFF2),
    onSurfaceVariant = Color(0xFF8C8C8C), outline = Color(0xFFD5D5D5), outlineVariant = Color(0xFFE8E8E8),
)

private val HyperDark = darkColorScheme(
    primary = Color(0xFF3F8CFF), onPrimary = Color.White,
    primaryContainer = Color(0xFF1B3157), onPrimaryContainer = Color(0xFF9EC2FF),
    secondaryContainer = Color(0xFF1F2A3D), onSecondaryContainer = Color(0xFF8FB4F5),
    tertiaryContainer = Color(0xFF173326), onTertiaryContainer = Color(0xFF6FD39A),
    errorContainer = Color(0xFF3B1E16), onErrorContainer = Color(0xFFFF9A7A), error = Color(0xFFFF6B4F),
    background = Color(0xFF000000), onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF000000), onSurface = Color(0xFFF2F2F2),
    surfaceContainerLowest = Color(0xFF1C1C1E), surfaceContainerLow = Color(0xFF1C1C1E), surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF262628), surfaceContainerHighest = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF8E8E93), outline = Color(0xFF48484A), outlineVariant = Color(0xFF2C2C2E),
)

/** 仿 HyperOS 设置的配色：浅灰底、白色圆角分组卡片、系统蓝强调色 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) HyperDark else HyperLight, content = content)
}

/**
 * 所有页面统一的滚动容器，底部留出操作栏空间。
 * loaded 为 false 时显示加载中，首次数据读完后再淡入内容，避免先出现一屏空数据再突然跳变。
 */
@Composable
fun Page(bottomSpace: Int = 24, loaded: Boolean = true, content: LazyListScope.() -> Unit) {
    Crossfade(loaded, animationSpec = tween(220), label = "page") { ok ->
        if (!ok) LoadingHint()
        else LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = bottomSpace.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
    }
}

/** 加载中提示：转圈 + 文字。前 150ms 不显示，读得快的页面不会闪一下 */
@Composable
fun LoadingHint(text: String = "正在读取…", modifier: Modifier = Modifier.fillMaxWidth().padding(top = 120.dp)) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(150); show = true }
    Column(modifier.alpha(if (show) 1f else 0f), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

val GroupHeaderColor = Color(0xFF8A93AC)

/** HyperOS 风格分组：卡片外的小标题 + 白色圆角卡片 */
@Composable
fun Section(title: String, desc: String? = null, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = GroupHeaderColor, modifier = Modifier.weight(1f))
            if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodySmall, color = GroupHeaderColor)
        }
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                if (!desc.isNullOrBlank()) {
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
                content()
            }
        }
    }
}

@Composable
fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

@Composable
fun SwitchRow(title: String, desc: String? = null, checked: Boolean, enabled: Boolean = true, badge: (@Composable () -> Unit)? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (badge != null) { Spacer(Modifier.width(6.dp)); badge() }
            }
            if (!desc.isNullOrBlank()) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun ClickRow(title: String, desc: String? = null, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (!desc.isNullOrBlank()) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 提示条：普通为浅蓝（同 HyperOS 页面顶部说明），警告为浅橙 */
@Composable
fun Note(text: String, warn: Boolean = false) {
    val bg = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val fg = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(color = bg, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = fg, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

/** 顶部横幅：放在分组卡片之外，和 HyperOS 设置的提示条一样 */
@Composable
fun Banner(text: String, warn: Boolean = false, onClick: (() -> Unit)? = null) {
    val bg = if (warn) Color(0xFFFFF3DD) else MaterialTheme.colorScheme.primaryContainer
    val fg = if (warn) Color(0xFFE08A00) else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(color = bg, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
    }
}

/** 一行入口：标题 + 灰色说明，右侧可选的值和箭头（HyperOS 设置列表项） */
@Composable
fun NavRow(title: String, summary: String? = null, value: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!value.isNullOrBlank()) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** 入口和开关合在一行：点文字进入详情页，右侧开关直接切换 */
@Composable
fun NavSwitchRow(title: String, summary: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
        Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun Tag(text: String, color: Color, bg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(text, fontSize = 11.sp, color = color, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

@Composable
fun Mono(text: String) = Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
