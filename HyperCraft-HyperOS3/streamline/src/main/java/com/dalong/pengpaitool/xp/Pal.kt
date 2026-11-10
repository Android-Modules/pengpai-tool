package com.dalong.pengpaitool.xp

import android.content.Context
import android.content.res.Configuration

/** 「关于手机」两套主题的配色：雾蓝白 / 银灰香槟，各带深色版本 */
class Pal(
    val theme: String,
    val dark: Boolean,
    val page: Int,
    val card0: Int,
    val card1: Int,
    val cardStroke: Int,
    val shadow: Int,
    val title: Int,
    val text: Int,
    val sub: Int,
    val faint: Int,
    val divider: Int,
    /** 版本号等强调文字 */
    val accent: Int,
    /** 进度条、百分比的渐变 */
    val accent0: Int,
    val accent1: Int,
    val tile: Int,
    val icon: Int,
    val track: Int,
    val tempBg: Int,
    val tempDot: Int,
    val tempText: Int,
    val cell: Int,
    val pressed: Int,
    /** 大标题渐变：雾蓝为「Hyper」/「OS 3」，香槟为机型前缀 / 型号 */
    val head0: Int,
    val head1: Int,
    val head2: Int,
    val head3: Int,
) {
    val mist get() = theme == MyDeviceCfg.THEME_MIST

    companion object {
        fun resolve(ctx: Context, theme: String): Pal {
            val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return if (theme == MyDeviceCfg.THEME_CHAMP) champ(dark) else mist(dark)
        }

        private fun c(v: Long) = v.toInt()

        private fun mist(dark: Boolean) = if (!dark) Pal(
            MyDeviceCfg.THEME_MIST, false,
            page = c(0xFFF2F7FE), card0 = c(0xFFFBFDFF), card1 = c(0xFFF5F9FE), cardStroke = c(0xCCFFFFFF), shadow = c(0x1A5E80B4),
            title = c(0xFF0B1022), text = c(0xFF1A2238), sub = c(0xFF8A93A8), faint = c(0xFFA9B1C2), divider = c(0xFFE3EAF3),
            accent = c(0xFF4A7ECF), accent0 = c(0xFF9FBCE3), accent1 = c(0xFF7096CF),
            tile = c(0xFFE8EFF8), icon = c(0xFF3F5578), track = c(0xFFE1E8F1),
            tempBg = c(0xFFFEF2E4), tempDot = c(0xFFF4A442), tempText = c(0xFFE0904A),
            cell = c(0xFFFEFEFF), pressed = c(0x140B1022),
            head0 = c(0xFFB3CAE9), head1 = c(0xFF7D9ECD), head2 = c(0xFF6286BB), head3 = c(0xFF3E5D92),
        ) else Pal(
            MyDeviceCfg.THEME_MIST, true,
            page = c(0xFF0C1119), card0 = c(0xFF172031), card1 = c(0xFF131A28), cardStroke = c(0x16FFFFFF), shadow = c(0x50000000),
            title = c(0xFFEFF4FC), text = c(0xFFDCE4F2), sub = c(0xFF8592A8), faint = c(0xFF66728A), divider = c(0xFF232D3F),
            accent = c(0xFF8DB4F0), accent0 = c(0xFF5A82C2), accent1 = c(0xFF92B8F0),
            tile = c(0xFF1F2A3E), icon = c(0xFFA9C1E6), track = c(0xFF263045),
            tempBg = c(0xFF3A2918), tempDot = c(0xFFF4A442), tempText = c(0xFFF3B06A),
            cell = c(0xFF1A2335), pressed = c(0x1FFFFFFF),
            head0 = c(0xFF8EA9D2), head1 = c(0xFF6F92C8), head2 = c(0xFFB6CDF0), head3 = c(0xFF84A8E0),
        )

        private fun champ(dark: Boolean) = if (!dark) Pal(
            MyDeviceCfg.THEME_CHAMP, false,
            page = c(0xFFF8F7F4), card0 = c(0xFFFFFFFF), card1 = c(0xFFFEFEFD), cardStroke = c(0x08000000), shadow = c(0x0F3A2A18),
            title = c(0xFF111111), text = c(0xFF1B1A19), sub = c(0xFF8C8783), faint = c(0xFFB0ABA6), divider = c(0xFFEFEDEA),
            accent = c(0xFFA8896D), accent0 = c(0xFFB4937A), accent1 = c(0xFFD5B99B),
            tile = c(0xFFF5F1EC), icon = c(0xFF2B2926), track = c(0xFFE8E6E3),
            tempBg = c(0xFFFBF5EC), tempDot = c(0xFFBB9D82), tempText = c(0xFF9C7F66),
            cell = c(0xFFFFFFFF), pressed = c(0x0F000000),
            head0 = c(0xFF3D3936), head1 = c(0xFF0D0C0B), head2 = c(0xFF8A6E58), head3 = c(0xFFCDB49B),
        ) else Pal(
            MyDeviceCfg.THEME_CHAMP, true,
            page = c(0xFF121110), card0 = c(0xFF1E1C1A), card1 = c(0xFF1A1816), cardStroke = c(0x10FFFFFF), shadow = c(0x50000000),
            title = c(0xFFF4F0EA), text = c(0xFFE8E2DA), sub = c(0xFF9A938B), faint = c(0xFF6E6862), divider = c(0xFF2B2825),
            accent = c(0xFFCDB092), accent0 = c(0xFFB4937A), accent1 = c(0xFFDCC2A4),
            tile = c(0xFF2A2622), icon = c(0xFFE6DED4), track = c(0xFF302C29),
            tempBg = c(0xFF2E261E), tempDot = c(0xFFC9A888), tempText = c(0xFFD6B898),
            cell = c(0xFF1E1C1A), pressed = c(0x1AFFFFFF),
            head0 = c(0xFFF6F2EC), head1 = c(0xFFD9D3CB), head2 = c(0xFFB39579), head3 = c(0xFFE3CBB0),
        )
    }
}
