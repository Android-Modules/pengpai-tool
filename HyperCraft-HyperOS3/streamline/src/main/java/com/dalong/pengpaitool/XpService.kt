package com.dalong.pengpaitool

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dalong.pengpaitool.xp.MyDeviceCfg
import io.github.libxposed.service.XposedService
import java.io.File
import java.io.FileOutputStream

/**
 * 与 LSPosed 的连接：模块在 LSPosed 里启用后框架会主动绑定过来。
 * 配置写进 remote preferences，「设置」进程里的 hook 直接读，不依赖文件权限。
 *
 * remote preferences 只存在 LSPosed 的数据库里，系统升级或重装框架后可能被清空。
 * 本机另存一份副本：连接时远程组为空就用副本恢复（含自定义页头图），
 * 远程有值而副本为空（旧版本升级上来）则反向补齐副本。
 */
object XpService {
    private const val LOCAL = "mydevice_backup"

    var service by mutableStateOf<XposedService?>(null)
        private set

    @Volatile private var app: Context? = null

    /** 宿主 Application 统一管理唯一的 libxposed 服务监听器；先于绑定回调传入 Context。 */
    @JvmStatic
    fun init(context: Context) { app = context.applicationContext }

    @JvmStatic
    fun accept(value: XposedService) {
        runCatching { reconcile(value) }.onFailure { Log.w("PengpaiTool", "mydevice restore: $it") }
        service = value
    }

    @JvmStatic
    fun clear(value: XposedService) { if (service === value) service = null }

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

    /** 写远程配置并同步本机副本；未连接 LSPosed 时返回 null，不保存任何一边。 */
    fun save(edit: SharedPreferences.Editor.() -> Unit): SharedPreferences? {
        val remote = prefs() ?: return null
        remote.edit().apply(edit).apply()
        local()?.edit()?.apply(edit)?.apply()
        return remote
    }

    private fun local(): SharedPreferences? = app?.getSharedPreferences(LOCAL, Context.MODE_PRIVATE)

    private fun reconcile(s: XposedService) {
        val backup = local() ?: return
        val remote = s.getRemotePreferences(MyDeviceCfg.GROUP)
        val remoteAll = remote.all
        val backupAll = backup.all
        when {
            remoteAll.isEmpty() && backupAll.isNotEmpty() -> {
                remote.edit().apply { backupAll.forEach { (k, v) -> put(k, v) } }.apply()
                val art = app?.let { File(it.filesDir, MyDeviceCfg.FILE_ART) }
                if (art != null && art.isFile && backup.getString(MyDeviceCfg.K_ART, null) == MyDeviceCfg.ART_CUSTOM) {
                    s.openRemoteFile(MyDeviceCfg.FILE_ART).use { pfd ->
                        FileOutputStream(pfd.fileDescriptor).use { o -> o.channel.truncate(0); o.write(art.readBytes()) }
                    }
                }
                Log.i("PengpaiTool", "mydevice restored ${backupAll.size} keys from local backup")
            }
            backupAll.isEmpty() && remoteAll.isNotEmpty() ->
                backup.edit().apply { remoteAll.forEach { (k, v) -> put(k, v) } }.apply()
        }
    }

    private fun SharedPreferences.Editor.put(key: String, value: Any?) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
}
