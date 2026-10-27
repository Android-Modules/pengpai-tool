package com.dalong.pengpaitool.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dalong.pengpaitool.Mod
import com.dalong.pengpaitool.Sys
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// HyperOS 自己的隐藏列表（系统设置「状态栏图标」用的也是它）：system/status_bar_hide_icon_slot_name。
// 原生的 secure/icon_blacklist 在 HyperOS 3 上对静音、勿扰等图标无效，所以不用它。
private const val HIDE_KEY = "status_bar_hide_icon_slot_name"
private val ICON_SLOTS = listOf(
    "network_speed" to "网速", "volume" to "静音/振动", "zen" to "勿扰", "alarm_clock" to "闹钟",
    "vpn" to "VPN", "nfc" to "NFC", "location" to "定位", "bluetooth" to "蓝牙",
    "bluetooth_handsfree_battery" to "蓝牙耳机电量", "headset" to "有线耳机", "wireless_headset" to "无线耳机",
    "hotspot" to "热点", "wifi" to "WLAN", "hd" to "HD 高清通话", "roaming" to "漫游", "airplane" to "飞行模式",
    "no_sim" to "无 SIM 卡", "second_space" to "第二空间", "managed_profile" to "工作资料", "cast" to "投屏",
    "phone" to "手机互联", "pc" to "电脑互联", "pad" to "平板互联", "car" to "车机互联", "tv" to "电视互联",
    "glasses" to "眼镜互联", "camera" to "相机互联", "sound_box" to "音箱互联",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisplayScreen(part: String) {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var fps by remember { mutableStateOf(listOf<String>()) }
    var overlay by remember { mutableStateOf(false) }
    var font by remember { mutableFloatStateOf(1f) }
    var notifIcons by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(setOf<String>()) }
    var color by remember { mutableStateOf(listOf<String>()) }
    var loaded by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    suspend fun reload() {
        if (part == "color") { color = Mod.rows("color-status").firstOrNull() ?: emptyList(); return }
        fps = Mod.rows("fps-status").firstOrNull() ?: emptyList()
        overlay = Mod.raw("fps-overlay").trim() == "1"
        font = Sys.get("system", "font_scale").toFloatOrNull() ?: 1f
        notifIcons = (Sys.get("system", "status_bar_show_notification_icon").toIntOrNull() ?: 0) > 0
        hidden = Sys.get("system", HIDE_KEY).takeIf { it != "null" }?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }
    LaunchedEffect(Unit) { reload(); loaded = true }

    Page(loaded = loaded) {
        if (part == "fps") item {
            val lock = fps.getOrNull(0) ?: "0"
            val modes = fps.getOrNull(3)?.split(',')?.mapNotNull { it.substringAfter(':').toIntOrNull() }?.sorted() ?: emptyList()
            val method = fps.getOrNull(4) ?: "smart"
            val smartMax = fps.getOrNull(6)?.toIntOrNull() ?: 144
            fun set(hz: Int, m: String) = scope.launch {
                val r = Mod.result("fps-set $hz $m"); reload(); scope.toast(snack, r.msg)
            }
            val last = fps.getOrNull(7)?.toIntOrNull() ?: smartMax
            // 开启时用上次选的值；本机不支持或超出该方式上限时，取本机支持的最高档
            fun pick(m: String): Int {
                val ok = if (m == "force") modes else modes.filter { it <= smartMax }
                return if (last in ok) last else ok.maxOrNull() ?: 60
            }
            val overSmart = modes.any { it > smartMax }
            val idle = fps.getOrNull(8)?.toIntOrNull() ?: 5000
            val idleBoot = fps.getOrNull(9)?.toIntOrNull() ?: 0
            val on = lock != "0"
            // 降帧等待只在开机时生效：要用的值和本次开机的值不一致就需要重启
            val idleWant = if (on && method == "hold") idle else 0
            val idlePending = idleWant != idleBoot
            Section("全局高刷", "下面四项只能开一个，关掉当前这项就回到系统默认。", "当前 ${fps.getOrNull(1) ?: "-"}Hz") {
                SwitchRow("系统默认", "不做任何修改，使用系统自己的刷新率设置", !on) { v -> if (v) set(0, method) else set(pick("smart"), "smart") }
                listOf(
                    Triple("smart", "智能（推荐）", "最高提到选定值，滑动时满帧，静止和看视频时自动降帧，几乎不额外耗电"),
                    Triple("hold", "强制（省电）", "操作时和停手后几秒内保持选定值，之后再降帧；比智能少很多降帧切换，比强制锁定省电。重启生效"),
                    Triple("force", "强制锁定", "任何时候都保持选定值，不降帧，最耗电${if (overSmart) "；可选 ${smartMax}Hz 以上" else ""}"),
                ).forEach { (m, t, d) ->
                    Divider()
                    SwitchRow(t, d, on && method == m) { v -> if (v) set(pick(m), m) else set(0, method) }
                }
                if (on) {
                    Divider()
                    Text("刷新率", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                    FlowRow(Modifier.padding(top = 2.dp)) {
                        modes.filter { it >= 60 }.forEach { hz ->
                            FilterChip(selected = lock == hz.toString(), enabled = method == "force" || hz <= smartMax, onClick = { set(hz, method) },
                                label = { Text("${hz}Hz") }, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
                if (on && method == "hold") {
                    Text("停手后保持", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    FlowRow(Modifier.padding(top = 2.dp)) {
                        listOf(3000, 5000, 10000).forEach { ms ->
                            FilterChip(selected = idle == ms, onClick = { scope.launch {
                                val r = Mod.result("fps-idle $ms"); reload(); scope.toast(snack, r.msg)
                            } }, label = { Text("${ms / 1000} 秒") }, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
                if (idlePending) Note(
                    if (idleWant > 0) "降帧等待改为 ${idleWant / 1000} 秒需要重启手机后生效，现在仍是${if (idleBoot > 0) " ${idleBoot / 1000} 秒" else "系统默认的 1.1 秒"}。"
                    else "本次开机仍是停手 ${idleBoot / 1000} 秒后降帧，重启手机后恢复系统默认的 1.1 秒。"
                )
                if (on) Note(
                    when (method) {
                        "force" -> "已强制锁定 ${lock}Hz，静止时也不降帧，会明显增加耗电（实测桌面静置多耗约 0.6W）。切换应用或打开相机时系统会改刷新率，约 1 秒内自动锁回。"
                        "hold" -> "操作时和停手后 ${idle / 1000} 秒内保持 ${lock}Hz，之后才降帧（系统默认停手 1.1 秒就降）。看视频、机身过热、省电模式时照常降帧，不会压掉温控保护，也不需要后台进程。"
                        else -> "最高 ${lock}Hz，由系统按画面内容调节：滑动、动画时到 ${lock}Hz，静止、视频时自动降低。" +
                            if ((lock.toIntOrNull() ?: 0) > 120) "系统设置里的刷新率选项只列到 120Hz，显示不出 ${lock}Hz 属于正常。" else ""
                    }
                )
                Divider()
                SwitchRow("显示刷新率", "开发者选项里的同名开关：在屏幕左上角实时显示当前刷新率", overlay) { v -> scope.launch {
                    val r = Mod.result("fps-overlay ${if (v) 1 else 0}"); reload(); scope.toast(snack, r.msg)
                } }
            }
        }
        if (part == "color") item {
            val on = color.getOrNull(0) == "1"
            val active = color.getOrNull(1) == "1"
            val orig = color.getOrNull(2).orEmpty()
            Section(
                "完整色彩风格",
                "官方系统把「显示与亮度 → 色彩风格」精简成了专业原色、生动两项。开启后恢复生动、鲜艳、高级模式等完整选项（只改设备配置里的 support_simplified_colormode）。",
                when { active -> "已生效"; on -> "重启后生效"; else -> "官方精简版" },
            ) {
                SwitchRow("恢复完整色彩风格", "改的是开机时读取的设备配置，切换后需要重启手机", on, enabled = orig == "true") { v -> scope.launch {
                    val r = Mod.result("color-set ${if (v) 1 else 0}"); reload(); scope.toast(snack, r.msg)
                } }
                when {
                    orig.isEmpty() -> Note("没找到本机设备配置里的简化色彩开关，无法开启。", warn = true)
                    orig == "false" && !active -> Note("本机系统本来就是完整版色彩风格，不需要开启。")
                    on && !active -> Note("已开启，重启手机后到系统设置的色彩风格里就能看到完整选项。")
                    !on && active -> Note("已关闭，重启手机后恢复官方精简版。")
                }
                Note("色彩选项是灰色、提示「开启了全局护眼模式」时，需要先到「显示与亮度 → 护眼模式」关掉全局护眼才能切换。")
                Divider()
                NavRow("打开系统色彩设置", "跳转到系统设置的「显示与亮度」") {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
        if (part == "font") item {
            Section("字体大小", "无级缩放系统字体。HyperOS 重启会还原字体大小，这里设置的值会在开机后由模块重新应用。", "当前 ${"%.2f".format(font)}") {
                Text("小猫阿布走丢了，它在陌生的街道上徘徊。", fontSize = (15 * font).sp, modifier = Modifier.padding(vertical = 8.dp))
                Slider(font, { font = (it * 20).roundToInt() / 20f }, valueRange = 0.8f..1.6f, steps = 15)
                Row {
                    FilledTonalButton(onClick = { scope.launch {
                        val r = Mod.result("font-set ${"%.2f".format(java.util.Locale.US, font)}"); reload(); scope.toast(snack, r.msg)
                    } }) { Text("应用并开机保持") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { scope.launch {
                        Mod.result("font-set 1.0"); Mod.result("font-set 0"); reload(); scope.toast(snack, "已恢复默认 1.0")
                    } }) { Text("恢复默认") }
                }
            }
        }
        if (part == "icons") item {
            Section("状态栏通知图标") {
                SwitchRow("在状态栏显示通知图标", "HyperOS 只区分显示/不显示，显示几个由状态栏剩余空间决定", notifIcons) { v ->
                    scope.launch { Sys.put("system", "status_bar_show_notification_icon", if (v) "1" else "0"); reload() }
                }
            }
        }
        if (part == "icons") item {
            Section("隐藏状态栏图标", "选中的图标不再显示在状态栏，立即生效。和系统设置里的状态栏图标开关是同一份列表。", "已隐藏 ${hidden.size} 个") {
                FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    ICON_SLOTS.forEach { (slot, label) ->
                        FilterChip(selected = slot in hidden, onClick = {
                            scope.launch {
                                // 重新读一次再改单个槽位，保留系统和其他地方写入的项
                                val cur = Sys.get("system", HIDE_KEY).takeIf { it != "null" }?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
                                val next = if (slot in cur) cur - slot else cur + slot
                                Sys.put("system", HIDE_KEY, next.joinToString(","))
                                reload()
                            }
                        }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        }
    }
}
