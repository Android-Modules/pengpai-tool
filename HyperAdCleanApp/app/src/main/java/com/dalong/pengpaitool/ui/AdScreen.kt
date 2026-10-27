package com.dalong.pengpaitool.ui

import android.util.Base64
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dalong.pengpaitool.Mod
import com.dalong.pengpaitool.ui.Tag
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.launch

@Composable
fun AdScreen() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var hosts by remember { mutableStateOf(listOf<String>()) }
    var custom by remember { mutableStateOf("") }
    var tweaks by remember { mutableStateOf(listOf<List<String>>()) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun reload() {
        hosts = Mod.rows("hosts-status").firstOrNull() ?: emptyList()
        tweaks = Mod.rows("tweak-status")
    }
    LaunchedEffect(Unit) {
        reload()
        custom = Mod.raw("hosts-custom-get")
        loaded = true
    }
    fun setHosts(group: String, on: Boolean) = scope.launch {
        val r = Mod.result("hosts-set $group ${if (on) 1 else 0}")
        reload(); scope.toast(snack, r.msg)
    }

    val mounted = hosts.getOrNull(0) == "1"
    Page(loaded = loaded) {
        item {
            Section("域名拦截", "通过 hosts 拦截广告和统计域名，不冻结任何应用。", "已拦截 ${hosts.getOrNull(4) ?: 0} 个域名") {
                SwitchRow("小米广告与统计域名", "ad.xiaomi.com、tracking.miui.com、data.mistat 等，不影响推送、账号、云服务、小爱",
                    hosts.getOrNull(1) == "1") { setHosts("xiaomi", it) }
                Divider()
                val cs = MaterialTheme.colorScheme
                SwitchRow("第三方广告 SDK 域名", "穿山甲、优量汇、快手联盟、百度联盟。对所有应用生效，部分 App 和游戏的「看广告领奖励」会失效",
                    hosts.getOrNull(2) == "1", badge = { Tag("谨慎", cs.onErrorContainer, cs.errorContainer) }) { setHosts("sdk", it) }
                Divider()
                SwitchRow("自定义规则", "每行一个域名，或「IP 域名」；# 开头为注释", hosts.getOrNull(3) == "1") { setHosts("custom", it) }
                OutlinedTextField(
                    custom, { custom = it },
                    placeholder = { Text("# 例如\nads.example.com") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(top = 4.dp),
                )
                FilledTonalButton(onClick = { scope.launch {
                    val b64 = Base64.encodeToString(custom.toByteArray(), Base64.NO_WRAP)
                    val r = Mod.result("hosts-custom-set $b64")
                    reload(); scope.toast(snack, r.msg)
                } }, modifier = Modifier.padding(top = 8.dp)) { Text("保存自定义规则") }
                if (hosts.isNotEmpty()) Note(if (mounted) "hosts 已挂载，开关即时生效。个别应用有 DNS 缓存，没生效就开关一次飞行模式。" else "还没开启任何规则，系统 hosts 保持原样。")
            }
        }
        item {
            Section("系统开关", "修改系统设置项，关闭时还原为原来的值。") {
                tweaks.forEachIndexed { i, t ->
                    if (i > 0) Divider()
                    val on = t.getOrNull(1) == "1"
                    SwitchRow(t.getOrElse(4) { t[0] }, "当前值 ${t.getOrNull(2)}" + if (on) "" else " · 开启后设为 ${t.getOrNull(3)}", on) { v ->
                        scope.launch {
                            val r = Mod.result("${if (v) "tweak-on" else "tweak-off"} ${t[0]}")
                            reload(); scope.toast(snack, r.msg)
                        }
                    }
                }
            }
        }
    }
}
