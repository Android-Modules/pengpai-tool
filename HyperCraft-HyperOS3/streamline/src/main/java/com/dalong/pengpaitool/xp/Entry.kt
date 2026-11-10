package com.dalong.pengpaitool.xp

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/** libxposed API 102 入口（LSPosed 2.x），作用域只有「设置」 */
class Entry : XposedModule() {

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SETTINGS || !param.isFirstPackage) return
        runCatching { MyDeviceHook.install(this, param.classLoader) }
            .onSuccess { log(Log.INFO, TAG, "我的设备 hook 已安装") }
            .onFailure { log(Log.ERROR, TAG, "我的设备 hook 失败", it) }
        runCatching { MituPetHook.install(this) }
            .onFailure { log(Log.ERROR, TAG, "米兔 hook 失败", it) }
    }

    companion object {
        const val TAG = "PengpaiTool"
        const val SETTINGS = "com.android.settings"
    }
}
