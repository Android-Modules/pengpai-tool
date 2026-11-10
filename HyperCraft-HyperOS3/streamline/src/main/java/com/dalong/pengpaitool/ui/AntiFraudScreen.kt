package com.dalong.pengpaitool.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    var reporting by remember { mutableStateOf(listOf<String>()) }
    var destinations by remember { mutableStateOf(listOf<String>()) }
    var confirmReporting by remember { mutableStateOf(false) }

    suspend fun reload() {
        s = Mod.rows("af-status").firstOrNull() ?: emptyList()
        reporting = Mod.rows("reporting-status").firstOrNull() ?: emptyList()
        destinations = Mod.rows("reporting-hosts-status").firstOrNull() ?: emptyList()
    }
    LaunchedEffect(Unit) { reload(); loaded = true }
    fun run(cmd: String) = scope.launch {
        busy = true
        try {
            val r = Mod.result(cmd)
            reload()
            scope.toast(snack, r.msg)
        } finally { busy = false }
    }

    val off = s.getOrNull(0) == "1"
    val disabled = s.getOrNull(1) ?: "0"
    val total = s.getOrNull(2) ?: "5"
    val running = s.getOrNull(3) == "1"
    val upload = s.getOrNull(8) == "1"
    val mounted = s.getOrNull(9) == "1"
    val call = s.getOrNull(10) == "1"
    val reportingMode = reporting.getOrNull(0)
    val reportingKnown = reportingMode in listOf("on", "off", "restoring")
    val reportingAvailable = reporting.drop(1).any { it in listOf("0", "1", "2", "3", "4") }
    fun packageState(value: String?) = when (value) {
        "0", "1" -> "启用"; "2", "3" -> "停用"; "4" -> "未使用时停用"
        "x" -> "本机未安装"; else -> "读取失败"
    }
    fun onOff(v: String?) = when (v) { "1", "true" -> "开启"; "0", "false" -> "关闭"; else -> "-" }

    Page(loaded = loaded) {
        item {
            Section("应用信息上报防护", "分别控制已知网络目的地和组件运行。开启前保存原状态，关闭后恢复；不卸载应用，不删除数据。") {
                SwitchRow("屏蔽已知上报域名", "拦截 fraud-detection.engine.miui.com、flash.sec.miui.com 和 dm.wo.com.cn，覆盖域名对应的全部路径与端口", destinations.getOrNull(0) == "1",
                    enabled = !busy && destinations.getOrNull(0) in listOf("0", "1")) {
                    run("hosts-set reporting ${if (it) 1 else 0}")
                }
                if (destinations.getOrNull(0) == "1") InfoRow("域名规则", if (destinations.getOrNull(1) == "1" && destinations.getOrNull(2) == "3") "已挂载 · 3 / 3" else "未确认全部生效，请检查 Root 挂载")
                Note("域名拦截不能阻止本地采集，也不能覆盖直连 IP、已有连接、应用自带 DNS 或新增地址。此处只显示规则状态，不表示已经抓包证实上传被阻止。")
                Divider()
                SwitchRow("停用两个上报组件", "停用 SystemHelper（联通 DM）和系统安全组件，阻止这两个包运行；也会影响运营商终端管理、病毒扫描和云端反诈", reportingMode == "on" || reportingMode == "restoring",
                    enabled = !busy && reportingKnown && (reportingAvailable || reportingMode != "off")) {
                    if (it) confirmReporting = true else run("reporting-off")
                }
                InfoRow("SystemHelper", packageState(reporting.getOrNull(1)))
                InfoRow("系统安全组件", packageState(reporting.getOrNull(2)))
                if (reportingMode == "restoring") Note("上次恢复尚未完成，原始状态仍保留。请关闭开关重试恢复。")
                if (reportingMode == "on" && reporting.drop(1).any { it in listOf("0", "1", "4", "unknown") }) Note("保存的开关与实际组件状态不一致，请关闭恢复后重新开启。")
                Note("仅针对 com.mobiletools.systemhelper 和 com.miui.guardprovider；不处理高通同名组件、电话服务或小米 SIM 激活服务。关闭时按原启用状态恢复，不会把原本停用的组件强行启用。系统升级后请刷新复查。")
            }
        }
        item {
            Section("手机管家反诈", "以下是手机管家自身的电诈防护开关和 XSOF 服务，与上方独立组件分开管理。") {
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
                Note("禁用后银行、支付类应用可能无法再向系统查询「是否正在屏幕共享 / 通话中」等欺诈态势。")
            }
        }
        item {
            Section("禁用反诈上传") {
                SwitchRow("屏蔽手机管家反诈域名", "拦截现有风险应用云扫描、风险指令和统计规则组；与上方三个域名的独立规则可以同时开启", upload, enabled = !busy) {
                    run("hosts-set antifraud ${if (it) 1 else 0}")
                }
                Divider()
                SwitchRow("陌生号码智能识别", "来电时把陌生号码发到云端查询标记（骚扰、诈骗、快递等）。关闭后不再上传来电号码，但也不会显示号码标记", call, enabled = !busy) {
                    run("af-call ${if (it) 1 else 0}")
                }
                Note("此规则包含 tracking.miui.com 等共享统计域名，会同时影响其他系统应用的统计。重叠域名会在仍有规则启用时继续屏蔽。")
            }
        }
    }
    if (confirmReporting) AlertDialog(
        onDismissRequest = { confirmReporting = false },
        title = { Text("停用上报组件？") },
        text = { Text("将停用本机存在的 SystemHelper 与系统安全组件，保留数据和原启用状态。病毒扫描、AI 反诈检测及运营商终端管理也可能不可用；尚未验证所有换卡和运营商业务。仅希望拦截已知网络地址时，可只开启上方域名开关。") },
        confirmButton = { TextButton(onClick = { confirmReporting = false; run("reporting-on") }) { Text("停用并保留恢复记录") } },
        dismissButton = { TextButton(onClick = { confirmReporting = false }) { Text("取消") } }
    )
    if (busy) BusyOverlay()
}
