package com.dalong.pengpaitool.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dalong.pengpaitool.Mod
import com.dalong.pengpaitool.Root
import kotlinx.coroutines.launch

/** sync-status 的一个用户空间 */
private data class SyncUser(val id: Int, val kind: String, val on: Boolean) {
    val title get() = when (kind) {
        "personal" -> "自动同步个人账号数据"
        "work" -> "自动同步工作账号数据"
        "xspace" -> "自动同步手机分身账号数据"
        else -> "自动同步用户 $id 的账号数据"
    }
}

@Composable
fun SyncScreen() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var rows by remember { mutableStateOf(listOf<List<String>>()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() { rows = Mod.rows("sync-status") }
    LaunchedEffect(Unit) { reload(); loaded = true }
    fun run(cmd: String) = scope.launch {
        busy = true
        val r = Mod.result(cmd)
        reload(); busy = false
        scope.toast(snack, r.msg)
    }

    val api = rows.firstOrNull { it[0] == "api" }?.getOrNull(1) == "1"
    val users = rows.filter { it[0] == "user" && it.size >= 4 }.mapNotNull { r -> r[1].toIntOrNull()?.let { SyncUser(it, r[2], r[3] == "1") } }
    val wifi = rows.firstOrNull { it[0] == "wifi" }?.getOrNull(1) == "1"
    val boot = rows.firstOrNull { it[0] == "boot" }?.getOrNull(1) == "1"
    val anyOn = users.any { it.on } || wifi

    Page(loaded = loaded) {
        item {
            Section("同步开关", "对应系统设置「账号与同步」顶部的开关。关闭自动同步后，小米云服务、谷歌、邮箱等账号不再在后台自动同步，需要时可在系统页手动「立即同步」。") {
                if (!api) Note("本机系统接口和预期不一致，自动同步开关无法在这里修改，请到系统设置里操作。", warn = true)
                users.forEachIndexed { i, u ->
                    if (i > 0) Divider()
                    SwitchRow(u.title, if (u.kind == "xspace") "系统设置里不显示这一项，但手机分身的账号同样会在后台同步" else null,
                        u.on, enabled = api && !busy) { v -> run("sync-set ${u.id} ${if (v) 1 else 0}") }
                }
                Divider()
                SwitchRow("仅在 WLAN 下同步", "开启后用移动数据时不同步", wifi, enabled = !busy) { v -> run("sync-set wifi ${if (v) 1 else 0}") }
                Row(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                    FilledTonalButton(onClick = { run("sync-off") }, enabled = !busy && anyOn) { Text(if (anyOn) "一键全部关闭" else "已全部关闭") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { scope.launch { Root.exec("am start -a android.settings.SYNC_SETTINGS") } }) { Text("系统同步页") }
                }
            }
        }
        item {
            Section("开机自动关闭", "账号登录、系统更新或小米云服务启动后可能把自动同步重新打开。开启后每次开机自动关闭上面全部开关（开机时和 2 分钟后各执行一次）。") {
                SwitchRow("开机自动关闭全部同步", if (boot) "开启时会立即关闭一次" else null, boot, enabled = !busy) { v -> run("sync-boot ${if (v) 1 else 0}") }
            }
        }
    }
}
