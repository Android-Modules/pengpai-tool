package com.dalong.pengpaitool

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** 主动通过 su 请求授权，兼容常见 Root 管理器；等待授权有超时。 */
object Root {
    internal const val OUTPUT_LIMIT_BYTES = 2 * 1024 * 1024

    data class Res(val code: Int, val out: String, val err: String, val truncated: Boolean = false) {
        val lines: List<String> get() = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val ok: Boolean get() = code == 0
    }

    suspend fun exec(cmd: String, timeoutSeconds: Long = 90): Res = withContext(Dispatchers.IO) {
        try {
            collectProcess(ProcessOwnership.root(cmd), timeoutSeconds)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Res(-1, "", e.message ?: e.toString())
        }
    }

    /** 命令收集与 su 启动分开，便于用普通子进程验证取消、超时及管道排空。 */
    internal suspend fun collectProcess(p: Process, timeoutSeconds: Long): Res =
        collectProcess(ProcessOwnership.direct(p), timeoutSeconds)

    internal suspend fun collectProcess(ownership: ProcessOwnership, timeoutSeconds: Long): Res {
        val p = ownership.process
        try {
            return withContext(Dispatchers.IO) {
                if (!ownership.controlledInput) p.outputStream.close()
                val out = BoundedOutput()
                val err = BoundedOutput()
                val buffer = ByteArray(16 * 1024)
                fun drain(): Int = drainAvailable(p.inputStream, out, buffer) + drainAvailable(p.errorStream, err, buffer)
                withTimeoutOrNull(timeoutSeconds * 1000) {
                    // 只读取管道已就绪的字节；取消时不必等待阻塞的 readText / waitFor。
                    // 两路轮流排空，子进程继承管道时也不会在关闭流时互相等待。
                    while (p.isAlive) {
                        if (drain() == 0) delay(25) else yield()
                    }
                    while (drain() > 0) yield()
                    val truncated = out.truncated || err.truncated
                    val diagnostic = err.toString("UTF-8") + if (truncated) "\n[命令输出超过每路 2 MiB，已截断显示]" else ""
                    Res(p.exitValue(), out.toString("UTF-8"), diagnostic, truncated)
                } ?: Res(-1, "", "等待 Root 命令超时，请检查管理器授权后重试")
            }
        } finally {
            // 包括进入 IO dispatcher 之前就已取消的情况，进程也必须交还。
            ownership.close()
        }
    }

    /** 超出显示上限后仍排空管道，避免子进程阻塞，也不再增长内存。 */
    private class BoundedOutput : ByteArrayOutputStream() {
        var truncated = false
            private set

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            val retained = length.coerceAtMost(OUTPUT_LIMIT_BYTES - size())
            if (retained > 0) super.write(buffer, offset, retained)
            if (retained < length) truncated = true
        }
    }

    private fun drainAvailable(input: InputStream, output: ByteArrayOutputStream, buffer: ByteArray): Int {
        var total = 0
        // 限制单次读取量，持续输出时也给另一条管道和取消检查留出机会。
        repeat(4) {
            val available = input.available().coerceAtMost(buffer.size)
            if (available == 0) return total
            val count = input.read(buffer, 0, available)
            if (count <= 0) return total
            output.write(buffer, 0, count)
            total += count
        }
        return total
    }

    suspend fun granted(): Boolean = exec("id -u", timeoutSeconds = 30).out.trim() == "0"
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
