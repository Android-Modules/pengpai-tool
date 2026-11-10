package com.dalong.pengpaitool

/** Debug-only allowlist. Unknown commands, unreadable configuration and duplicates fail closed. */
internal object ManagedDaemonPolicy {
    data class Process(val pid: Long, val parent: Long, val group: Long, val command: String)
    data class Setting(val id: String, val path: String, val key: String)
    data class Readback(val status: String, val value: String)
    private const val STATE = "/data/adb/hypercraft/state"
    private const val RUNTIME = "/data/adb/hypercraft/runtime"
    val settings = listOf(
        Setting("cycle.MODE", "$STATE/battery/config.txt", "MODE"),
        Setting("guard.enable", "$STATE/config", "guard_enable"),
        Setting("charge.enable", "$STATE/charge/config", "enable"),
        Setting("charge.record", "$STATE/charge/config", "record"),
    )
    private val arguments = mapOf(
        "cycle-watch" to listOf("$RUNTIME/adclean.sh", "cycle-watch"),
        "guard-daemon" to listOf("$RUNTIME/adclean.sh", "guard-daemon"),
        "charge-daemon" to listOf("$RUNTIME/battery/charge.sh", "daemon"),
    )

    private fun rule(command: String): String? {
        val argv = command.trim().split(Regex("\\s+"))
        if (argv.firstOrNull() !in setOf("sh", "/system/bin/sh")) return null
        return arguments.entries.firstOrNull { argv.drop(1) == it.value }?.key
    }

    fun classify(processes: List<Process>, config: Map<String, Readback>): Map<Long, String> {
        fun value(key: String) = config[key]?.takeIf { it.status == "read" }?.value
        fun enabled(rule: String) = when (rule) {
            "cycle-watch" -> value("cycle.MODE") in setOf("map", "fixed")
            "guard-daemon" -> value("guard.enable") == "1"
            "charge-daemon" -> value("charge.enable") == "1" || value("charge.record") == "1"
            else -> false
        }
        // Count before checking enabled/detached: no duplicate can be hidden as the legitimate one.
        return processes.mapNotNull { p -> rule(p.command)?.let { it to p } }.groupBy({ it.first }, { it.second })
            .filter { (rule, instances) -> enabled(rule) && instances.size == 1 && instances[0].group == instances[0].pid }
            .map { (rule, instances) -> instances.single().pid to rule }.toMap()
    }
}
