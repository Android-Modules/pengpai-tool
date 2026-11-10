package com.dalong.pengpaitool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** APK 自带的 Root 后端。脚本归 Root 所有，配置独立于 APK，升级只替换程序。 */
object BackendRuntime {
    private val installLock = Mutex()
    var error: String? = null
        private set

    private suspend fun extract(context: Context): Pair<File, String> =
        withContext(Dispatchers.IO) {
                val dir = File(context.createDeviceProtectedStorageContext().filesDir, "backend-stage")
                val digest = MessageDigest.getInstance("SHA-256")
                fun extract(relative: String) {
                    val asset = "backend/$relative".trimEnd('/')
                    val children = context.assets.list(asset).orEmpty().sorted()
                    if (children.isNotEmpty()) {
                        children.forEach { extract(if (relative.isEmpty()) it else "$relative/$it") }
                    } else {
                        val bytes = context.assets.open(asset).use { it.readBytes() }
                        digest.update(relative.toByteArray(Charsets.UTF_8))
                        digest.update(0.toByte())
                        digest.update(bytes)
                        File(dir, relative).apply { parentFile?.mkdirs(); writeBytes(bytes) }
                    }
                }
                extract("")
                dir to digest.digest().joinToString("") { "%02x".format(it) }
        }

    /** 只读检查；旧 APK 本身存在不会构成冲突。 */
    suspend fun legacyConflict(context: Context): Boolean = installLock.withLock {
        val (stage, _) = extract(context)
        val result = Root.exec("sh ${shq(File(stage, "legacy.sh").absolutePath)} status")
        result.ok && result.lines.any { it == "active|1" }
    }

    /** 只能在用户明确选择停用旧后台后调用；不会卸载旧 APK 或删除配置。 */
    suspend fun deactivateLegacy(context: Context): Root.Res = installLock.withLock {
        val (stage, _) = extract(context)
        val result = Root.exec("sh ${shq(File(stage, "legacy.sh").absolutePath)} deactivate", timeoutSeconds = 90)
        context.createDeviceProtectedStorageContext().getSharedPreferences("runtime", Context.MODE_PRIVATE)
            .edit().putBoolean("ready", false).apply()
        result
    }

    suspend fun prepare(context: Context): Boolean = installLock.withLock {
        try {
            val (stage, hash) = extract(context)
            val command = "sh ${shq(File(stage, "install.sh").absolutePath)} ${shq(stage.absolutePath)} " +
                "${shq(hash)} ${shq(BuildInfo.versionName(context))}"
            val result = retryRuntimePreparation { Root.exec(command, timeoutSeconds = 90) }
            error = if (result.ok) null else result.lines.firstOrNull { it.startsWith("ERR|") }
                ?.substringAfterLast('|') ?: result.err.trim().ifBlank { "Root 运行环境初始化失败，请重新检查" }
            context.createDeviceProtectedStorageContext().getSharedPreferences("runtime", Context.MODE_PRIVATE)
                .edit().putBoolean("ready", result.ok).apply()
            result.ok
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "无法释放内置运行环境"
            false
        }
    }
}
