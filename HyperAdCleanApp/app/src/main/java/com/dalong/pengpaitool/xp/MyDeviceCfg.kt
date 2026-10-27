package com.dalong.pengpaitool.xp

import android.content.SharedPreferences

/**
 * 「关于手机」美化配置。App 通过 LSPosed 的 remote preferences 写入，
 * 「设置」进程里的 hook 只读。两边共用这一份 key 定义。
 */
object MyDeviceCfg {
    const val GROUP = "mydevice"

    const val K_ENABLED = "enabled"
    const val K_THEME = "theme"
    const val K_HIDE_PREFS = "hide_prefs"

    const val K_ART = "hero_art"
    const val K_MITU_COLOR = "mitu_color"
    /** 自定义图片每次更换时递增，「设置」进程据此重新读取远程文件 */
    const val K_ART_VER = "hero_art_ver"
    const val K_PET = "pet"
    const val K_PET_ECO = "pet_eco"

    const val THEME_MIST = "mist"
    const val THEME_CHAMP = "champ"

    val THEMES = listOf(THEME_MIST to "雾蓝白", THEME_CHAMP to "银灰香槟")

    const val ART_PHONE = "phone"
    const val ART_MITU = "mitu"
    const val ART_CUSTOM = "custom"
    val ARTS = listOf(ART_MITU to "米兔动图", ART_PHONE to "手机图", ART_CUSTOM to "自定义图片")

    /** 米兔配色：跟随主题（雾蓝 / 香槟）或经典原色 */
    const val MITU_THEME = "theme"
    const val MITU_ORIG = "orig"
    val MITU_COLORS = listOf(MITU_THEME to "跟随主题", MITU_ORIG to "经典原色")

    /** LSPosed 远程文件：App 写入自定义页头图片，hook 侧只读 */
    const val FILE_ART = "hero_custom"

    /** 服务列表里的按钮：Preference key to 标题 */
    val PREFS = listOf(
        "device_more_parameter" to "全部参数与信息",
        "one_key_migrate" to "小米换机",
        "huanji_history" to "设备换机记录",
        "privacy_settings" to "备份与恢复",
        "after_sale_service" to "售后服务",
        "service_support" to "服务与支持",
        "trade_in" to "以旧换新",
        "maintenancemode_key" to "维修模式",
        "factory_reset_key" to "恢复出厂设置",
        "credentials" to "认证信息",
    )

    data class Snapshot(
        val enabled: Boolean,
        val theme: String,
        val hidePrefs: Set<String>,
        val art: String = ART_MITU,
        val mituColor: String = MITU_THEME,
        val artVer: Long = 0,
        val pet: Boolean = true,
        /** 省电：米兔不呼吸，只偶尔做小动作，屏幕能一直停在 LTPO 低刷新 */
        val petEco: Boolean = false,
    ) {
        /** 米兔切图名：跟随主题时用当前主题的配色 */
        val mituAsset get() = "mitu_${if (mituColor == MITU_ORIG) "orig" else theme}.webp"
    }

    fun read(p: SharedPreferences?): Snapshot = Snapshot(
        enabled = p?.getBoolean(K_ENABLED, true) ?: true,
        theme = (p?.getString(K_THEME, THEME_MIST) ?: THEME_MIST).let { t -> if (THEMES.any { it.first == t }) t else THEME_MIST },
        hidePrefs = split(p?.getString(K_HIDE_PREFS, "")),
        art = (p?.getString(K_ART, ART_MITU) ?: ART_MITU).let { a -> if (ARTS.any { it.first == a }) a else ART_MITU },
        mituColor = if (p?.getString(K_MITU_COLOR, MITU_THEME) == MITU_ORIG) MITU_ORIG else MITU_THEME,
        artVer = runCatching { p?.getLong(K_ART_VER, 0) }.getOrNull() ?: 0,
        pet = p?.getBoolean(K_PET, true) ?: true,
        petEco = p?.getBoolean(K_PET_ECO, false) ?: false,
    )

    fun join(s: Set<String>) = s.sorted().joinToString(",")

    private fun split(s: String?) = s.orEmpty().split(',').filter { it.isNotBlank() }.toSet()
}
