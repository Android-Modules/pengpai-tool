package com.dalong.pengpaitool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dalong.pengpaitool.Mod
import com.dalong.pengpaitool.startPage
import kotlinx.coroutines.launch

@Composable
fun AntiFraudScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var s by remember { mutableStateOf(listOf<String>()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() { s = Mod.rows("af-status").firstOrNull() ?: emptyList() }
    LaunchedEffect(Unit) { reload(); loaded = true }
    fun run(cmd: String) = scope.launch {
        busy = true
        val r = Mod.result(cmd)
        reload(); busy = false
        scope.toast(snack, r.msg)
    }

    val off = s.getOrNull(0) == "1"
    val disabled = s.getOrNull(1) ?: "0"
    val total = s.getOrNull(2) ?: "5"
    val running = s.getOrNull(3) == "1"
    val upload = s.getOrNull(8) == "1"
    val mounted = s.getOrNull(9) == "1"
    val call = s.getOrNull(10) == "1"
    fun onOff(v: String?) = when (v) { "1", "true" -> "开启"; "0", "false" -> "关闭"; else -> "-" }

    Page(loaded = loaded) {
        item {
            Section("当前状态", "反诈都在「手机管家」里：电诈防护页的各项开关，加上后台常驻的 XSOF 欺诈态势采集（汇总截图、屏幕共享、通话、呼叫转移、安装应用分类等情况，供其他应用查询）。") {
                InfoRow("后台采集服务", if (running) "运行中" else "未运行")
                InfoRow("已停用组件", "$disabled / $total")
                InfoRow("短信风险提醒", onOff(s.getOrNull(4)))
                InfoRow("屏幕共享防护", onOff(s.getOrNull(5)))
                InfoRow("通话 / 短信后支付警告", onOff(s.getOrNull(6)))
                InfoRow("风险应用识别", onOff(s.getOrNull(7)))
                InfoRow("上传屏蔽", if (!upload) "未开启" else if (mounted) "已生效" else "重启后生效")
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = {
                        scope.launch {
                            startPage(ctx, "-n com.miui.securitycenter/com.miui.electricrisk.ElectricRiskMainActivity")?.let { scope.toast(snack, it) }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("打开电诈防护") }
                    OutlinedButton(onClick = { scope.launch { reload() } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("刷新") }
                }
            }
        }
        item {
            Section("禁用反诈") {
                SwitchRow("禁用反诈", "关闭短信风险与验证码剪贴板提醒、屏幕共享防护、支付页防诈警告、风险应用识别、AI 通话预警、网页风险提醒，并停用支付页监控、XSOF 欺诈态势采集与对外查询、AI 反诈场景监控 5 个后台组件。开机自动再关一遍，关闭开关时按原值恢复", off, enabled = !busy) {
                    run(if (it) "af-on" else "af-off")
                }
                Note("不影响手机管家其他功能和系统骚扰拦截。禁用后银行、支付类应用无法再向系统查询「是否正在屏幕共享 / 通话中」等欺诈态势。")
            }
        }
        item {
            Section("禁用反诈上传") {
                SwitchRow("屏蔽反诈上传", "用 hosts 拦截风险应用云扫描（会上传已安装应用信息和设备标识）、风险指令、NFC 反诈配置和反诈事件埋点的域名", upload, enabled = !busy) {
                    run("hosts-set antifraud ${if (it) 1 else 0}")
                }
                Divider()
                SwitchRow("陌生号码智能识别", "来电时把陌生号码发到云端查询标记（骚扰、诈骗、快递等）。关闭后不再上传来电号码，但也不会显示号码标记", call, enabled = !busy) {
                    run("af-call ${if (it) 1 else 0}")
                }
                Note("埋点域名 tracking.miui.com 是小米统一的统计上报地址，屏蔽后其他系统应用的统计也一起拦截，不影响正常使用。")
            }
        }
    }
    if (busy) BusyOverlay()
}
