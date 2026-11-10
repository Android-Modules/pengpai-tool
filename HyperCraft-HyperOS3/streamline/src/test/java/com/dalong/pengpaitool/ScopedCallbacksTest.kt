package com.dalong.pengpaitool

import com.dalong.pengpaitool.xp.ScopedCallbacks
import org.junit.Assert.*
import org.junit.Test

class ScopedCallbacksTest {
    @Test fun stoppingCancelsOnlyOwnedCallbacksAndPreventsLateDeliveryAfterResume() {
        val queue = mutableListOf<Runnable>()
        val removed = mutableListOf<Runnable>()
        var visible = true
        var calls = 0
        val unrelated = Runnable {}
        queue += unrelated
        val tasks = ScopedCallbacks({ task, _ -> queue.add(task) }, { task -> removed.add(task); queue.remove(task) }, { visible })
        tasks.post(700) { calls++ }
        val stale = queue.last()
        visible = false
        tasks.clear()
        assertEquals(listOf(unrelated), queue)
        assertEquals(listOf(stale), removed)
        visible = true
        stale.run() // Even an already dispatched callback cannot restart an old animation.
        assertEquals(0, calls)
        tasks.post(700) { calls++ }
        queue.last().run()
        assertEquals(1, calls)
    }

    @Test fun hiddenHostsDoNotQueueOrRunAnimationsAndCompletedCallbacksAreReleased() {
        val queue = mutableListOf<Runnable>()
        var visible = false
        var calls = 0
        var removed = 0
        val tasks = ScopedCallbacks({ task, _ -> queue.add(task) }, { _ -> removed++; true }, { visible })
        tasks.post(300) { calls++ }
        assertTrue(queue.isEmpty())
        visible = true
        tasks.post(300) { calls++ }
        visible = false
        queue.last().run()
        tasks.clear()
        assertEquals(0, calls)
        assertEquals(0, removed)
    }
}
