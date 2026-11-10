package com.dalong.pengpaitool

import org.junit.Assert.*
import org.junit.Test

class ManagedDaemonPolicyTest {
    private val runtime = "/data/adb/hypercraft/runtime"
    private fun process(pid: Long, args: String, group: Long = pid) = ManagedDaemonPolicy.Process(pid, 1, group, "sh $runtime/$args ")
    private fun settings(vararg values: Pair<String, String>) = values.associate { it.first to ManagedDaemonPolicy.Readback("read", it.second) }

    @Test fun onlyExplicitlyEnabledSingleDetachedCommandsAreManaged() {
        val processes = listOf(process(1, "adclean.sh cycle-watch"), process(2, "adclean.sh guard-daemon"), process(3, "battery/charge.sh daemon"))
        val config = settings("cycle.MODE" to "fixed", "guard.enable" to "1", "charge.record" to "1")
        assertEquals(mapOf(1L to "cycle-watch", 2L to "guard-daemon", 3L to "charge-daemon"), ManagedDaemonPolicy.classify(processes, config))
    }

    @Test fun disabledUnknownOrUnreadableConfigNeverHidesProcesses() {
        val processes = listOf(process(1, "adclean.sh cycle-watch"), process(2, "adclean.sh guard-daemon"), process(3, "battery/charge.sh daemon"))
        assertTrue(ManagedDaemonPolicy.classify(processes, emptyMap()).isEmpty())
        assertTrue(ManagedDaemonPolicy.classify(processes, settings("cycle.MODE" to "off", "guard.enable" to "0", "charge.enable" to "0")).isEmpty())
        assertTrue(ManagedDaemonPolicy.classify(processes, mapOf("guard.enable" to ManagedDaemonPolicy.Readback("unreadable", "1"))).isEmpty())
    }

    @Test fun duplicatesWrongProcessGroupAndExtendedCommandsRemainResiduals() {
        val config = settings("guard.enable" to "1")
        assertTrue(ManagedDaemonPolicy.classify(listOf(process(1, "adclean.sh guard-daemon"), process(2, "adclean.sh guard-daemon")), config).isEmpty())
        assertTrue(ManagedDaemonPolicy.classify(listOf(process(1, "adclean.sh guard-daemon", 20)), config).isEmpty())
        assertTrue(ManagedDaemonPolicy.classify(listOf(process(1, "adclean.sh guard-daemon extra"), process(2, "adclean.sh guard-daemon; sleep 30")), config).isEmpty())
        val wrapper = ManagedDaemonPolicy.Process(3, 1, 3, "sh -c exec 4<&0; $runtime/adclean.sh guard-daemon; kill -KILL")
        assertTrue(ManagedDaemonPolicy.classify(listOf(wrapper), config).isEmpty())
    }
}
