package com.dalong.pengpaitool

import java.util.concurrent.TimeUnit

/**
 * su 本身可能只是 Root 管理器的客户端，结束它不会结束正在执行的脚本。
 * 前台命令使用独立进程组；保持 stdin 管道，关闭时由组内 Root 监视器负责结束本组。
 * 显式 setsid 启动的常驻守护属于其它组，不随页面的前台命令结束。
 */
internal class ProcessOwnership private constructor(val process: Process, val controlledInput: Boolean) {
    fun close() {
        // 必须先通知 Root 端，再结束 su 客户端。应用 UID 不能直接向 Root 子进程发信号。
        runCatching { process.outputStream.close() }
        if (controlledInput) runCatching { process.waitFor(750, TimeUnit.MILLISECONDS) }
        if (process.isAlive) process.destroyForcibly()
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    companion object {
        fun direct(process: Process) = ProcessOwnership(process, false)

        fun root(command: String): ProcessOwnership = launch(command) { script ->
            // su 若已是 session leader，setsid 会 fork；-w 保持客户端等待并传回真实退出码。
            ProcessBuilder("su", "-c", "exec /system/bin/setsid -w sh -c ${shq(script)}").start()
        }

        /** launcher 必须将包装器作为新 session 的 leader 启动；测试可注入普通用户进程。 */
        internal fun launch(command: String, launcher: (String) -> Process): ProcessOwnership {
            val script = """
                owner=${'$'}${'$'}
                exec 4<&0
                (
                  trap '' TERM HUP INT
                  IFS= read -r _ <&4
                  kill -TERM "-${'$'}owner" 2>/dev/null
                  sleep 0.2
                  kill -KILL "-${'$'}owner" 2>/dev/null
                ) &
                monitor=${'$'}!
                sh -c ${shq(command)} </dev/null 4<&- &
                worker=${'$'}!
                exec 4<&-
                wait "${'$'}worker"
                result=${'$'}?
                # 保留监视器直到客户端关闭 stdin：正常退出也回收同组遗留的后台子进程。
                # 包装器直接返回 worker 的真实退出码；显式 setsid 的任务已离开本组。
                exit "${'$'}result"
            """.trimIndent()
            return ProcessOwnership(launcher(script), true)
        }
    }
}
