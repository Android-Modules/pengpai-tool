package com.dalong.pengpaitool

import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 下拉通知栏的「开发者选项」快捷开关；读状态不需要权限，写入走 Root */
class DevTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun devOn() = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    private fun refresh(on: Boolean = devOn()) {
        val t = qsTile ?: return
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = if (on) "已开启" else "已关闭"
        t.updateTile()
    }

    override fun onStartListening() = refresh()

    override fun onClick() {
        val target = !devOn()
        refresh(target)
        scope.launch {
            Sys.put("global", "development_settings_enabled", if (target) "1" else "0")
            // 没有 Root 时写入失败，按实际状态回退
            refresh()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
