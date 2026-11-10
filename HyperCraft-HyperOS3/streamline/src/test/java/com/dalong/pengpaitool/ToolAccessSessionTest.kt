package com.dalong.pengpaitool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ToolAccessSessionTest {
    @Test fun firstNavigationDoesNotRequestRootAndManualGrantRestoresNextProcess() = runBlocking {
        var remembered = false
        var checks = 0
        var prepares = 0
        fun session() = ToolAccessCoordinator({ remembered }, { remembered = true }, { checks++; true }, { prepares++; true }, { 1L })
        val first = session()
        assertEquals(ToolAccessState.IDLE, first.ensure(false))
        assertEquals(0, checks)
        assertEquals(ToolAccessState.OK, first.ensure(true))
        assertTrue(remembered)
        assertEquals(ToolAccessState.OK, session().ensure(false))
        assertEquals(2, checks)
        assertEquals(2, prepares)
    }

    @Test fun cachedGrantIsRecheckedAndRevocationFallsBackWithoutPreparing() = runBlocking {
        var time = 0L
        var allowed = true
        var checks = 0
        var prepares = 0
        val session = ToolAccessCoordinator({ true }, {}, { checks++; allowed }, { prepares++; true }, { time })
        assertEquals(ToolAccessState.OK, session.ensure(false))
        time += 1000
        assertEquals(ToolAccessState.OK, session.ensure(false))
        assertEquals(1, checks)
        time += ToolAccessCoordinator.RECHECK_MS
        assertEquals(ToolAccessState.OK, session.ensure(false))
        assertEquals(1, prepares) // A permission check does not redeploy an already prepared backend.
        allowed = false
        time += ToolAccessCoordinator.RECHECK_MS
        assertEquals(ToolAccessState.NO_ROOT, session.ensure(false))
        assertEquals(1, prepares)
        allowed = true
        assertEquals(ToolAccessState.OK, session.ensure(true))
        assertEquals(2, prepares)
    }

    @Test fun simultaneousPagesShareOneRootCheckAndPreparation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var checks = 0
        var prepares = 0
        val session = ToolAccessCoordinator({ true }, {}, { checks++; started.complete(Unit); resume.await(); true }, { prepares++; true }, { 0L })
        val first = async { session.ensure(false) }
        started.await()
        val second = async { session.ensure(true) }
        yield()
        resume.complete(Unit)
        assertEquals(ToolAccessState.OK, first.await())
        assertEquals(ToolAccessState.OK, second.await())
        assertEquals(1, checks)
        assertEquals(1, prepares)
    }

    @Test fun failedPreparationLeavesAManualRetryWithoutRememberingAReadyBackend() = runBlocking {
        var ready = false
        val session = ToolAccessCoordinator({ true }, {}, { true }, { ready }, { 0L })
        assertEquals(ToolAccessState.NO_MODULE, session.ensure(false))
        ready = true
        assertEquals(ToolAccessState.OK, session.ensure(true))
    }

    @Test fun cancellingAnExpiredRecheckDoesNotCacheSuccessOrBlockTheNextRevocationCheck() = runBlocking {
        var time = 0L
        var checks = 0
        var prepares = 0
        val checking = CompletableDeferred<Unit>()
        val session = ToolAccessCoordinator({ true }, {}, {
            checks++
            when (checks) {
                1 -> true
                2 -> { checking.complete(Unit); awaitCancellation() }
                else -> false
            }
        }, { prepares++; true }, { time })
        assertEquals(ToolAccessState.OK, session.ensure(false))
        time += ToolAccessCoordinator.RECHECK_MS
        val interrupted = async { session.ensure(false) }
        checking.await()
        interrupted.cancelAndJoin()
        assertEquals(ToolAccessState.NO_ROOT, session.ensure(false))
        assertEquals(3, checks)
        assertEquals(1, prepares)
    }
}
