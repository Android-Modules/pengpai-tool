package com.dalong.pengpaitool.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class UiWritesTest {
    @Test fun systemWriteMustMatchReadbackBeforeReportingSuccess() = runBlocking {
        var stored = "0"
        assertTrue(verifySystemWrite("1", { stored = "1" }, { stored }))
        assertFalse(verifySystemWrite("0", {}, { stored }))
    }

    @Test fun optionalCustomDeviceFieldsRemainSeparateShellArguments() {
        val fields = listOf("", "", "澎湃 工坊", "型号'甲", "")
        val command = "set -- ${customDeviceArgs(fields[0], fields[1], fields[2], fields[3], fields[4])}; " +
            "printf '%s\\n' \"\$#\"; for arg do printf '%s\\n' \"\$arg\"; done"
        val process = ProcessBuilder("sh", "-c", command).start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        val error = process.errorStream.bufferedReader().use { it.readText() }
        assertEquals(error, 0, process.waitFor())
        assertEquals("6", lines[0])
        assertEquals("apply-custom", lines[1])
        assertEquals(fields, lines.drop(2).map { String(Base64.getDecoder().decode(it), Charsets.UTF_8) })
    }
}
