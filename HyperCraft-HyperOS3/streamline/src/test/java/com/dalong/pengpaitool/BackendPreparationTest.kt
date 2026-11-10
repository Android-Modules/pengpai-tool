package com.dalong.pengpaitool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackendPreparationTest {
    private val initializing = Root.Res(1, "ERR|runtime|运行环境正在初始化，请稍后重新检查", "")
    private val starting = Root.Res(1, "ERR|runtime|运行环境正在启动，请稍后重新检查", "")

    @Test fun aPriorProcessesBusyLocksCanFinishWithoutAnotherUserAction() = runBlocking {
        val waits = mutableListOf<Long>()
        val success = Root.Res(0, "OK|runtime|运行环境已准备", "")
        val replies = ArrayDeque(listOf(initializing, starting, success))
        val result = retryRuntimePreparation(pause = { waits.add(it) }) { replies.removeFirst() }
        assertSame(success, result)
        assertEquals(listOf(250L, 500L), waits)
        assertTrue(replies.isEmpty())
    }

    @Test fun permanentlyBusyReturnsTheLastFailureAfterTheBoundedBudget() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val result = retryRuntimePreparation(pause = { waits.add(it) }) { calls++; starting }
        assertSame(starting, result)
        assertEquals(6, calls)
        assertEquals(5, waits.size)
        assertEquals(5250L, waits.sum())
    }

    @Test fun permissionLegacyAndMixedRealFailuresAreNeverRetried() = runBlocking {
        val failures = listOf(
            Root.Res(1, "ERR|legacy|请先停用旧后台", ""),
            Root.Res(-1, "", "Permission denied"),
            Root.Res(1, starting.out + "\nERR|runtime|文件校验失败", ""),
        )
        for (failure in failures) {
            var calls = 0
            val result = retryRuntimePreparation(pause = { fail("Real errors must not wait") }) { calls++; failure }
            assertSame(failure, result)
            assertEquals(1, calls)
        }
    }

    @Test fun cancellationDuringBackoffStopsBeforeAnotherRootCommand() = runBlocking {
        var calls = 0
        val waiting = CompletableDeferred<Unit>()
        val job = launch {
            retryRuntimePreparation(pause = { waiting.complete(Unit); delay(Long.MAX_VALUE) }) { calls++; starting }
        }
        waiting.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }

    @Test fun cancellationFromTheCommandPropagatesDirectly() = runBlocking {
        val cancelled = CancellationException("Leaving tool screen")
        try {
            retryRuntimePreparation(pause = { fail("Cancelled commands must not wait") }) { throw cancelled }
            fail("Cancellation must propagate")
        } catch (error: CancellationException) { assertSame(cancelled, error) }
    }
}
