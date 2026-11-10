package com.dalong.pengpaitool

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** 只执行主机普通进程；不调用 su，也不访问 Android 或手机。 */
class RootProcessTest {
    /** macOS 没有 setsid CLI；Python 只负责建立 session，之后 exec 同一份生产包装器。 */
    private fun owned(command: String): ProcessOwnership = ProcessOwnership.launch(command) { script ->
        ProcessBuilder("python3", "-c",
            "import os,sys; os.setsid(); os.execl('/bin/sh','sh','-c',sys.argv[1])", script).start()
    }

    private suspend fun waitForPid(file: java.io.File): Long = withTimeout(2000) {
        var pid: Long? = null
        while (pid == null) {
            pid = file.readText().trim().toLongOrNull()
            if (pid == null) delay(10)
        }
        pid
    }

    private suspend fun assertEventuallyDead(pid: Long) = withTimeout(2000) {
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10)
    }

    @Test
    fun cancellationBeforeIoDispatchStillClosesProcess() = runBlocking {
        val process = ProcessBuilder("sh", "-c", "exec sleep 30").start()
        try {
            val job = launch {
                currentCoroutineContext().cancel()
                Root.collectProcess(process, 90)
            }
            withTimeout(2000) { job.join() }
            assertTrue(process.waitFor(2, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun cancellationDoesNotWaitForDescendantsHoldingPipes() = runBlocking {
        val childFile = Files.createTempFile("pengpai-process-", ".pid").toFile()
        val ownership = owned("sleep 30 & echo \$! > ${shq(childFile.absolutePath)}; wait")
        val process = ownership.process
        try {
            val job = async { Root.collectProcess(ownership, 90) }
            val child = waitForPid(childFile)
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(process.waitFor(2, TimeUnit.SECONDS))
            assertEventuallyDead(child)
        } finally {
            ownership.close()
            childFile.readText().trim().toLongOrNull()?.let { pid ->
                ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
            }
            childFile.delete()
        }
    }

    @Test
    fun ownedTimeoutEndsForegroundDescendants() = runBlocking {
        val childFile = Files.createTempFile("hypercraft-timeout-", ".pid").toFile()
        val ownership = owned("sleep 30 & echo \$! > ${shq(childFile.absolutePath)}; wait")
        try {
            val result = async { Root.collectProcess(ownership, 1) }
            val child = waitForPid(childFile)
            assertFalse(withTimeout(3000) { result.await() }.ok)
            assertEventuallyDead(child)
        } finally {
            ownership.close()
            childFile.readText().trim().toLongOrNull()?.let { pid ->
                ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
            }
            childFile.delete()
        }
    }

    @Test
    fun ownedCommandKeepsStdinClosedForWorkerAndPreservesExitAndOutput() = runBlocking {
        val ownership = owned("if read value; then exit 9; fi; echo output; echo diagnostic >&2; exit 7")
        val result = withTimeout(3000) { Root.collectProcess(ownership, 2) }
        assertEquals(7, result.code)
        assertEquals("output", result.out.trim())
        assertEquals("diagnostic", result.err.trim())
    }

    @Test
    fun normalExitCleansNonDetachedBackgroundDescendantsAndPreservesExit() = runBlocking {
        val childFile = Files.createTempFile("hypercraft-normal-", ".pid").toFile()
        val ownership = owned("sleep 30 & echo \$! > ${shq(childFile.absolutePath)}; echo output; exit 7")
        try {
            val result = withTimeout(3000) { Root.collectProcess(ownership, 2) }
            val child = waitForPid(childFile)
            assertEquals(7, result.code)
            assertEquals("output", result.out.trim())
            assertEventuallyDead(child)
        } finally {
            ownership.close()
            childFile.readText().trim().toLongOrNull()?.let { pid ->
                ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
            }
            childFile.delete()
        }
    }

    @Test
    fun normalExitPreservesExplicitlyDetachedDaemon() = runBlocking {
        val childFile = Files.createTempFile("hypercraft-normal-detached-", ".pid").toFile()
        val detach = "import os,sys; os.setsid(); open(sys.argv[1],'w').write(str(os.getpid())); os.execl('/bin/sleep','sleep','30')"
        val ownership = owned("python3 -c ${shq(detach)} ${shq(childFile.absolutePath)} </dev/null >/dev/null 2>&1 & while [ ! -s ${shq(childFile.absolutePath)} ]; do sleep 0.01; done; exit 7")
        try {
            val result = withTimeout(3000) { Root.collectProcess(ownership, 2) }
            val daemon = waitForPid(childFile)
            assertEquals(7, result.code)
            delay(300)
            assertTrue(ProcessHandle.of(daemon).map { it.isAlive }.orElse(false))
        } finally {
            ownership.close()
            childFile.readText().trim().toLongOrNull()?.let { pid ->
                ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
            }
            childFile.delete()
        }
    }

    @Test
    fun cancellationPreservesExplicitlyDetachedDaemon() = runBlocking {
        val childFile = Files.createTempFile("hypercraft-detached-", ".pid").toFile()
        val detach = "import os,sys; os.setsid(); open(sys.argv[1],'w').write(str(os.getpid())); os.execl('/bin/sleep','sleep','30')"
        val ownership = owned("python3 -c ${shq(detach)} ${shq(childFile.absolutePath)} </dev/null >/dev/null 2>&1 & wait")
        try {
            val job = async { Root.collectProcess(ownership, 90) }
            val daemon = waitForPid(childFile)
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(ProcessHandle.of(daemon).map { it.isAlive }.orElse(false))
        } finally {
            ownership.close()
            childFile.readText().trim().toLongOrNull()?.let { pid ->
                ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
            }
            childFile.delete()
        }
    }

    @Test
    fun cancellationStopsProcessPromptly() = runBlocking {
        val process = ProcessBuilder("sh", "-c", "exec sleep 30").start()
        try {
            val job = async { Root.collectProcess(process, 90) }
            delay(150)
            withTimeout(2000) { job.cancelAndJoin() }
            // destroyForcibly 发出终止请求，内核回收可以晚于协程返回。
            assertTrue(process.waitFor(2, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun timeoutStopsProcessAndReturnsFailure() = runBlocking {
        val process = ProcessBuilder("sh", "-c", "exec sleep 30").start()
        try {
            val result = withTimeout(3000) { Root.collectProcess(process, 1) }
            assertFalse(result.ok)
            assertTrue(process.waitFor(2, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun drainsBothPipesWithoutDeadlock() = runBlocking {
        val process = ProcessBuilder("sh", "-c",
            "i=0; while [ \"\$i\" -lt 10000 ]; do echo output; echo error >&2; i=\$((i+1)); done; exit 7").start()
        val result = withTimeout(5000) { Root.collectProcess(process, 4) }
        assertEquals(7, result.code)
        assertEquals(10000, result.out.lineSequence().count { it == "output" })
        assertEquals(10000, result.err.lineSequence().count { it == "error" })
    }

    @Test
    fun oversizedOutputIsBoundedWhileBothPipesStillDrain() = runBlocking {
        val process = ProcessBuilder("sh", "-c",
            "head -c 8388608 /dev/zero; head -c 8388608 /dev/zero >&2; exit 7").start()
        val result = withTimeout(10000) { Root.collectProcess(process, 8) }
        assertEquals(7, result.code)
        assertTrue(result.truncated)
        assertEquals(Root.OUTPUT_LIMIT_BYTES, result.out.length)
        assertEquals(Root.OUTPUT_LIMIT_BYTES, result.err.takeWhile { it == '\u0000' }.length)
        assertTrue(result.err.endsWith("[命令输出超过每路 2 MiB，已截断显示]"))
        assertFalse(process.isAlive)
    }
}
