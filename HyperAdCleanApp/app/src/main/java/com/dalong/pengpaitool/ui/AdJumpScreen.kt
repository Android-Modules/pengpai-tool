package com.dalong.pengpaitool.ui

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dalong.pengpaitool.Access
import com.dalong.pengpaitool.startPage
import kotlinx.coroutines.launch

/** 一个广告开关入口：标题、说明、目标页面组件，以及部分页面必需的 am start 额外参数 */
private data class AdJump(val title: String, val desc: String, val component: String, val extras: String = "")

// 各系统应用里「广告 / 个性化推荐 / 用户体验计划」开关所在的页面（与「高级设置」的广告设置一致，已在 HyperOS 3 上逐个确认）
private val AD_JUMPS = listOf(
    "手机管家" to listOf(
        AdJump("推荐广告", "安全中心的个性化推荐", "com.miui.securitycenter/com.miui.securityscan.ui.settings.SettingsPrivacyActivity"),
        AdJump("应用广告", "应用管理里的推荐", "com.miui.securitycenter/com.miui.appmanager.AppManagerSettings"),
    ),
    "应用商店" to listOf(
        AdJump("福利广告", "应用商店的功能与福利推送", "com.xiaomi.market/.business_ui.settings.function.FunctionSettingsFragmentActivity"),
        AdJump("服务广告", "个性化服务（推荐内容和广告）", "com.xiaomi.market/.business_ui.settings.recommend.RecommendSettingsFragmentActivity"),
        AdJump("通知广告", "应用商店的通知推送", "com.xiaomi.market/.ui.NotificationSettingsActivity"),
    ),
    "系统广告" to listOf(
        AdJump("系统广告", "小米账号里的系统广告开关", "com.xiaomi.account/.settings.SystemAdActivity"),
        AdJump("清理广告", "垃圾清理的推荐", "com.miui.cleanmaster/com.miui.optimizecenter.settings.SettingsActivity"),
        AdJump("下载广告", "下载管理的信息流", "com.android.providers.downloads.ui/.activity.InfoFlowSettingActivity"),
        AdJump("日历广告", "日历的用户体验与推荐", "com.android.calendar/.settings.UserExperienceActionBarActivity"),
        AdJump("天气广告", "天气的体验计划与推荐", "com.miui.weather2/.ActivityExperiencePlan"),
        AdJump("主题广告", "主题商店的隐私与推荐", "com.android.thememanager/.activity.PrivacySettingsActivity"),
        AdJump("钱包广告", "小米钱包设置", "com.mipay.wallet/.app.settings.MiuiStyleSettingActivity"),
        AdJump("社区广告", "小米社区的高级设置", "com.xiaomi.vipaccount/.ui.setting.HighSettingsActivity"),
        AdJump("营业厅广告", "电话里的营业厅", "com.android.contacts/com.mobile.businesshall.ui.about.BhAboutActivity"),
        AdJump("负一屏广告", "负一屏（智能助理）设置", "com.miui.personalassistant/.settings.PASettingActivity"),
        AdJump("包管理广告", "应用安装器的推荐", "com.miui.packageinstaller/com.android.packageinstaller.SettingsActivity"),
        AdJump("浏览器广告", "浏览器的个性化设置", "com.android.browser/.preferences.activity.CustomizationPreferenceActivity"),
        AdJump("小爱同学广告", "小爱同学的隐私设置", "com.miui.voiceassist/com.xiaomi.voiceassistant.settings.PrivacySettingsActivity"),
        AdJump("搜狗输入法广告", "搜狗输入法小米版的广告设置", "com.sohu.inputmethod.sogou.xiaomi/com.sohu.inputmethod.settings.activity.AdvertisementSettings"),
        AdJump("小米有品广告", "小米有品设置", "com.xiaomi.youpin/com.xiaomi.profile.model.setting.SettingActivity"),
        AdJump("小米澎湃引擎广告", "澎湃 AI 引擎的隐私管理", "com.xiaomi.aicr/.PrivacyManagerActivity"),
        // 搜索的设置页必须带 other_settings_preference_key，否则打开就关闭
        AdJump("全局搜索广告", "首页展示模块：应用推荐、热搜榜单、搜索提示词", "com.android.quicksearchbox/.preferences.OtherSettingsPreferenceActivity",
            "--es other_settings_preference_key home_display_module"),
        AdJump("搜索热榜", "我的热榜：推荐、热搜、娱乐、话题", "com.android.quicksearchbox/.preferences.OtherSettingsPreferenceActivity",
            "--es other_settings_preference_key common_hot"),
    ),
)

/** 页面可用状态：null 可打开；否则是不能打开的原因 */
private fun unavailable(pm: PackageManager, component: String): String? {
    val cn = ComponentName.unflattenFromString(component) ?: return "入口无效"
    val ai = runCatching { pm.getApplicationInfo(cn.packageName, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull() ?: return "未安装"
    if (!ai.enabled) return "已冻结"
    val act = runCatching { pm.getActivityInfo(cn, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull() ?: return "此版本没有该页面"
    return if (!act.exported && !Access.root) "该页面未公开，需要 Root 才能打开" else null
}

@Composable
fun AdJumpScreen() {
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val state = remember { AD_JUMPS.flatMap { it.second }.associate { it.title to unavailable(pm, it.component) } }

    Page {
        item { Banner("一键直达各系统应用里的广告、个性化推荐和用户体验计划开关，进入后手动关闭即可。") }
        AD_JUMPS.forEach { (group, items) ->
            item {
                Section(group) {
                    items.forEachIndexed { i, j ->
                        if (i > 0) Divider()
                        val why = state[j.title]
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = why == null) {
                                    scope.launch { startPage(ctx, "-n ${j.component} ${j.extras}")?.let { scope.toast(snack, it) } }
                                }
                                .padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(j.title, style = MaterialTheme.typography.bodyLarge,
                                    color = if (why == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(why ?: j.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (why == null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }
}
