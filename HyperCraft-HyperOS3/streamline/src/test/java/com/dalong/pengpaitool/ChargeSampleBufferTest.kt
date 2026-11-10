package com.dalong.pengpaitool

import com.dalong.pengpaitool.ui.ChargeSampleBuffer
import com.dalong.pengpaitool.ui.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeSampleBufferTest {
    private fun sample(t: Long, ma: Int = 1000, mv: Int = 4000, bt: Int = 300, bd: Int = 350, soc: Int = 50) =
        Sample(t, soc, ma, mv, bt, bd, 0, 0, 0, 1, 0, "system")

    @Test fun shortSessionRetainsAllPointsAndEmptyPollDoesNotRebuild() {
        val input = (1L..100L).map { sample(it) }
        val state = ChargeSampleBuffer.append(null, input)
        assertEquals(input, state.samples)
        assertEquals(100L, state.lastTimestamp)
        assertFalse(state.compacted)
        assertSame(state, ChargeSampleBuffer.append(state, emptyList()))
    }

    @Test fun longSessionStaysBoundedAndIncrementalCursorKeepsActualLatestSample() {
        var state: ChargeSampleBuffer.State? = null
        for (batch in 0 until 100) {
            val input = (1L..500L).map { sample(batch * 500L + it, ma = 1000 + (it % 250).toInt()) }
            state = ChargeSampleBuffer.append(state, input)
            assertTrue(state.samples.size <= ChargeSampleBuffer.MAX_SAMPLES)
            assertEquals((batch + 1) * 500L, state.lastTimestamp)
            assertEquals(state.lastTimestamp, state.samples.last().t)
            assertEquals(1L, state.samples.first().t)
            assertTrue(state.samples.zipWithNext().all { (a, b) -> a.t < b.t })
        }
        assertTrue(state!!.compacted)
    }

    @Test fun compactionRetainsMetricPeaksValleysAndSessionEndpoints() {
        val input = (1L..1200L).map { t -> when (t) {
            100L -> sample(t, ma = 10000)
            101L -> sample(t, ma = -1000)
            102L -> sample(t, mv = 6000)
            103L -> sample(t, bt = 460)
            104L -> sample(t, bd = 500)
            105L -> sample(t, soc = 100)
            else -> sample(t)
        } }
        val state = ChargeSampleBuffer.append(null, input, limit = 26)
        val times = state.samples.map { it.t }
        assertTrue(times.size <= 26)
        assertEquals(1L, times.first())
        assertEquals(1200L, times.last())
        assertTrue(times.containsAll(listOf(100L, 101L, 102L, 103L, 104L, 105L)))
        assertEquals(1200L, state.lastTimestamp)
    }

    @Test fun repeatedOrOlderRowsDoNotDuplicateSamplesOrMoveCursorBackward() {
        val state = ChargeSampleBuffer.append(null, (1L..100L).map { sample(it) })
        val next = ChargeSampleBuffer.append(state, listOf(sample(99), sample(100), sample(101), sample(101), sample(102)))
        assertEquals(102, next.samples.size)
        assertEquals(102L, next.lastTimestamp)
        assertEquals((1L..102L).toList(), next.samples.map { it.t })
    }
}
