package com.dalong.pengpaitool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dalong.pengpaitool.Mod
import com.dalong.pengpaitool.labelOf

@Composable
fun ResultDialog(title: String, list: List<Mod.Result>, onDismiss: () -> Unit) {
    val ok = list.count { it.ok }
    val pm = androidx.compose.ui.platform.LocalContext.current.packageManager
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("成功 $ok / ${list.size}" + if (ok > 0) "，建议重启一次手机" else "", style = MaterialTheme.typography.bodyMedium)
                list.forEach { r ->
                    val (mark, color) = when (r.kind) {
                        "OK" -> "✓" to MaterialTheme.colorScheme.primary
                        "ERR" -> "✕" to MaterialTheme.colorScheme.error
                        else -> "–" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(Modifier.padding(top = 6.dp)) {
                        Text(mark, color = color, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text("${labelOf(r.target, pm)}  ${r.msg}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (list.isEmpty()) Text("没有输出", modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
    )
}

@Composable
fun BusyOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            .clickable(remember { MutableInteractionSource() }, null) {},
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator() }
}
