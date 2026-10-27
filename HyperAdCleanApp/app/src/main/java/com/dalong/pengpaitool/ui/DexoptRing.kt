package com.dalong.pengpaitool.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 应用编译的圆环进度：
 * 编译选中应用时显示 已完成/总数 的进度弧，弧上有一道流光转动，中间是正在编译的应用；
 * 后台优化没有总数，圆环持续旋转。下方列出最近完成的几个。
 * dex = 运行中|已完成数|总数|当前包名|成功|失败|完成时间|任务
 */
@Composable
fun DexoptPanel(dex: List<String>, recent: List<Pair<String, Boolean>>, onPick: () -> Unit, onBg: () -> Unit) {
    val running = dex.getOrNull(0) == "1"
    val done = dex.getOrNull(1)?.toIntOrNull() ?: 0
    val total = dex.getOrNull(2)?.toIntOrNull() ?: 0
    val cur = dex.getOrNull(3).orEmpty()
    val task = dex.getOrNull(7).orEmpty()
    val finishedAt = dex.getOrNull(6).orEmpty()
    val bg = task == "bg"
    val target = when {
        running && bg -> 0.25f
        total > 0 -> done.toFloat() / total
        else -> 0f
    }
    val progress by animateFloatAsState(target, tween(700), label = "progress")
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(if (bg) 1100 else 2400, easing = LinearEasing)), label = "angle")
    val pulse by spin.animateFloat(0.92f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")

    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val accent = Color(0xFF59C3FF)

    Section("应用深度优化", "提前把应用代码编译成机器码，启动更快、运行更省电。编译期间手机会发热，可以正常使用。") {
        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(230.dp)) {
                val stroke = 16.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                val sweep = Brush.sweepGradient(listOf(accent, primary, accent))
                if (running && bg) {
                    rotate(angle) { drawArc(sweep, -90f, 90f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round)) }
                } else if (progress > 0f) {
                    drawArc(sweep, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (running) {
                        // 流光：沿已完成的弧转动的一小段亮色
                        val glow = Brush.sweepGradient(0f to Color.Transparent, 0.06f to Color.White.copy(alpha = 0.65f), 0.12f to Color.Transparent)
                        rotate(angle) { drawArc(glow, -90f, 360f * progress.coerceAtLeast(0.12f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round)) }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(160.dp)) {
                when {
                    running && !bg && cur.isNotBlank() -> {
                        // 换应用时淡入 + 轻微放大
                        AnimatedContent(cur, transitionSpec = { (fadeIn(tween(250)) + scaleIn(tween(250), 0.85f)) togetherWith fadeOut(tween(150)) }, label = "app") { pkg ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size((52 * pulse).dp), contentAlignment = Alignment.Center) { AppIcon(pkg, 52) }
                                Spacer(Modifier.height(6.dp))
                                Text(appName(pkg), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            }
                        }
                        Text("${done + 1} / $total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    running && bg -> {
                        Text("后台优化中", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text("系统按使用记录处理", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    total > 0 && finishedAt.isNotBlank() -> {
                        Text("${(progress * 100).toInt()}%", fontSize = 40.sp, fontWeight = FontWeight.Light, color = primary)
                        Text("上次 $finishedAt 完成", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        Text("准备就绪", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text("选择应用开始编译", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (!running && finishedAt.isNotBlank() && !bg) {
            val ok = dex.getOrNull(4)?.toIntOrNull() ?: 0
            val fail = dex.getOrNull(5)?.toIntOrNull() ?: 0
            Text("成功 $ok 个" + if (fail > 0) "，失败 $fail 个" else "", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            FilledTonalButton(enabled = !running, onClick = onPick) { Text("选择应用编译") }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(enabled = !running, onClick = onBg) { Text("执行后台优化") }
        }
        Text("后台优化：立即执行系统每晚在充电空闲时才跑的那次优化，按使用记录编译常用应用，并给长期不用的应用降级释放空间。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
    }

    if (recent.isNotEmpty() && !bg) {
        Spacer(Modifier.height(18.dp))
        Section(if (running) "刚刚完成" else "最近编译") {
            recent.reversed().forEachIndexed { i, (pkg, ok) ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg, 34)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(appName(pkg), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Mono(pkg)
                    }
                    Text(if (ok) "✓ 完成" else "✕ 失败", color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun appName(pkg: String): String {
    val pm = LocalContext.current.packageManager
    return remember(pkg) { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
}
