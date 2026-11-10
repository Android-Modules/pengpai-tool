package com.dalong.pengpaitool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 通用 Root 开机脚本之外的补偿入口；不在广播里申请新授权或执行耗时恢复。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        if (!context.createDeviceProtectedStorageContext()
                .getSharedPreferences("runtime", Context.MODE_PRIVATE).getBoolean("ready", false)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Root.exec(
                    "if [ -s ${Mod.DIR}/payload.sha256 ]; then " +
                        ". ${Mod.DIR}/env.sh && start_detached sh ${Mod.DIR}/boot.sh start; fi",
                    timeoutSeconds = 8,
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val ACTIONS = setOf(Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
