package com.dalong.pengpaitool.ui

import org.junit.Assert.*
import org.junit.Test

class AdbPairingInfoTest {
    private val row = listOf("OK", "123456", "192.0.2.10", "41001", "41002")

    @Test fun validPairingAndConnectionUseDifferentPorts() {
        val info = AdbPairingInfo.parse(row)!!
        assertEquals("adb pair 192.0.2.10:41001 123456", info.pairingCommand)
        assertEquals("adb connect 192.0.2.10:41002", info.connectionCommand)
    }

    @Test fun missingConnectionPortStillAllowsPairingWithoutInventingAConnectCommand() {
        for (port in listOf("", "0", "-1", "65536", "null", "not ready")) {
            val info = AdbPairingInfo.parse(row.take(4) + port)!!
            assertNotNull(info.pairingCommand)
            assertNull(info.connection)
            assertNull(info.connectionCommand)
        }
        assertNull(AdbPairingInfo.parse(row.take(4))!!.connectionCommand)
    }

    @Test fun malformedPairingDataIsRejectedBeforeShowingOrCopying() {
        val rows = listOf(emptyList(), listOf("ERR", "pair", "unavailable"),
            row.toMutableList().apply { this[0] = "other" },
            row.toMutableList().apply { this[1] = "12345" },
            row.toMutableList().apply { this[1] = "12345;" },
            row.toMutableList().apply { this[2] = "192.0.2.999" },
            row.toMutableList().apply { this[3] = "" },
            row.toMutableList().apply { this[3] = "65536" })
        rows.forEach { assertNull(it.toString(), AdbPairingInfo.parse(it)) }
    }

    @Test fun endpointRejectsIncompleteAndInjectedAddresses() {
        listOf("192.0.2.10:", ":41002", "192.0.2.10:0", "192.0.2.10:-1",
            "192.0.2.10:41002;id", "192.0.2.10:41002\nother", "192.0.2.10:9999999999999",
            "192.0.2:41002", "192.0.2.10: 41002").forEach { assertNull(it, AdbEndpoint.parse(it)) }
        assertEquals("192.0.2.10:41002", AdbEndpoint.parse("192.0.2.10:41002\n")?.address)
    }

    @Test fun refreshUpdatesOnlyConnectionAndPreservesActivePairing() {
        val initial = AdbPairingInfo.parse(row.take(4))!!
        val refreshed = initial.withConnection("192.0.2.10:41003")
        assertEquals(initial.pairingCommand, refreshed.pairingCommand)
        assertEquals("adb connect 192.0.2.10:41003", refreshed.connectionCommand)
        assertNull(refreshed.withConnection("").connectionCommand)
        assertNull(refreshed.withConnection("192.0.2.11:41003").connectionCommand)
    }
}
