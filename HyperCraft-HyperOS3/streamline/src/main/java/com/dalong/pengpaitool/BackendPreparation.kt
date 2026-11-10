package com.dalong.pengpaitool

import kotlinx.coroutines.delay

/** The prior process may still hold a backend lock after returning readiness. */
internal suspend fun retryRuntimePreparation(
    pause: suspend (Long) -> Unit = { delay(it) },
    attempt: suspend () -> Root.Res,
): Root.Res {
    val backoff = longArrayOf(250, 500, 1000, 1500, 2000)
    var retries = 0
    while (true) {
        val result = attempt()
        val errors = result.lines.filter { it.startsWith("ERR|") }
        if (result.ok || errors.isEmpty() || errors.any {
                it != "ERR|runtime|运行环境正在初始化，请稍后重新检查" &&
                    it != "ERR|runtime|运行环境正在启动，请稍后重新检查"
            } || retries == backoff.size) return result
        // delay and the command both remain cancellable; never turn cancellation into readiness.
        pause(backoff[retries++])
    }
}
