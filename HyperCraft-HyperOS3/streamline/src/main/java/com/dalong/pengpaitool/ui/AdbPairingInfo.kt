package com.dalong.pengpaitool.ui

/** Only complete WLAN IPv4 endpoints may be displayed or copied as commands. */
internal data class AdbEndpoint(val host: String, val port: Int) {
    val address get() = "$host:$port"

    companion object {
        fun parse(value: String): AdbEndpoint? {
            val parts = value.trim().split(':')
            if (parts.size != 2) return null
            val host = parts[0]
            val octets = host.split('.')
            if (octets.size != 4 || octets.any {
                it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } ||
                    it.toIntOrNull() !in 0..255
            }) return null
            val rawPort = parts[1]
            if (rawPort.isEmpty() || rawPort.length > 5 || rawPort.any { it !in '0'..'9' }) return null
            val port = rawPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return AdbEndpoint(host, port)
        }
    }
}

internal data class AdbPairingInfo(
    val code: String,
    val pairing: AdbEndpoint,
    val connection: AdbEndpoint?,
) {
    val pairingCommand get() = "adb pair ${pairing.address} $code"
    val connectionCommand get() = connection?.let { "adb connect ${it.address}" }

    fun withConnection(value: String): AdbPairingInfo = copy(
        connection = AdbEndpoint.parse(value)?.takeIf { it.host == pairing.host }
    )

    companion object {
        fun parse(row: List<String>): AdbPairingInfo? {
            if (row.size < 4 || row[0] != "OK" || !row[1].matches(Regex("[0-9]{6}"))) return null
            val pairing = AdbEndpoint.parse("${row[2]}:${row[3]}") ?: return null
            val connection = AdbEndpoint.parse("${row[2]}:${row.getOrNull(4).orEmpty()}")
            return AdbPairingInfo(row[1], pairing, connection)
        }
    }
}
