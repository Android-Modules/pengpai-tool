package com.dalong.pengpaitool

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dalong.pengpaitool.xp.MyDeviceCfg
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 与 LSPosed 的连接：模块在 LSPosed 里启用后框架会主动绑定过来。
 * 配置写进 remote preferences，「设置」进程里的 hook 直接读，不依赖文件权限。
 */
object XpService {
    var service by mutableStateOf<XposedService?>(null)
        private set

    private var registered = false

    fun init() {
        if (registered) return
        registered = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(s: XposedService) { service = s }
            override fun onServiceDied(s: XposedService) { if (service === s) service = null }
        })
    }

    val framework: String?
        get() = service?.let { runCatching { "${it.frameworkName} ${it.frameworkVersion} · API ${it.apiVersion}" }.getOrNull() }

    fun scope(): List<String> = service?.let { runCatching { it.scope }.getOrNull() }.orEmpty()

    fun requestSettingsScope(done: (Boolean) -> Unit) {
        val s = service ?: return done(false)
        runCatching {
            s.requestScope(listOf("com.android.settings"), object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) = done(true)
                override fun onScopeRequestFailed(message: String) = done(false)
            })
        }.onFailure { done(false) }
    }

    fun prefs(): SharedPreferences? = service?.let { runCatching { it.getRemotePreferences(MyDeviceCfg.GROUP) }.getOrNull() }
}
