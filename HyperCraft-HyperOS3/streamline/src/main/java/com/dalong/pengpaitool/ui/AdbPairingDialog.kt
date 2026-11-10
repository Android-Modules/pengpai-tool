package com.dalong.pengpaitool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun AdbPairingDialog(
    info: AdbPairingInfo,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onCopy: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 20.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("无线调试配对", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("手机与电脑连接同一 WLAN 后，依次执行两步。",
                    style = MaterialTheme.typography.bodySmall)
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("本次配对码", style = MaterialTheme.typography.labelMedium)
                            SelectionContainer {
                                Text(info.code.chunked(3).joinToString(" "), fontFamily = FontFamily.Monospace,
                                    fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        IconButton(onClick = { onCopy("配对码", info.code) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "复制配对码")
                        }
                    }
                }
                PairingCommandCard("1 · 配对电脑", info.pairing.address, info.pairingCommand, onCopy)
                PairingCommandCard("2 · 连接手机", info.connection?.address, info.connectionCommand, onCopy)
                if (info.connection == null) {
                    Text("连接端口尚未就绪。可先完成配对，再刷新地址；配对端口不能代替连接端口。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onRefresh, enabled = !refreshing) {
                    Text(if (refreshing) "正在刷新…" else "刷新连接地址", maxLines = 1, softWrap = false)
                }
                Text("请保持弹窗打开，关闭后本次配对码失效。已配对的电脑只需执行第 2 步。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成", maxLines = 1, softWrap = false) } },
    )
}

@Composable
private fun PairingCommandCard(
    title: String,
    address: String?,
    command: String?,
    onCopy: (String, String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { command?.let { onCopy("命令", it) } }, enabled = command != null) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制$title 命令")
                }
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(address ?: "等待连接端口", style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (command != null) Text(command, fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}
