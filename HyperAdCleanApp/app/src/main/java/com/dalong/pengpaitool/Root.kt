package com.dalong.pengpaitool

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** 通过 su 执行命令。KernelSU 不弹授权框，需要用户在管理器里给本应用授权。 */
object Root {
    data class Res(val code: Int, val out: String, val err: String) {
        val lines: List<String> get() = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val ok: Boolean get() = code == 0
    }

    suspend fun exec(cmd: String): Res = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder("su", "-c", cmd).start()
            p.outputStream.close()
            coroutineScope {
                val out = async { p.inputStream.bufferedReader().readText() }
                val err = async { p.errorStream.bufferedReader().readText() }
                val code = p.waitFor()
                Res(code, out.await(), err.await())
            }
        } catch (e: Exception) {
            Res(-1, "", e.message ?: e.toString())
        }
    }

    suspend fun granted(): Boolean = exec("id -u").out.trim() == "0"
}

/** 单引号转义，用于拼接到 shell 命令里的任意字符串 */
fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** 启动时检查到的权限：没有 Root 或模块时首页只显示能用的功能 */
object Access {
    var root by mutableStateOf(false)
    var module by mutableStateOf(false)
}

/**
 * 打开系统页面，参数格式同 am start：-n 组件 / -a 动作，可带 --es 键 值。
 * 先直接 startActivity（导出的页面不需要 Root），打不开且有 Root 时再用 am start。返回 null 表示成功，否则是失败原因。
 */
suspend fun startPage(ctx: Context, args: String): String? {
    val t = args.trim().split(Regex("\\s+"))
    val intent = Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    var k = 0
    while (k < t.size) {
        when (t[k]) {
            "-n" -> intent.component = ComponentName.unflattenFromString(t.getOrNull(++k).orEmpty())
            "-a" -> intent.action = t.getOrNull(++k)
            "--es" -> { intent.putExtra(t.getOrNull(k + 1), t.getOrNull(k + 2)); k += 2 }
        }
        k++
    }
    val e = runCatching { ctx.startActivity(intent) }.exceptionOrNull() ?: return null
    if (!Access.root) return if (e is SecurityException) "该页面未公开，需要 Root 才能打开" else "打开失败：${e.message ?: e}"
    val r = Root.exec("am start " + t.joinToString(" ") { if (it.startsWith("-")) it else shq(it) })
    return if (!r.ok || r.err.contains("Error")) "打开失败：${r.err.lines().firstOrNull { it.isNotBlank() } ?: ""}" else null
}
