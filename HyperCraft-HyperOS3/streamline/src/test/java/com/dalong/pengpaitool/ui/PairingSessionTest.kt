package com.dalong.pengpaitool.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PairingSessionTest {
    @Test fun cancellationWhileStartingStillStopsSystemService() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var stops = 0
        val job = launch {
            PairingSession.run(stop = { stops++ }) {
                started.complete(Unit)
                awaitCancellation() // Binder started the service, but shell has not returned its port.
            }
        }
        started.await()
        job.cancelAndJoin()
        assertEquals(1, stops)
    }

    @Test fun replacementWaitsForCancelledSessionCleanup() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopping = CompletableDeferred<Unit>()
        val finishStop = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = launch {
            PairingSession.run(stop = {
                stopping.complete(Unit)
                finishStop.await()
                events += "stopped-first"
            }) { started.complete(Unit); awaitCancellation() }
        }
        started.await()
        first.cancel()
        stopping.await()
        val second = launch {
            PairingSession.run(stop = { events += "stopped-second" }) { events += "started-second" }
        }
        yield()
        assertTrue(events.isEmpty())
        finishStop.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("stopped-first", "started-second", "stopped-second"), events)
    }

    @Test fun failureAlsoReleasesTheSession() = runBlocking {
        var stops = 0
        try {
            PairingSession.run(stop = { stops++ }) { error("start failed") }
            fail("Expected startup failure")
        } catch (_: IllegalStateException) { }
        PairingSession.run(stop = { stops++ }) { }
        assertEquals(2, stops)
    }
}
